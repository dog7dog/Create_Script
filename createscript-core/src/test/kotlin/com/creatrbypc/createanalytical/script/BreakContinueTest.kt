package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第3.8a段階の break / continue を、ソースから CS_VM・Scheduler まで通して確かめる
class BreakContinueTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する。終わらなければ失敗（continue が条件を飛ばすと終わらない）
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
    fun breakEndsTheLoop() {
        assertEquals(listOf("0", "1", "2", "after"), output("var i = 0\nwhile (true) {\n  if (i == 3) {\n    break\n  }\n  println(i)\n  i = i + 1\n}\nprintln(\"after\")"))
        // break の後の文は実行しない
        assertEquals(listOf("ok"), output("while (true) {\n  break\n  println(\"never\")\n}\nprintln(\"ok\")"))
    }

    @Test
    fun continueGoesToTheNextIteration() {
        assertEquals(listOf("1", "3", "5"), output("var i = 0\nwhile (i < 5) {\n  i = i + 1\n  if (i % 2 == 0) {\n    continue\n  }\n  println(i)\n}"))
    }

    // continue は条件を評価し直す。本体の先頭へ戻すと go = false が効かず、終わらない
    @Test
    fun continueReevaluatesTheCondition() {
        assertEquals(listOf("1"), output("var go = true\nvar n = 0\nwhile (go) {\n  n = n + 1\n  go = false\n  continue\n  println(\"never\")\n}\nprintln(n)"))
        // 条件の副作用（短絡評価の右辺の 0 除算）も毎回評価される: 3 回目の条件で 10 / 0
        val (vm, _) = runToEnd("var k = 2\nwhile (k == 2 || 10 / k > 0) {\n  k = k - 1\n  continue\n}")
        assertEquals("CS_RUN_DIVISION_BY_ZERO", vm.error?.code?.id)
        assertEquals(2, vm.error?.position?.line)
    }

    @Test
    fun insideIfElseAndNestedLoops() {
        assertEquals(listOf("1", "skip", "3"), output("var i = 0\nwhile (true) {\n  i = i + 1\n  if (i == 2) {\n    println(\"skip\")\n    continue\n  } else if (i > 3) {\n    break\n  } else {\n    println(i)\n  }\n}"))
        // 内側の break / continue は内側のループだけに効く
        assertEquals(
            listOf("11", "-1", "21", "31", "33", "-3"),
            output(
                "var a = 0\nwhile (a < 3) {\n  a = a + 1\n  var b = 0\n  while (true) {\n    b = b + 1\n    if (b > a) { break }\n    if (b == 2) { continue }\n" +
                    "    println(a * 10 + b)\n  }\n  if (a == 2) { continue }\n  println(-a)\n}",
            ),
        )
    }

    @Test
    fun scopesAndShadowing() {
        // continue / break でブロックを途中で抜けても、外側の同名の変数に戻る
        assertEquals(
            listOf("true", "1", "outer"),
            output("val x = \"outer\"\nvar i = 0\nwhile (i < 2) {\n  val x = i\n  i = i + 1\n  if (x == 0) {\n    val x = true\n    println(x)\n    continue\n  }\n  println(x)\n  break\n}\nprintln(x)"),
        )
        // 本体の変数は continue の後の反復でも初期化式で入れ直す
        assertEquals(listOf("0", "2"), output("var i = 0\nwhile (i < 3) {\n  var c = 0\n  c = c + i\n  i = i + 1\n  if (c == 1) { continue }\n  println(c)\n}"))
    }

    @Test
    fun emptyLoopsAndWaitTicks() {
        assertEquals(listOf("ok"), output("while (false) { break }\nwhile (false) { continue }\nwhile (true) { break }\nwhile (true) { if (true) { break } else { continue } }\nprintln(\"ok\")"))
        val (vm, ticks) = runToEnd("var i = 0\nwhile (true) {\n  i = i + 1\n  waitTicks(2)\n  if (i < 3) { continue }\n  println(i)\n  break\n}")
        assertEquals(listOf("3"), vm.console.history)
        assertTrue(ticks >= 6, "ticks $ticks")
    }

    // continue だけの無限ループも予算どおりに制御を返し、Scheduler の上限・公平性を守る
    @Test
    fun continueLoopsStayWithinBudget() {
        val spin = compile("while (true) { continue }")
        val vm = CsVm(spin)
        assertEquals(16, vm.run(16))
        assertEquals(VmState.RUNNING, vm.state)
        val scheduler = Scheduler<Int>(4096)
        repeat(3) { scheduler.register(it, CsVm(spin)) }
        repeat(500) { scheduler.register(3 + it, CsVm(compile("var n = 0\nwhile (true) { n = n + 1\nif (n > 5) { n = 0\ncontinue }\nif (n == 3) { continue } }"))) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(503)
        repeat(20) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }
}
