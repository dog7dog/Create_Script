package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.project.Dependency
import com.creatrbypc.createanalytical.project.Manifest
import com.creatrbypc.createanalytical.project.Manifests
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

// S8.1 の追加検証: init のロールバックが差し替えられたものを消さないこと、manifest check・lock check がプロジェクトの外・通常のファイル以外を読まないこと
class ProjectFileSafetyTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) }
        }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun names(path: Path): List<String> = Files.list(path).use { files -> files.map { it.fileName.toString() }.sorted().toList() }

    // 2 つ目のファイル（src/main.cscr）を書く前に、change で 1 つ目（createscript.json）などを差し替えてから失敗させる
    private fun initFailingAfter(project: Path, change: (Path) -> Unit): ProjectCommands.Failure {
        var calls = 0
        val failing: (Path, String) -> FileIdentity = { path, text ->
            if (++calls == 2) {
                change(project)
                throw IOException("disk full (simulated)")
            }
            SafeFiles.writeNew(path, text)
        }
        val commands = ProjectCommands(PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), failing)
        return assertThrows(ProjectCommands.Failure::class.java) { commands.init(project.toString(), "app") }
    }

    private fun fileKeysAvailable(): Boolean = Files.readAttributes(directory, BasicFileAttributes::class.java).fileKey() != null

    // ---- init のロールバック ----

    @Test
    fun rollbackKeepsAReplacedFile() {
        val project = directory.resolve("replaced")
        val failure = initFailingAfter(project) { root ->
            val manifest = root.resolve("createscript.json")
            Files.delete(manifest)
            Files.writeString(manifest, "written by another process")
        }
        assertTrue(failure.message!!.contains("createscript.json (it was replaced by another file)") || failure.message!!.contains("createscript.json (its contents changed)"), failure.message)
        assertEquals("written by another process", Files.readString(project.resolve("createscript.json")))
        // 作った src は消し、空でなくなったプロジェクトのディレクトリは残す
        assertEquals(listOf("createscript.json"), names(project))
        assertTrue(failure.message!!.contains("(it is not empty)"), failure.message)
    }

    // 内容が同じでも、別のファイル（fileKey が違う）に差し替えられたものは消さない
    @Test
    fun rollbackKeepsAFileWithTheSameContents() {
        assumeTrue(fileKeysAvailable(), "the file system gives no file keys")
        val project = directory.resolve("same-contents")
        val failure = initFailingAfter(project) { root ->
            val manifest = root.resolve("createscript.json")
            val contents = Files.readAllBytes(manifest)
            Files.delete(manifest)
            Files.write(manifest, contents)
        }
        assertTrue(failure.message!!.contains("(it was replaced by another file)"), failure.message)
        assertTrue(Files.exists(project.resolve("createscript.json")))
    }

    @Test
    fun rollbackKeepsLinksAndChangedFiles() {
        val target = Files.writeString(directory.resolve("target.txt"), "do not touch")
        val linked = directory.resolve("linked")
        val link = initFailingAfter(linked) { root ->
            Files.delete(root.resolve("createscript.json"))
            Files.createSymbolicLink(root.resolve("createscript.json"), target)
        }
        assertTrue(link.message!!.contains("(it was replaced by something that is not a regular file)"), link.message)
        assertTrue(Files.isSymbolicLink(linked.resolve("createscript.json")))
        assertEquals("do not touch", Files.readString(target))
        // 同じファイル（fileKey が同じ）でも、内容が変わったものは消さない
        val edited = directory.resolve("edited")
        val changed = initFailingAfter(edited) { root -> Files.writeString(root.resolve("createscript.json"), "\n", java.nio.file.StandardOpenOption.APPEND) }
        assertTrue(changed.message!!.contains("(its contents changed)"), changed.message)
        assertTrue(Files.exists(edited.resolve("createscript.json")))
    }

    // 作ったディレクトリが差し替えられたら消さない
    @Test
    fun rollbackKeepsAReplacedDirectory() {
        assumeTrue(fileKeysAvailable(), "the file system gives no file keys")
        val project = directory.resolve("dir-replaced")
        val failure = initFailingAfter(project) { root ->
            Files.delete(root.resolve("src"))
            Files.createDirectory(root.resolve("src"))
        }
        assertTrue(failure.message!!.contains("src (it was replaced by another directory)"), failure.message)
        assertTrue(Files.isDirectory(project.resolve("src")))
        // createscript.json は作ったときのままなので消す
        assertFalse(Files.exists(project.resolve("createscript.json")))
    }

    // 差し替えの無いときは、作ったものをすべて消す
    @Test
    fun rollbackRemovesEverythingItCreated() {
        val project = directory.resolve("a/b/app")
        val failure = initFailingAfter(project) { }
        assertTrue(failure.message!!.endsWith("nothing was left behind"), failure.message)
        assertEquals(emptyList<String>(), names(directory))
    }

    // 作ったものを開いたままにするのは、照合と削除を終えるまで（成功・失敗のどちらでも、init の後にプロジェクトの記述子は残らない）
    @Test
    fun initReleasesWhatItHeldOpen() {
        assumeTrue(fileKeysAvailable(), "the file system gives no file keys (nothing is held open)")
        val done = directory.resolve("done").toAbsolutePath()
        assertEquals(ExitCode.OK, cli("init", done.toString(), "--name", "done").code)
        val afterSuccess = openUnder(done)
        assumeTrue(afterSuccess != null, "cannot list the open files of this process")
        assertEquals(0, afterSuccess, "open after a successful init")
        val failed = directory.resolve("failed").toAbsolutePath()
        initFailingAfter(failed) { }
        assertFalse(Files.exists(failed))
        assertEquals(0, openUnder(directory.toAbsolutePath()), "open after a rolled-back init")
    }

    // このプロセスが開いている、root の下のファイル・ディレクトリの数（Linux は /proc/self/fd、macOS は lsof。数えられなければ null）
    private fun openUnder(root: Path): Int? {
        val prefix = root.toRealPath().toString()
        val proc = Path.of("/proc/self/fd")
        if (Files.isDirectory(proc)) {
            return Files.list(proc).use { s ->
                s.toList().count { fd ->
                    try {
                        Files.readSymbolicLink(fd).toString().startsWith(prefix)
                    } catch (error: IOException) {
                        false
                    }
                }
            }
        }
        val lsof = Path.of("/usr/sbin/lsof")
        if (!Files.isExecutable(lsof)) return null
        val process = ProcessBuilder(lsof.toString(), "-n", "-P", "-Fn", "-p", ProcessHandle.current().pid().toString()).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return output.lines().count { it.startsWith("n") && it.removePrefix("n").startsWith(prefix) }
    }

    // fileKey が取得できない環境（FileIdentity.fileKey が null）では、大きさと内容だけで判断する
    @Test
    fun deletionWithoutFileKeys() {
        val file = Files.writeString(directory.resolve("x.txt"), "abc")
        val identity = FileIdentity(null, 3, SafeFiles.sha256("abc".toByteArray()))
        Files.writeString(file, "abd")
        assertEquals("its contents changed", SafeFiles.deleteIfSame(file, identity))
        Files.writeString(file, "abcd")
        assertEquals("its contents changed", SafeFiles.deleteIfSame(file, identity))
        Files.writeString(file, "abc")
        assertNull(SafeFiles.deleteIfSame(file, identity))
        assertFalse(Files.exists(file))
        // 既に無いものは消したものとする
        assertNull(SafeFiles.deleteIfSame(file, identity))
        // 空でないディレクトリは消さない
        val folder = Files.createDirectory(directory.resolve("folder"))
        Files.writeString(folder.resolve("inside"), "x")
        assertEquals("it is not empty", SafeFiles.deleteDirectoryIfSame(folder, null))
        assertEquals("it was replaced by something that is not a directory", SafeFiles.deleteDirectoryIfSame(folder.resolve("inside"), null))
    }

    // ---- manifest check・lock check が読むファイル ----

    private val manifest = Manifests.write(Manifest("demo", "1.0.0", "src/main.cscr"))

    private fun project(name: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/main.cscr"), "println(1)\n")
        return root
    }

    @Test
    fun linksInsideTheProjectAreAllowed() {
        val root = project("inside")
        Files.createDirectories(root.resolve("config"))
        Files.writeString(root.resolve("config/real.json"), manifest)
        Files.createSymbolicLink(root.resolve("createscript.json"), Path.of("config/real.json"))
        assertEquals(ExitCode.OK, cli("manifest", "check", root.toString()).code)
        // プロジェクトのディレクトリ自体をリンクで指定してもよい
        val alias = Files.createSymbolicLink(directory.resolve("alias"), root)
        assertEquals(ExitCode.OK, cli("manifest", "check", alias.toString()).code)
        // 上限ちょうどの大きさ
        Files.writeString(root.resolve("config/real.json"), manifest + " ".repeat(Manifests.MAX_BYTES - manifest.length))
        assertEquals(ExitCode.OK, cli("manifest", "check", root.toString()).code)
    }

    @Test
    fun linksOutsideTheProjectAreRejected() {
        val outside = Files.writeString(directory.resolve("outside.json"), manifest)
        val cases = mapOf<String, (Path) -> Unit>(
            "absolute link" to { root -> Files.createSymbolicLink(root.resolve("createscript.json"), outside) },
            "relative link" to { root -> Files.createSymbolicLink(root.resolve("createscript.json"), Path.of("../outside.json")) },
            "chain of links" to { root ->
                Files.createSymbolicLink(root.resolve("hop.json"), outside)
                Files.createSymbolicLink(root.resolve("createscript.json"), Path.of("hop.json"))
            },
            "device" to { root -> Files.createSymbolicLink(root.resolve("createscript.json"), Path.of("/dev/null")) },
        )
        for ((label, make) in cases) {
            val root = project(label.replace(' ', '-'))
            make(root)
            val result = cli("manifest", "check", root.toString())
            assertEquals(ExitCode.INVALID_INPUT, result.code, label)
            assertEquals("${root.resolve("createscript.json")}:1:1: error CS_PKG_FILE_OUTSIDE_PROJECT (\"createscript.json\")\n", result.err, label)
        }
        assertEquals(manifest, Files.readString(outside))
        // ロックファイルも同じ
        val withDependency = project("lock-outside")
        Files.writeString(withDependency.resolve("createscript.json"), Manifests.write(Manifest("demo", "1.0.0", "src/main.cscr", dependencies = listOf(Dependency("lib", "1.0.0", "github:o/lib")))))
        Files.createSymbolicLink(withDependency.resolve("createscript.lock"), outside)
        assertTrue(cli("lock", "check", withDependency.toString()).err.contains("error CS_PKG_FILE_OUTSIDE_PROJECT (\"createscript.lock\")"))
    }

    @Test
    fun onlyRegularFilesAreRead() {
        val folder = project("folder")
        Files.createDirectory(folder.resolve("createscript.json"))
        assertTrue(cli("manifest", "check", folder.toString()).err.contains("error CS_PKG_NOT_A_REGULAR_FILE (\"createscript.json\")"))
        // 壊れたリンクは、ファイルが無いものとして報告する
        val broken = project("broken")
        Files.createSymbolicLink(broken.resolve("createscript.json"), Path.of("missing.json"))
        val missing = cli("manifest", "check", broken.toString())
        assertEquals(ExitCode.NO_INPUT, missing.code)
        assertTrue(missing.err.contains("is a broken symbolic link"), missing.err)
        // 依存の無いプロジェクトでも、壊れたロックファイルのリンクは見過ごさない
        val brokenLock = project("broken-lock")
        Files.writeString(brokenLock.resolve("createscript.json"), manifest)
        Files.createSymbolicLink(brokenLock.resolve("createscript.lock"), Path.of("missing.lock"))
        assertEquals(ExitCode.NO_INPUT, cli("lock", "check", brokenLock.toString()).code)
    }

    // FIFO（名前付きパイプ）は、開くと書き手を待って止まるため、開く前に拒否する（止まらないことを時間の上限で確かめる）
    @Test
    fun fifosAreRejectedWithoutBlocking() {
        val root = project("fifo")
        val fifo = root.resolve("createscript.json")
        val made = try {
            ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0
        } catch (error: IOException) {
            false
        }
        assumeTrue(made && Files.exists(fifo, LinkOption.NOFOLLOW_LINKS), "mkfifo is not available")
        val result = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { cli("manifest", "check", root.toString()) })
        assertEquals(ExitCode.INVALID_INPUT, result.code)
        assertTrue(result.err.contains("error CS_PKG_NOT_A_REGULAR_FILE"), result.err)
        // リンクの先の FIFO も同じ
        val other = project("fifo-link")
        Files.createSymbolicLink(other.resolve("createscript.json"), Path.of("pipe"))
        ProcessBuilder("mkfifo", other.resolve("pipe").toString()).start().waitFor()
        val linked = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { cli("manifest", "check", other.toString()) })
        assertTrue(linked.err.contains("error CS_PKG_NOT_A_REGULAR_FILE"), linked.err)
    }

    // 読むときはリンクをたどらずに開く（確かめた後でリンクに差し替えられたら読まない）
    @Test
    fun readingDoesNotFollowLinks() {
        val target = Files.writeString(directory.resolve("t.txt"), "secret")
        val link = Files.createSymbolicLink(directory.resolve("l.txt"), target)
        assertThrows(IOException::class.java) { SafeFiles.readLimited(link, 100) }
        assertNotNull(SafeFiles.readLimited(target, 100))
        assertEquals(4, SafeFiles.readLimited(target, 3).size)
    }
}
