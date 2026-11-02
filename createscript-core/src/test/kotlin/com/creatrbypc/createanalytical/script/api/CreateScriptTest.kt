package com.creatrbypc.createanalytical.script.api

import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第3.12段階 S4: Minecraft 以外のアプリケーションから使う API（CreateScript・ScriptRun）。各テストは利用例を兼ねる
class CreateScriptTest {
    private fun compile(source: String): CompiledProgram {
        val result = CreateScript.compile(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 終わるまで tick を進め、進めた tick 数を返す
    private fun finish(run: ScriptRun): Int {
        var ticks = 0
        while (!run.isFinished) {
            run.tick()
            check(++ticks < 1_000_000) { "did not finish" }
        }
        return ticks
    }

    // ---- 利用例: コンパイルして診断を受け取る ----

    @Test
    fun compileReturnsDiagnostics() {
        val result = CreateScript.compile("val x: Int = \"a\"\nprintln(x)")
        assertNull(result.program)
        assertTrue(result.hasErrors)
        val diagnostic = result.diagnostics.single()
        assertEquals("CS_SEM_TYPE_MISMATCH", diagnostic.code.id)
        assertEquals(1 to 14, diagnostic.position.line to diagnostic.position.column)
    }

    // ---- 利用例: 実行して出力を受け取る（乱数・Vector・Matrix・isClose。S3 の実機の確認と同じ出力） ----

    @Test
    fun runsProgramTickByTick() {
        val source = "randomSeed(42)\nprintln(random())\nprintln(randomInt(1, 6))\nval v = vector(3.0, 4.0)\nprintln(length(v))\nprintln(normalize(v))\n" +
            "val m = matrix(vector(1.0, 2.0), vector(3.0, 4.0))\nprintln(matmul(m, transpose(m)))\nprintln(isClose(0.1 + 0.2, 0.3))\nprintln(0.1 + 0.2 == 0.3)"
        val printed = ArrayList<String>()
        val run = CreateScript.start(compile(source), RunOptions(instructionsPerTick = 128, randomSeed = 0L)) { printed.add(it) }
        finish(run)
        val expected = listOf("0.7415648787718233", "1", "5.0", "[0.6, 0.8]", "[[5.0, 11.0], [11.0, 25.0]]", "true", "false")
        assertEquals(VmState.HALTED, run.state)
        assertEquals(expected, printed)
        assertEquals(expected, run.console)
    }

    // ---- コンソールの通知は 32 行を超えた分も受け取れる。履歴（Minecraft の表示）は最大 32 行のまま ----

    @Test
    fun callbackReceivesMoreThan32Lines() {
        val printed = ArrayList<String>()
        val run = CreateScript.start(compile("for (i in 0 until 40) { println(i) }"), RunOptions(instructionsPerTick = 7, randomSeed = 0L)) { printed.add(it) }
        finish(run)
        assertEquals((0 until 40).map { "$it" }, printed)
        assertEquals((8 until 40).map { "$it" }, run.console)
    }

    // 1行の長さは履歴と同じ（64 単位を超えたら「…」で切る）
    @Test
    fun callbackLinesAreFittedLikeHistory() {
        val long = "a".repeat(100)
        val printed = ArrayList<String>()
        finish(CreateScript.start(compile("println(\"$long\")"), RunOptions(instructionsPerTick = 128, randomSeed = 0L)) { printed.add(it) })
        assertEquals(listOf("a".repeat(63) + "…"), printed)
    }

    // 通知先が無ければ記録しない（Minecraft 側と同じ）
    @Test
    fun runsWithoutCallback() {
        val run = CreateScript.start(compile("for (i in 0 until 40) { println(i) }"), RunOptions(instructionsPerTick = 128, randomSeed = 0L))
        finish(run)
        assertEquals((8 until 40).map { "$it" }, run.console)
    }

    // ---- 命令の予算: 1 tick の命令数は instructionsPerTick を超えない（回転速度とは無関係） ----

    @Test
    fun instructionsPerTickLimitsEachTick() {
        val program = compile((1..10).joinToString("\n") { "println($it)" })
        for (limit in listOf(1, 3, 128)) {
            val run = CreateScript.start(program, RunOptions(instructionsPerTick = limit, randomSeed = 0L))
            while (!run.isFinished) assertTrue(run.tick().executed <= limit)
            assertEquals((1..10).map { "$it" }, run.console)
        }
    }

    // waitTicks は tick を数える（Scheduler と同じく、待機のカウントは命令より先）
    @Test
    fun waitTicksCountsTicks() {
        val run = CreateScript.start(compile("println(1)\nwaitTicks(3)\nprintln(2)"), RunOptions(instructionsPerTick = 128, randomSeed = 0L))
        run.tick()
        assertEquals(VmState.WAITING to listOf("1"), run.state to run.console)
        repeat(2) { run.tick() }
        assertEquals(VmState.WAITING, run.state)
        run.tick()
        assertEquals(VmState.HALTED to listOf("1", "2"), run.state to run.console)
    }

    // ---- 乱数の種: 同じ種なら CsVm を直接使った場合と同じ列、違う種なら違う列 ----

    @Test
    fun randomSeedOption() {
        val program = compile("println(random())\nprintln(randomInt(0, 1000000))")
        fun viaApi(seed: Long) = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = seed)).also { finish(it) }.console
        val direct = CsVm(program, 12345L).also { while (it.state == VmState.RUNNING) it.run(128) }.console.history
        assertEquals(direct, viaApi(12345L))
        assertNotEquals(viaApi(12345L), viaApi(12346L))
    }

