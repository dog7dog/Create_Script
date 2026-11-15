package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.InstallFailure
import com.creatrbypc.createanalytical.install.InstalledPackages
import com.creatrbypc.createanalytical.install.Installer
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.PackageSpec
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.PackageArchives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

// メモリーの中の Release と、numbers 1.0.0（v() は 1）・2.0.0（v() は 2）
internal class NumbersReleases : ReleaseStore {
    val assets = HashMap<String, ByteArray>()

    init {
        for (v in 1..2) {
            val manifest = """{"schemaVersion": 2, "kind": "library", "name": "numbers", "version": "$v.0.0", "modules": ["numbers"], "dependencies": {}}"""
            assets["github:owner/numbers@$v.0.0"] = PackageArchives.write(
                mapOf("createscript.json" to manifest.toByteArray(), "modules/numbers.cscr" to "fun v(): Int {\n    return $v\n}\n".toByteArray()),
            )
        }
    }

    override fun download(source: String, version: String) = assets["$source@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/x"))

    override fun releaseInfo(source: String, version: String) = GitHubReleases.ReleaseInfo(assets["$source@$version"]?.let(GitHubReleases::sha256Hex), true)
}

// 別のプロセスで動かす（同時実行の試験）。引数: プロジェクト・キャッシュ・回数。numbers の 2.0.0 と 1.0.0 を交互にインストールし、最後に "done 成功 BUSY" を出力する
object InstallLoop {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Paths.get(args[0])
        val installer = Installer(root, PackageFetcher(NumbersReleases(), ArchiveCache(Paths.get(args[1]))))
        var ok = 0
        var busy = 0
        // run が確かめている間はロックを取れない（BUSY）ので、取れるまで繰り返す
        for (i in 0 until args[2].toInt()) {
            while (true) {
                try {
                    installer.install(PackageSpec("github:owner/numbers", if (i % 2 == 0) "2.0.0" else "1.0.0"), false)
                    ok++
                    break
                } catch (failure: InstallFailure) {
                    if (failure.kind != InstallFailure.Kind.BUSY) throw failure
                    busy++
                }
            }
        }
        println("done $ok $busy")
    }
}

// 別のプロセスで install.lock の排他ロックを取り、標準入力が閉じるまで持ち続ける（インストールの代わり）
object ExclusiveLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Paths.get(args[0]), StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            println(if (lock == null) "busy" else "locked")
            System.out.flush()
            while (System.`in`.read() != -1) {
                // 閉じるまで待つ
            }
            lock?.release()
        }
    }
}

