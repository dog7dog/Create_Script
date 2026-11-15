package com.creatrbypc.createanalytical.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

// 第9.0〜9.3段階の統合試験: createscript run（独立版の設定）で、リストの関数・パイプライン・エラーフォールバック・ラムダを、
// import したモジュールの関数（|> の右・関数の参照）とともに動かす
class LanguageS9CliTest {
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

    private fun write(name: String, source: String): Path = directory.resolve(name).also { Files.writeString(it, source) }

    @Test
    fun runsCollectionsPipelinesFallbacksAndLambdas() {
        write(
            "stats.cscr",
            "fun scale(x: Int, k: Int): Int {\n    return x * k\n}\n\nfun safeDiv(a: Int, b: Int): Int {\n    return a / b ?! 0\n}\n\nfun odd(x: Int): Boolean {\n    return x % 2 == 1\n}\n\n" +
                "fun odds(xs: List<Int>): List<Int> {\n    return xs.where(::odd)\n}\n",
        )
        val main = write(
            "main.cscr",
            """
            import stats

            val readings = listOf(3, 8, 1, 6, 5, 0)
            val scaled = readings
                |> where { it > 0 }
                |> map { stats.scale(it, 10) }
            println(scaled)
            println(readings |> stats.odds())
            println(readings.group { it % 3 })
            println(readings.average())
            println(readings.map { 60 / it ?! -1 })
            println(stats.safeDiv(1, 0))
            val label: (Int) -> String = { n ->
                var text = "small"
                if (n > 4) {
                    text = "big"
                }
                text
            }
            readings.where { it > 4 }.each { println(label(it)) }
            val empty: List<Double> = listOf()
            println(empty.average())

            """.trimIndent(),
        )
        val result = cli("run", main.toString())
        assertEquals("", result.err)
        assertEquals(ExitCode.OK, result.code)
        assertEquals(listOf("[30, 80, 10, 60, 50]", "[3, 1, 5]", "[[3, 6, 0], [8, 5], [1]]", "3.8333333333333335", "[20, 7, 60, 10, 12, -1]", "0", "big", "big", "big", "NaN"), result.lines)
    }

    @Test
    fun reportsLambdaErrorsWithCodes() {
        val main = write("bad.cscr", "val xs = listOf(1, 2)\nprintln(xs.map { x -> return 1 })\n")
        val result = cli("run", main.toString())
        assertTrue(result.code != ExitCode.OK)
        assertTrue(result.err.contains("CS_SEM_RETURN_IN_LAMBDA"), result.err)
    }
}