    // ---- 実行時エラーは error に CS_RUN_* として残る ----

    @Test
    fun runtimeErrorIsReported() {
        val run = CreateScript.start(compile("val l = mutableListOf(1)\nprintln(l[5])"), RunOptions(instructionsPerTick = 128, randomSeed = 0L))
        finish(run)
        assertEquals(VmState.ERROR, run.state)
        assertEquals(DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS, run.error?.code)
    }

    // ---- 回収（GC）は gcBudgetPerTick の範囲で進め、結果は Scheduler で実行した場合と同じ ----

    @Test
    fun garbageCollectionStaysWithinBudget() {
        val source = "class Robot\nval keep = Robot()\nvar t = Robot()\nfor (i in 0 until 300) { t = Robot() }\nprintln(t)\nprintln(keep)"
        val run = CreateScript.start(compile(source), RunOptions(instructionsPerTick = 128, randomSeed = 0L, gcBudgetPerTick = 13))
        var collected = 0
        while (!run.isFinished) collected += run.tick().collected.also { assertTrue(it <= 13) }
        assertTrue(collected > 0)
        assertEquals(VmState.HALTED, run.state)
        assertEquals(scheduled(source, PowerLevel.FAST).first, run.console)
    }

    // 回転速度（PowerLevel）で動かす Scheduler と、同じ命令数を指定した ScriptRun は同じ出力・同じ tick 数になる
    // （Scheduler は登録した tick には実行しないため 1 tick 多い）
    @Test
    fun matchesSchedulerWithSameSpeed() {
        val source = "var s = 0\nfor (i in 0 until 20) {\n  s = s + i\n  if (i % 5 == 0) { waitTicks(2) }\n}\nprintln(s)\nprintln(random())"
        for (power in listOf(PowerLevel.SLOW, PowerLevel.MEDIUM, PowerLevel.FAST)) {
            val (lines, ticks) = scheduled(source, power)
            val run = CreateScript.start(compile(source), RunOptions(instructionsPerTick = power.instructionsPerTick, randomSeed = 0L))
            assertEquals(ticks - 1, finish(run), "$power")
            assertEquals(lines, run.console, "$power")
        }
    }

