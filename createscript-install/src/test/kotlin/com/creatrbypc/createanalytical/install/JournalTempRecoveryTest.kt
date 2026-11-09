package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfiles
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// S8.2 N-1: ジャーナルを書き換える途中（作業用のディレクトリの journal.tmp を書いている途中）で止まったインストールの復旧。
// N-2: ジャーナルが参照する作業用のディレクトリが無いときの説明
class JournalTempRecoveryTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private val numbers = Packages.source("numbers")
    private val vectorMath = Packages.source("vector-math")
    private val spec = PackageSpec(vectorMath, "1.2.0")
    private var count = 0

    @BeforeEach
    fun setUp() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
    }

    private fun installer(root: Path, hooks: InstallHooks = InstallHooks.NONE) = Installer(root, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), hooks)

    private fun journalPath(root: Path) = root.resolve(".createscript/transaction.json")

    private fun journal(root: Path) = Journal.parse(Files.readAllBytes(journalPath(root)), 256).first!!

    private fun txn(root: Path) = root.resolve(".createscript/txn-${journal(root).id}")

    // マニフェスト・ロックファイルを差し替えた後、committed のジャーナルを書く前に止まったプロジェクト（ジャーナルは prepared のまま）
    private fun crashedBeforeCommit(step: String = "lock"): Path {
        val root = createProject(temp.resolve("app-${count++}"))
        assertThrows(SimulatedCrash::class.java) { installer(root, { if (it == step) throw SimulatedCrash(it) }).install(spec, false) }
        return root
    }

    private fun assertRecovered(root: Path, expected: String = "completed an interrupted installation") {
        val result = installer(root).install(null, false)
        assertEquals(expected, result.recovery)
        assertEquals(listOf("install.lock", "packages"), Files.list(root.resolve(".createscript")).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
        val lockfile = Lockfiles.parse(Files.readAllBytes(root.resolve("createscript.lock"))).lockfile!!
        assertEquals(listOf("numbers", "vector-math"), InstalledPackages.verify(root, lockfile).map { it.name })
    }

    // 自動では復旧せず、何も変えずに、具体的な手順を示して止まる
    private fun assertStops(root: Path, vararg expected: String): String {
        val before = snapshot(root)
        val failure = assertThrows(InstallFailure::class.java) { installer(root).install(null, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind)
        val message = failure.message!!
        assertTrue(message.contains("cannot be finished or undone safely") && message.contains("Nothing was changed") && message.contains("To check by hand:"), message)
        for (text in expected) assertTrue(message.contains(text), "missing <$text> in:\n$message")
        assertEquals(before, snapshot(root))
        return message
    }

    // ---- N-1 の再現と修正 ----

    // 監査の再現: committed のジャーナルを journal.tmp に書いている途中で止まった（書きかけ）。以前は、その後の install がずっと 73 で止まった
    @Test
    fun recoversWhenTheCommittedJournalWasBeingWritten() {
        for (fraction in listOf(0.0, 0.01, 0.5, 0.99)) {
            val root = crashedBeforeCommit()
            val committed = journal(root).committed().write()
            Files.write(txn(root).resolve(Recovery.JOURNAL_TEMP), committed.copyOf((committed.size * fraction).toInt()))
            assertRecovered(root)
        }
        // 書き終えて、名前を変える前に止まったもの（完全な committed のジャーナル）
        val root = crashedBeforeCommit()
        Files.write(txn(root).resolve(Recovery.JOURNAL_TEMP), journal(root).committed().write())
        assertRecovered(root)
    }

    // 最初のジャーナル（prepared）の書きかけ・リンクで名前を付けた後に消す前に止まったものも、このトランザクションのもの
    @Test
    fun recoversWithAPreparedJournalTemp() {
        for (step in listOf("journal", "package:numbers", "manifest")) {
            val root = crashedBeforeCommit(step)
            val prepared = Files.readAllBytes(journalPath(root))
            Files.write(txn(root).resolve(Recovery.JOURNAL_TEMP), prepared.copyOf(prepared.size / 3))
            assertRecovered(root)
            val again = crashedBeforeCommit(step)
            Files.createLink(txn(again).resolve(Recovery.JOURNAL_TEMP), journalPath(again))
            assertRecovered(again)
        }
    }

    // ---- 片付けてよいと確かめられない journal.tmp ----

    // 正式なジャーナルは正しく、一時ファイルだけが別の中身: 消さずに、手順を示して止まる。手順のとおり外へ移せば復旧する
    @Test
    fun stopsWhenTheTemporaryFileHasOtherContents() {
        val root = crashedBeforeCommit()
        val committed = journal(root).committed().write()
        for (contents in listOf("my notes".toByteArray(), committed.copyOf(committed.size / 2).also { it[it.size - 1] = 'X'.code.toByte() }, journal(root).copy(id = "00000000-0000-4000-8000-000000000000").write())) {
            val temporary = txn(root).resolve(Recovery.JOURNAL_TEMP)
            Files.write(temporary, contents)
            val message = assertStops(
                root,
                "journal.tmp, the temporary file used to rewrite the journal, cannot be removed safely (it is neither a journal of this installation nor the beginning of one that was being written)",
                "  1. Move .createscript/txn-${journal(root).id}/journal.tmp out of the project (keep it until you are sure). It is only a temporary copy; the journal itself is .createscript/transaction.json.",
            )
            assertTrue(message.contains("  2. Compare createscript.json and createscript.lock with the saved copies"), message)
            assertArrayEquals(contents, Files.readAllBytes(temporary))
        }
        Files.move(txn(root).resolve(Recovery.JOURNAL_TEMP), temp.resolve("kept-journal.tmp"))
        assertRecovered(root)
    }

    // シンボリックリンク・ディレクトリ: 消さず、リンクの先にも触らない
    @Test
    fun stopsAtALinkOrADirectory() {
        val root = crashedBeforeCommit()
        val outside = Files.write(temp.resolve("outside.json"), journal(root).committed().write().copyOf(10))
        val temporary = txn(root).resolve(Recovery.JOURNAL_TEMP)
        Files.createSymbolicLink(temporary, outside)
        assertStops(root, "cannot be removed safely (it is a symbolic link)")
        assertTrue(Files.isSymbolicLink(temporary))
        assertEquals(10L, Files.size(outside))
        Files.delete(temporary)
        Files.createDirectories(temporary.resolve("inside"))
        assertStops(root, "cannot be removed safely (it is not a regular file)")
        assertTrue(Files.isDirectory(temporary.resolve("inside")))
    }

    // ほかのユーザーのもの・読めないもの（同一性を確かめられない）
    @Test
    fun stopsAtAFileOfAnotherUserOrOneThatCannotBeRead() {
        val root = crashedBeforeCommit()
        val temporary = txn(root).resolve(Recovery.JOURNAL_TEMP)
        Files.write(temporary, journal(root).committed().write().copyOf(5))
        // テストではほかのユーザーのファイルを作れないので、journal.tmp だけをほかのユーザーのものとみなす
        val layout = Layout(root.toRealPath())
        val owner = Owner.forTests { it.fileName.toString() != Recovery.JOURNAL_TEMP }
        val before = snapshot(root)
        val failure = assertThrows(InstallFailure::class.java) { Recovery(layout, owner, InstallLimits(), InstallHooks.NONE).recover(preferForward = true) }
        assertTrue(failure.message!!.contains("cannot be removed safely (it belongs to another user)") && failure.message!!.contains("Nothing was changed"), failure.message)
        assertEquals(before, snapshot(root))
        // 読めないもの
        Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("---------"))
        try {
            val manifest = Files.readAllBytes(root.resolve("createscript.json"))
            val unreadable = assertThrows(InstallFailure::class.java) { installer(root).install(null, false) }
            assertTrue(unreadable.message!!.contains("cannot be removed safely (cannot read") && unreadable.message!!.contains("Nothing was changed"), unreadable.message)
            assertTrue(Files.exists(temporary) && Files.exists(journalPath(root)))
            assertArrayEquals(manifest, Files.readAllBytes(root.resolve("createscript.json")))
        } finally {
            Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        }
        assertRecovered(root)
    }

    // 正式なジャーナルが偽造されたもの（別の場所で書かれたもの）は、journal.tmp を見る前に、書いた場所の確認で止まる（L-2）。journal.tmp も消さない
    @Test
    fun aForgedJournalIsRefusedBeforeTheTemporaryFile() {
        val root = crashedBeforeCommit()
        val temporary = txn(root).resolve(Recovery.JOURNAL_TEMP)
        Files.write(temporary, journal(root).committed().write().copyOf(7))
        val copied = temp.resolve("copied")
        Files.walk(root).use { s -> s.forEach { p -> val t = copied.resolve(root.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(t) else Files.copy(p, t) } }
        assertStops(copied, "was written for another directory")
        assertTrue(Files.exists(copied.resolve(root.relativize(temporary).toString())))
    }

    // ---- N-2: 作業用のディレクトリが無い ----

    @Test
    fun explainsAMissingTransactionDirectory() {
        val root = crashedBeforeCommit("manifest")
        val id = journal(root).id
        val moved = temp.resolve("moved-txn")
        Files.move(txn(root), moved)
        val message = assertStops(
            root,
            ".createscript/transaction.json refers to .createscript/txn-$id, which does not exist (it was removed or moved), so the saved copies needed to finish or undo the installation are missing",
            "  1. The saved copies (.createscript/txn-$id/) are missing. If you moved that directory, put it back and run createscript install again.",
            "  3. Only when both files are as you want them, move .createscript/transaction.json out of the project",
        )
        assertFalse(message.contains("file identities"), message)
        // 手順のとおり戻せば復旧する
        Files.move(moved, root.resolve(".createscript/txn-$id"))
        assertRecovered(root)
        // 済んだ（committed）インストールの片付けの途中なら、作業用のディレクトリが無くても片付けを終える
        val done = crashedBeforeCommit("committed")
        txn(done).toFile().deleteRecursively()
        assertRecovered(done, "finished cleaning up an installation that had completed")
    }

    // ---- 正常なインストールと中断からの復旧は、これまでどおり ----

    @Test
    fun normalInstallationsAndRecoveries() {
        val root = createProject(temp.resolve("normal"))
        assertEquals(2, installer(root).install(spec, false).packages.count { it.installed })
        assertEquals(emptyList<String>(), installer(root).install(null, false).warnings)
        for (step in listOf("journal", "package:vector-math", "manifest", "lock", "committed", "cleaned")) {
            val crashed = crashedBeforeCommit(step)
            assertRecovered(crashed, if (step == "committed" || step == "cleaned") "finished cleaning up an installation that had completed" else "completed an interrupted installation")
        }
    }
}
