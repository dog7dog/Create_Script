package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// S8.3 L-5（Java 21 でできる対策）: 途中のディレクトリをリンクに差し替える操作を、決まった時点で起こして確かめる。
// 守る性質: 外のファイルを消さない・置き換えない、外に作られたファイルに中身を書かない
class SafeIoL5Test {
    @TempDir
    lateinit var temp: Path

    private val root: Path by lazy { temp.toRealPath() }

    // directory を、中身を別の場所へ退けてから、outside へのシンボリックリンクに差し替える（同じユーザーの攻撃者の操作）
    private fun swap(directory: Path, outside: Path): Path {
        val parked = directory.resolveSibling(directory.fileName.toString() + ".parked")
        Files.move(directory, parked)
        Files.createSymbolicLink(directory, outside)
        return parked
    }

    private fun bait(): Path {
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.writeString(outside.resolve("journal.tmp"), "user journal")
        Files.createDirectories(outside.resolve("staging"))
        Files.writeString(outside.resolve("staging/LICENSE"), "user license")
        return outside
    }

    @Test
    fun contentsAreNotWrittenToAFileCreatedOutside() {
        val txn = Files.createDirectories(root.resolve("txn"))
        val outside = bait()
        val owned = HashSet<String>()
        val failure = assertThrows(InstallFailure::class.java) {
            SafeIo.writeNew(txn.resolve("data.json"), "secret contents".toByteArray(), { step -> if (step == "creating") swap(txn, outside) }, owned)
        }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind)
        // 外に作られたのは空のファイルだけで、中身は書いていない。外の既存のファイルは変わらない
        val created = Files.list(outside).use { s -> s.filter { it.fileName.toString().startsWith(".part-") }.toList() }
        assertTrue(created.size <= 1)
        created.forEach { assertEquals(0L, Files.size(it)) }
        assertEquals("user journal", Files.readString(outside.resolve("journal.tmp")))
        assertTrue(owned.isEmpty(), "nothing is recorded as created when the file escaped")
    }

    // 作る前から途中のディレクトリがリンクだった（外に作られた）場合も、中身を書かない
    @Test
    fun contentsAreNotWrittenWhenTheDirectoryWasAlreadyReplaced() {
        val txn = Files.createDirectories(root.resolve("txn"))
        val outside = bait()
        swap(txn, outside)
        assertThrows(InstallFailure::class.java) { SafeIo.writeNew(txn.resolve("journal.tmp"), "secret contents".toByteArray(), {}, HashSet()) }
        // 外に作った一意の名前の空のファイルは、自分のものと確かめて片付ける
        assertEquals(emptyList<String>(), Files.list(outside).use { st -> st.map { it.fileName.toString() }.filter { it.startsWith(".part-") || it.startsWith(".delete-") }.toList() })
        assertThrows(InstallFailure::class.java) { SafeIo.createFile(txn.resolve("other.json"), "secret contents".toByteArray()) }
        assertEquals("user journal", Files.readString(outside.resolve("journal.tmp")))
        Files.list(outside).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString() != "journal.tmp" }.toList() }.forEach { assertEquals(0L, Files.size(it), "$it") }
    }

    @Test
    fun aFileCreatedInPlaceIsWrittenAndRecorded() {
        val txn = Files.createDirectories(root.resolve("txn"))
        val owned = HashSet<String>()
        val identity = SafeIo.writeNew(txn.resolve("data.json"), "contents".toByteArray(), {}, owned)
        assertEquals("contents", Files.readString(txn.resolve("data.json")))
        assertTrue(SafeIo.fileId(txn.resolve("data.json")) in owned)
        assertEquals(listOf("data.json"), Files.list(txn).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun renamingNeverReplacesAnExistingFile() {
        val source = Files.writeString(root.resolve(".part-x"), "new")
        val target = Files.writeString(root.resolve("createscript.json"), "user manifest")
        assertThrows(FileAlreadyExistsException::class.java) { SafeIo.renameNoReplace(source, target) }
        assertEquals("user manifest", Files.readString(target))
        assertEquals("new", Files.readString(source))
        val free = root.resolve("other.json")
        SafeIo.renameNoReplace(source, free)
        assertEquals("new", Files.readString(free))
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS))
    }

    // 作業用のディレクトリを消す途中で、その一部が外へのリンクに差し替えられても、外の同じ名前のファイルは消さない
    @Test
    fun deletingTheOwnTreeKeepsFilesItDidNotCreate() {
        val txn = root.resolve("txn")
        val owned = HashSet<String>()
        SafeIo.createOwnedDirectory(txn, owned)
        SafeIo.writeNew(txn.resolve("journal.tmp"), "ours".toByteArray(), {}, owned)
        SafeIo.createOwnedDirectory(txn.resolve("staging"), owned)
        SafeIo.writeNew(txn.resolve("staging/LICENSE"), "ours".toByteArray(), {}, owned)
        val outside = bait()
        // staging を外へのリンクに差し替える（作業用のディレクトリの中の、作っていないもの）
        swap(txn.resolve("staging"), outside.resolve("staging"))
        val kept = SafeIo.deleteOwnTree(txn, owned)
        assertEquals("user license", Files.readString(outside.resolve("staging/LICENSE")))
        assertEquals("user journal", Files.readString(outside.resolve("journal.tmp")))
        assertTrue(kept.any { it.contains("was not created by this installation") }, "$kept")
        assertFalse(Files.exists(txn.resolve("journal.tmp"), LinkOption.NOFOLLOW_LINKS))
    }

    // 確かめた後に名前の指すものが変わったら、消さずに元の名前に戻す
    @Test
    fun verifiedDeletionRestoresWhatIsNotOurs() {
        val file = Files.writeString(root.resolve("journal.tmp"), "user data")
        val reason = SafeIo.deleteVerified(file) { false }
        assertNotNull(reason)
        assertEquals("user data", Files.readString(file))
        assertEquals(listOf("journal.tmp"), Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() })
        assertNull(SafeIo.deleteVerified(file) { true })
        assertFalse(Files.exists(file))
    }

    // 内容と fileKey を確かめてから消す（deleteFileIfSame）: 確かめた内容と違うものは消さない
    @Test
    fun deleteFileIfSameChecksAgainAfterMovingAside() {
        val file = Files.writeString(root.resolve("createscript.json.old"), "old")
        val sha = SafeIo.sha256Hex("old".toByteArray())
        assertEquals("its contents changed", SafeIo.deleteFileIfSame(file, SafeIo.sha256Hex("x".toByteArray()), 100))
        assertTrue(Files.exists(file))
        assertNull(SafeIo.deleteFileIfSame(file, sha, 100))
        assertFalse(Files.exists(file))
    }

    @Test
    fun aDirectoryCreatedOutsideStopsTheInstallation() {
        val parent = Files.createDirectories(root.resolve("state"))
        val outside = Files.createDirectories(root.resolve("elsewhere"))
        swap(parent, outside)
        val owned = HashSet<String>()
        // 親がリンクなので、作ったディレクトリは意図した場所に無い
        assertThrows(InstallFailure::class.java) { SafeIo.createOwnedDirectory(parent.resolve("txn-1"), owned) }
        assertTrue(owned.isEmpty())
    }
}