// S8.2 L-8: createscript run と createscript install の同時実行（install.lock の共有ロック）
class RunLockTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    private val releases = NumbersReleases()
    private lateinit var app: Path
    private lateinit var cache: Path

    @BeforeEach
    fun setUp() {
        cache = directory.resolve("cache")
        app = Files.createDirectories(directory.resolve("app"))
        Files.createDirectories(app.resolve("src"))
        Files.writeString(app.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.writeString(app.resolve("src/main.cscr"), "import numbers\nprintln(numbers.v())\n")
        install("1.0.0")
    }

    private fun install(version: String, root: Path = app) =
        Installer(root, PackageFetcher(releases, ArchiveCache(cache))).install(PackageSpec("github:owner/numbers", version), false)

    private fun run(root: Path = app, locker: RunLock.Locker = RunLock.DEFAULT): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e ->
                // install を待つ時間は、テストでは 1 秒（S8.2 L-8）
                CreateScriptCli(o, e).also { it.runLocker = locker; it.runLockWait = RunLock.Wait(1_000, 10, 200) }.execute(listOf("run", root.toString()))
            }
        }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private val lockFile get() = app.resolve(".createscript/install.lock")
    private val journal get() = app.resolve(".createscript/transaction.json")

    // ファイルの状態（種類・大きさ・SHA-256・更新時刻・fileKey）。run が何も書かないことを確かめる
    private fun state(root: Path): Map<String, String> = Files.walk(root).use { s ->
        s.toList().associate { path ->
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            root.relativize(path).toString() to if (a.isRegularFile) {
                "file:${a.size()}:${GitHubReleases.sha256Hex(Files.readAllBytes(path))}:${a.lastModifiedTime()}:${a.fileKey()}"
            } else {
                "${if (a.isDirectory) "dir" else "other"}:${a.lastModifiedTime()}:${a.fileKey()}"
            }
        }
    }

    // 別のプロセス（このテストと同じクラスパス）
    private fun process(main: Any, vararg args: String): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        return ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), main.javaClass.name) + args).redirectErrorStream(true).start()
    }

    // ---- 通常の run・install ----

    @Test
    fun aNormalRunAndInstall() {
        assertTrue(Files.exists(lockFile))
        val result = run()
        assertEquals(ExitCode.OK, result.code, result.err)
        assertEquals("1\n", result.out)
        install("2.0.0")
        assertEquals("2\n", run().out)
    }

    // ---- インストールが動いている ----

    // ほかのプロセスが install.lock の排他ロックを持つ（インストールの最中）: 75。外れれば実行できる
    @Test
    fun refusesWithBusyWhileAnotherProcessInstalls() {
        val holder = process(ExclusiveLockHolder, lockFile.toString())
        try {
            assertEquals("locked", holder.inputStream.bufferedReader().readLine())
            val before = state(app)
            val result = run()
            assertEquals(ExitCode.TEMPORARY_FAILURE, result.code, result.err)
            assertEquals(
                "createscript: waiting for createscript install to finish in this project (up to 1 seconds)\n" +
                    "createscript: createscript install is running in this project (.createscript/install.lock is locked) and did not finish within 1 seconds; try again when it has finished\n",
                result.err,
            )
            assertEquals("", result.out)
            assertEquals(before, state(app))
            // ジャーナルがあっても（インストールの途中）、「中断した」とは言わない
            Files.writeString(journal, "{}")
            val during = run()
            assertEquals(ExitCode.TEMPORARY_FAILURE, during.code, during.err)
            assertFalse(during.err.contains("interrupted"), during.err)
            Files.delete(journal)
        } finally {
            holder.outputStream.close()
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS))
        }
        assertEquals("1\n", run().out)
    }

    // 同じプロセスの中のインストール（OverlappingFileLockException）も 75
    @Test
    fun refusesWithBusyWhileThisProcessHoldsTheLock() {
        FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertEquals(ExitCode.TEMPORARY_FAILURE, run().code)
            }
        }
        assertEquals(ExitCode.OK, run().code)
    }

    // ---- 中断したインストール ----

    // インストールが動いていない（共有ロックを取れた）のにジャーナルがある: 65
    @Test
    fun anInterruptedInstallationGives65() {
        Files.writeString(journal, "{}")
        val result = run()
        assertEquals(ExitCode.INVALID_INPUT, result.code, result.err)
        assertTrue(result.err.contains("an installation was interrupted") && result.err.contains("run createscript install to finish or undo it"), result.err)
        assertEquals("", result.out)
    }

    // ---- install.lock が無い・読み取り専用 ----

    @Test
    fun runsWithoutALockFileAndCreatesNone() {
        Files.delete(lockFile)
        val before = state(app)
        assertEquals("1\n", run().out)
        assertEquals(before, state(app))
        assertFalse(Files.exists(lockFile))
        // ジャーナルだけがある（install は、ジャーナルの前に必ず install.lock を作る）: 中断したもの
        Files.writeString(journal, "{}")
        val result = run()
        assertEquals(ExitCode.INVALID_INPUT, result.code)
        assertTrue(result.err.contains("an installation was interrupted"), result.err)
        assertFalse(Files.exists(lockFile))
    }

    // パッケージが無いプロジェクト（.createscript も無い）
    @Test
    fun runsAProjectWithoutPackages() {
        val plain = Files.createDirectories(directory.resolve("plain/src"))
        Files.writeString(plain.parent.resolve("createscript.json"), """{"schemaVersion": 1, "name": "plain", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.writeString(plain.resolve("main.cscr"), "println(7)\n")
        assertEquals("7\n", run(plain.parent).out)
        assertFalse(Files.exists(plain.parent.resolve(".createscript")))
    }

    // 読み取り専用のプロジェクト（ディレクトリもファイルも書けない）: 共有ロックは読むために開いたファイルで取れる
    @Test
    fun runsAReadOnlyProject() {
        val all = Files.walk(app).use { it.toList() }
        val readOnlyDir = PosixFilePermissions.fromString("r-xr-xr-x")
        val readOnlyFile = PosixFilePermissions.fromString("r--r--r--")
        try {
            for (path in all.reversed()) Files.setPosixFilePermissions(path, if (Files.isDirectory(path)) readOnlyDir else readOnlyFile)
            val before = state(app)
            val result = run()
            assertEquals(ExitCode.OK, result.code, result.err)
            assertEquals("1\n", result.out)
            assertEquals(before, state(app))
        } finally {
            for (path in all) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwxr-xr-x" else "rw-r--r--"))
        }
    }

    // 読めない install.lock（ロックを試せない）: 安全とはみなさず、ジャーナルがあれば「動いているか中断したか分からない」
    @Test
    fun anUnreadableLockFileIsNotTreatedAsSafe() {
        Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("-w-------"))
        try {
            assertEquals("1\n", run().out)
            Files.writeString(journal, "{}")
            val result = run()
            assertEquals(ExitCode.INVALID_INPUT, result.code)
            assertTrue(result.err.contains("an installation is running or was interrupted") && result.err.contains("could not check whether createscript install is running: cannot open .createscript/install.lock for reading"), result.err)
        } finally {
            Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    // ---- 共有ロックを扱えないファイルシステム ----

    @Test
    fun withoutSharedLocksRunIsNotTreatedAsSafe() {
        val unsupported = RunLock.Locker { _, _, _ -> throw IOException("Operation not supported") }
        // ジャーナルが無ければ、これまでどおり確かめて実行する（確かめたバイト列だけを実行する）
        assertEquals("1\n", run(locker = unsupported).out)
        // ジャーナルがあれば、動いているか中断したか分からないとして止まる（「インストールは動いていない」とはみなさない）
        Files.writeString(journal, "{}")
        val result = run(locker = unsupported)
        assertEquals(ExitCode.INVALID_INPUT, result.code)
        assertTrue(result.err.contains("could not check whether createscript install is running: the file system did not lock .createscript/install.lock (Operation not supported)"), result.err)
    }

    // ---- ロックを外すこと・書かないこと ----

    @Test
    fun runReleasesTheLockAndWritesNothing() {
        var taken: FileLock? = null
        // data の範囲の共有ロック（gate は取ってすぐ外す）
        val recording = RunLock.Locker { channel, position, size ->
            RunLock.DEFAULT.tryShared(channel, position, size).also { if (position == com.creatrbypc.createanalytical.install.InstallLayout.LOCK_DATA_POSITION) taken = it }
        }
        val before = state(app)
        assertEquals("1\n", run(locker = recording).out)
        assertEquals(before, state(app))
        assertNotNull(taken)
        assertTrue(taken!!.isShared)
        assertFalse(taken!!.isValid)
        // 失敗した run も外す
        Files.writeString(journal, "{}")
        assertEquals(ExitCode.INVALID_INPUT, run(locker = recording).code)
        assertFalse(taken!!.isValid)
        Files.delete(journal)
        // インストールは、すぐにロックを取れる
        install("2.0.0")
        assertEquals("2\n", run().out)
    }

    // ---- 同時実行の繰り返し ----

    // 別のプロセスでインストールを繰り返す間に、run を繰り返す。run の結果は、成功（そのときのロックファイルの版の値）か 75 だけ。
    // 中断（65）と誤って判定しない、誤ったコードを実行しない、インストールを壊さない
    @Test
    fun runAndInstallInParallel() {
        val rounds = (System.getenv("L8_INSTALLS") ?: "40").toInt()
        val loop = process(InstallLoop, app.toString(), cache.toString(), rounds.toString())
        val counts = java.util.TreeMap<String, Int>()
        val unexpected = ArrayList<String>()
        while (loop.isAlive) {
            val result = run()
            val key = when {
                result.code == ExitCode.OK && (result.out == "1\n" || result.out == "2\n") -> "ok ${result.out.trim()}"
                result.code == ExitCode.TEMPORARY_FAILURE -> "busy"
                else -> {
                    unexpected += "${result.code}: ${result.out}${result.err}"
                    "unexpected ${result.code}"
                }
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        val output = loop.inputStream.bufferedReader().readText()
        assertEquals(0, loop.waitFor(), output)
        println("L8[parallel] installs=$rounds install=${output.trim()} runs=$counts")
        assertEquals(emptyList<String>(), unexpected.take(5))
        assertTrue(output.startsWith("done "), output)
        // 最後のインストール（rounds が偶数なら 1.0.0）のとおりに、確かめて実行できる
        val expected = if (rounds % 2 == 0) "1" else "2"
        val lockfile = Lockfiles.parse(Files.readAllBytes(app.resolve("createscript.lock"))).lockfile!!
        assertEquals("$expected.0.0", InstalledPackages.verify(app, lockfile).single().version)
        assertEquals("$expected\n", run().out)
        assertFalse(Files.exists(journal))
    }
}
