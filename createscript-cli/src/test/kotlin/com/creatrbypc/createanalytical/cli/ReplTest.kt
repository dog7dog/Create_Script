package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

// 第3.12段階 S5.1: 対話モード（引数なしの createscript）
class ReplTest {
    private class Session(val code: Int, val out: String, val err: String) {
        // プロンプト（">>> "・"... "）を除いた標準出力の行。最初の 2 行は案内
        val results: List<String>
            get() = out.lines().drop(2).map { line ->
                var rest = line
                while (rest.startsWith(">>> ") || rest.startsWith("... ")) rest = rest.drop(4)
                rest
            }.filter { it.isNotEmpty() }
    }

    private fun repl(vararg lines: String, eof: Boolean = false): Session {
        val text = lines.joinToString("\n") + if (eof) "" else "\n"
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e ->
                CreateScriptCli(o, e, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).execute(emptyList())
            }
        }
        return Session(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun startsWithBannerAndPrompt() {
        val session = repl("exit")
        assertEquals(ExitCode.OK, session.code)
        assertEquals(listOf("CreateScript ${BuildInfo.VERSION}", "Type \"help\" for help, \"exit\" to quit.", ">>> "), session.out.lines())
    }

    // 1. 式の値を表示する
    @Test
    fun evaluatesExpression() {
        assertEquals(listOf("2"), repl("1 + 1", "exit").results)
    }

    // 2・3. 変数の宣言・参照・代入が次の入力に引き継がれ、前の入力は実行し直さない
    @Test
    fun keepsVariablesWithoutReexecution() {
        assertEquals(listOf("20"), repl("val x = 10", "x * 2", "exit").results)
        assertEquals(listOf("1", "1"), repl("var counter = 0", "counter = counter + 1", "counter", "counter", "exit").results)
    }

    // 4. println の出力は 1 回だけ（後の入力で前の入力の出力をくり返さない）
    @Test
    fun printlnIsNotRepeated() {
        val session = repl("println(\"Hello, CreateScript!\")", "println(\"second\")", "1", "exit")
        assertEquals(listOf("Hello, CreateScript!", "second", "1"), session.results)
        assertEquals("", session.err)
    }

    // 5. 複数行の入力（括弧が閉じるまで "..." で続きを待つ）
    @Test
    fun runsMultiLineInput() {
        val session = repl(
            "fun square(n: Int): Int {", "  return n * n", "}",
            "var i = 0",
            "while (i < 3) {", "  println(square(i))", "  i = i + 1", "}",
            "if (i == 3) {", "  println(\"done\")", "} else {", "  println(\"no\")", "}",
            "square(9)",
            "exit",
        )
        assertEquals(listOf("0", "1", "4", "done", "81"), session.results)
        // "..." は関数 2 行・while 3 行・if / else 4 行
        assertEquals(9, Regex("\\.\\.\\. ").findAll(session.out).count())
    }

    // 6. 型の誤りの後も続けられる（誤りのある入力はセッションに加えない）
    @Test
    fun continuesAfterTypeError() {
        val session = repl("val x = 10", "val s: Int = \"a\"", "x + 1", "val s = 5", "s", "exit")
        assertEquals("<input>:1:14: error CS_SEM_TYPE_MISMATCH (\"Int\", \"String\")\n", session.err)
        assertEquals(listOf("11", "5"), session.results)
    }

    // 実行時エラーの入力は、それまでの変更も含めてセッションに加えない（前の状態のまま続く）
    @Test
    fun runtimeErrorRollsBackInput() {
        val session = repl("var a = 1", "if (true) {", "  a = 2", "  println(listOf(1)[3])", "}", "a", "exit")
        assertEquals(listOf("1"), session.results)
        assertTrue(session.err.startsWith("<input>:3:20: error CS_RUN_INDEX_OUT_OF_BOUNDS (3, 1)"), session.err)
        assertTrue(session.err.contains("(this input was not added to the session)"))
    }

    // 7. 無限ループは実行の上限で止まり、セッションは続く
    @Test
    fun infiniteLoopStops() {
        val session = repl("var n = 7", "while (true) {", "  n = n + 1", "}", "n", "exit")
        assertEquals(listOf("7"), session.results)
        assertTrue(session.err.contains("<input>: stopped after ${CliLimits.DEFAULT_MAX_TICKS} ticks"), session.err)
    }

    // 8. exit・quit・EOF（Ctrl+D）で終わる。終わった後の行は実行しない
    @Test
    fun exitQuitAndEof() {
        for (command in listOf("exit", "quit", "  exit  ")) {
            val session = repl("println(1)", command, "println(2)")
            assertEquals(ExitCode.OK to listOf("1"), session.code to session.results, command)
        }
        val eof = repl("println(1)", "println(2)", eof = true)
        assertEquals(ExitCode.OK to listOf("1", "2"), eof.code to eof.results)
        // 入力の途中の EOF は、その入力を実行しない
        assertEquals(listOf("1"), repl("println(1)", "if (true) {", "  println(2)", eof = true).results)
    }

    @Test
    fun help() {
        val session = repl("help", "exit")
        assertTrue(session.out.contains("CreateScript interactive mode") && session.out.contains("exit   quit"), session.out)
    }

    // クラス・オブジェクトの状態と、文字列（定数の番号は後の入力で変わる）を引き継ぐ
    @Test
    fun keepsObjectsAndStrings() {
        val session = repl(
            "class Robot {", "  var name = \"r2\"", "}",
            "fun label(): String {", "  return \"from a function\"", "}",
            "val r = Robot()",
            "val l = label()",
            "r.name = \"d2\"",
            "val other = \"a new constant\"",
            "r.name", "l", "other", "r",
            "exit",
        )
        assertEquals(listOf("d2", "from a function", "a new constant", "Robot@1"), session.results)
    }

    // 乱数の列は入力をまたいで続く（同じ種のスクリプトと同じ列）
    @Test
    fun randomSequenceContinues() {
        val expected = CreateScript.start(CreateScript.compile("println(random())\nprintln(randomInt(0, 100))\nprintln(random())").program!!, RunOptions(128, CliLimits.DEFAULT_SEED))
            .also { while (!it.isFinished) it.tick() }.console
        assertEquals(expected, repl("random()", "randomInt(0, 100)", "random()", "exit").results)
    }

    // 入力の中の waitTicks は論理 tick を数えて進む
    @Test
    fun waitTicksInsideInput() {
        assertEquals(listOf("1", "2"), repl("println(1)", "waitTicks(500)", "println(2)", "exit").results)
    }

    // セッションの上限（ソース 4096 文字・変数 64 個など）に達すると、それ以上は加えられないが、それまでの状態は使える
    @Test
    fun sessionLimitIsReported() {
        val inputs = (0 until 300).map { "println($it)" }
        val session = repl(*(inputs + listOf("val kept = 1", "exit")).toTypedArray())
        val accepted = session.results
        assertEquals(accepted.indices.map { "$it" }, accepted)
        assertTrue(accepted.size in 100 until 300, "${accepted.size}")
        assertTrue(session.err.contains("error CS_LEX_SOURCE_TOO_LONG") && session.err.contains("the session has reached a CreateScript limit"), session.err.take(300))
    }

    // 同じ名前の再宣言はエラーになり、前の宣言が残る
    @Test
    fun redeclarationIsRejected() {
        val session = repl("val x = 1", "val x = 2", "x", "exit")
        assertEquals(listOf("1"), session.results)
        assertTrue(session.err.startsWith("<input>:1:5: error CS_SEM_"), session.err)
    }
}
