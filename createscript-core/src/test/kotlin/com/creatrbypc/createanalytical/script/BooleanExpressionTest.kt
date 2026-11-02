package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// 第3.7b段階の Boolean・比較・論理演算子を、ソースから Lexer → Parser → Semantic → Compiler → CS_VM まで通して確かめる
class BooleanExpressionTest {
    private sealed interface Outcome {
        data class Output(val lines: List<String>) : Outcome
        data class Failure(val code: DiagnosticCode) : Outcome
    }

    private fun run(source: String): Outcome {
        val result = Compiler.compileSource(source)
        val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
        val vm = CsVm(program)
        vm.run(100_000)
        return when (vm.state) {
            VmState.HALTED -> Outcome.Output(vm.console.history)
            VmState.ERROR -> Outcome.Failure(vm.error!!.code)
            else -> error("did not finish: ${vm.state} in $source")
        }
    }

    private fun output(source: String) = (run(source) as? Outcome.Output ?: error("${run(source)} in $source")).lines

    @Test
    fun booleanDeclarationsAssignmentsAndOutput() {
        assertEquals(listOf("true", "false", "false", "true"), output("val t = true\nvar f: Boolean = false\nprintln(t)\nprintln(f)\nf = !t\nprintln(f)\nf = t\nprintln(f)"))
        assertEquals(listOf("true", "false"), output("val b = 3 > 2\nprintln(b)\nprintln(!b)"))
    }

    @Test
    fun equality() {
        assertEquals(
            listOf("true", "false", "false", "true", "true", "false", "true", "false", "false", "true"),
            output(
                listOf(
                    "1 == 1", "1 == 2", "1 != 1", "-2147483648 != 2147483647", "true == true", "true == false", "false != true",
                    // String は内容で比べる（大文字小文字・長さの違いは等しくない）
                    "\"abc\" == \"abd\"", "\"a\" == \"A\"", "\"\" == \"\"",
                ).joinToString("\n") { "println($it)" },
            ),
        )
        assertEquals(listOf("true", "false"), output("val s = \"ab\"\nval t = \"ab\"\nvar u = \"x\"\nprintln(s == t)\nu = \"ab \"\nprintln(s == u)"))
        // Boolean 同士の == は「同じ真偽値か」
        assertEquals(listOf("true", "false"), output("println((1 < 2) == (3 < 4))\nprintln((1 < 2) == (3 > 4))"))
    }

