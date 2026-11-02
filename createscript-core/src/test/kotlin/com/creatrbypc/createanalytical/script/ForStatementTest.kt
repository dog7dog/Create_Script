package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第3.8b段階の整数範囲の for を、ソースから CS_VM・Scheduler まで通して確かめる
class ForStatementTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する。終わらなければ失敗（Int の端で無限ループにならないことの確認を兼ねる）
    private fun runToEnd(source: String): Pair<CsVm, Int> {
        val vm = CsVm(compile(source))
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1000)
            check(++ticks < 10_000) { "did not finish: $source" }
        }
        return vm to ticks
    }

    private fun output(source: String): List<String> {
        val (vm, _) = runToEnd(source)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    @Test
    fun threeKindsOfRanges() {
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), output("for (i in 0..5) {\n    println(i)\n}"))
        assertEquals(listOf("0", "1", "2", "3", "4"), output("for (i in 0 until 5) {\n    println(i)\n}"))
        assertEquals(listOf("5", "4", "3", "2", "1"), output("for (i in 5 downTo 1) {\n    println(i)\n}"))
        // 1 要素
        assertEquals(listOf("3", "3", "3"), output("for (i in 3..3) { println(i) }\nfor (i in 3 until 4) { println(i) }\nfor (i in 3 downTo 3) { println(i) }"))
        // 端点は式
        assertEquals(listOf("8", "9", "10"), output("val a = 4\nfor (k in a * 2..a + 6) { println(k) }"))
    }

    @Test
    fun emptyRangesSkipTheBody() {
        assertEquals(listOf("end"), output("for (i in 5..0) { println(i) }\nfor (i in 5 until 0) { println(i) }\nfor (i in 0 downTo 5) { println(i) }\nfor (i in 3 until 3) { println(i) }\nprintln(\"end\")"))
    }

    // Int の端でもオーバーフロー・無限ループにならない（最後の反復の後に加算・減算しない）
    @Test
    fun intBoundaries() {
        assertEquals(listOf("2147483645", "2147483646", "2147483647"), output("for (i in 2147483645..2147483647) { println(i) }"))
        assertEquals(listOf("2147483647"), output("for (i in 2147483647..2147483647) { println(i) }"))
        assertEquals(listOf("2147483646"), output("for (i in 2147483646 until 2147483647) { println(i) }"))
        assertEquals(listOf("-2147483646", "-2147483647", "-2147483648"), output("for (i in -2147483646 downTo -2147483648) { println(i) }"))
        assertEquals(listOf("-2147483648"), output("for (i in -2147483648 downTo -2147483648) { println(i) }"))
        assertEquals(emptyList<String>(), output("for (i in -2147483648 until -2147483648) { println(i) }\nfor (i in 2147483647 downTo -2147483648) { break }"))
        // 最後の反復で continue しても加算しない
        assertEquals(listOf("done"), output("for (i in 2147483646..2147483647) { continue }\nfor (i in -2147483647 downTo -2147483648) { continue }\nprintln(\"done\")"))
        // 端点の計算そのもののオーバーフローは従来どおり実行時エラー
        val (overflow, _) = runToEnd("val m = 2147483647\nfor (i in 0..m + 1) { }")
        assertEquals("CS_RUN_INTEGER_OVERFLOW", overflow.error?.code?.id)
    }

    // 始点・終点はループの開始時に1回だけ評価する（途中で元の変数を変えても範囲は変わらない）
    @Test
    fun boundsAreEvaluatedOnce() {
        assertEquals(listOf("0", "1", "2", "6"), output("var n = 3\nfor (i in 0 until n) {\n  n = n + 1\n  println(i)\n}\nprintln(n)"))
        assertEquals(listOf("0", "1", "2"), output("var s = 0\nvar e = 2\nfor (i in s..e) {\n  s = 100\n  e = -100\n  println(i)\n}"))
        // 終点の式（a * 2 の MULTIPLY）を実行した回数を数える: ループが何回回っても 1 回
        val program = compile("val a = 3\nfor (i in a..a * 2) { }")
        val multiply = program.instructions.indexOfFirst { it.opcode == Opcode.MULTIPLY }
        val vm = CsVm(program)
        var evaluated = 0
        while (vm.state == VmState.RUNNING) {
            if (vm.snapshot()!!.pc == multiply) evaluated++
            vm.run(1)
        }
        assertEquals(1, evaluated)
    }

    @Test
    fun scopesAndShadowing() {
        // 外側の同名の変数は、終点では外側・本体ではループ変数。本体の中の同名の宣言はシャドーイング。for の後は外側に戻る
        assertEquals(
            listOf("10", "20", "outer"),
            output("val i = \"outer\"\nval n = 2\nfor (i in 1..n) {\n  val i = i * 10\n  println(i)\n}\nprintln(i)"),
        )
        // 本体の変数は反復ごとに初期化式で入れ直す
        assertEquals(listOf("1", "2", "3"), output("for (i in 1..3) {\n  var acc = 0\n  acc = acc + i\n  println(acc)\n}"))
        // 同じ名前の for を入れ子にすると内側が外側を隠す
        assertEquals(listOf("1", "2", "2"), output("for (i in 0..1) {\n  for (i in i + 1..2) {\n    println(i)\n  }\n}"))
    }

    @Test
    fun nestingWithWhileIfBreakAndContinue() {
        assertEquals(
            listOf("1", "3", "x"),
            output("for (i in 0..4) {\n  if (i % 2 == 0) {\n    continue\n  } else if (i == 4) {\n    break\n  }\n  println(i)\n}\nprintln(\"x\")"),
        )
        assertEquals(listOf("0", "1", "2"), output("for (i in 0..10) {\n  if (i == 3) {\n    break\n  }\n  println(i)\n}"))
        // while の中の for、for の中の while。break / continue は最も内側のループだけに効く
        assertEquals(
            listOf("2", "1", "11", "22", "21"),
            output(
                "var outer = 0\nwhile (outer < 3) {\n  for (j in 2 downTo 0) {\n    if (j == 0) { break }\n    if (outer == 1 && j == 2) { continue }\n" +
                    "    var k = 0\n    while (true) {\n      k = k + 1\n      if (k >= j) { break }\n    }\n    println(outer * 10 + k)\n  }\n  outer = outer + 1\n}",
            ),
        )
        // for の中の for（Kotlin の同じループで計算した値と比べる）
        var expected = 0
        for (i in 0..3) for (j in i until 4) { if (j == 2) continue; expected += i * 10 + j }
        assertEquals(listOf("$expected"), output("var sum = 0\nfor (i in 0..3) {\n  for (j in i until 4) {\n    if (j == 2) { continue }\n    sum = sum + i * 10 + j\n  }\n}\nprintln(sum)"))
    }

    @Test
    fun waitTicksInsideFor() {
        val (vm, ticks) = runToEnd("for (i in 3 downTo 1) {\n  println(i)\n  waitTicks(4)\n}\nprintln(\"go\")")
        assertEquals(listOf("3", "2", "1", "go"), vm.console.history)
        assertTrue(ticks >= 12, "ticks $ticks")
    }

    // 1 tick あたりの Scheduler の処理時間（1000 台の長い for）と、最大規模の for の入れ子のコンパイル・検証の時間（参考値）
    @Test
    fun measureFor() {
        val long = compile("var sum = 0\nfor (i in -2147483648..2147483647) { sum = i % 7 }")
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(long)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(200) { scheduler.tick { fast } }
        val start = System.nanoTime()
        repeat(1000) { scheduler.tick { fast } }
        println("[3.8b measure] scheduler tick with 1000 long for loops (budget 4096): ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 1000)} ms per tick")
        val nested = (0 until 30).joinToString("") { "for (i$it in 0..1) {\n" } + "println(1)" + "\n}".repeat(30)
        val program = compile(nested)
        repeat(50) { compile(nested) }
        val compileStart = System.nanoTime()
        repeat(200) { compile(nested) }
        val compileMillis = (System.nanoTime() - compileStart) / 1_000_000.0 / 200
        val verifyStart = System.nanoTime()
        repeat(200) { SnapshotVerifier.framesOf(program) }
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0 / 200
        println(
            "[3.8b measure] nested for (30 blocks): ${program.instructions.size} instructions, ${program.variableCount} variables, " +
                "compile ${"%.3f".format(compileMillis)} ms (verification included), verification ${"%.3f".format(verifyMillis)} ms",
        )
    }

    // 非常に長い範囲も予算どおりに制御を返し、Scheduler の上限・公平性を守る
    @Test
    fun longRangesStayWithinBudget() {
        val long = compile("for (i in -2147483648..2147483647) { }")
        val vm = CsVm(long)
        assertEquals(16, vm.run(16))
        assertEquals(VmState.RUNNING, vm.state)
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) long else compile("for (i in 0 until 2147483647) { if (i % 3 == 0) { continue } }"))) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(600)
        repeat(20) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }
}