    private fun scheduled(source: String, power: PowerLevel): Pair<List<String>, Int> {
        val vm = CsVm(compile(source))
        val scheduler = Scheduler<Int>(4096)
        scheduler.register(0, vm)
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            scheduler.tick { ExecutionCondition(power, loaded = true) }
            check(++ticks < 1_000_000) { "did not finish" }
        }
        return vm.console.history to ticks
    }

    // ---- 外部のコールバックの安全性 ----

    // onPrint の中から tick を呼ぶと拒否する（1 tick の命令の上限を超えて進められない）
    @Test
    fun callbackCannotTickReentrantly() {
        lateinit var run: ScriptRun
        var rejected = 0
        run = CreateScript.start(compile("println(1)\nprintln(2)\nprintln(3)"), RunOptions(instructionsPerTick = 1, randomSeed = 0L)) {
            assertThrows(IllegalStateException::class.java) { run.tick() }
            rejected++
        }
        finish(run)
        assertEquals(3, rejected)
        assertEquals(listOf("1", "2", "3"), run.console)
    }

    // onPrint が例外を投げても VM は壊れない（例外は tick の呼び出し元へ伝わり、その後の tick で続きから実行できる）
    @Test
    fun callbackExceptionDoesNotCorruptVm() {
        val printed = ArrayList<String>()
        val run = CreateScript.start(compile("println(1)\nwaitTicks(1)\nprintln(2)\nprintln(3)"), RunOptions(instructionsPerTick = 128, randomSeed = 0L)) {
            if (it == "1") throw IllegalArgumentException("host failure")
            printed.add(it)
        }
        assertThrows(IllegalArgumentException::class.java) { run.tick() }
        assertEquals(VmState.WAITING, run.state)
        finish(run)
        assertEquals(VmState.HALTED, run.state)
        assertEquals(listOf("2", "3"), printed)
        assertEquals(listOf("1", "2", "3"), run.console)
    }

    // ---- 実行の途中の状態（S5.1）: snapshot で写し、resume で続きを実行する ----

    // 待機中に写した状態から続けると、中断しなかった場合と同じ出力になる。onPrint には再開した後の行だけを通知する
    @Test
    fun resumeContinuesFromSnapshot() {
        val program = compile("randomSeed(5)\nprintln(random())\nwaitTicks(3)\nprintln(random())\nprintln(\"end\")")
        val options = RunOptions(instructionsPerTick = 128, randomSeed = 0L)
        val uninterrupted = CreateScript.start(program, options).also { finish(it) }.console
        val first = CreateScript.start(program, options)
        first.tick()
        val snapshot = first.snapshot()!!
        assertEquals(VmState.WAITING, snapshot.state)
        val printed = ArrayList<String>()
        val resumed = CreateScript.resume(program, snapshot, options) { printed.add(it) }!!
        finish(resumed)
        assertEquals(uninterrupted, resumed.console)
        assertEquals(uninterrupted.drop(1), printed)
        // 写した後の元の実行は、写しの影響を受けない
        finish(first)
        assertEquals(uninterrupted, first.console)
    }

    // 検証（SnapshotVerifier）に通らない状態からは再開しない。終わった実行は写せない
    @Test
    fun resumeRejectsInvalidSnapshot() {
        val program = compile("println(1)\nwaitTicks(3)\nprintln(2)")
        val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0L))
        run.tick()
        val snapshot = run.snapshot()!!
        val options = RunOptions(instructionsPerTick = 128, randomSeed = 0L)
        assertNull(CreateScript.resume(program, snapshot.copy(pc = 999), options))
        assertNull(CreateScript.resume(program, snapshot.copy(variables = snapshot.variables + null), options))
        assertNull(CreateScript.resume(compile("println(\"other\")"), snapshot, options))
        finish(run)
        assertNull(run.snapshot())
    }

    // 不正な設定は作れない
    @Test
    fun rejectsInvalidOptions() {
        assertThrows(IllegalArgumentException::class.java) { RunOptions(instructionsPerTick = 0, randomSeed = 0L) }
        assertThrows(IllegalArgumentException::class.java) { RunOptions(instructionsPerTick = -1, randomSeed = 0L) }
        assertThrows(IllegalArgumentException::class.java) { RunOptions(instructionsPerTick = 1, randomSeed = 0L, gcBudgetPerTick = 0) }
        assertFalse(CreateScript.start(compile("println(1)"), RunOptions(instructionsPerTick = 1, randomSeed = 0L)).isFinished)
    }
}