    @Test
    fun intComparisons() {
        val values = listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)
        // コンソールは最大 32 行のため、左辺の値ごとに分けて実行する
        for (a in values) {
            val source = StringBuilder()
            val expected = ArrayList<String>()
            for (b in values) {
                for ((symbol, compare) in listOf<Pair<String, (Int, Int) -> Boolean>>("<" to { x, y -> x < y }, "<=" to { x, y -> x <= y }, ">" to { x, y -> x > y }, ">=" to { x, y -> x >= y })) {
                    source.append("println($a $symbol $b)\n")
                    expected.add(compare(a, b).toString())
                }
            }
            assertEquals(expected, output(source.toString()), "left $a")
        }
    }

    @Test
    fun precedence() {
        // && は || より先に結びつく: true || (false && false)
        assertEquals(listOf("true"), output("println(true || false && false)"))
        assertEquals(listOf("false"), output("println((true || false) && false)"))
        // ! は == より先: (!true) == false
        assertEquals(listOf("true"), output("println(!true == false)"))
        // 算術 > 大小比較 > 等価 > &&
        assertEquals(listOf("true"), output("println(1 + 2 * 3 == 7 && 10 - 4 > 5 == true)"))
        assertEquals(listOf("false", "true"), output("println(!!false)\nprintln(!!!false)"))
        // 次の行の先頭の && || は継続する
        assertEquals(listOf("false"), output("val x = true\n  && false\n  || false\nprintln(x)"))
    }

    @Test
    fun shortCircuitSkipsRightOperand() {
        // 右辺のゼロ除算は、右辺を評価する場合にだけ起きる
        assertEquals(listOf("false", "true"), output("val z = 0\nprintln(false && 1 / z == 0)\nprintln(true || 1 / z == 0)"))
        assertEquals(Outcome.Failure(DiagnosticCode.RUN_DIVISION_BY_ZERO), run("val z = 0\nprintln(true && 1 / z == 0)"))
        assertEquals(Outcome.Failure(DiagnosticCode.RUN_DIVISION_BY_ZERO), run("val z = 0\nprintln(false || 1 / z == 0)"))
        // 右辺のオーバーフローも同じ
        assertEquals(listOf("false"), output("val m = 2147483647\nprintln(m < 0 && m + 1 > 0)"))
        assertEquals(Outcome.Failure(DiagnosticCode.RUN_INTEGER_OVERFLOW), run("val m = 2147483647\nprintln(m > 0 && m + 1 > 0)"))
        // 入れ子: 左の && が false なら、その右辺も外側の || の左辺の残りも評価しない
        assertEquals(listOf("true"), output("val z = 0\nprintln(z != 0 && 10 / z > 1 || z == 0)"))
        assertEquals(listOf("before", "false"), output("val z = 0\nprintln(\"before\")\nval b = z == 1 && (z / z == 1 || z / z == 0)\nprintln(b)"))
        // 左辺は必ず評価する
        assertEquals(Outcome.Failure(DiagnosticCode.RUN_DIVISION_BY_ZERO), run("val z = 0\nprintln(1 / z == 0 && false)"))
    }

    // 字句の上限（2048 トークン）近くまで短絡評価を並べたプログラムの、コンパイル全体（検証を含む）と検証だけの時間（参考値）
    @Test
    fun measureLargestBooleanProgram() {
        val source = "val p = true\nval q = false\n" + "println(p&&q||!p)\n".repeat(200)
        val program = Compiler.compileSource(source).program ?: error("compile failed")
        repeat(50) { Compiler.compileSource(source) }
        val compileStart = System.nanoTime()
        repeat(200) { Compiler.compileSource(source) }
        val compileMillis = (System.nanoTime() - compileStart) / 1_000_000.0 / 200
        val verifyStart = System.nanoTime()
        repeat(200) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0 / 200
        println(
            "[3.7b measure] boolean program: ${source.length} UTF-16 units, ${program.instructions.size} instructions, " +
                "compile ${"%.3f".format(compileMillis)} ms (verification included), verification ${"%.3f".format(verifyMillis)} ms",
        )
    }

    // ---- 無作為に作った式と、Kotlin で書いた参照の評価器の比較 ----

    private sealed interface Expr

    private data class IntLit(val value: Int) : Expr
    private data class BoolLit(val value: Boolean) : Expr
    private data class StrLit(val value: String) : Expr
    private data class Var(val name: String) : Expr
    private data class Unary(val symbol: String, val operand: Expr) : Expr
    private data class Binary(val symbol: String, val left: Expr, val right: Expr) : Expr

    private class EvalError(val code: DiagnosticCode) : RuntimeException(null, null, false, false)

    private val intVariables = mapOf("a" to 7, "b" to 0, "c" to Int.MIN_VALUE, "d" to -3)
    private val boolVariables = mapOf("p" to true, "q" to false)
    private val stringVariables = mapOf("s" to "x", "t" to "xy")

    private fun evaluate(expr: Expr): Any = when (expr) {
        is IntLit -> expr.value
        is BoolLit -> expr.value
        is StrLit -> expr.value
        is Var -> intVariables[expr.name] ?: boolVariables[expr.name] ?: stringVariables.getValue(expr.name)
        is Unary -> when (expr.symbol) {
            "-" -> (evaluate(expr.operand) as Int).let { if (it == Int.MIN_VALUE) throw EvalError(DiagnosticCode.RUN_INTEGER_OVERFLOW) else -it }
            else -> !(evaluate(expr.operand) as Boolean)
        }
        is Binary -> when (expr.symbol) {
            "&&" -> evaluate(expr.left) as Boolean && evaluate(expr.right) as Boolean
            "||" -> evaluate(expr.left) as Boolean || evaluate(expr.right) as Boolean
            else -> {
                val left = evaluate(expr.left)
                val right = evaluate(expr.right)
                when (expr.symbol) {
                    "==" -> left == right
                    "!=" -> left != right
                    else -> arithmetic(expr.symbol, left as Int, right as Int)
                }
            }
        }
    }

    private fun arithmetic(symbol: String, a: Int, b: Int): Any {
        fun exact(block: () -> Int) = try {
            block()
        } catch (overflow: ArithmeticException) {
            throw EvalError(DiagnosticCode.RUN_INTEGER_OVERFLOW)
        }
        return when (symbol) {
            "<" -> a < b
            "<=" -> a <= b
            ">" -> a > b
            ">=" -> a >= b
            "+" -> exact { Math.addExact(a, b) }
            "-" -> exact { Math.subtractExact(a, b) }
            "*" -> exact { Math.multiplyExact(a, b) }
            else -> {
                if (b == 0) throw EvalError(DiagnosticCode.RUN_DIVISION_BY_ZERO)
                if (symbol == "/" && a == Int.MIN_VALUE && b == -1) throw EvalError(DiagnosticCode.RUN_INTEGER_OVERFLOW)
                if (symbol == "/") a / b else a % b
            }
        }
    }

    private class Generator(private val random: Random) {
        fun bool(depth: Int): Expr {
            if (depth == 0 || random.nextInt(5) == 0) return if (random.nextBoolean()) BoolLit(random.nextBoolean()) else Var(if (random.nextBoolean()) "p" else "q")
            return when (random.nextInt(6)) {
                0 -> Unary("!", bool(depth - 1))
                1, 2 -> Binary(if (random.nextBoolean()) "&&" else "||", bool(depth - 1), bool(depth - 1))
                3 -> Binary(listOf("<", "<=", ">", ">=", "==", "!=").random(random), int(depth - 1), int(depth - 1))
                4 -> Binary(if (random.nextBoolean()) "==" else "!=", bool(depth - 1), bool(depth - 1))
                else -> Binary(if (random.nextBoolean()) "==" else "!=", string(), string())
            }
        }

        private fun int(depth: Int): Expr {
            if (depth == 0 || random.nextInt(3) == 0) return if (random.nextBoolean()) IntLit(listOf(0, 1, 2, -1, 2147483647).random(random)) else Var(listOf("a", "b", "c", "d").random(random))
            return if (random.nextInt(5) == 0) Unary("-", int(depth - 1)) else Binary(listOf("+", "-", "*", "/", "%").random(random), int(depth - 1), int(depth - 1))
        }

        private fun string(): Expr = if (random.nextBoolean()) StrLit(listOf("x", "xy", "").random(random)) else Var(if (random.nextBoolean()) "s" else "t")
    }

    private fun precedenceOf(expr: Expr): Int = when (expr) {
        is Binary -> when (expr.symbol) {
            "||" -> 1
            "&&" -> 2
            "==", "!=" -> 3
            "<", "<=", ">", ">=" -> 4
            "+", "-" -> 5
            else -> 6
        }
        is Unary -> 7
        // 負の整数リテラルは単項の - として読まれる
        is IntLit -> if (expr.value < 0) 7 else 8
        else -> 8
    }

    // full なら全部の部分式を括弧で囲む。そうでなければ優先順位と左結合から必要な括弧だけを付ける
    private fun render(expr: Expr, full: Boolean): String = when (expr) {
        is IntLit -> expr.value.toString()
        is BoolLit -> expr.value.toString()
        is StrLit -> "\"${expr.value}\""
        is Var -> expr.name
        is Unary -> {
            val operand = renderChild(expr.operand, full, precedenceOf(expr.operand) < 7)
            // "- -1" を "--"（デクリメント）にしない。"!!" は !(!x) なのでそのままでよい
            expr.symbol + (if (expr.symbol == "-" && operand.startsWith("-")) " " else "") + operand
        }
        is Binary -> {
            val own = precedenceOf(expr)
            val left = renderChild(expr.left, full, precedenceOf(expr.left) < own)
            val right = renderChild(expr.right, full, precedenceOf(expr.right) <= own)
            "$left ${expr.symbol} $right"
        }
    }

    private fun renderChild(expr: Expr, full: Boolean, needed: Boolean): String {
        val text = render(expr, full)
        val atomic = expr is BoolLit || expr is StrLit || expr is Var || (expr is IntLit && expr.value >= 0)
        return if ((full && !atomic) || needed) "($text)" else text
    }

    private val prelude = (intVariables.map { (name, value) -> "val $name = $value" } +
        boolVariables.map { (name, value) -> "val $name = $value" } +
        stringVariables.map { (name, value) -> "val $name = \"$value\"" }).joinToString("\n")

    @Test
    fun randomExpressionsMatchReferenceEvaluator() {
        val random = Random(37)
        val generator = Generator(random)
        var programs = 0
        var failures = 0
        repeat(1500) {
            val expr = generator.bool(random.nextInt(1, 6))
            val expected = try {
                Outcome.Output(listOf(evaluate(expr).toString()))
            } catch (error: EvalError) {
                failures++
                Outcome.Failure(error.code)
            }
            for (full in listOf(false, true)) {
                val source = "$prelude\nprintln(${render(expr, full)})"
                // Compiler.compile は出力を検証してから返す。念のためここでも検証できることを確かめる
                assertNotNull(SnapshotVerifier.framesOf(Compiler.compileSource(source).program ?: error("compile failed: $source")), source)
                assertEquals(expected, run(source), source)
                programs++
            }
        }
        assertEquals(3000, programs)
        // 実行時エラーになる式（短絡で避けられなかったもの）と、ならない式の両方を含む
        assertTrue(failures in 1 until 1500, "failures $failures")
    }
}
