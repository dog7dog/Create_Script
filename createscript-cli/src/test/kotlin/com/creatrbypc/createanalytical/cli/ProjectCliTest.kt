package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.project.Dependency
import com.creatrbypc.createanalytical.project.LockRoot
import com.creatrbypc.createanalytical.project.LockedPackage
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest
import com.creatrbypc.createanalytical.project.Manifests
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

// S8.1: createscript init・manifest check・lock check
class ProjectCliTest {
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

    private fun write(path: Path, text: String): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    // ---- init ----

    @Test
    fun initCreatesAProject() {
        val project = directory.resolve("my-app")
        val result = cli("init", project.toString())
        assertEquals(ExitCode.OK, result.code, result.err)
        assertEquals("", result.err)
        val manifest = Files.readString(project.resolve("createscript.json"))
        assertEquals("{\n  \"schemaVersion\": 1,\n  \"name\": \"my-app\",\n  \"version\": \"0.1.0\",\n  \"entry\": \"src/main.cscr\",\n  \"dependencies\": {}\n}\n", manifest)
        assertEquals("println(\"Hello, CreateScript!\")\n", Files.readString(project.resolve("src/main.cscr")))
        // ロックファイル・一時ファイルは作らない。.gitignore が無かったので、/.createscript/ の 1 行で作る（S8.2 Phase 4）
        assertEquals(listOf(".gitignore", "createscript.json", "src"), Files.list(project).use { files -> files.map { it.fileName.toString() }.sorted().toList() })
        assertEquals("/.createscript/\n", Files.readString(project.resolve(".gitignore")))
        assertTrue(result.out.contains("Created ${project.resolve(".gitignore")}"), result.out)
        assertTrue(result.out.contains("createscript run ${project.resolve("src/main.cscr")}"), result.out)
        // 作ったプロジェクトは、そのまま確かめられ、実行できる
        assertEquals(ExitCode.OK, cli("manifest", "check", project.toString()).code)
        assertEquals(ExitCode.OK, cli("lock", "check", project.toString()).code)
        val run = cli("run", project.resolve("src/main.cscr").toString())
        assertEquals(ExitCode.OK, run.code)
        assertEquals("Hello, CreateScript!\n", run.out)
    }

    @Test
    fun initChecksTheName() {
        val named = directory.resolve("Some Dir")
        val rejected = cli("init", named.toString())
        assertEquals(ExitCode.USAGE, rejected.code)
        assertTrue(rejected.err.contains("invalid package name: Some Dir (from the directory name); use --name <name>"), rejected.err)
        assertFalse(Files.exists(named))
        assertEquals(ExitCode.OK, cli("init", named.toString(), "--name", "some-dir").code)
        assertTrue(Files.readString(named.resolve("createscript.json")).contains("\"name\": \"some-dir\""))
        assertEquals(ExitCode.USAGE, cli("init", directory.resolve("x").toString(), "--name", "Bad_Name").code)
        assertEquals(ExitCode.USAGE, cli("init", "--name").code)
        assertEquals(ExitCode.USAGE, cli("init", "a", "b").code)
        assertEquals(ExitCode.USAGE, cli("init", "--force").code)
    }

    // 既にあるファイルは上書きせず、何も作らない
    @Test
    fun initKeepsExistingFiles() {
        val project = directory.resolve("existing")
        val manifest = write(project.resolve("createscript.json"), "my own manifest")
        val result = cli("init", project.toString())
        assertEquals(ExitCode.CANNOT_CREATE, result.code)
        assertTrue(result.err.contains("already exists; nothing was created"), result.err)
        assertEquals("my own manifest", Files.readString(manifest))
        assertFalse(Files.exists(project.resolve("src")))

        val other = directory.resolve("other")
        val main = write(other.resolve("src/main.cscr"), "println(1)\n")
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", other.toString()).code)
        assertEquals("println(1)\n", Files.readString(main))
        assertFalse(Files.exists(other.resolve("createscript.json")))
        // 壊れたシンボリックリンクも「ある」とみなす
        val linked = Files.createDirectories(directory.resolve("linked"))
        Files.createSymbolicLink(linked.resolve("createscript.json"), linked.resolve("missing.json"))
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", linked.toString()).code)
        assertFalse(Files.exists(linked.resolve("missing.json")))
        // ディレクトリでなくファイル
        val file = write(directory.resolve("plain"), "x")
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", file.toString(), "--name", "plain").code)
        assertEquals("x", Files.readString(file))
    }

