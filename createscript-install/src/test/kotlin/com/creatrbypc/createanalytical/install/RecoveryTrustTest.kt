package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.LockRoot
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.PackageArchives
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// S8.2 L-2: 中断した後の install が、持ち込まれた・書き換えられたジャーナルと写しで、利用者が確かめた createscript.json・createscript.lock を置き換えないこと。
// ジャーナルを書いた場所（binding）の確認と、書き込む写しの内容の確認
class RecoveryTrustTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private val numbers = Packages.source("numbers")
    private val vectorMath = Packages.source("vector-math")
    private val spec = PackageSpec(vectorMath, "1.2.0")
    private val forgedModule = "fun one(): Int {\n    println(\"FORGED\")\n    return 1\n}\n".toByteArray()

    @BeforeEach
    fun setUp() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
    }

    private fun project(name: String): Path {
        val root = createProject(temp.resolve(name))
        Files.writeString(root.resolve("src/main.cscr"), "import vector_math\nprintln(vector_math.one())\n")
        return root
    }

    private fun installer(root: Path, hooks: InstallHooks = InstallHooks.NONE, keys: DirectoryKeys = DirectoryKeys.FILE_KEY) =
        Installer(root, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), hooks, keys)

    private fun crashAt(step: String): InstallHooks {
        var done = false
        return InstallHooks { if (it == step && !done) { done = true; throw SimulatedCrash(it) } }
    }

    private fun crash(root: Path, step: String, spec: PackageSpec? = this.spec, keys: DirectoryKeys = DirectoryKeys.FILE_KEY) {
        assertThrows(SimulatedCrash::class.java) { installer(root, crashAt(step), keys).install(spec, false) }
    }

    private fun journalPath(root: Path): Path = root.resolve(".createscript/transaction.json")

    private fun journal(root: Path): Journal = Journal.parse(Files.readAllBytes(journalPath(root)), 256).first!!

    private fun rewriteJournal(root: Path, change: (Journal) -> Journal) {
        Files.write(journalPath(root), change(journal(root)).write())
    }

    private fun txn(root: Path): Path = root.resolve(".createscript/txn-${journal(root).id}")

    private fun lock(root: Path): Path = root.resolve("createscript.lock")

    // 自動では復旧せず、何も変えずに止まった（UNSAFE と手で確かめる手順）
    private fun assertRefused(root: Path, expected: String, installer: Installer = installer(root)): InstallFailure {
        val before = snapshot(root)
        val failure = assertThrows(InstallFailure::class.java) { installer.install(null, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind, failure.message)
        val message = failure.message!!
        assertTrue(message.contains("cannot be finished or undone safely") && message.contains("Nothing was changed") && message.contains("To check by hand:"), message)
        assertTrue(message.contains(expected), "missing <$expected> in:\n$message")
        assertUnchanged(before, root)
        // 実行の前の確認も、ジャーナルがある間は使わない
        assertRunRefused(root)
        return failure
    }

    private fun assertRunRefused(root: Path) {
        val lockfile = if (Files.exists(lock(root))) Lockfiles.parse(Files.readAllBytes(lock(root))).lockfile!! else Lockfile.of(LockRoot("app", "0.1.0"), emptyList())
        val failure = assertThrows(InstallFailure::class.java) { InstalledPackages.verify(root, lockfile) }
        assertTrue(failure.message!!.contains("an installation was interrupted"), failure.message)
    }

    // プロジェクトを別の場所に写す（配布・展開の代わり。ディレクトリは作り直されるので fileKey が変わる）
    private fun copyTree(from: Path, to: Path): Path {
        Files.walk(from).use { stream ->
            for (path in stream) {
                val target = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
        return to
    }

    // 以前の再評価の L-2 の攻撃: 一度正しくインストールした（ロックファイルを確かめた）プロジェクトで、もう一度のインストールを中断させ、
    // 作業用の写し（パッケージ・印・新しいロックファイル）とジャーナルを、互いに合うように書き換える。書き換えたソースは FORGED を出力する。
    // 書き換えたパッケージは、作り直したアーカイブの SHA-256 が、書き換えたロックファイルと合う（M-1 の照合にも通る）
    private fun forgeL2(root: Path): ByteArray {
        installer(root).install(spec, false)
        root.resolve(".createscript/packages").toFile().deleteRecursively()
        crash(root, "journal", spec = null)
        val t = txn(root)
        val staged = t.resolve("staging/vector-math/1.2.0")
        Files.write(staged.resolve("modules/vector_math.cscr"), forgedModule)
        val markerPath = staged.resolve(InstallLayout.MARKER)
        val marker = Marker.parse(Files.readAllBytes(markerPath))!!
        val files = marker.files.keys.associateWith { Files.readAllBytes(staged.resolve(it)) }
        val forgedSha = SafeIo.sha256Hex(PackageArchives.rebuild(files)!!)
        val newMarker = marker.copy(sha256 = forgedSha, files = marker.files + ("modules/vector_math.cscr" to SafeIo.sha256Hex(forgedModule))).write()
        Files.write(markerPath, newMarker)
        val genuine = SafeIo.sha256Hex(store.assets["$vectorMath@1.2.0"]!!)
        val lockNew = Files.readString(t.resolve(Recovery.LOCK_NEW)).replace(genuine, forgedSha).toByteArray()
        Files.write(t.resolve(Recovery.LOCK_NEW), lockNew)
        rewriteJournal(root) { j ->
            j.copy(lockNew = SafeIo.sha256Hex(lockNew), packages = j.packages.map { if (it.name == "vector-math") it.copy(marker = SafeIo.sha256Hex(newMarker)) else it })
        }
        return Files.readAllBytes(lock(root))
    }

    private fun assertNoForgedSource(root: Path) {
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path) || path.toString().contains("/txn-")) continue
                assertFalse(Files.readAllBytes(path).contentEquals(forgedModule), "the forged source was installed at $path")
            }
        }
    }

    // ---- L-2 の攻撃 ----

    // 持ち込まれたプロジェクト（写したもの）: ジャーナルを書いた場所が違うので、自動では復旧しない。確かめたロックファイルはそのまま
    @Test
    fun theL2AttackInACopiedProjectIsRefused() {
        val original = project("original")
        forgeL2(original)
        // 同じパスに写し戻したもの（バックアップからの復元・同じ名前での展開）: パスは同じでも、ディレクトリの fileKey が違う
        val away = copyTree(original, temp.resolve("away"))
        original.toFile().deleteRecursively()
        copyTree(away, original)
        assertRefused(original, "was not written in this directory on this computer")
        assertArrayEquals(Files.readAllBytes(lock(away)), Files.readAllBytes(lock(original)))
        // 別のパスに写したもの（ダウンロード・展開）
        val copied = copyTree(away, temp.resolve("downloaded"))
        val reviewed = Files.readAllBytes(lock(copied))
        assertRefused(copied, "was written for another directory")
        assertArrayEquals(reviewed, Files.readAllBytes(lock(copied)))
        assertNoForgedSource(copied)
        // 手順のとおり、ジャーナルと作業用のディレクトリを外へ移してから install すると、確かめたロックファイルのとおりに本物のパッケージを置く
        val id = journal(copied).id
        Files.move(journalPath(copied), temp.resolve("kept-journal.json"))
        Files.move(copied.resolve(".createscript/txn-$id"), temp.resolve("kept-txn"))
        val result = installer(copied).install(null, false)
        assertNull(result.recovery)
        assertArrayEquals(reviewed, Files.readAllBytes(lock(copied)))
        val verified = InstalledPackages.verify(copied, Lockfiles.parse(reviewed).lockfile!!)
        assertTrue(verified.none { p -> p.modules.values.any { it.contains("FORGED") } })
        assertNoForgedSource(copied)
    }

    // 同じ場所で書き換えたもの（binding は合う）: 新しいロックファイルが、前のロックファイルと同じ版の SHA-256 を変えているので、続行しない
    @Test
    fun theL2AttackInPlaceIsRefusedByTheContentCheck() {
        val root = project("app")
        val reviewed = forgeL2(root)
        assertRefused(root, "changes the SHA-256 or the dependencies of vector-math 1.2.0")
        assertArrayEquals(reviewed, Files.readAllBytes(lock(root)))
        assertNoForgedSource(root)
    }

    // 移動したプロジェクト（fileKey は同じでも、ルートの実パスが違う）も、自動では復旧しない
    @Test
    fun aMovedProjectIsNotRecoveredAutomatically() {
        val root = project("before-move")
        crash(root, "journal")
        val moved = temp.resolve("after-move")
        Files.move(root, moved)
        assertRefused(moved, "was written for another directory")
        // 元の場所に戻せば復旧できる
        Files.move(moved, root)
        assertEquals("completed an interrupted installation", installer(root).install(null, false).recovery)
    }

    // ---- 正常な中断の後の自動復旧 ----

    @Test
    fun recoversAnInterruptedInstallationInPlace() {
        val root = project("app")
        crash(root, "package:numbers")
        val bound = journal(root).binding
        assertEquals(root.toRealPath().toString(), bound.root)
        assertNotNull(bound.directory)
        assertNotNull(bound.transaction)
        assertEquals("completed an interrupted installation", installer(root).install(null, false).recovery)
        assertFalse(Files.exists(journalPath(root)))
        val verified = InstalledPackages.verify(root, Lockfiles.parse(Files.readAllBytes(lock(root))).lockfile!!)
        assertEquals(listOf("numbers", "vector-math"), verified.map { it.name })
        // 取り消し（続行に必要な写しが無いとき）も、確認に通れば行う
        val other = project("undo")
        crash(other, "manifest")
        Files.delete(txn(other).resolve(Recovery.LOCK_NEW))
        assertEquals("undid an interrupted installation", installer(other).install(null, false).recovery)
        assertFalse(Files.exists(lock(other)))
    }

    // ---- 偽造したジャーナル ----

    @Test
    fun refusesAForgedJournal() {
        val root = project("app")
        crash(root, "manifest")
        val good = journal(root)
        val cases = mapOf<String, (Journal) -> Journal>(
            "another directory key" to { it.copy(binding = it.binding.copy(directory = "(dev=1,ino=1)")) },
            "another transaction key" to { it.copy(binding = it.binding.copy(transaction = "(dev=1,ino=2)")) },
            "another root" to { it.copy(binding = it.binding.copy(root = temp.resolve("elsewhere").toString())) },
            "no keys" to { it.copy(binding = it.binding.copy(directory = null, transaction = null)) },
        )
        for ((label, change) in cases) {
            Files.write(journalPath(root), change(good).write())
            val expected = when (label) {
                "another root" -> "was written for another directory"
                "no keys" -> "does not report file identities"
                else -> "was not written in this directory on this computer"
            }
            assertRefused(root, expected)
        }
        // 別のプロジェクトで書いたジャーナルと作業用のディレクトリを持ち込む
        val other = project("other")
        crash(other, "manifest")
        val target = project("target")
        Files.createDirectories(target.resolve(".createscript"))
        Files.copy(journalPath(other), journalPath(target))
        copyTree(txn(other), target.resolve(".createscript/txn-${journal(other).id}"))
        Files.copy(other.resolve("createscript.json"), target.resolve("createscript.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertRefused(target, "was written for another directory")
        // 元に戻せば復旧できる
        Files.write(journalPath(root), good.write())
        assertEquals("completed an interrupted installation", installer(root).install(null, false).recovery)
    }

    // ---- 偽造した新しいロックファイル（binding は合う） ----

    @Test
    fun refusesAForgedNewLockfile() {
        val cases = mapOf<String, Pair<(String) -> String, String>>(
            "not valid" to ({ _: String -> "{}" } to "the new createscript.lock is not valid"),
            "another root version" to ({ s: String -> s.replace("\"version\": \"0.1.0\"", "\"version\": \"9.9.9\"") } to "does not match the new createscript.json"),
            "not canonical" to ({ s: String -> s.replace("\n", "\n ") } to "does not match the new createscript.json"),
            "another archive" to ({ s: String -> s.replace(SafeIo.sha256Hex(store.assets["$numbers@3.0.0"]!!), "f".repeat(64)) } to "does not match the archive in the new createscript.lock"),
        )
        for ((label, case) in cases) {
            val (change, expected) = case
            val root = project("lock-${label.replace(' ', '-')}")
            crash(root, "journal")
            val path = txn(root).resolve(Recovery.LOCK_NEW)
            val forged = change(Files.readString(path)).toByteArray()
            Files.write(path, forged)
            rewriteJournal(root) { it.copy(lockNew = SafeIo.sha256Hex(forged)) }
            val before = snapshot(root)
            val failure = assertThrows(InstallFailure::class.java) { installer(root).install(null, false) }
            assertEquals(InstallFailure.Kind.UNSAFE, failure.kind, label)
            assertTrue(failure.message!!.contains(expected) && failure.message!!.contains("Nothing was changed"), "$label: ${failure.message}")
            assertEquals(before, snapshot(root), label)
            assertFalse(Files.exists(lock(root)), label)
        }
    }

    // ---- 不正なマニフェスト ----

    @Test
    fun refusesAnInvalidNewManifest() {
        val cases = mapOf(
            "not json" to "this is not a manifest",
            "another version" to """{"schemaVersion": 1, "name": "app", "version": "6.6.6", "entry": "src/main.cscr"}""",
            "another entry" to """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/other.cscr", "dependencies": {"vector-math": {"version": "1.2.0", "source": "github:owner/vector-math"}}}""",
        )
        for ((label, text) in cases) {
            val root = project("manifest-${label.replace(' ', '-')}")
            val original = Files.readAllBytes(root.resolve("createscript.json"))
            crash(root, "journal")
            val forged = text.toByteArray()
            Files.write(txn(root).resolve(Recovery.MANIFEST_NEW), forged)
            rewriteJournal(root) { it.copy(manifestNew = SafeIo.sha256Hex(forged)) }
            val before = snapshot(root)
            val failure = assertThrows(InstallFailure::class.java) { installer(root).install(null, false) }
            val expected = if (label == "not json") "is not a valid manifest" else "is not the one from before it with one dependency added or changed"
            assertTrue(failure.message!!.contains(expected) && failure.message!!.contains("the installation was not completed"), "$label: ${failure.message}")
            assertEquals(before, snapshot(root), label)
            assertArrayEquals(original, Files.readAllBytes(root.resolve("createscript.json")), label)
        }
    }

    // ---- 偽造したバックアップ（取り消しの側） ----

    @Test
    fun refusesAForgedBackupManifest() {
        for ((label, text) in mapOf(
            "not json" to "this is not a manifest",
            "another manifest" to """{"schemaVersion": 1, "name": "evil", "version": "0.1.0", "entry": "src/main.cscr"}""",
        )) {
            val root = project("backup-${label.replace(' ', '-')}")
            crash(root, "manifest")
            val t = txn(root)
            // 続行できないようにして、取り消しを選ばせる
            Files.delete(t.resolve(Recovery.LOCK_NEW))
            val forged = text.toByteArray()
            Files.write(t.resolve(Recovery.MANIFEST_OLD), forged)
            rewriteJournal(root) { it.copy(manifestOld = SafeIo.sha256Hex(forged)) }
            val current = Files.readAllBytes(root.resolve("createscript.json"))
            val before = snapshot(root)
            val failure = assertThrows(InstallFailure::class.java) { installer(root).install(null, false) }
            assertTrue(failure.message!!.contains("the installation was not undone") && failure.message!!.contains("Nothing was changed"), "$label: ${failure.message}")
            assertEquals(before, snapshot(root), label)
            assertArrayEquals(current, Files.readAllBytes(root.resolve("createscript.json")), label)
        }
    }

    @Test
    fun refusesAForgedBackupLockfile() {
        val numbersSha = SafeIo.sha256Hex(store.assets["$numbers@3.0.0"]!!)
        for ((label, change) in mapOf<String, (String) -> String>(
            "not valid" to { "{}" },
            "another archive" to { it.replace(numbersSha, "e".repeat(64)) },
        )) {
            val root = project("backup-lock-${label.replace(' ', '-')}")
            // ロックファイルがあるプロジェクトに、依存を足すインストールを、ロックファイルを差し替えた直後に止める
            installer(root).install(PackageSpec(numbers, "3.0.0"), false)
            crash(root, "lock")
            // 置いた vector-math を消して続行できないようにし、取り消しを選ばせる
            root.resolve(".createscript/packages/vector-math").toFile().deleteRecursively()
            val path = txn(root).resolve(Recovery.LOCK_OLD)
            val forged = change(Files.readString(path)).toByteArray()
            Files.write(path, forged)
            rewriteJournal(root) { it.copy(lockOld = SafeIo.sha256Hex(forged)) }
            val current = Files.readAllBytes(lock(root))
            val before = snapshot(root)
            val failure = assertThrows(InstallFailure::class.java) { installer(root).install(null, false) }
            val expected = if (label == "not valid") "is not valid (from before the installation)" else "changes the SHA-256 or the dependencies of numbers 3.0.0"
            assertTrue(failure.message!!.contains(expected) && failure.message!!.contains("the installation was not undone"), "$label: ${failure.message}")
            assertEquals(before, snapshot(root), label)
            assertArrayEquals(current, Files.readAllBytes(lock(root)), label)
        }
    }

    // ---- fileKey が取れないファイルシステム ----

    @Test
    fun withoutFileKeysTheInstallationWorksAndRecoveryStopsSafely() {
        val none = DirectoryKeys { null }
        // 通常のインストールと、途中の失敗のその場の取り消しは、そのまま動く
        val normal = project("normal")
        val result = installer(normal, keys = none).install(spec, false)
        assertEquals(listOf("numbers", "vector-math"), result.packages.map { it.name })
        val failing = project("failing")
        val failure = assertThrows(InstallFailure::class.java) { installer(failing, { if (it == "manifest") throw IllegalStateException("disk full") }, none).install(spec, false) }
        assertTrue(failure.message!!.contains("disk full; undid an interrupted installation"), failure.message)
        assertFalse(Files.exists(journalPath(failing)))
        assertFalse(Files.exists(lock(failing)))
        // 中断（クラッシュ）の後は、自動では復旧しない
        val crashed = project("crashed")
        crash(crashed, "manifest", keys = none)
        assertNull(journal(crashed).binding.directory)
        val before = snapshot(crashed)
        val refused = assertThrows(InstallFailure::class.java) { installer(crashed, keys = none).install(null, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, refused.kind)
        assertTrue(refused.message!!.contains("does not report file identities") && refused.message!!.contains("Nothing was changed"), refused.message)
        assertEquals(before, snapshot(crashed))
        // fileKey を返すファイルシステムに移ったとしても、記録が無いので自動では復旧しない
        assertTrue(assertThrows(InstallFailure::class.java) { installer(crashed).install(null, false) }.message!!.contains("does not report file identities"))
        assertEquals(before, snapshot(crashed))
    }

    // ---- M-1 の照合は、復旧の後も働く ----

    @Test
    fun theM1CheckStillWorksAfterARecovery() {
        val root = project("app")
        crash(root, "package:numbers")
        installer(root).install(null, false)
        val lockfile = Lockfiles.parse(Files.readAllBytes(lock(root))).lockfile!!
        InstalledPackages.verify(root, lockfile)
        // 中身と印を同じように書き換える（印だけの照合には通る）
        val directory = root.resolve(".createscript/packages/vector-math/1.2.0")
        Files.write(directory.resolve("modules/vector_math.cscr"), forgedModule)
        val markerPath = directory.resolve(InstallLayout.MARKER)
        val marker = Marker.parse(Files.readAllBytes(markerPath))!!
        Files.write(markerPath, marker.copy(files = marker.files + ("modules/vector_math.cscr" to SafeIo.sha256Hex(forgedModule))).write())
        val failure = assertThrows(InstallFailure::class.java) { InstalledPackages.verify(root, lockfile) }
        assertEquals(InstallFailure.Kind.INVALID, failure.kind)
        assertTrue(failure.message!!.contains("does not match the archive in createscript.lock"), failure.message)
    }

    // ---- 復旧の後の失敗でも、復旧したことを知らせる ----

    @Test
    fun reportsTheRecoveryWhenTheInstallationFailsAfterIt() {
        val root = project("app")
        crash(root, "package:numbers")
        val failure = assertThrows(InstallFailure::class.java) { installer(root).install(PackageSpec(Packages.source("missing"), "1.0.0"), true) }
        assertEquals(InstallFailure.Kind.NETWORK, failure.kind)
        assertTrue(failure.message!!.startsWith("first completed an interrupted installation (check createscript.json and createscript.lock); then this installation failed: "), failure.message)
        assertFalse(Files.exists(journalPath(root)))
        assertTrue(Files.exists(lock(root)))
    }
}
