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

// 第3.7c段階の if / else とブロックスコープを、ソースから CS_VM まで通して確かめる
class IfStatementTest {
    private fun vm(source: String): CsVm {
        val result = Compiler.compileSource(source)
        val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
        return CsVm(program).also { it.run(100_000) }
    }

    private fun output(source: String): List<String> {
        val machine = vm(source)
        assertEquals(VmState.HALTED, machine.state, "error ${machine.error} in $source")
        return machine.console.history
    }

    @Test
    fun minimalIf() {
        assertEquals(listOf("OK"), output("if (true) {\n    println(\"OK\")\n}"))
        assertEquals(emptyList<String>(), output("if (false) {\n    println(\"OK\")\n}"))
    }

    @Test
    fun elseAndElseIfChains() {
        fun grade(n: Int) = output(
            "val n = $n\nif (n >= 90) {\n  println(\"A\")\n} else if (n >= 70) {\n  println(\"B\")\n} else if (n >= 50) {\n  println(\"C\")\n} else {\n  println(\"D\")\n}\nprintln(\"end\")",
        )
        assertEquals(listOf("A", "end"), grade(95))
        assertEquals(listOf("B", "end"), grade(70))
        assertEquals(listOf("C", "end"), grade(69))
        assertEquals(listOf("D", "end"), grade(-2147483648))
        // else の無い else if で、どれにも当たらない
        assertEquals(listOf("end"), output("val n = 1\nif (n == 2) { println(2) } else if (n == 3) { println(3) }\nprintln(\"end\")"))
        // 最初に当たった1つだけを実行する（後の条件は評価しない）
        assertEquals(listOf("first"), output("val z = 0\nif (true) { println(\"first\") } else if (1 / z == 0) { println(\"never\") }"))
    }

    @Test
    fun scopesAtRuntime() {
        // 内側の宣言は外側の同名の変数を隠し、ブロックを出ると外側の変数に戻る
        assertEquals(
            listOf("inner s", "true", "inner s", "1", "2"),
            output(
                "val x = 1\nvar n = 1\nif (x == 1) {\n  val x = \"inner s\"\n  println(x)\n  if (true) {\n    val x = true\n    println(x)\n  }\n  println(x)\n  n = n + 1\n}\n" +
                    "println(x)\nprintln(n)",
            ),
        )
        // 外側の変数への代入はブロックの外にも残る。ブロックの変数は毎回初期化してから使う
        assertEquals(listOf("3", "30"), output("var total = 0\nif (true) { val add = 3\ntotal = total + add }\nprintln(total)\nif (total > 1) { val add = 27\ntotal = total + add }\nprintln(total)"))
    }

    @Test
    fun nestedIfsAtMaximumDepth() {
        val source = "var n = 0\n" + (0 until 30).joinToString("") { "if (n == $it) { val v$it = $it\nn = v$it + 1\n" } +
            "println(n)" + (0 until 30).joinToString("") { "\n} else { n = 0 }" }
        assertEquals(listOf("30"), output(source))
    }