    // 途中で失敗したら、今回作ったファイル・ディレクトリだけを消す（前からあったものは残す）
    @Test
    fun initRollsBackOnFailure() {
        val parent = Files.createDirectories(directory.resolve("parent"))
        val keep = write(parent.resolve("notes.txt"), "keep me")
        val project = parent.resolve("new-app")
        var calls = 0
        val failing: (Path, String) -> FileIdentity = { path, text ->
            if (++calls == 2) throw IOException("disk full (simulated)")
            SafeFiles.writeNew(path, text)
        }
        val err = ByteArrayOutputStream()
        val commands = ProjectCommands(PrintStream(ByteArrayOutputStream()), PrintStream(err), failing)
        val failure = assertThrows(ProjectCommands.Failure::class.java) { commands.init(project.toString(), null) }
        assertEquals(ExitCode.CANNOT_CREATE, failure.code)
        assertTrue(failure.message!!.contains("disk full (simulated); nothing was left behind"), failure.message)
        assertFalse(Files.exists(project))
        assertEquals(listOf("notes.txt"), Files.list(parent).use { files -> files.map { it.fileName.toString() }.toList() })
        assertEquals("keep me", Files.readString(keep))

        // 前からあるディレクトリ（src も）は残し、作ったファイルだけを消す
        val existing = Files.createDirectories(directory.resolve("existing-app/src"))
        val note = write(existing.resolve("README"), "x")
        calls = 0
        assertThrows(ProjectCommands.Failure::class.java) { ProjectCommands(PrintStream(ByteArrayOutputStream()), PrintStream(err), failing).init(existing.parent.toString(), null) }
        assertTrue(Files.isDirectory(existing))
        assertEquals("x", Files.readString(note))
        assertFalse(Files.exists(existing.parent.resolve("createscript.json")))
        assertFalse(Files.exists(existing.resolve("main.cscr")))
    }

