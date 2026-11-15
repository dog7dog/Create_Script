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
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

// S8.2 L-3: ジャーナルから参照されない作業用のディレクトリ（txn-*）の片付け。L-6: ハードリンクを作れないファイルシステムでのジャーナルの公開と、
// ._ のファイルを作るファイルシステム
class LeftoverTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private val numbers = Packages.source("numbers")
    private val vectorMath = Packages.source("vector-math")
    private val spec = PackageSpec(vectorMath, "1.2.0")
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
        root = createProject(temp.resolve("app"))
    }

    private fun installer(hooks: InstallHooks = InstallHooks.NONE, fileSystem: InstallFileSystem = InstallFileSystem.DEFAULT, dir: Path = root) =
        Installer(dir, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), hooks, DirectoryKeys.FILE_KEY, fileSystem)

    private fun crashAt(step: String): InstallHooks {
        var done = false
        return InstallHooks { if (it == step && !done) { done = true; throw SimulatedCrash(it) } }
    }

    private fun crash(step: String, fileSystem: InstallFileSystem = InstallFileSystem.DEFAULT) {
        assertThrows(SimulatedCrash::class.java) { installer(crashAt(step), fileSystem).install(spec, false) }
    }

    private fun txn(): Path = Files.list(root.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }

    private fun stateFiles(): List<String> = Files.list(root.resolve(".createscript")).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    // 作業用のディレクトリの中身（パス → SHA-256・リンク先）
    private fun tree(dir: Path): Map<String, String> = Files.walk(dir).use { s ->
        s.filter { it != dir }.toList().associate { path ->
            dir.relativize(path).toString() to when {
                Files.isSymbolicLink(path) -> "link:${Files.readSymbolicLink(path)}"
                Files.isDirectory(path) -> "dir"
                else -> SafeIo.sha256Hex(Files.readAllBytes(path))
            }
        }
    }

    // 残した: 中身はそのまま、理由を知らせ、インストールは続ける（作業用のディレクトリは使わない）
    private fun assertKept(dir: Path, reason: String) {
        val before = tree(dir)
        val result = installer().install(spec, false)
        val name = ".createscript/${dir.fileName}"
        val warning = result.warnings.singleOrNull { it.startsWith("$name was not removed: ") } ?: throw AssertionError(result.warnings.toString())
        assertTrue(warning.contains(reason), warning)
        assertTrue(warning.endsWith("createscript does not use it; check it and remove it by hand"), warning)
        assertFalse(warning.contains("stopped before it changed anything"), warning)
        assertEquals(before, tree(dir))
        assertEquals(2, result.packages.size)
        assertTrue(Files.exists(root.resolve("createscript.lock")))
    }

    // ---- 消すもの ----

    @Test
    fun removesAnEmptyDirectoryAndOneThatStoppedWhileBeingRemoved() {
        // 片付けの途中で止まったもの: パッケージの写しは消え、写しと journal.tmp が残る
        crash("staged")
        val partial = txn()
        partial.resolve("staging").toFile().deleteRecursively()
        // 作った直後に止まったもの（空）
        Files.createDirectories(root.resolve(".createscript/txn-00000000-0000-4000-8000-000000000001"))
        val result = installer().install(spec, false)
        assertEquals(2, result.warnings.count { it.startsWith("removed .createscript/txn-") }, result.warnings.toString())
        assertEquals(listOf("install.lock", "packages"), stateFiles())
    }

    // 印を書いた直後（パッケージのファイルはまだ無い）・パッケージの一部を書いた後でも、記録と照合できるので消す
    @Test
    fun removesAPartlyStagedPackage() {
        crash("staging:numbers")
        val staged = txn().resolve("staging/numbers/3.0.0")
        assertEquals(listOf(InstallLayout.MARKER), Files.list(staged).use { s -> s.map { it.fileName.toString() }.toList() })
        val result = installer().install(spec, false)
        assertEquals(1, result.warnings.count { it.startsWith("removed .createscript/txn-") }, result.warnings.toString())
        assertEquals(listOf("install.lock", "packages"), stateFiles())
        // 書いたファイルが一部だけ（印に書かれたファイルの一部が無い）
        val other = createProject(temp.resolve("other"))
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("staged"), dir = other).install(spec, false) }
        val otherTxn = Files.list(other.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }
        Files.delete(otherTxn.resolve("staging/vector-math/1.2.0/LICENSE"))
        assertEquals(1, installer(dir = other).install(spec, false).warnings.count { it.startsWith("removed .createscript/txn-") })
        assertFalse(Files.exists(otherTxn))
    }

    // ---- 残すもの ----

    // ジャーナルの作成の途中で止まったもの（journal.tmp が書きかけ）: 記録として読めないので、消さない
    @Test
    fun keepsAnIncompleteRecord() {
        crash("recorded")
        val dir = txn()
        val record = dir.resolve(Recovery.JOURNAL_TEMP)
        val bytes = Files.readAllBytes(record)
        Files.write(record, bytes.copyOf(bytes.size / 2))
        assertKept(dir, "its journal.tmp is incomplete or damaged")
    }

    // 利用者が変えたファイル・書きかけのファイル・知らないファイルは、消さない（何も消さない）
    @Test
    fun keepsChangedAndUnknownFiles() {
        for ((label, change) in mapOf<String, (Path) -> Unit>(
            "changed copy" to { t -> Files.writeString(t.resolve(Recovery.MANIFEST_OLD), "edited by the user") },
            "changed package file" to { t -> Files.writeString(t.resolve("staging/numbers/3.0.0/modules/numbers.cscr"), "fun one(): Int {\n    return 2\n}\n") },
            "file written in part" to { t -> t.resolve("staging/vector-math/1.2.0/LICENSE").let { Files.write(it, Files.readAllBytes(it).copyOf(3)) } },
            "unknown file" to { t -> Files.writeString(t.resolve("notes.txt"), "mine") },
            "unknown package file" to { t -> Files.writeString(t.resolve("staging/numbers/3.0.0/extra.txt"), "mine") },
            "AppleDouble file" to { t -> Files.writeString(t.resolve("staging/numbers/3.0.0/._LICENSE"), "x") },
            "files without a marker" to { t -> Files.delete(t.resolve("staging/numbers/3.0.0/${InstallLayout.MARKER}")) },
        )) {
            setUp()
            root = createProject(temp.resolve("keep-${label.replace(' ', '-')}"))
            crash("staged")
            val dir = txn()
            change(dir)
            val expected = when (label) {
                "changed copy" -> "createscript.json.old does not match the record in journal.tmp"
                "changed package file" -> "modules/numbers.cscr does not match .createscript-package.json"
                "file written in part" -> "LICENSE does not match .createscript-package.json (it was changed, or written only in part)"
                "unknown file" -> "it has a file that createscript install does not make: notes.txt"
                "unknown package file" -> "has a file that was not in the package: extra.txt"
                "AppleDouble file" -> "macOS makes such ._ files on FAT and exFAT drives"
                else -> "has files but no .createscript-package.json"
            }
            assertKept(dir, expected)
        }
    }

    // シンボリックリンクを含むもの: 消さず、リンク先にも触らない
    @Test
    fun keepsLeftoversWithSymbolicLinks() {
        val outside = Files.createDirectories(temp.resolve("outside"))
        Files.writeString(outside.resolve("precious.txt"), "keep me")
        for ((label, change) in mapOf<String, (Path) -> Unit>(
            "staging" to { t ->
                t.resolve("staging").toFile().deleteRecursively()
                Files.createSymbolicLink(t.resolve("staging"), outside)
            },
            "package file" to { t ->
                val file = t.resolve("staging/numbers/3.0.0/LICENSE")
                Files.delete(file)
                Files.createSymbolicLink(file, outside.resolve("precious.txt"))
            },
            "record" to { t ->
                val record = t.resolve(Recovery.JOURNAL_TEMP)
                val copy = Files.copy(record, outside.resolve("journal.tmp"))
                Files.delete(record)
                Files.createSymbolicLink(record, copy)
            },
        )) {
            root = createProject(temp.resolve("link-$label".replace(' ', '-')))
            crash("staged")
            val dir = txn()
            change(dir)
            assertKept(dir, "is a symbolic link")
            assertEquals("keep me", Files.readString(outside.resolve("precious.txt")))
            assertTrue(Files.exists(outside.resolve("precious.txt")))
        }
        // 作業用のディレクトリそのものがリンク
        root = createProject(temp.resolve("link-txn"))
        Files.createDirectories(root.resolve(".createscript"))
        val link = Files.createSymbolicLink(root.resolve(".createscript/txn-00000000-0000-4000-8000-000000000002"), outside)
        val result = installer().install(spec, false)
        assertTrue(result.warnings.any { it.contains("txn-00000000-0000-4000-8000-000000000002 was not removed: it is a symbolic link") }, result.warnings.toString())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("keep me", Files.readString(outside.resolve("precious.txt")))
        // createscript が作らない名前
        val odd = Files.createDirectories(root.resolve(".createscript/txn-mine"))
        assertKept(odd, "its name is not one that createscript install makes")
    }

    // 取り消しの後の残り（片付けで消せなかったもの。journal.tmp はもう無い）: 「何も変えずに止まったインストールの残り」とは言わない
    @Test
    fun describesWhatAnUndoLeftCorrectly() {
        crash("manifest")
        val dir = txn()
        Files.delete(dir.resolve(Recovery.LOCK_NEW))
        Files.writeString(dir.resolve("notes.txt"), "the user's notes")
        val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertTrue(failure.message!!.contains("undid an interrupted installation, but some files were changed by another program"), failure.message)
        assertFalse(Files.exists(root.resolve(".createscript/transaction.json")))
        assertKept(dir, "it has no record of what it holds (journal.tmp); it may hold files that an earlier installation, finished or undone, could not confirm")
        assertEquals("the user's notes", Files.readString(dir.resolve("notes.txt")))
    }

    // 正常な中断の復旧は、作業用のディレクトリを Recovery が消し、残りの警告は出ない
    @Test
    fun aNormalRecoveryLeavesNothing() {
        crash("package:numbers")
        val recovered = installer().install(null, false)
        assertEquals("completed an interrupted installation", recovered.recovery)
        assertEquals(emptyList<String>(), recovered.warnings)
        assertEquals(listOf("install.lock", "packages"), stateFiles())
    }

    // ジャーナルがある間は、ほかの作業用のディレクトリにも触らない（復旧できずに止まる）
    @Test
    fun doesNotTouchLeftoversWhileAJournalExists() {
        crash("manifest")
        val leftover = Files.createDirectories(root.resolve(".createscript/txn-00000000-0000-4000-8000-000000000003"))
        Files.write(root.resolve(".createscript/transaction.json"), "{".toByteArray())
        val before = snapshot(root)
        assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertEquals(before, snapshot(root))
        assertTrue(Files.isDirectory(leftover))
    }

    // ---- L-6: ジャーナルの公開 ----

    private val noLinks = InstallFileSystem(createLink = { target, _ -> throw FileSystemException(target.toString(), null, "Operation not supported") })

    // ハードリンクを作れないファイルシステムでは、原子的な移動で公開する（写さない）。インストールも、中断からの復旧も同じ結果になる
    @Test
    fun publishesTheJournalWithAnAtomicMoveWithoutHardLinks() {
        val result = installer(fileSystem = noLinks).install(spec, false)
        assertEquals(2, result.packages.count { it.installed })
        assertEquals(listOf("install.lock", "packages"), stateFiles())
        val other = createProject(temp.resolve("crashed"))
        assertThrows(SimulatedCrash::class.java) { installer(crashAt("journal"), noLinks, other).install(spec, false) }
        val txnDir = Files.list(other.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }
        // journal.tmp は移動したので、作業用のディレクトリには無い
        assertFalse(Files.exists(txnDir.resolve(Recovery.JOURNAL_TEMP)))
        assertEquals("completed an interrupted installation", installer(fileSystem = noLinks, dir = other).install(null, false).recovery)
        assertArrayEquals(Files.readAllBytes(root.resolve("createscript.lock")), Files.readAllBytes(other.resolve("createscript.lock")))
    }

    // ハードリンクも原子的な移動もできなければ、何も変えずに止まる（ジャーナルを写して作ることはしない）
    @Test
    fun stopsWithoutHardLinksAndAtomicMoves() {
        val neither = InstallFileSystem(
            createLink = { _, _ -> throw UnsupportedOperationException() },
            atomicMove = { source, target -> throw java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported") },
        )
        val before = snapshot(root)
        val failure = assertThrows(InstallFailure::class.java) { installer(fileSystem = neither).install(spec, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind)
        assertTrue(failure.message!!.contains("supports neither hard links nor atomic renames") && failure.message!!.contains("nothing was changed"), failure.message)
        assertUnchanged(before, root)
        assertFalse(Files.exists(root.resolve(".createscript/transaction.json")))
        assertEquals(listOf("install.lock"), stateFiles())
    }

    // 公開の途中の失敗で、ジャーナルが公開されたか分からないときは、作業用のディレクトリを消さずに止まり、次の install が扱う
    @Test
    fun keepsTheTransactionWhenThePublicationIsUncertain() {
        val uncertain = InstallFileSystem(
            createLink = { target, _ -> throw FileSystemException(target.toString(), null, "Operation not supported") },
            atomicMove = { source, target ->
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                throw FileSystemException(target.toString(), null, "Input/output error")
            },
        )
        val failure = assertThrows(InstallFailure::class.java) { installer(fileSystem = uncertain).install(spec, false) }
        assertTrue(failure.message!!.contains("cannot tell whether .createscript/transaction.json was written completely"), failure.message)
        assertTrue(Files.exists(root.resolve(".createscript/transaction.json")))
        assertEquals("completed an interrupted installation", installer().install(null, false).recovery)
        assertEquals(listOf("install.lock", "packages"), stateFiles())
    }

    // ジャーナルの作成の途中で止まったもの（書きかけの transaction.json）は、正しいジャーナルとして使わない
    @Test
    fun aHalfWrittenJournalIsNeverUsed() {
        crash("journal")
        val journal = root.resolve(".createscript/transaction.json")
        val good = Files.readAllBytes(journal)
        for (size in listOf(0, 1, good.size / 3, good.size - 2)) {
            Files.write(journal, good.copyOf(size))
            val before = snapshot(root)
            val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
            assertTrue(failure.message!!.contains("is damaged") && failure.message!!.contains("Nothing was changed"), "$size: ${failure.message}")
            assertEquals(before, snapshot(root))
        }
        Files.write(journal, good)
        assertEquals("completed an interrupted installation", installer().install(null, false).recovery)
    }

    // ---- L-6: ._ のファイルを作るファイルシステム ----

    @Test
    fun refusesFileSystemsThatAddSidecarFiles() {
        val before = snapshot(root)
        val failure = assertThrows(InstallFailure::class.java) { installer(fileSystem = InstallFileSystem(addsSidecarFiles = { true })).install(spec, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind)
        assertTrue(failure.message!!.contains("adds a ._ file next to every new file") && failure.message!!.contains("Nothing was changed"), failure.message)
        assertUnchanged(before, root)
        // 本物の確認は、ふつうのファイルシステムでは false で、試しのファイルを残さない
        val dir = Files.createDirectories(temp.resolve("probe"))
        assertFalse(SafeIo.addsSidecarFiles(dir))
        assertEquals(0L, Files.list(dir).use { it.count() })
    }

    // 置いたパッケージに ._ のファイルがあれば、run の前の確認は（弱めずに）拒否し、理由を示す
    @Test
    fun explainsSidecarFilesInInstalledPackages() {
        installer().install(spec, false)
        Files.writeString(root.resolve(".createscript/packages/numbers/3.0.0/modules/._numbers.cscr"), "x")
        val lockfile = Lockfiles.parse(Files.readAllBytes(root.resolve("createscript.lock"))).lockfile!!
        val failure = assertThrows(InstallFailure::class.java) { InstalledPackages.verify(root, lockfile) }
        assertTrue(failure.message!!.contains("it has a file that was not installed: modules/._numbers.cscr (macOS makes such ._ files on FAT and exFAT drives"), failure.message)
    }
}
