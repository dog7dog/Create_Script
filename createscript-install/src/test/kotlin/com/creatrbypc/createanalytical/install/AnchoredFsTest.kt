package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// S8.3 L-5 の修正（作業ディレクトリを固定した子プロセス）を、差し替えの時点を決めて確かめる。
// 守る性質: プロジェクトの外の既存のファイルを変えない・消さない、外に新しいものを作らない・移さない
class AnchoredFsTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var root: Path
    private lateinit var outside: Path
    private lateinit var fs: AnchoredFs

    @BeforeEach
    fun setUp() {
        val base = temp.toRealPath()
        root = Files.createDirectories(base.resolve("project"))
        Files.createDirectories(root.resolve(".createscript/txn-1/staging"))
        outside = Files.createDirectories(base.resolve("outside"))
        // おとり: 作業用のディレクトリと同じ名前の利用者のファイル
        Files.writeString(outside.resolve("journal.tmp"), "user journal")
        Files.writeString(outside.resolve("createscript.json"), "user manifest")
        Files.createDirectories(outside.resolve("staging"))
        Files.writeString(outside.resolve("staging/LICENSE"), "user license")
        val opened = AnchoredFs.open(root, timeoutMillis = 5_000)
        assumeTrue(opened != null, "the file helper is used on macOS and Linux only")
        fs = opened!!
    }

    private val txn get() = root.resolve(".createscript/txn-1")

    private fun outsideState(): Map<String, String> = Files.walk(outside).use { s ->
        s.filter { it != outside }.toList().associate { path ->
            outside.relativize(path).toString() to when {
                Files.isSymbolicLink(path) -> "link"
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "dir"
                else -> Files.readString(path) + " " + PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
            }
        }
    }

    // directory を、中身ごと外へ退け（プロジェクトの中の別の名前）、同じ名前で外へのリンクを作る
    private fun swap(directory: Path, to: Path = outside): Path {
        val parked = directory.resolveSibling(directory.fileName.toString() + ".parked")
        Files.move(directory, parked)
        Files.createSymbolicLink(directory, to)
        return parked
    }

    private fun unswap(directory: Path, parked: Path) {
        Files.delete(directory)
        Files.move(parked, directory)
    }

    // ---- 通常の操作 ----

    @Test
    fun normalOperations() {
        val dir = txn.resolve("staging/numbers")
        val id = fs.mkdir(dir)
        assertEquals(SafeIo.fileId(dir), id)
        val fileId = fs.write(dir.resolve("LICENSE"), "MIT".toByteArray(), replace = false)
        assertEquals("MIT", Files.readString(dir.resolve("LICENSE")))
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("LICENSE"))))
        assertEquals(SafeIo.fileId(dir.resolve("LICENSE")), fileId)
        assertThrows(FileAlreadyExistsException::class.java) { fs.write(dir.resolve("LICENSE"), "x".toByteArray(), replace = false) }
        fs.write(dir.resolve("LICENSE"), "MIT 2".toByteArray(), replace = true, mode = 0b110_000_000)
        assertEquals("MIT 2", Files.readString(dir.resolve("LICENSE")))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("LICENSE"))))
        val licenseId = fs.stat(dir.resolve("LICENSE"))!!.id
        fs.link(dir.resolve("LICENSE"), dir.resolve("COPY"), licenseId)
        assertEquals(licenseId, SafeIo.fileId(dir.resolve("COPY")))
        assertTrue(fs.remove(dir.resolve("COPY"), licenseId))
        fs.rename(dir.resolve("LICENSE"), dir.resolve("LICENSE2"), licenseId, replace = false)
        assertFalse(Files.exists(dir.resolve("LICENSE")))
        assertThrows(java.nio.file.DirectoryNotEmptyException::class.java) { fs.remove(dir, null) }
        assertTrue(fs.remove(dir.resolve("LICENSE2"), null))
        assertTrue(fs.remove(dir, id))
        assertFalse(fs.remove(dir, null))
        assertNull(fs.stat(dir))
        // 空のファイルも書ける
        fs.write(txn.resolve("empty"), ByteArray(0), replace = false)
        assertEquals(0L, Files.size(txn.resolve("empty")))
    }

    // ---- 操作の前に、途中のディレクトリがリンクに差し替えられている ----

    @Test
    fun aReplacedDirectoryOnThePathIsRefusedByEveryOperation() {
        val before = outsideState()
        for (swapped in listOf(root.resolve(".createscript"), txn, txn.resolve("staging"))) {
            val parked = swap(swapped)
            val operations = listOf<Pair<String, () -> Unit>>(
                "mkdir" to { fs.mkdir(txn.resolve("staging/new")) },
                "write" to { fs.write(txn.resolve("staging/LICENSE"), "ours".toByteArray(), replace = false) },
                "replace" to { fs.write(txn.resolve("staging/LICENSE"), "ours".toByteArray(), replace = true) },
                "remove" to { fs.remove(txn.resolve("staging/LICENSE"), null) },
                "rename" to { fs.rename(txn.resolve("staging/LICENSE"), txn.resolve("staging/X"), "1:1", replace = true) },
                "link" to { fs.link(txn.resolve("staging/LICENSE"), txn.resolve("staging/X"), "1:1") },
                "stat" to { fs.stat(txn.resolve("staging/LICENSE")) },
            )
            for ((label, operation) in operations) {
                val failure = assertThrows(InstallFailure::class.java, { operation() }, "$swapped $label")
                assertTrue(failure.message!!.contains("L-5"), "$swapped $label: ${failure.message}")
            }
            assertEquals(before, outsideState(), "$swapped")
            unswap(swapped, parked)
        }
    }

    // プロジェクトのディレクトリそのものが差し替えられた
    @Test
    fun aReplacedProjectDirectoryIsRefused() {
        val before = outsideState()
        val parked = swap(root)
        assertTrue(assertThrows(InstallFailure::class.java) { fs.write(root.resolve("createscript.json"), "x".toByteArray(), replace = true) }.message!!.contains("L-5"))
        assertEquals(before, outsideState())
        unswap(root, parked)
    }

    // ---- 書き込みの各段階で、親のディレクトリを差し替える ----

    // 固定した後の差し替えは、固定したディレクトリ（退けた元のディレクトリ）に対して続くか、失敗する。外には何も書かれない
    @Test
    fun aSwapAtAnyStepOfAWriteNeverReachesOutside() {
        val before = outsideState()
        for (step in listOf("creating", "writing:journal.tmp", "written:journal.tmp", "renamed:journal.tmp")) {
            var parked: Path? = null
            try {
                fs.write(txn.resolve("journal.tmp"), "prepared journal".toByteArray(), replace = false, onStep = { if (it == step) parked = swap(txn) })
            } catch (failure: Exception) {
                // 失敗してよい（外へは書かない）
            }
            assertEquals(before, outsideState(), step)
            val real = parked ?: txn
            // 書いたものは、固定した元のディレクトリにある
            assertEquals("prepared journal", Files.readString(real.resolve("journal.tmp")), step)
            parked?.let { unswap(txn, it) }
            Files.delete(txn.resolve("journal.tmp"))
        }
    }

    // ---- 最後の要素への攻撃 ----

    @Test
    fun theFinalNameIsNeverFollowed() {
        val before = outsideState()
        val dir = txn.resolve("staging")
        // 新しい名前の公開: 既にリンクがあれば置き換えず、リンクの先も変えない
        Files.createSymbolicLink(dir.resolve("createscript.json"), outside.resolve("createscript.json"))
        assertThrows(FileAlreadyExistsException::class.java) { fs.write(dir.resolve("createscript.json"), "ours".toByteArray(), replace = false) }
        assertEquals(before, outsideState())
        // 置き換え: リンクそのものを置き換え、リンクの先（外のファイル）は変えない
        fs.write(dir.resolve("createscript.json"), "ours".toByteArray(), replace = true)
        assertFalse(Files.isSymbolicLink(dir.resolve("createscript.json")))
        assertEquals("ours", Files.readString(dir.resolve("createscript.json")))
        assertEquals(before, outsideState())
        // 外のディレクトリへのリンクを宛先にした付け替え: その中へは入らない
        Files.createSymbolicLink(dir.resolve("into"), outside.resolve("staging"))
        val id = fs.stat(dir.resolve("createscript.json"))!!.id
        fs.rename(dir.resolve("createscript.json"), dir.resolve("into"), id, replace = true)
        assertEquals(before, outsideState())
        // 削除: リンクそのものを消す
        Files.createSymbolicLink(dir.resolve("gone"), outside.resolve("journal.tmp"))
        assertTrue(fs.remove(dir.resolve("gone"), null))
        assertEquals(before, outsideState())
    }

    // 公開の直後（権限を変える前）に、名前を外のファイルへのハードリンクに差し替えても、外のファイルの権限は変えない
    @Test
    fun aHardLinkToAnOutsideFileIsNotChanged() {
        val victim = outside.resolve("private.txt")
        Files.writeString(victim, "secret")
        Files.setPosixFilePermissions(victim, PosixFilePermissions.fromString("rw-------"))
        val before = outsideState()
        val target = txn.resolve("LICENSE")
        // 公開したものが自分のファイルでなくなったことを見つけて失敗する
        val failure = assertThrows(InstallFailure::class.java) {
            fs.write(target, "MIT".toByteArray(), replace = false, onStep = {
                if (it == "renamed:LICENSE") {
                    Files.delete(target)
                    Files.createLink(target, victim)
                }
            })
        }
        assertTrue(failure.message!!.contains("L-5"), failure.message)
        assertEquals(before, outsideState())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(victim)))
    }

    // 権限は、変えた後に記述子で読み直して確かめる（指定どおりの権限になる）
    @Test
    fun theModeIsCheckedAfterItIsSet() {
        for ((name, mode, shown) in listOf(Triple("private.json", 0b110_000_000, "rw-------"), Triple("tool.sh", 0b111_101_101, "rwxr-xr-x"))) {
            val target = txn.resolve(name)
            fs.write(target, "x".toByteArray(), replace = false, mode = mode)
            assertEquals(shown, PosixFilePermissions.toString(Files.getPosixFilePermissions(target)), name)
        }
    }

    // 権限を変えられなかったときは、成功にしない。macOS では、属していないグループ（/private/tmp の下は wheel）のファイルに
    // setgid を付けると chmod が EPERM で失敗する。残るのは、書き終えた 0400 のファイル（名前を付けた直後に止まったものと同じ）で、外には何もしない
    @Test
    fun aModeThatCannotBeSetIsAFailure() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"), "uses the macOS rule for setgid on another group's file")
        val base = Files.createTempDirectory(Path.of("/private/tmp"), "createscript-anchored-").toRealPath()
        try {
            val project = Files.createDirectories(base.resolve("project"))
            val probe = Files.writeString(project.resolve("probe"), "")
            val refused = ProcessBuilder("/bin/chmod", "2644", probe.toString()).redirectErrorStream(true).start().let { it.inputStream.readAllBytes(); it.waitFor() != 0 }
            Files.delete(probe)
            assumeTrue(refused, "chmod 2644 is allowed here, so it cannot be made to fail")
            val before = outsideState()
            val helper = AnchoredFs.open(project, timeoutMillis = 5_000)!!
            val target = project.resolve("setgid.json")
            val failure = assertThrows(java.io.IOException::class.java) { helper.write(target, "complete".toByteArray(), replace = false, mode = 0b010_110_100_100) }
            assertTrue(failure.message!!.contains("chmod"), failure.message)
            assertEquals("complete", Files.readString(target))
            assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
            assertEquals(before, outsideState())
        } finally {
            Files.walk(base).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    // 公開の直後（権限を変える前）に、名前をディレクトリに差し替えた: 差し替えたものの権限は変えず、L-5 として止まる
    @Test
    fun aNameReplacedByADirectoryBeforeTheModeIsSetIsReported() {
        val target = txn.resolve("NOTICE")
        val failure = assertThrows(InstallFailure::class.java) {
            fs.write(target, "x".toByteArray(), replace = false, onStep = {
                if (it == "renamed:NOTICE") {
                    Files.delete(target)
                    Files.createDirectory(target)
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"))
                }
            })
        }
        assertTrue(failure.message!!.contains("L-5"), failure.message)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    // ---- 子プロセスの異常 ----

    // 書いている途中で止まった（強制終了）: 最終の名前は使われず、書きかけ（0400 の .part-…）だけが固定したディレクトリに残る
    @Test
    fun aKilledHelperLeavesOnlyAReadOnlyPart() {
        for (step in listOf("creating", "writing:data.json", "written:data.json")) {
            assertThrows(IllegalStateException::class.java) {
                fs.write(txn.resolve("data.json"), "complete contents".toByteArray(), replace = false, onStep = { if (it == step) throw IllegalStateException(step) })
            }
            assertFalse(Files.exists(txn.resolve("data.json")), step)
            val parts = Files.list(txn).use { s -> s.filter { it.fileName.toString().startsWith(".part-") }.toList() }
            assertEquals(1, parts.size, step)
            assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(parts.single())), step)
            Files.delete(parts.single())
        }
    }

    // 答えない子プロセス（宛先の名前を FIFO に差し替えて、開く操作を止める）は、時間で止め、外には何もしない
    @Test
    fun aHelperThatDoesNotAnswerIsStopped() {
        val fifo = txn.resolve("fifo")
        val made = try {
            ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0
        } catch (error: Exception) {
            false
        }
        assumeTrue(made, "mkfifo is not available")
        val before = outsideState()
        val slow = AnchoredFs.open(root, timeoutMillis = 1_500)!!
        val target = txn.resolve("NOTICE")
        val failure = assertThrows(InstallFailure::class.java) {
            slow.write(target, "x".toByteArray(), replace = false, onStep = {
                if (it == "renamed:NOTICE") {
                    Files.delete(target)
                    Files.move(fifo, target)
                }
            })
        }
        assertTrue(failure.message!!.contains("did not answer"), failure.message)
        assertEquals(before, outsideState())
    }

    // 名前は厳格に確かめる（スクリプトには埋め込まず、引数として渡す）
    @Test
    fun namesAreChecked() {
        for (name in listOf("a/b", "..", ".", "-rf", "a\nb", "$(touch x)", "`x`", "a;b")) {
            assertThrows(InstallFailure::class.java, { fs.checkName(name) }, name)
        }
        fs.checkName("vector_math.cscr")
        fs.checkName(".part-123-45-createscript.json")
        assertNotNull(fs.stat(root.resolve(".createscript")))
    }
}
