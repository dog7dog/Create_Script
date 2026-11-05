package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// S8.2 Phase 3: ジャーナルによる中断・クラッシュからの復旧
class RecoveryTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private lateinit var root: Path
    private lateinit var cache: Path
    private val vectorMath = Packages.source("vector-math")
    private val numbers = Packages.source("numbers")
    private val spec get() = PackageSpec(vectorMath, "1.2.0")
    private lateinit var original: Map<String, String>
    private lateinit var originalManifest: ByteArray

    @BeforeEach
    fun setUp() {
        root = createProject(temp.resolve("app"))
        cache = temp.resolve("cache")
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
        original = snapshot(root)
        originalManifest = Files.readAllBytes(root.resolve("createscript.json"))
    }

    private fun installer(hooks: InstallHooks = InstallHooks.NONE, dir: Path = root) = Installer(dir, PackageFetcher(store, ArchiveCache(cache)), InstallLimits(), hooks)

    private fun crashAt(step: String): InstallHooks {
        var done = false
        return InstallHooks { if (it == step && !done) { done = true; throw SimulatedCrash(it) } }
    }

    private val journal get() = root.resolve(".createscript/transaction.json")

    private fun transaction(): Path = Files.list(root.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }

    // 中断しなかったときの結果（同じ入力で、別のディレクトリにインストールしたもの）
    private fun reference(): Map<String, String> {
        val other = createProject(temp.resolve("reference"))
        installer(dir = other).install(spec, false)
        return contents(other)
    }

    // プロジェクトのファイルと、置いたパッケージ（作業用のディレクトリを除く）
    private fun result(dir: Path = root) = contents(dir).filterKeys { !it.startsWith(".createscript/txn-") }

    private fun crash(step: String) {
        assertThrows(SimulatedCrash::class.java) { installer(crashAt(step)).install(spec, false) }
    }

    private fun stateFiles(): List<String> = Files.list(root.resolve(".createscript")).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    // ---- 続行 ----

    // ジャーナルを書いた後のどこで止まっても、次の install が続きを行い、中断しなかったときと同じ結果になる
    @ParameterizedTest
    @ValueSource(strings = ["journal", "package:numbers", "package:vector-math", "manifest", "lock", "committed", "cleaned"])
    fun completesAfterACrash(step: String) {
        val expected = reference()
        crash(step)
        assertTrue(Files.exists(journal), "the journal is kept after a crash at $step")
        val recovered = installer().install(null, false)
        assertEquals(if (step == "committed" || step == "cleaned") "finished cleaning up an installation that had completed" else "completed an interrupted installation", recovered.recovery)
        assertEquals(expected, result())
        assertEquals(listOf("install.lock", "packages"), stateFiles())
        // もう一度実行しても変わらない
        val before = snapshot(root)
        val again = installer().install(spec, false)
        assertEquals(null, again.recovery)
        assertEquals(before, snapshot(root))
    }

    // マニフェストを差し替えた直後・ロックファイルを差し替えた直後の状態と、その間の検査
    @Test
    fun crashAfterTheManifestAndAfterTheLock() {
        val expected = reference()
        crash("manifest")
        // マニフェストは新しく、ロックファイルはまだ無い（ジャーナルが、このずれを記録している）
        assertFalse(originalManifest.contentEquals(Files.readAllBytes(root.resolve("createscript.json"))))
        assertFalse(Files.exists(root.resolve("createscript.lock")))
        val parsed = Journal.parse(Files.readAllBytes(journal), 256).first!!
        assertEquals(Journal.State.PREPARED, parsed.state)
        assertEquals(GitHubReleases.sha256Hex(originalManifest), parsed.manifestOld)
        assertEquals(null, parsed.lockOld)
        // 復旧の途中、ロックファイルを差し替えた直後に止まる（ジャーナルはまだ prepared）
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("lock")).install(null, false) }
        assertTrue(Files.exists(root.resolve("createscript.lock")))
        assertEquals(Journal.State.PREPARED, Journal.parse(Files.readAllBytes(journal), 256).first!!.state)
        assertEquals("completed an interrupted installation", installer().install(null, false).recovery)
        assertEquals(expected, result())
        assertFalse(Files.exists(journal))
    }

    // 復旧の途中で止まっても、次の install が続きを行う
    @Test
    fun aCrashDuringRecoveryIsRecovered() {
        val expected = reference()
        crash("package:numbers")
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("manifest")).install(null, false) }
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("committed")).install(null, false) }
        assertEquals("finished cleaning up an installation that had completed", installer().install(null, false).recovery)
        assertEquals(expected, result())
    }

    // ---- 取り消し ----

    // 続きに必要な写しが無ければ取り消す（今回書いたものだけを戻し、置いたパッケージを消す）。元のファイルは同じバイト列に戻る
    @Test
    fun undoesWhenTheInstallationCannotBeCompleted() {
        crash("manifest")
        Files.delete(transaction().resolve("createscript.lock.new"))
        val recovered = installer().install(null, false)
        assertEquals("undid an interrupted installation", recovered.recovery)
        assertArrayEquals(originalManifest, Files.readAllBytes(root.resolve("createscript.json")))
        assertFalse(Files.exists(root.resolve("createscript.lock")))
        // マニフェストは差し替えた後なので、同じバイト列の別のファイルとして書き戻す（ほかのファイルは、ファイルそのものも同じ）
        assertEquals(outside(original, rewritten = true), outside(snapshot(root), rewritten = true))
        assertNoInstalledFiles()
    }

    // 取り消しの途中で止まっても、次の install が取り消しを終える
    @Test
    fun aCrashDuringUndoIsRecovered() {
        crash("lock")
        // マニフェストもロックファイルも既に new（new の写しは差し替えの移動で使われて、もう無い）。写しが要るのは、まだ差し替えていないファイルだけなので続行できる
        assertFalse(Files.exists(transaction().resolve("createscript.json.new")))
        assertEquals("completed an interrupted installation", installer().install(null, false).recovery)
        // 取り消しが必要な場合: マニフェストを差し替えた直後に止まり、ロックファイルの new の写しが無い
        val project = createProject(temp.resolve("undo"))
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("manifest"), project).install(spec, false) }
        val txn = Files.list(project.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }
        Files.delete(txn.resolve("createscript.lock.new"))
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("rollback:manifest"), project).install(null, false) }
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("rollback:package:vector-math"), project).install(null, false) }
        assertEquals("undid an interrupted installation", installer(dir = project).install(null, false).recovery)
        assertArrayEquals(originalManifest, Files.readAllBytes(project.resolve("createscript.json")))
        assertEquals(emptyList<Path>(), Files.walk(project.resolve(".createscript")).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString() != "install.lock" }.toList() })
    }

    // 途中の失敗（例外）は、その場で取り消す
    @ParameterizedTest
    @ValueSource(strings = ["journal", "package:numbers", "package:vector-math", "manifest", "lock"])
    fun undoesAFailureDuringTheInstallation(step: String) {
        val failure = assertThrows(InstallFailure::class.java) {
            installer({ if (it == step) throw IllegalStateException("disk full at $it") }).install(spec, false)
        }
        assertTrue(failure.message!!.contains("disk full at $step; undid an interrupted installation"), failure.message)
        assertArrayEquals(originalManifest, Files.readAllBytes(root.resolve("createscript.json")))
        assertFalse(Files.exists(root.resolve("createscript.lock")))
        // マニフェストを差し替える前の失敗なら、マニフェストはファイルそのもの（fileKey）も同じ。差し替えた後なら、同じバイト列に書き戻す
        val rewritten = step == "manifest" || step == "lock"
        assertEquals(outside(original, rewritten), outside(snapshot(root), rewritten))
        assertNoInstalledFiles()
    }

    // .createscript の外のファイル。rewritten なら、createscript.json は中身だけを比べる（fileKey を除く）
    private fun outside(snapshot: Map<String, String>, rewritten: Boolean): Map<String, String> = snapshot.filterKeys { !it.startsWith(".createscript") }
        .mapValues { (key, value) -> if (rewritten && key == "createscript.json") value.substringBeforeLast(":(") else value }

    private fun assertNoInstalledFiles() {
        val files = Files.walk(root.resolve(".createscript")).use { s -> s.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.map { root.relativize(it).toString() }.toList() }
        assertEquals(listOf(".createscript/install.lock"), files)
    }

    // ---- ほかのプログラムによる変更 ----

    // インストールの最中にマニフェストが書き換えられたら、上書きせずに取り消す（書き換えられた内容は残す）
    @Test
    fun keepsAManifestChangedByAnotherProgram() {
        val edited = """{"schemaVersion": 1, "name": "app", "version": "0.2.0", "entry": "src/main.cscr"}"""
        val failure = assertThrows(InstallFailure::class.java) {
            installer({ if (it == "journal") Files.writeString(root.resolve("createscript.json"), edited) }).install(spec, false)
        }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind)
        assertTrue(failure.message!!.contains("createscript.json was changed by another program during the installation; it was not overwritten"), failure.message)
        assertEquals(edited, Files.readString(root.resolve("createscript.json")))
        assertFalse(Files.exists(root.resolve("createscript.lock")))
        assertNoInstalledFiles()
    }

    // 同じ内容の別のファイルに差し替えられた場合も（fileKey で見分ける）
    @Test
    fun keepsAManifestReplacedByAnotherProgram() {
        val failure = assertThrows(InstallFailure::class.java) {
            installer({
                if (it == "journal") {
                    val copy = root.resolve("copy.json")
                    Files.write(copy, originalManifest)
                    Files.move(copy, root.resolve("createscript.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
            }).install(spec, false)
        }
        assertTrue(failure.message!!.contains("was replaced by another program"), failure.message)
        assertArrayEquals(originalManifest, Files.readAllBytes(root.resolve("createscript.json")))
        assertNoInstalledFiles()
    }

    // 置こうとしたディレクトリが、ほかのプログラムに作られていたら、それには触らない
    @Test
    fun keepsAPackageDirectoryCreatedByAnotherProgram() {
        val foreign = root.resolve(".createscript/packages/numbers/3.0.0")
        val failure = assertThrows(InstallFailure::class.java) {
            installer({
                if (it == "journal") {
                    Files.createDirectories(foreign)
                    Files.writeString(foreign.resolve("mine.txt"), "keep me")
                }
            }).install(spec, false)
        }
        assertTrue(failure.message!!.contains("cannot be used") || failure.message!!.contains("another program"), failure.message)
        assertEquals("keep me", Files.readString(foreign.resolve("mine.txt")))
        assertArrayEquals(originalManifest, Files.readAllBytes(root.resolve("createscript.json")))
        assertFalse(Files.exists(root.resolve(".createscript/packages/vector-math/1.2.0")))
        assertFalse(Files.exists(journal))
    }

    // 中断の後に、利用者がマニフェストを編集していたら、それを残して今回の変更だけを取り消し、止まる
    @Test
    fun keepsUserEditsMadeAfterACrash() {
        crash("lock")
        val edited = """{"schemaVersion": 1, "name": "app", "version": "0.3.0", "entry": "src/main.cscr"}"""
        Files.writeString(root.resolve("createscript.json"), edited)
        val lockBefore = Files.readAllBytes(root.resolve("createscript.lock"))
        val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind)
        assertTrue(failure.message!!.contains("undid an interrupted installation, but some files were changed by another program"), failure.message)
        assertEquals(edited, Files.readString(root.resolve("createscript.json")))
        // ロックファイルは今回書いたもの（new）なので取り消す（元は無かった）
        assertFalse(Files.exists(root.resolve("createscript.lock")), String(lockBefore))
        assertFalse(Files.exists(journal))
    }

    // ---- ジャーナルの破損・改ざん ----

    @Test
    fun refusesADamagedJournal() {
        crash("manifest")
        val good = Files.readAllBytes(journal)
        val text = String(good)
        val id = Journal.parse(good, 256).first!!.id
        val cases = mapOf(
            "garbage" to "not json".toByteArray(),
            "truncated" to good.copyOf(good.size / 2),
            "extra field" to text.replaceFirst("{", "{\n  \"extra\": 1,").toByteArray(),
            "path in id" to text.replace(id, "../../outside").toByteArray(),
            "unknown state" to text.replace("\"prepared\"", "\"done\"").toByteArray(),
            "invalid SHA-256" to text.replace(Journal.parse(good, 256).first!!.manifestOld, "abc").toByteArray(),
            "unknown version" to text.replace("\"journalVersion\": 1", "\"journalVersion\": 2").toByteArray(),
            "duplicate keys" to text.replaceFirst("{", "{\n  \"id\": \"$id\",").toByteArray(),
        )
        for ((label, bytes) in cases) {
            Files.write(journal, bytes)
            val before = snapshot(root)
            val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
            assertEquals(InstallFailure.Kind.UNSAFE, failure.kind, label)
            assertTrue(failure.message!!.contains("cannot be finished or undone safely") && failure.message!!.contains("Nothing was changed"), "$label: ${failure.message}")
            assertEquals(before, snapshot(root), label)
        }
        // シンボリックリンクのジャーナル
        val outside = Files.write(temp.resolve("outside.json"), good)
        Files.delete(journal)
        Files.createSymbolicLink(journal, outside)
        val before = snapshot(root)
        assertTrue(assertThrows(InstallFailure::class.java) { installer().install(null, false) }.message!!.contains("is not a regular file"))
        assertEquals(before, snapshot(root))
        assertArrayEquals(good, Files.readAllBytes(outside))
        // 元に戻せば復旧できる
        Files.delete(journal)
        Files.write(journal, good)
        assertEquals("completed an interrupted installation", installer().install(null, false).recovery)
    }

    // 形は正しいが、値を書き換えたジャーナル: 今のファイルが old でも new でもなければ、そのファイルには触らない
    @Test
    fun doesNotTrustATamperedJournal() {
        crash("manifest")
        val current = Files.readAllBytes(root.resolve("createscript.json"))
        val parsed = Journal.parse(Files.readAllBytes(journal), 256).first!!
        Files.write(journal, parsed.copy(manifestOld = "1".repeat(64), manifestNew = "2".repeat(64)).write())
        val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertTrue(failure.message!!.contains("left as they are"), failure.message)
        assertArrayEquals(current, Files.readAllBytes(root.resolve("createscript.json")))
        // 印のファイルの SHA-256 をジャーナルと違うものにすると、そのパッケージは「このインストールのもの」と認めない（消さない）
        val project = createProject(temp.resolve("marker"))
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("manifest"), project).install(spec, false) }
        val projectJournal = project.resolve(".createscript/transaction.json")
        val j = Journal.parse(Files.readAllBytes(projectJournal), 256).first!!
        Files.write(projectJournal, j.copy(packages = j.packages.map { it.copy(marker = "3".repeat(64)) }).write())
        Files.delete(Files.list(project.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }.resolve("createscript.lock.new"))
        // マニフェストは今回書いたもの（new）なので戻すが、パッケージは「このインストールのもの」と確かめられないので消さない
        assertEquals("undid an interrupted installation", installer(dir = project).install(null, false).recovery)
        assertArrayEquals(originalManifest, Files.readAllBytes(project.resolve("createscript.json")))
        assertTrue(Files.exists(project.resolve(".createscript/packages/numbers/3.0.0/modules/numbers.cscr")))
        assertTrue(Files.exists(project.resolve(".createscript/packages/vector-math/1.2.0/modules/vector_math.cscr")))
    }

    // 作業用のディレクトリの写しが書き換えられていたら、それを使わない
    @Test
    fun doesNotUseChangedSavedCopies() {
        crash("manifest")
        val txn = transaction()
        Files.writeString(txn.resolve("createscript.json.old"), "{}")
        Files.writeString(txn.resolve("createscript.lock.new"), "{}")
        val before = snapshot(root)
        // 続行（lock.new が違う）も、取り消し（json.old が違う）もできない
        val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertTrue(failure.message!!.contains("cannot be finished or undone safely"), failure.message)
        assertEquals(before, snapshot(root))
        assertNotNull(Files.readString(txn.resolve("createscript.json.old")))
    }
}
