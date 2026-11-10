package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

// S8.2 N-3 の対策 A・B: 書いている間の一時ファイルは 0400（ほかの書き込みは権限で拒否される）。片付けるのは 0400 のものだけ。
// 消す直前にも、確かめたときと同じファイル（SHA-256 と fileKey）であることを確かめる
class ReadOnlyPartTest {
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

    private fun crashed(step: String): Path {
        val root = createProject(temp.resolve("app-${count++}"))
        var done = false
        assertThrows(SimulatedCrash::class.java) { installer(root, { if (it == step && !done) { done = true; throw SimulatedCrash(it) } }).install(spec, false) }
        return root
    }

    private fun txn(root: Path): Path = Files.list(root.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }

    private fun part(root: Path): Path = Files.walk(txn(root)).use { s -> s.filter { it.fileName.toString().startsWith(".part-") }.findFirst().orElseThrow() }

    private fun permissions(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))

    private fun setPermissions(path: Path, text: String) = Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(text))

    private fun cleaned(root: Path): Boolean = installer(root).install(spec, false).warnings.any { it.startsWith("removed .createscript/txn-") } &&
        Files.list(root.resolve(".createscript")).use { s -> s.noneMatch { it.fileName.toString().startsWith("txn-") } }

    private fun keptBecause(root: Path, reason: String): Boolean {
        val warnings = installer(root).install(spec, false).warnings
        return warnings.any { it.contains("was not removed") && it.contains(reason) }.also { if (!it) println(warnings) }
    }

    // ---- 権限 ----

    // 書いている間は 0400、書き終えたファイル（インストールしたもの）は 0644
    @Test
    fun partsAreReadOnlyAndFinalFilesAreNot() {
        for (step in listOf("creating", "writing:createscript.json.old", "written:numbers.cscr")) {
            val root = crashed(step)
            assertEquals("r--------", permissions(part(root)), step)
        }
        val root = createProject(temp.resolve("normal"))
        installer(root).install(spec, false)
        Files.walk(root.resolve(".createscript/packages")).use { s ->
            for (path in s) if (Files.isRegularFile(path)) assertEquals("rw-r--r--", permissions(path), path.toString())
        }
    }

    // 最終の名前に変えた直後（0644 にする前）に止まったものは、書き終えた 0400 のファイル。記録と照合して片付ける
    @Test
    fun aStopAfterTheRenameLeavesACompleteReadOnlyFile() {
        for (step in listOf("renamed:journal.tmp", "renamed:createscript.json.new", "renamed:.createscript-package.json", "renamed:numbers.cscr")) {
            val root = crashed(step)
            val name = step.removePrefix("renamed:")
            val final = Files.walk(txn(root)).use { s -> s.filter { it.fileName.toString() == name }.findFirst().orElseThrow() }
            assertEquals("r--------", permissions(final), step)
            assertTrue(cleaned(root), step)
        }
    }

    // ---- 直接の書き換えは、権限で拒否される ----

    @Test
    fun directAppendsAndOverwritesAreRefused() {
        val root = crashed("writing:createscript.json.old")
        val p = part(root)
        val before = Files.readAllBytes(p)
        assertThrows(AccessDeniedException::class.java) { Files.write(p, "user".toByteArray(), StandardOpenOption.APPEND) }
        assertThrows(AccessDeniedException::class.java) { Files.write(p, "user".toByteArray()) }
        assertThrows(AccessDeniedException::class.java) { FileChannel.open(p, StandardOpenOption.WRITE).close() }
        assertArrayEquals(before, Files.readAllBytes(p))
        assertTrue(cleaned(root))
    }

    // 権限を変えて書き換え、そのままにしたもの: 0400 でないので残す（中身はそのまま）
    @Test
    fun keepsAPartThatWasMadeWritable() {
        val root = crashed("writing:createscript.lock.new")
        val p = part(root)
        setPermissions(p, "rw-------")
        Files.write(p, "my notes".toByteArray())
        assertTrue(keptBecause(root, "is not read-only (createscript keeps a file it is writing read-only), so it may have been changed"))
        assertEquals("my notes", Files.readString(p))
        // 以前の版が書いた（0644 の）書きかけも、同じ理由で残す
        val older = crashed("writing:createscript.json.old")
        setPermissions(part(older), "rw-r--r--")
        assertTrue(keptBecause(older, "is not read-only"))
    }

    // ---- 防げないもの（文書に書いた限界）: 権限を戻したもの・開いたままの記述子による書き換え ----

    @Test
    fun changesThatRestoreTheReadOnlyPermissionAreNotNoticed() {
        // chmod で書けるようにし、同じ大きさで書き換え、0400 に戻す
        val root = crashed("writing:createscript.json.old")
        val p = part(root)
        val size = SafeIo.part(p.fileName.toString())!!.size.toInt()
        setPermissions(p, "rw-------")
        Files.write(p, ByteArray(size) { 'u'.code.toByte() })
        setPermissions(p, "r--------")
        assertEquals(SafeIo.part(p.fileName.toString())!!.inode, SafeIo.inode(p))
        assertTrue(cleaned(root), "documented limit: a change that restores 0400 within the size is not noticed")
        // 書けるうちに開いた記述子を持ち続け、0400 に戻った後に、その記述子で書く
        val other = crashed("writing:createscript.json.old")
        val q = part(other)
        setPermissions(q, "rw-------")
        FileChannel.open(q, StandardOpenOption.WRITE).use { channel ->
            setPermissions(q, "r--------")
            channel.write(ByteBuffer.wrap("x".toByteArray()))
        }
        assertEquals("r--------", permissions(q))
        assertTrue(cleaned(other), "documented limit: a descriptor opened before cannot be stopped")
    }

    // ---- ハードリンク・シンボリックリンク・別のファイル ----

    @Test
    fun linksAndReplacedFiles() {
        // ハードリンク: 消すのは作業用のディレクトリの中の名前だけで、もう一方の名前の中身は残る
        val root = crashed("writing:createscript.lock.new")
        val p = part(root)
        val other = Files.createLink(temp.resolve("another-name"), p)
        val contents = Files.readAllBytes(other)
        assertTrue(cleaned(root))
        assertArrayEquals(contents, Files.readAllBytes(other))
        // シンボリックリンク: 残す（リンクの先は変えない）
        val linked = crashed("writing:createscript.lock.new")
        val target = Files.writeString(temp.resolve("target.txt"), "user data")
        val q = part(linked)
        Files.delete(q)
        Files.createSymbolicLink(q, target)
        assertTrue(keptBecause(linked, "is a symbolic link or not a regular file"))
        assertEquals("user data", Files.readString(target))
        // 同じ中身・0400 の別のファイル（inode が違う）: 残す
        val replaced = crashed("writing:createscript.lock.new")
        val r = part(replaced)
        val copy = Files.write(r.resolveSibling("copy"), Files.readAllBytes(r))
        setPermissions(copy, "r--------")
        Files.move(copy, r, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertTrue(keptBecause(replaced, "was replaced by another file"))
    }

    // 対策 B: 確かめたときと fileKey が違えば、SHA-256 が同じでも消さない
    @Test
    fun deletionChecksTheFileKey() {
        val dir = Files.createDirectories(temp.resolve("keys"))
        val file = Files.writeString(dir.resolve("f"), "same contents")
        val sha = SafeIo.sha256Hex(Files.readAllBytes(file))
        val key = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        // 同じ中身の別のファイルに差し替える
        val other = Files.writeString(dir.resolve("g"), "same contents")
        Files.move(other, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals("it was replaced by another file", SafeIo.deleteFileIfSame(file, sha, 1024, key))
        assertTrue(Files.exists(file))
        val newKey = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        assertEquals(null, SafeIo.deleteFileIfSame(file, sha, 1024, newKey))
        assertFalse(Files.exists(file))
    }
}
