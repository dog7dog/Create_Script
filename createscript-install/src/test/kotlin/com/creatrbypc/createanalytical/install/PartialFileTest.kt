package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// S8.2 N-3: ジャーナルを公開する前に止まったインストールの、書きかけのファイル。どのファイルも .part-… の名前で書き終えてから最終の名前を付けるので、
// 書きかけは .part-… にだけ残る。作ったファイルそのもの（inode）で、大きさが書く大きさ以下なら、次の install が片付ける
class PartialFileTest {
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

    // step で止まったプロジェクト（ジャーナルは公開されていない）
    private fun crashed(step: String): Path {
        val root = createProject(temp.resolve("app-${count++}"))
        var done = false
        assertThrows(SimulatedCrash::class.java) { installer(root, { if (it == step && !done) { done = true; throw SimulatedCrash(it) } }).install(spec, false) }
        assertFalse(Files.exists(root.resolve(".createscript/transaction.json")))
        return root
    }

    private fun txn(root: Path): Path = Files.list(root.resolve(".createscript")).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }

    private fun parts(root: Path): List<Path> = Files.walk(txn(root)).use { s -> s.filter { it.fileName.toString().startsWith(".part-") }.toList() }

    private fun tree(dir: Path): Map<String, String> = Files.walk(dir).use { s ->
        s.filter { it != dir }.toList().associate { p ->
            dir.relativize(p).toString() to when {
                Files.isSymbolicLink(p) -> "link:${Files.readSymbolicLink(p)}"
                Files.isDirectory(p) -> "dir"
                else -> SafeIo.sha256Hex(Files.readAllBytes(p)) + ":" + SafeIo.inode(p)
            }
        }
    }

    private fun assertCleaned(root: Path) {
        val result = installer(root).install(spec, false)
        assertEquals(1, result.warnings.count { it.startsWith("removed .createscript/txn-") }, result.warnings.toString())
        assertEquals(listOf("install.lock", "packages"), Files.list(root.resolve(".createscript")).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
        assertEquals(2, result.packages.count { it.installed })
    }

    // ---- 片付けられるもの ----

    // 書く直前・書いている途中（書きかけの中身）・書き終えて名前を付ける前の、どの時点で止まっても片付ける。最終の名前の書きかけは無い
    @Test
    fun cleansUpAfterAStopAtAnyPointOfWriting() {
        val steps = listOf(
            "creating",
            "writing:journal.tmp", "written:journal.tmp",
            "writing:createscript.json.old", "written:createscript.json.new", "writing:createscript.lock.new",
            "writing:.createscript-package.json", "written:.createscript-package.json",
            "writing:numbers.cscr", "writing:LICENSE", "written:vector_math.cscr",
        )
        for (step in steps) {
            val root = crashed(step)
            val part = parts(root).single()
            val name = part.fileName.toString()
            if (step.startsWith("writing:")) {
                // 書いている途中で止まったもの（同じファイルに、書く大きさより少なく書いた）
                val size = SafeIo.part(name)!!.size
                Files.write(part, ByteArray((size / 2).toInt()) { 'x'.code.toByte() })
                assertFalse(Files.exists(part.resolveSibling(step.removePrefix("writing:"))), "$step: the final name is not used before the file is complete")
            }
            assertCleaned(root)
        }
    }

    // 公開の直前（すべて書き終えた）も、これまでどおり片付ける
    @Test
    fun cleansUpJustBeforeThePublication() {
        val root = crashed("staged")
        assertEquals(emptyList<Path>(), parts(root))
        assertCleaned(root)
    }

    // ---- 片付けないもの（何も消さず、理由を知らせる） ----

    @Test
    fun keepsPartialFilesThatCannotBeConfirmed() {
        val outside = Files.writeString(temp.resolve("outside.txt"), "user data")
        val cases = linkedMapOf<String, Pair<(Path) -> Any?, String>>(
            // エディターで保存し直したもの（別のファイルに置き換わる）
            "replaced" to ({ p: Path ->
                val copy = Files.writeString(p.resolveSibling("copy"), "edited by the user")
                Files.move(copy, p, StandardCopyOption.REPLACE_EXISTING)
            } to "was replaced by another file (it is not the file that createscript was writing)"),
            // 書く大きさを超えたもの
            "grown" to ({ p: Path -> Files.write(p, ByteArray((SafeIo.part(p.fileName.toString())!!.size + 1).toInt())) } to "is larger than the file it was being written for"),
            "link" to ({ p: Path -> Files.delete(p); Files.createSymbolicLink(p, outside) } to "is a symbolic link or not a regular file"),
            "unknown final name" to ({ p: Path ->
                val tag = SafeIo.part(p.fileName.toString())!!
                Files.move(p, p.resolveSibling(".part-${tag.inode}-${tag.size}-notes.txt"))
            } to "is for notes.txt, which is not in its record"),
            "final also exists" to ({ p: Path -> Files.writeString(p.resolveSibling("createscript.json.old"), "{}") } to "createscript.json.old exist"),
        )
        for ((label, case) in cases) {
            val (change, reason) = case
            val root = crashed("writing:createscript.json.old")
            change(parts(root).single())
            val dir = txn(root)
            val before = tree(dir)
            val result = installer(root).install(spec, false)
            val warning = result.warnings.singleOrNull { it.contains("was not removed") } ?: throw AssertionError("$label: ${result.warnings}")
            assertTrue(warning.contains(reason) && warning.endsWith("check it and remove it by hand"), "$label: $warning")
            assertEquals(before, tree(dir), label)
            assertEquals("user data", Files.readString(outside))
        }
        // 名前を付ける前の空のファイルに、何かが書かれたもの
        val root = crashed("creating")
        Files.writeString(parts(root).single(), "something")
        val result = installer(root).install(spec, false)
        assertTrue(result.warnings.any { it.contains("is not empty, so it is not a file that createscript was about to write") }, result.warnings.toString())
    }

    // 書き終えた最終の名前のファイルを利用者が書き換えたものは、これまでどおり残す
    @Test
    fun keepsCompleteFilesThatWereChanged() {
        val root = crashed("writing:createscript.lock.new")
        Files.writeString(txn(root).resolve("createscript.json.old"), "edited by the user")
        val before = tree(txn(root))
        val result = installer(root).install(spec, false)
        assertTrue(result.warnings.any { it.contains("createscript.json.old does not match the record in journal.tmp") }, result.warnings.toString())
        assertEquals(before, tree(txn(root)))
    }

    // ---- 通常のインストールと、ジャーナルを公開した後の復旧 ----

    @Test
    fun normalInstallationsAndRecoveriesLeaveNoPartialFiles() {
        val root = createProject(temp.resolve("normal"))
        installer(root).install(spec, false)
        Files.walk(root).use { s -> assertTrue(s.noneMatch { it.fileName.toString().startsWith(".part-") }) }
        for (step in listOf("journal", "package:numbers", "lock")) {
            val crashedRoot = createProject(temp.resolve("after-$step"))
            assertThrows(SimulatedCrash::class.java) { installer(crashedRoot, { if (it == step) throw SimulatedCrash(it) }).install(spec, false) }
            assertEquals("completed an interrupted installation", installer(crashedRoot).install(null, false).recovery)
            Files.walk(crashedRoot).use { s -> assertTrue(s.noneMatch { it.fileName.toString().startsWith(".part-") }) }
        }
    }
}
