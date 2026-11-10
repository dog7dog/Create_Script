package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.InstallLayout
import com.creatrbypc.createanalytical.install.Installer
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.PackageSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

// 別のプロセスで install.lock を使う（S8.2 L-8 の試験）。引数: パス・動き・ミリ秒
// - run-loop: run と同じ手順（gate 共有 → data 共有 → gate を外す）で、data を 30 ミリ秒ずつ持つことを、ミリ秒の間くり返す
// - install-hold: install と同じ手順（gate 排他 → data 排他）で取り、"locked" を出力して、ミリ秒の間持つ（0 なら標準入力が閉じるまで）
object LockProtocol {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = Paths.get(args[0])
        val millis = args[2].toLong()
        when (args[1]) {
            "run-loop" -> FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val end = System.currentTimeMillis() + millis
                println("started")
                System.out.flush()
                while (System.currentTimeMillis() < end) {
                    val gate = channel.tryLock(InstallLayout.LOCK_GATE_POSITION, InstallLayout.LOCK_GATE_SIZE, true)
                    if (gate == null) {
                        Thread.sleep(5)
                        continue
                    }
                    val data = channel.tryLock(InstallLayout.LOCK_DATA_POSITION, InstallLayout.LOCK_DATA_SIZE, true)
                    gate.release()
                    if (data != null) {
                        Thread.sleep(30)
                        data.release()
                    }
                }
            }
            "install-hold" -> FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                val gate = channel.lock(InstallLayout.LOCK_GATE_POSITION, InstallLayout.LOCK_GATE_SIZE, false)
                val data = channel.lock(InstallLayout.LOCK_DATA_POSITION, InstallLayout.LOCK_DATA_SIZE, false)
                println("locked")
                System.out.flush()
                if (millis > 0) Thread.sleep(millis) else while (System.`in`.read() != -1) { }
                data.release()
                gate.release()
            }
        }
    }
}

// S8.2 L-8: install を優先する読み書きのロック（gate・data）と、待つ時間の上限
class LockFairnessTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var app: Path

    @BeforeEach
    fun setUp() {
        app = Files.createDirectories(directory.resolve("app"))
        Files.createDirectories(app.resolve("src"))
        Files.writeString(app.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.writeString(app.resolve("src/main.cscr"), "import numbers\nprintln(numbers.v())\n")
        installer().install(PackageSpec("github:owner/numbers", "1.0.0"), false)
    }

    private val waits = ArrayList<String>()

    private fun installer() = Installer(app, PackageFetcher(NumbersReleases(), ArchiveCache(directory.resolve("cache")))) { waits += it }

    private val lockFile get() = app.resolve(".createscript/install.lock")

    private fun process(vararg args: String): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        return ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), LockProtocol::class.java.name) + args).start()
    }

    private fun run(wait: RunLock.Wait): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).also { it.runLockWait = wait }.execute(listOf("run", app.toString())) }
        }
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    // run が絶えず続いても（ほかのプロセスの共有ロックが重なり続けても）、install は gate を取って待ち、data を取れる
    @Test
    @Timeout(120)
    fun installIsNotStarvedByContinuousRuns() {
        val runners = (1..3).map { process(lockFile.toString(), "run-loop", "8000") }
        try {
            runners.forEach { assertEquals("started", it.inputStream.bufferedReader().readLine()) }
            Thread.sleep(300)
            var ok = 0
            for (round in 0 until 5) {
                installer().install(PackageSpec("github:owner/numbers", if (round % 2 == 0) "2.0.0" else "1.0.0"), false)
                ok++
            }
            assertEquals(5, ok)
        } finally {
            runners.forEach { it.destroy(); it.waitFor(30, TimeUnit.SECONDS) }
        }
    }

    // install が動いている間、run は待ち、終われば実行する（以前は、すぐに 75）
    @Test
    @Timeout(60)
    fun runWaitsForARunningInstallation() {
        val holder = process(lockFile.toString(), "install-hold", "1500")
        assertEquals("locked", holder.inputStream.bufferedReader().readLine())
        val started = System.nanoTime()
        val (code, out, err) = run(RunLock.Wait(10_000, 10, 200))
        val waited = (System.nanoTime() - started) / 1_000_000
        assertEquals(ExitCode.OK, code, err)
        assertEquals("1\n", out)
        assertTrue(err.contains("createscript: waiting for createscript install to finish in this project (up to 10 seconds)"), err)
        assertTrue(waited >= 500, "it waited ${waited}ms")
        assertTrue(holder.waitFor(30, TimeUnit.SECONDS))
    }

    // 待つ時間の上限を過ぎれば、run は 75、install は BUSY（意味はこれまでと同じ）。止まったまま待ち続けることは無い
    @Test
    @Timeout(60)
    fun waitingHasALimit() {
        val holder = process(lockFile.toString(), "install-hold", "0")
        try {
            assertEquals("locked", holder.inputStream.bufferedReader().readLine())
            val (code, out, err) = run(RunLock.Wait(1_000, 10, 200))
            assertEquals(ExitCode.TEMPORARY_FAILURE, code, err)
            assertEquals("", out)
            assertTrue(err.contains("and did not finish within 1 seconds; try again when it has finished"), err)
            assertFalse(err.contains("interrupted"), err)
        } finally {
            holder.outputStream.close()
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS))
        }
        assertEquals("1\n", run(RunLock.Wait(1_000, 10, 200)).second)
    }

    // N-4: run がシンボリックリンクを見つけたときは、run の言葉と、プロジェクトからの相対パスで知らせる（絶対パス・install の言葉を出さない）
    @Test
    fun runReportsLinksInItsOwnWords() {
        val numbers = app.resolve(".createscript/packages/numbers")
        val away = Files.move(numbers, directory.resolve("away"))
        Files.createSymbolicLink(numbers, away)
        val (code, out, err) = run(RunLock.Wait(1_000, 10, 200))
        assertEquals(ExitCode.INVALID_INPUT, code)
        assertEquals("", out)
        assertEquals(
            "createscript: .createscript/packages/numbers is a symbolic link; createscript run does not use packages through symbolic links. Remove the link and run createscript install\n",
            err,
        )
        assertFalse(err.contains(directory.toString()), err)
        // install も、相対パスで知らせる（終了コードはこれまでどおり 73）
        val failure = org.junit.jupiter.api.Assertions.assertThrows(com.creatrbypc.createanalytical.install.InstallFailure::class.java) { installer().install(null, false) }
        assertEquals(com.creatrbypc.createanalytical.install.InstallFailure.Kind.UNSAFE, failure.kind)
        assertTrue(failure.message!!.startsWith(".createscript/packages/numbers is a symbolic link; createscript install does not write through symbolic links"), failure.message)
        assertFalse(failure.message!!.contains(directory.toString()), failure.message)
    }
}
