package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// 第3.12段階 S5: createscript コマンド（標準出力・標準エラー出力・終了コード）
class CreateScriptCliTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String) {
        val lines: List<String> get() = out.lines().dropLast(1)
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) }
        }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun script(name: String, source: String): String = directory.resolve(name).also { Files.writeString(it, source) }.toString()

    private val samples: Path = Paths.get(System.getProperty("createscript.samples"))

    private fun sample(name: String) = samples.resolve(name).toString()

    // ---- 実行 ----

    @Test
    fun runsHello() {
        val result = cli("run", sample("hello.cscr"))
        assertEquals(ExitCode.OK, result.code)
        assertEquals(listOf("Hello, CreateScript!"), result.lines)
        assertEquals("", result.err)
    }

    @Test
    fun runsRandomVectorMatrixIsClose() {
        val result = cli("run", sample("features.cscr"))
        assertEquals(ExitCode.OK, result.code)
        assertEquals(listOf("0.7415648787718233", "1", "5.0", "[0.6, 0.8]", "[[5.0, 11.0], [11.0, 25.0]]", "true", "false"), result.lines)
    }

    @Test
    fun printsMoreThan32Lines() {
        val result = cli("run", sample("many_lines.cscr"))
        assertEquals(ExitCode.OK, result.code)
        assertEquals((1..40).map { "$it" }, result.lines)
    }

    // 標準出力は println した順。実行時エラーの診断は標準エラー出力（その時点までの出力は残る）
    @Test
    fun runtimeErrorGoesToStandardError() {
        val file = script("error.cscr", "println(1)\nval l = mutableListOf(1)\nprintln(l[5])\nprintln(2)")
        val result = cli("run", file)
        assertEquals(ExitCode.RUNTIME_ERROR, result.code)
        assertEquals(listOf("1"), result.lines)
        assertEquals("$file:3:10: error CS_RUN_INDEX_OUT_OF_BOUNDS (5, 1)\n", result.err)
    }

    @Test
    fun compileErrorGoesToStandardError() {
        val file = sample("type_error.cscr")
        val result = cli("run", file)
        assertEquals(ExitCode.COMPILE_ERROR, result.code)
        assertEquals("", result.out)
        assertEquals("$file:1:14: error CS_SEM_TYPE_MISMATCH (\"Int\", \"String\")\n", result.err)
    }

    // ソースの上限（4096 単位）は Core の字句解析が診断する
    @Test
    fun sourceLimitIsReportedByCore() {
        val result = cli("run", script("long.cscr", "//" + "a".repeat(5000)))
        assertEquals(ExitCode.COMPILE_ERROR, result.code)
        assertTrue(result.err.contains("error CS_LEX_SOURCE_TOO_LONG"), result.err)
    }

    // ---- waitTicks と --max-ticks（論理 tick） ----

    // tick 1 で 1 を出力して待機、tick 2〜11 で 10 tick を数え、tick 11 で 2 を出力して終わる。実時間では待たない
    @Test
    fun waitTicksCountsLogicalTicks() {
        val file = script("wait.cscr", "println(1)\nwaitTicks(10)\nprintln(2)")
        assertEquals(listOf("1", "2"), cli("run", file, "--max-ticks", "11").also { assertEquals(ExitCode.OK, it.code) }.lines)
        val stopped = cli("run", file, "--max-ticks", "10")
        assertEquals(ExitCode.MAX_TICKS_EXCEEDED, stopped.code)
        assertEquals(listOf("1"), stopped.lines)
        val started = System.nanoTime()
        assertEquals(ExitCode.OK, cli("run", sample("wait.cscr")).code)
        assertTrue(System.nanoTime() - started < 10_000_000_000L)
    }

    @Test
    fun infiniteLoopStopsAtMaxTicks() {
        val result = cli("run", sample("infinite_loop.cscr"), "--max-ticks", "1000")
        assertEquals(ExitCode.MAX_TICKS_EXCEEDED, result.code)
        assertEquals("${sample("infinite_loop.cscr")}: stopped after 1000 ticks (--max-ticks); the program did not finish\n", result.err)
        assertEquals(ExitCode.MAX_TICKS_EXCEEDED, cli("run", sample("infinite_loop.cscr")).code)
    }

    // 1 tick の命令数は --instructions-per-tick を超えない（1 命令/tick なら 10 tick では終わらない）
    @Test
    fun instructionsPerTickLimitsProgress() {
        val file = script("count.cscr", (1..10).joinToString("\n") { "println($it)" })
        assertEquals(ExitCode.MAX_TICKS_EXCEEDED, cli("run", file, "--instructions-per-tick", "1", "--max-ticks", "10").code)
        assertEquals((1..10).map { "$it" }, cli("run", file, "--instructions-per-tick", "4096", "--max-ticks", "1").lines)
    }

    // --seed は乱数の最初の状態（Core の API に同じ種を渡した場合と同じ。既定は 0）
    @Test
    fun seedOption() {
        val source = "println(random())\nprintln(randomInt(0, 1000000))"
        val file = script("random.cscr", source)
        fun expected(seed: Long) = CreateScript.start(CreateScript.compile(source).program!!, RunOptions(128, seed)).also { while (!it.isFinished) it.tick() }.console
        assertEquals(expected(42L), cli("run", file, "--seed", "42").lines)
        assertEquals(expected(-7L), cli("run", file, "--seed", "-7").lines)
        assertEquals(expected(0L), cli("run", file).lines)
    }

    // ---- ファイル ----

    @Test
    fun missingFile() {
        val result = cli("run", directory.resolve("missing.cscr").toString())
        assertEquals(ExitCode.NO_INPUT, result.code)
        assertTrue(result.err.startsWith("createscript: file not found: "), result.err)
    }

    // 拡張子はファイル名だけで判定する（内容が正しいプログラムでも、.cscr でなければ拒否する）
    @Test
    fun rejectsOtherExtensions() {
        for (name in listOf("hello.cs", "hello.txt", "hello.cscr.txt", "hello", ".cscr", "hello.CSCR")) {
            val result = cli("run", script(name, "println(1)"))
            assertEquals(ExitCode.USAGE, result.code, name)
            assertTrue(result.err.contains("not a CreateScript file") && result.err.contains(".cscr extension"), result.err)
            assertEquals("", result.out, name)
        }
    }

    // ディレクトリの名前は判定に使わない
    @Test
    fun directoryNamesDoNotAffectExtensionCheck() {
        val csDirectory = Files.createDirectory(directory.resolve("scripts.cs"))
        Files.writeString(csDirectory.resolve("ok.cscr"), "println(1)")
        assertEquals(listOf("1"), cli("run", csDirectory.resolve("ok.cscr").toString()).lines)
        val cscrDirectory = Files.createDirectory(directory.resolve("scripts.cscr"))
        Files.writeString(cscrDirectory.resolve("ng.txt"), "println(1)")
        assertEquals(ExitCode.USAGE, cli("run", cscrDirectory.resolve("ng.txt").toString()).code)
        // .cscr という名前のディレクトリはファイルではない
        assertEquals(ExitCode.NO_INPUT, cli("run", cscrDirectory.toString()).code)
    }

    @Test
    fun rejectsInvalidUtf8AndTooLargeFiles() {
        val invalid = directory.resolve("invalid.cscr").also { Files.write(it, byteArrayOf(0x70, 0xC3.toByte(), 0x28)) }
        assertEquals(ExitCode.INVALID_INPUT, cli("run", invalid.toString()).code)
        val large = script("large.cscr", "a".repeat(CliLimits.MAX_SOURCE_BYTES + 1))
        val result = cli("run", large)
        assertEquals(ExitCode.INVALID_INPUT, result.code)
        assertTrue(result.err.contains("file too large"), result.err)
    }

    // ---- オプション ----

    @Test
    fun rejectsInvalidOptions() {
        val file = sample("hello.cscr")
        val cases = listOf(
            listOf("start", file),
            listOf("--verbose"),
            listOf("run"),
            listOf("run", file, file),
            listOf("run", file, "--unknown", "1"),
            listOf("run", file, "--seed"),
            listOf("run", file, "--seed", "abc"),
            listOf("run", file, "--seed", "9223372036854775808"),
            listOf("run", file, "--seed", "1", "--seed", "2"),
            listOf("run", file, "--instructions-per-tick", "0"),
            listOf("run", file, "--instructions-per-tick", "4097"),
            listOf("run", file, "--instructions-per-tick", "1.5"),
            listOf("run", file, "--max-ticks", "-1"),
            listOf("run", file, "--max-ticks", "1000001"),
            listOf("--version", "extra"),
        )
        for (args in cases) {
            val result = cli(*args.toTypedArray())
            assertEquals(ExitCode.USAGE, result.code, "$args")
            assertEquals("", result.out, "$args")
            assertTrue(result.err.startsWith("createscript: ") && result.err.contains("createscript --help"), "$args: ${result.err}")
        }
        // 上限ちょうどは受け付ける
        assertEquals(ExitCode.OK, cli("run", file, "--instructions-per-tick", "4096", "--max-ticks", "1000000", "--seed", "-9223372036854775808").code)
    }

    @Test
    fun helpAndVersion() {
        val help = cli("--help")
        assertEquals(ExitCode.OK, help.code)
        assertTrue(help.out.startsWith("Usage:") && help.out.contains("createscript run <file.cscr>"), help.out)
        assertEquals(help.out, cli("-h").out)
        val version = cli("--version")
        assertEquals(ExitCode.OK to listOf("createscript ${BuildInfo.VERSION}"), version.code to version.lines)
    }

    // ---- サンプルと起動スクリプト ----

    @Test
    fun allSamplesBehaveAsDocumented() {
        val expected = mapOf(
            "hello.cscr" to ExitCode.OK, "features.cscr" to ExitCode.OK, "many_lines.cscr" to ExitCode.OK, "wait.cscr" to ExitCode.OK,
            "type_error.cscr" to ExitCode.COMPILE_ERROR, "infinite_loop.cscr" to ExitCode.MAX_TICKS_EXCEEDED,
        )
        val names = Files.list(samples).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(expected.keys.sorted(), names)
        for ((name, code) in expected) assertEquals(code, cli("run", sample(name)).code, name)
    }

    // macOS・Linux 用（POSIX sh）と Windows 用の起動スクリプトを生成する
    @Test
    fun startScriptsAreGenerated() {
        val scripts = Paths.get(System.getProperty("createscript.scripts"))
        val unix = scripts.resolve("createscript")
        assertTrue(Files.isRegularFile(unix) && Files.isExecutable(unix), "$unix")
        val text = Files.readString(unix)
        assertTrue(text.startsWith("#!/bin/sh"), text.take(40))
        assertTrue(text.contains("com.creatrbypc.createanalytical.cli.MainKt"))
        assertTrue(Files.isRegularFile(scripts.resolve("createscript.bat")))
    }
}