    @Test
    fun runtimeErrorsInsideBlocksKeepTheirLine() {
        val machine = vm("val z = 0\nif (z == 0) {\n  println(1)\n  println(1 / z)\n}")
        assertEquals(VmState.ERROR, machine.state)
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, machine.error!!.code)
        assertEquals(4, machine.error!!.position.line)
        assertEquals(listOf("1"), machine.console.history)
    }

    // ---- 無作為に作った if 文のプログラムと、Kotlin で書いた参照の実行器の比較 ----

    private sealed interface Stmt

    // name の変数を宣言する（block の中ではシャドーイングになることがある）
    private data class Declare(val name: String, val add: Int) : Stmt
    private data class AddToTotal(val name: String) : Stmt
    private data class Print(val name: String) : Stmt
    private data class SetFlag(val threshold: Int) : Stmt
    private data class If(val branches: List<Pair<String, List<Stmt>>>, val otherwise: List<Stmt>?) : Stmt

    private class Overflow : RuntimeException(null, null, false, false)

    private class Interpreter {
        val output = ArrayList<String>()
        var overflowed = false
        // 生成するソースの "var total = 1" と同じ
        var total = 1
        var flag = false
        private val scopes = ArrayList<HashMap<String, Int>>().also { it.add(HashMap()) }

        private fun lookup(name: String) = if (name == "total") total else scopes.asReversed().firstNotNullOf { it[name] }

        fun condition(text: String): Boolean = when (text) {
            "flag" -> flag
            "!flag" -> !flag
            else -> {
                val (symbol, value) = text.removePrefix("total ").split(" ")
                val k = value.toInt()
                when (symbol) {
                    "<" -> total < k
                    ">=" -> total >= k
                    "==" -> total == k
                    else -> total != k && flag
                }
            }
        }

        private fun add(a: Int, b: Int) = try {
            Math.addExact(a, b)
        } catch (overflow: ArithmeticException) {
            throw Overflow()
        }

        // オーバーフローしたら（CS_VM では CS_RUN_INTEGER_OVERFLOW）、そこで止める
        fun runProgram(statements: List<Stmt>) {
            try {
                run(statements)
            } catch (overflow: Overflow) {
                overflowed = true
            }
        }

        private fun run(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Declare -> scopes.last()[statement.name] = add(total, statement.add)
                    is AddToTotal -> total = add(total, lookup(statement.name))
                    is Print -> output.add(lookup(statement.name).toString())
                    is SetFlag -> flag = total > statement.threshold
                    is If -> {
                        val taken = statement.branches.firstOrNull { condition(it.first) }?.second ?: statement.otherwise
                        if (taken != null) {
                            scopes.add(HashMap())
                            run(taken)
                            scopes.removeAt(scopes.lastIndex)
                        }
                    }
                }
            }
        }
    }

    private class Generator(private val random: Random) {
        var prints = 0

        fun block(depth: Int, visible: Set<String>): List<Stmt> {
            val declared = HashSet<String>()
            val names = visible.toMutableSet()
            val statements = ArrayList<Stmt>()
            repeat(random.nextInt(0, 5)) {
                when (random.nextInt(6)) {
                    0 -> listOf("a", "b", "c").filter { it !in declared }.randomOrNull(random)?.let {
                        declared.add(it)
                        names.add(it)
                        statements.add(Declare(it, random.nextInt(-3, 4)))
                    }
                    1 -> names.randomOrNull(random)?.let { statements.add(AddToTotal(it)) }
                    2 -> if (prints < 30) names.randomOrNull(random)?.let {
                        prints++
                        statements.add(Print(it))
                    }
                    3 -> statements.add(SetFlag(random.nextInt(-5, 6)))
                    else -> if (depth < 4) {
                        val branches = List(random.nextInt(1, 4)) { condition() to block(depth + 1, names) }
                        statements.add(If(branches, if (random.nextBoolean()) block(depth + 1, names) else null))
                    }
                }
            }
            return statements
        }

        private fun condition(): String = when (random.nextInt(4)) {
            0 -> "flag"
            1 -> "!flag"
            else -> "total ${listOf("<", ">=", "==", "!=&&").random(random)} ${random.nextInt(-5, 6)}"
        }
    }

    private fun renderCondition(text: String): String =
        if (text.contains("!=&&")) text.replace("!=&&", "!=") + " && flag" else text

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        indent + when (statement) {
            is Declare -> "val ${statement.name} = total + ${statement.add}\n".replace("+ -", "- ")
            is AddToTotal -> "total = total + ${statement.name}\n"
            is Print -> "println(${statement.name})\n"
            is SetFlag -> "flag = total > ${statement.threshold}\n"
            is If -> statement.branches.withIndex().joinToString("") { (index, branch) ->
                (if (index == 0) "if" else " else if") + " (${renderCondition(branch.first)}) {\n" + render(branch.second, "$indent  ") + "$indent}"
            } + (statement.otherwise?.let { " else {\n" + render(it, "$indent  ") + "$indent}" } ?: "") + "\n"
        }
    }

    @Test
    fun randomIfProgramsMatchReferenceInterpreter() {
        val random = Random(41)
        var compiled = 0
        var branchesTaken = 0
        repeat(800) {
            val generator = Generator(random)
            val statements = generator.block(0, setOf("total"))
            val source = "var total = 1\nvar flag = false\n" + render(statements, "")
            if (source.length > 4096) return@repeat
            val result = Compiler.compileSource(source)
            // 字句・構文の上限（トークン数など）を超えたものは対象外。それ以外はすべてコンパイルでき、検証に通る
            if (result.diagnostics.any { it.code == DiagnosticCode.LEX_TOO_MANY_TOKENS || it.code == DiagnosticCode.PARSE_TOO_MANY_NODES }) return@repeat
            val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }}\n$source")
            assertNotNull(SnapshotVerifier.framesOf(program), source)
            val expected = Interpreter().also { it.runProgram(statements) }
            val machine = vm(source)
            assertEquals(expected.output, machine.console.history, source)
            assertEquals(if (expected.overflowed) VmState.ERROR else VmState.HALTED, machine.state, source)
            if (expected.overflowed) assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, machine.error!!.code)
            compiled++
            if (statements.any { it is If }) branchesTaken++
        }
        assertTrue(compiled >= 700, "compiled $compiled")
        assertTrue(branchesTaken >= 300, "programs with if: $branchesTaken")
    }
}