    // S8.1 の最終検証: シンボリックリンク・既存のファイルを壊さず、リンクの先にも書かない
    @Test
    fun initDoesNotFollowOrBreakLinks() {
        // src がプロジェクトの外を指すシンボリックリンク: 何も作らず、リンクの先にも書かない
        val outside = Files.createDirectories(directory.resolve("outside"))
        val linked = Files.createDirectories(directory.resolve("linked-src"))
        Files.createSymbolicLink(linked.resolve("src"), outside)
        val result = cli("init", linked.toString())
        assertEquals(ExitCode.CANNOT_CREATE, result.code)
        assertTrue(result.err.contains("is a symbolic link; nothing was created"), result.err)
        assertEquals(emptyList<String>(), Files.list(outside).use { files -> files.map { it.fileName.toString() }.toList() })
        assertFalse(Files.exists(linked.resolve("createscript.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(linked.resolve("src")))
        // 壊れたリンクの src も同じ
        val dangling = Files.createDirectories(directory.resolve("dangling-src"))
        Files.createSymbolicLink(dangling.resolve("src"), directory.resolve("nowhere"))
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", dangling.toString()).code)
        assertFalse(Files.exists(directory.resolve("nowhere")))
        // src が通常のファイル
        val fileSrc = Files.createDirectories(directory.resolve("file-src"))
        write(fileSrc.resolve("src"), "not a directory")
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", fileSrc.toString()).code)
        assertEquals("not a directory", Files.readString(fileSrc.resolve("src")))
        assertEquals(listOf("src"), Files.list(fileSrc).use { files -> files.map { it.fileName.toString() }.toList() })
        // createscript.json がファイルへのシンボリックリンク: リンクも、その先のファイルも変えない
        val target = write(directory.resolve("real.json"), "original")
        val manifestLink = Files.createDirectories(directory.resolve("manifest-link"))
        Files.createSymbolicLink(manifestLink.resolve("createscript.json"), target)
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", manifestLink.toString()).code)
        assertEquals("original", Files.readString(target))
        assertTrue(Files.isSymbolicLink(manifestLink.resolve("createscript.json")))
        // プロジェクトのディレクトリが壊れたシンボリックリンク: 何も作らない
        val brokenProject = Files.createSymbolicLink(directory.resolve("broken-project"), directory.resolve("missing-dir"))
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", brokenProject.toString(), "--name", "broken").code)
        assertFalse(Files.exists(directory.resolve("missing-dir")))
        // createscript.json という名前のディレクトリ
        val dirManifest = Files.createDirectories(directory.resolve("dir-manifest/createscript.json"))
        assertEquals(ExitCode.CANNOT_CREATE, cli("init", dirManifest.parent.toString()).code)
        assertTrue(Files.isDirectory(dirManifest))
        // どの場合も一時ファイル（.createscript-*.tmp）を残さない
        val leftovers = Files.walk(directory).use { files -> files.filter { it.fileName.toString().startsWith(".createscript-") }.toList() }
        assertEquals(emptyList<Path>(), leftovers)
    }

    // 作るファイルの権限は、通常のファイルと同じ（umask に従う。一時ファイルの 0600 のままにしない）
    @Test
    fun initUsesNormalPermissions() {
        val project = directory.resolve("perms")
        assertEquals(ExitCode.OK, cli("init", project.toString()).code)
        val reference = write(directory.resolve("reference.txt"), "x")
        val expected = Files.getPosixFilePermissions(reference)
        assertEquals(expected, Files.getPosixFilePermissions(project.resolve("createscript.json")))
        assertEquals(expected, Files.getPosixFilePermissions(project.resolve("src/main.cscr")))
    }

    // 最初の書き込みで失敗しても、作ったディレクトリを消す
    @Test
    fun initRollsBackWhenTheFirstWriteFails() {
        val project = directory.resolve("deep/new/app")
        val failing: (Path, String) -> FileIdentity = { _, _ -> throw IOException("read-only file system (simulated)") }
        assertThrows(ProjectCommands.Failure::class.java) { ProjectCommands(PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), failing).init(project.toString(), null) }
        assertFalse(Files.exists(directory.resolve("deep")))
    }

    // 新しいファイルとしてだけ書く（既にあれば上書きしない。一時ファイルは残らない）
    @Test
    fun writesFilesSafely() {
        val target = directory.resolve("a.txt")
        SafeFiles.writeNew(target, "first")
        assertThrows(FileAlreadyExistsException::class.java) { SafeFiles.writeNew(target, "second") }
        assertEquals("first", Files.readString(target))
        assertEquals(listOf("a.txt"), Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    // ---- manifest check ----

    private fun project(manifest: String, entry: String? = "src/main.cscr"): Path {
        val root = Files.createDirectories(directory.resolve("p${System.nanoTime()}"))
        write(root.resolve("createscript.json"), manifest)
        entry?.let { write(root.resolve(it), "println(1)\n") }
        return root
    }

    private val valid = Manifests.write(Manifest("demo", "1.0.0", "src/main.cscr", "MIT", listOf(Dependency("vector-math", "1.2.0", "github:owner/vector-math"))))

    @Test
    fun manifestCheck() {
        val root = project(valid)
        val ok = cli("manifest", "check", root.toString())
        assertEquals(ExitCode.OK, ok.code, ok.err)
        assertEquals("${root.resolve("createscript.json")}: ok (demo 1.0.0, entry src/main.cscr, 1 dependencies)\n", ok.out)
        val bad = project(valid.replace("\"1.0.0\"", "\"1.0\"").replace("\"MIT\"", "\"MIT;\""))
        val result = cli("manifest", "check", bad.toString())
        assertEquals(ExitCode.INVALID_INPUT, result.code)
        val file = bad.resolve("createscript.json")
        assertEquals("$file:4:14: error CS_PKG_INVALID_VERSION (\"1.0\")\n$file:6:14: error CS_PKG_INVALID_LICENSE (\"MIT;\")\n", result.err)
        val syntax = cli("manifest", "check", project("{\"schemaVersion\": 1,,}").toString())
        assertEquals(ExitCode.INVALID_INPUT, syntax.code)
        assertTrue(syntax.err.contains(":1:21: error CS_PKG_JSON_SYNTAX"), syntax.err)
        val duplicate = cli("manifest", "check", project(valid.replace("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"license\": \"MIT\",")).toString())
        assertTrue(duplicate.err.contains(":7:3: error CS_PKG_JSON_DUPLICATE_KEY (\"license\")"), duplicate.err)
        // 無いマニフェスト・大きすぎるマニフェスト
        val empty = Files.createDirectories(directory.resolve("empty"))
        val missing = cli("manifest", "check", empty.toString())
        assertEquals(ExitCode.NO_INPUT, missing.code)
        assertTrue(missing.err.contains("createscript.json not found (create one with createscript init)"), missing.err)
        assertEquals(ExitCode.NO_INPUT, cli("manifest", "check", directory.resolve("nothing").toString()).code)
        val large = cli("manifest", "check", project(valid + " ".repeat(Manifests.MAX_BYTES)).toString())
        assertEquals(ExitCode.INVALID_INPUT, large.code)
        assertTrue(large.err.contains("error CS_PKG_FILE_TOO_LARGE (\"65536\")"), large.err)
    }

    // エントリーのパス: 字面の検査（..・絶対パス）と、ファイルの有無・シンボリックリンクでルートの外に出ないこと
    @Test
    fun manifestCheckChecksTheEntry() {
        fun check(entry: String): Result = cli("manifest", "check", project(valid.replace("src/main.cscr", entry), entry = null).toString())
        for (entry in listOf("../outside.cscr", "/etc/main.cscr", "src/../../x.cscr", "C:/main.cscr")) {
            val result = check(entry)
            assertEquals(ExitCode.INVALID_INPUT, result.code, entry)
            assertTrue(result.err.contains("error CS_PKG_INVALID_PATH (\"$entry\""), result.err)
        }
        val missing = check("src/main.cscr")
        assertTrue(missing.err.contains(":5:12: error CS_PKG_ENTRY_NOT_FOUND (\"src/main.cscr\")"), missing.err)
        // シンボリックリンクでルートの外を指すエントリー・ディレクトリのエントリー
        val outside = write(directory.resolve("outside/main.cscr"), "println(1)\n")
        val root = project(valid, entry = null)
        Files.createDirectories(root.resolve("src"))
        Files.createSymbolicLink(root.resolve("src/main.cscr"), outside)
        val escaped = cli("manifest", "check", root.toString())
        assertEquals(ExitCode.INVALID_INPUT, escaped.code)
        assertTrue(escaped.err.contains("error CS_PKG_ENTRY_OUTSIDE_PROJECT (\"src/main.cscr\")"), escaped.err)
        val folder = project(valid, entry = null)
        Files.createDirectories(folder.resolve("src/main.cscr"))
        assertTrue(cli("manifest", "check", folder.toString()).err.contains("error CS_PKG_ENTRY_NOT_A_FILE"))
        // ルートの中を指すシンボリックリンクは使える
        val inside = project(valid, entry = null)
        write(inside.resolve("lib/real.cscr"), "println(1)\n")
        Files.createDirectories(inside.resolve("src"))
        Files.createSymbolicLink(inside.resolve("src/main.cscr"), inside.resolve("lib/real.cscr"))
        assertEquals(ExitCode.OK, cli("manifest", "check", inside.toString()).code)
    }

    // ---- lock check ----

    private val sha = "0123456789abcdef".repeat(4)

    private fun lock(vararg packages: LockedPackage, root: LockRoot = LockRoot("demo", "1.0.0")) = Lockfiles.write(Lockfile.of(root, packages.toList()))

    @Test
    fun lockCheck() {
        val root = project(valid)
        val missing = cli("lock", "check", root.toString())
        assertEquals(ExitCode.INVALID_INPUT, missing.code)
        assertEquals("${root.resolve("createscript.lock")}:1:1: error CS_PKG_LOCK_MISSING (\"createscript.lock\")\n", missing.err)
        val locked = LockedPackage("vector-math", "1.2.0", "github:owner/vector-math", sha)
        write(root.resolve("createscript.lock"), lock(locked))
        val ok = cli("lock", "check", root.toString())
        assertEquals(ExitCode.OK, ok.code, ok.err)
        assertEquals("${root.resolve("createscript.lock")}: ok (1 packages, consistent with createscript.json)\n", ok.out)
        // 版の違い・正規形でない・正しくない SHA-256
        write(root.resolve("createscript.lock"), lock(locked.copy(version = "1.3.0")))
        assertTrue(cli("lock", "check", root.toString()).err.contains("error CS_PKG_LOCK_VERSION_MISMATCH (\"vector-math\", \"1.2.0\", \"1.3.0\")"))
        write(root.resolve("createscript.lock"), lock(locked).replace("\n", "\r\n"))
        assertTrue(cli("lock", "check", root.toString()).err.contains("error CS_PKG_LOCK_NOT_CANONICAL"))
        write(root.resolve("createscript.lock"), lock(locked).replace(sha, sha.uppercase()))
        val upper = cli("lock", "check", root.toString())
        assertEquals(ExitCode.INVALID_INPUT, upper.code)
        assertTrue(upper.err.contains("error CS_PKG_INVALID_SHA256"), upper.err)
        // マニフェストが正しくなければ、その診断を出す
        write(root.resolve("createscript.json"), "{}")
        assertTrue(cli("lock", "check", root.toString()).err.contains("createscript.json:1:1: error CS_PKG_MISSING_FIELD (\"schemaVersion\")"))
        // 依存の無いプロジェクトはロックファイルが無くてよい
        val plain = project(Manifests.write(Manifest("demo", "1.0.0", "src/main.cscr")))
        assertEquals("${plain.resolve("createscript.lock")}: not needed (no dependencies)\n", cli("lock", "check", plain.toString()).out)
        // 検査はファイルを変えない
        val before = Files.readAllBytes(root.resolve("createscript.lock"))
        cli("lock", "check", root.toString())
        assertArrayEquals(before, Files.readAllBytes(root.resolve("createscript.lock")))
    }

    @Test
    fun usageErrors() {
        for (args in listOf(listOf("manifest"), listOf("manifest", "verify"), listOf("lock", "check", "a", "b"), listOf("lock", "check", "--strict"), listOf("lock"))) {
            val result = cli(*args.toTypedArray())
            assertEquals(ExitCode.USAGE, result.code, "$args")
            assertTrue(result.err.contains("createscript --help"), result.err)
        }
        val help = cli("--help").out
        for (line in listOf("createscript init [directory] [--name <name>]", "createscript manifest check [directory]", "createscript lock check [directory]", "73 cannot create a file")) {
            assertTrue(help.contains(line), line)
        }
    }

    // カレントディレクトリでの使い方（ディレクトリを省略すると、表示するファイル名はファイル名だけ）
    @Test
    fun defaultsToTheCurrentDirectory() {
        val result = cli("manifest", "check")
        // テストのカレントディレクトリ（createscript-cli）には createscript.json が無い
        assertEquals(ExitCode.NO_INPUT, result.code)
        assertEquals("createscript: createscript.json not found (create one with createscript init)\n", result.err)
    }
}
