package com.creatrbypc.createanalytical.script.scheduler

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchedulerTest {
    private val fast = ExecutionCondition(PowerLevel.FAST, true)

    private fun vm(source: String) = CsVm(Compiler.compileSource(source).program!!)

    // 100 行の println（301 命令。FAST の上限 128 で 3 tick かかる）。予算の配分を見るための長いプログラム
    private fun longVm() = vm((1..100).joinToString("\n") { "println($it)" })

    private fun <K : Any> Scheduler<K>.tickAll(condition: ExecutionCondition = fast) = tick { condition }

    @Test
    fun singleVmRunsToCompletion() {
        val scheduler = Scheduler<String>(100)
        val machine = vm("println(1)\nprintln(2)")
        scheduler.register("a", machine)
        // 登録を反映した tick は実行しない（A24）
        assertEquals(emptyMap<String, Int>(), scheduler.tickAll().executed)
        val result = scheduler.tickAll()
        assertEquals(mapOf("a" to 7), result.executed)
        assertEquals(mapOf("a" to VmState.HALTED), result.finished)
        assertEquals(listOf("1", "2"), machine.console.history)
        assertEquals(emptySet<String>(), scheduler.registeredKeys)
    }

    @Test
    fun perVmBudgetFollowsPowerLevel() {
        for (level in listOf(PowerLevel.SLOW, PowerLevel.MEDIUM, PowerLevel.FAST)) {
            val scheduler = Scheduler<String>(1000)
            scheduler.register("a", longVm())
            scheduler.tickAll()
            assertEquals(mapOf("a" to level.instructionsPerTick), scheduler.tickAll(ExecutionCondition(level, true)).executed, level.name)
        }
    }

    // 第3.10a-P段階: FAST だけを 128 に変更し、SLOW・MEDIUM は変わらない。FAST の台が全体の予算（4096）を超える台数でも、
    // 全体の予算を超えず、SLOW・MEDIUM の台は毎 tick 上限どおり実行され、FAST の台どうしは均等に分け合う
    @Test
    fun fastLimitKeepsGlobalBudgetAndFairness() {
        assertEquals(listOf(0, 1, 4, 128), PowerLevel.entries.map { it.instructionsPerTick })
        val spin = "var n = 0\nwhile (true) { n = n + 1 }"
        val levelOf = { key: Int -> if (key < 40) PowerLevel.FAST else if (key < 44) PowerLevel.MEDIUM else PowerLevel.SLOW }
        val scheduler = Scheduler<Int>(4096)
        repeat(48) { scheduler.register(it, vm(spin)) }
        scheduler.tick { ExecutionCondition(levelOf(it), true) }
        val totals = IntArray(40)
        repeat(50) {
            val result = scheduler.tick { ExecutionCondition(levelOf(it), true) }
            assertEquals(4096, result.budgetUsed)
            for (key in 40 until 48) assertEquals(levelOf(key).instructionsPerTick, result.executed[key], "key $key")
            for (key in 0 until 40) totals[key] += result.executed.getValue(key)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }

    @Test
    fun globalBudgetIsSharedFairly() {
        val scheduler = Scheduler<String>(5)
        scheduler.register("a", longVm())
        scheduler.register("b", longVm())
        scheduler.tickAll()
        // 1回の訪問で1命令ずつ交互に実行する: a b a b a
        val first = scheduler.tickAll()
        assertEquals(mapOf("a" to 3, "b" to 2), first.executed)
        assertEquals(5, first.budgetUsed)
        // 次の tick は前回最後に実行した a の次（b）から始まる: b a b a b
        assertEquals(mapOf("b" to 3, "a" to 2), scheduler.tickAll().executed)
    }

    @Test
    fun executionOrderIsDeterministicRoundRobin() {
        val scheduler = Scheduler<String>(1)
        for (key in listOf("a", "b", "c")) scheduler.register(key, longVm())
        scheduler.tickAll()
        val order = (1..6).map { scheduler.tickAll().executed.keys.single() }
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), order)
    }

    @Test
    fun waitTicksZeroResumesNextTick() {
        val scheduler = Scheduler<String>(100)
        val machine = vm("println(1)\nwaitTicks(0)\nprintln(2)")
        scheduler.register("a", machine)
        scheduler.tickAll()
        // tick t: waitTicks(0) まで実行し、同じ tick では再実行しない
        assertEquals(mapOf("a" to 5), scheduler.tickAll().executed)
        assertEquals(VmState.WAITING, machine.state)
        // tick t+1 で再開する
        val resumed = scheduler.tickAll()
        assertEquals(mapOf("a" to VmState.HALTED), resumed.finished)
        assertEquals(listOf("1", "2"), machine.console.history)
    }

    // waitTicks(T) を tick t に実行したとき、再開する tick が t+T になること（D6）
    private fun resumeTickAfterWait(ticks: Int): Int {
        val scheduler = Scheduler<String>(100)
        val machine = vm("waitTicks($ticks)\nprintln(\"done\")")
        scheduler.register("a", machine)
        scheduler.tickAll()
        scheduler.tickAll()
        assertEquals(VmState.WAITING, machine.state)
        var elapsed = 0
        while (machine.console.history.isEmpty()) {
            elapsed++
            scheduler.tickAll()
            check(elapsed < 100)
        }
        return elapsed
    }

    @Test
    fun waitTicksPositive() {
        assertEquals(1, resumeTickAfterWait(1))
        assertEquals(3, resumeTickAfterWait(3))
    }

    @Test
    fun waitIsFrozenWithoutPowerOrWhenUnloaded() {
        for (frozen in listOf(ExecutionCondition(PowerLevel.NONE, true), ExecutionCondition(PowerLevel.FAST, false))) {
            val scheduler = Scheduler<String>(100)
            val machine = vm("waitTicks(3)\nprintln(\"done\")")
            scheduler.register("a", machine)
            scheduler.tickAll()
            scheduler.tickAll()
            scheduler.tickAll()
            // 2 tick 凍結しても残りは減らない
            scheduler.tickAll(frozen)
            scheduler.tickAll(frozen)
            assertEquals(2, machine.waitRemaining, frozen.toString())
            scheduler.tickAll()
            assertEquals(1, machine.waitRemaining)
            scheduler.tickAll()
            assertEquals(listOf("done"), machine.console.history)
        }
    }

    @Test
    fun runningVmIsPausedWithoutPower() {
        val scheduler = Scheduler<String>(1000)
        val machine = longVm()
        scheduler.register("a", machine)
        scheduler.tickAll()
        assertEquals(emptyMap<String, Int>(), scheduler.tickAll(ExecutionCondition(PowerLevel.NONE, true)).executed)
        // 動力による一時停止は VM の状態とは別。VM は RUNNING のまま
        assertEquals(VmState.RUNNING, machine.state)
        assertEquals(mapOf("a" to PowerLevel.FAST.instructionsPerTick), scheduler.tickAll().executed)
    }

    @Test
    fun runtimeErrorRemovesVm() {
        val scheduler = Scheduler<String>(100)
        val machine = vm("println(1)\nval x = 1 / 0\nprintln(2)")
        scheduler.register("a", machine)
        scheduler.tickAll()
        assertEquals(mapOf("a" to VmState.ERROR), scheduler.tickAll().finished)
        assertEquals(emptySet<String>(), scheduler.registeredKeys)
        assertEquals(emptyMap<String, Int>(), scheduler.tickAll().executed)
        assertEquals(listOf("1"), machine.console.history)
    }

    @Test
    fun registerAndUnregister() {
        val scheduler = Scheduler<String>(100)
        val first = longVm()
        scheduler.register("a", first)
        scheduler.tickAll()
        scheduler.tickAll()
        // 解除は次の tick の Phase 0 で反映され、その tick から実行されない
        scheduler.unregister("a")
        assertEquals(emptyMap<String, Int>(), scheduler.tickAll().executed)
        assertEquals(emptySet<String>(), scheduler.registeredKeys)
        // 同じキーへの再登録は置き換え
        val second = longVm()
        scheduler.register("a", first)
        scheduler.register("a", second)
        scheduler.tickAll()
        scheduler.tickAll()
        assertTrue(second.console.history.isNotEmpty())
        // 同じ VM を別のキーで登録することはできない
        assertThrows(IllegalArgumentException::class.java) { scheduler.register("b", second) }
    }

    @Test
    fun noDuplicateExecutionInOneTick() {
        val scheduler = Scheduler<String>(1000)
        scheduler.register("a", longVm())
        scheduler.tickAll()
        // 予算が余っていても、1台が1tickに実行するのは動力の段階の上限まで
        assertEquals(mapOf("a" to PowerLevel.FAST.instructionsPerTick), scheduler.tickAll().executed)
        // conditionOf は登録済みの VM ごとに1回だけ呼ばれる
        var calls = 0
        scheduler.tick { calls++; fast }
        assertEquals(1, calls)
        // tick の中から tick を呼ぶことはできない
        assertThrows(IllegalStateException::class.java) { scheduler.tick { scheduler.tickAll(); fast } }
    }

    @Test
    fun budgetMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { Scheduler<String>(0) }
    }
}
