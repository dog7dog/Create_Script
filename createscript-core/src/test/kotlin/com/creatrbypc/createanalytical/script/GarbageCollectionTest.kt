package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第3.9b段階の GC 対策: 分割できる回収（回収中は VM の命令を止める）・命令とは別枠の回収の予算・NEW のやり直し・保存と復元を確かめる
class GarbageCollectionTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    private val fast = ExecutionCondition(PowerLevel.FAST, true)

    // keep と t を参照したまま 300 個作り直す（256 個で満杯になり回収が始まる）
    private val churn = "class Robot\nval keep = Robot()\nvar t = Robot()\nfor (i in 0 until 300) { t = Robot() }\nprintln(t)\nprintln(keep)"

    // Scheduler（回収の予算は全体 gcBudget・1 台 perVm）で最後まで実行する。回収が続いた tick 数の最大と、回収を始めた回数も返す
    private class Scheduled(val vm: CsVm, val longestCollection: Int, val collections: Int, val ticks: Int)

    private fun scheduled(source: String, gcBudget: Int = 97, perVm: Int = 13): Scheduled {
        val vm = CsVm(compile(source))
        val scheduler = Scheduler<Int>(4096, gcBudget, perVm)
        scheduler.register(0, vm)
        var ticks = 0
        var streak = 0
        var longest = 0
        var collections = 0
        var wasCollecting = false
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096 && result.gcBudgetUsed <= gcBudget && (result.collected[0] ?: 0) <= perVm)
            if (vm.isCollecting && !wasCollecting) collections++
            wasCollecting = vm.isCollecting
            streak = if ((result.collected[0] ?: 0) > 0) streak + 1 else 0
            longest = maxOf(longest, streak)
            check(++ticks < 1_000_000) { "did not finish: $source" }
        }
        return Scheduled(vm, longest, collections, ticks)
    }

    private fun output(source: String): List<String> {
        val result = scheduled(source)
        assertEquals(VmState.HALTED, result.vm.state, "${result.vm.error} in $source")
        return result.vm.console.history
    }

    // ---- 1・2・4・21. 満杯になるまでは回収しない。満杯の NEW は実行せずに回収を始め、回収の後に 1 回だけやり直す ----

    @Test
    fun newWaitsForCollectionAndRetriesOnce() {
        // 256 個未満なら回収は始まらない
        val small = scheduled("class Robot\nvar t = Robot()\nfor (i in 0 until 250) { t = Robot() }\nprintln(t)")
        assertEquals(0, small.collections)
        assertEquals(listOf("Robot@251"), small.vm.console.history)
        val program = compile(churn)
        val vm = CsVm(program)
        var before: VmSnapshot
        while (true) {
            before = vm.snapshot()!!
            val executed = vm.run(1)
            if (vm.isCollecting) {
                assertEquals(0, executed)
                break
            }
        }
        // 回収を始めた NEW は実行していない: PC（NEW のまま）・スタック・ヒープ・次の id は NEW の直前と同じ
        val during = vm.snapshot()!!
        assertEquals(before, during)
        assertEquals(Opcode.NEW, program.instructions[during.pc].opcode)
        assertEquals(VmLimits.MAX_OBJECTS, during.objects.size)
        assertEquals(257, during.nextObjectId)
        // 回収中は命令を実行しない（予算を渡しても 0）
        assertEquals(0, vm.run(100))
        assertEquals(during, vm.snapshot())
        // 回収で keep（1）と t（256）だけが残り、次の run で NEW を 1 回だけ実行する（id 257 を 1 回だけ発行する）
        assertTrue(vm.collectGarbage(Int.MAX_VALUE) > 0)
        assertFalse(vm.isCollecting)
        assertEquals(0, vm.collectGarbage(100))
        assertEquals(listOf(1, 256), vm.snapshot()!!.objects.map { it.id })
        assertEquals(1, vm.run(1))
        val after = vm.snapshot()!!
        assertEquals(listOf(1, 256, 257), after.objects.map { it.id })
        assertEquals(258, after.nextObjectId)
        assertEquals(Value.ObjectRef(257), after.stack.last())
        assertEquals(during.pc + 1, after.pc)
        while (vm.state == VmState.RUNNING) vm.advance(1000)
        assertEquals(listOf("Robot@302", "Robot@1"), vm.console.history)
    }

    // ---- 3・22. 回収は予算の範囲で複数の tick に分かれ、予算を使い切ったら次の tick に続く ----

    @Test
    fun collectionIsSplitAcrossTicks() {
        val program = compile(churn)
        val full = CsVm(program).also { while (!it.isCollecting) it.run(1) }.snapshot()!!
        val oneShot = CsVm.restore(program, full)!!.also { it.run(1) }.collectGarbage(Int.MAX_VALUE)
        val vm = CsVm.restore(program, full)!!
        val scheduler = Scheduler<Int>(4096, 1000, 50)
        scheduler.register(0, vm)
        scheduler.tick { fast }
        var total = 0
        var gcTicks = 0
        while (total == 0 || vm.isCollecting) {
            val result = scheduler.tick { fast }
            val units = result.collected[0] ?: 0
            assertTrue(units <= 50, "$units")
            // 回収中の tick は命令を実行しない
            assertEquals(0, result.executed[0] ?: 0)
            total += units
            gcTicks++
        }
        assertEquals(oneShot, total)
        assertEquals((oneShot + 49) / 50, gcTicks)
        // 次の tick から NEW をやり直して続ける
        assertTrue((scheduler.tick { fast }.executed[0] ?: 0) > 0)
    }

    // ---- 5〜9. 回収を分けても、到達できるオブジェクトは残り、到達できないもの（循環を含む）は回収される ----

    @Test
    fun reachableObjectsSurviveSplitCollections() {
        assertEquals(listOf("Robot@302", "Robot@1"), output(churn))
        // 大域変数からは先頭しか参照しない 101 個の連結（プロパティを通した参照）
        val chain = "class Node {\n  var next: Node = this\n  var value = 0\n}\nvar head = Node()\nfor (i in 1..100) {\n  val n = Node()\n  n.value = i\n  n.next = head\n  head = n\n}\n" +
            "for (i in 0 until 600) {\n  val garbage = Node()\n}\nvar sum = 0\nvar cur = head\nfor (i in 0 until 101) {\n  sum = sum + cur.value\n  cur = cur.next\n}\nprintln(sum)\nprintln(cur == cur.next)"
        assertEquals(listOf("5050", "true"), output(chain))
        // 互いに参照し合う 2 つ組を捨て続ける
        assertEquals(listOf("true", "Node@602"), output("class Node { var next: Node = this }\nval keep = Node()\nfor (i in 0 until 300) {\n  val a = Node()\n  val b = Node()\n  a.next = b\n  b.next = a\n}\nprintln(keep == keep.next)\nprintln(Node())"))
        // 再帰するメソッドの各フレームのローカル変数（mine・引数 r・this）が参照するオブジェクトは、深いフレームで回収が走っても残る
        val recursion = "class Robot { var tag = 1 }\nclass Keeper {\n  fun hold(n: Int, r: Robot): Int {\n    val mine = Robot()\n    for (i in 0 until 40) { val junk = Robot() }\n" +
            "    var below = 0\n    if (n > 0) { below = hold(n - 1, r) }\n    return below + mine.tag\n  }\n}\nval r = Robot()\nprintln(Keeper().hold(10, r) + r.tag)\nprintln(r)"
        assertEquals(listOf("12", "Robot@1"), output(recursion))
        // 初期化中のオブジェクト（コンストラクタのフレームの this）と、初期化を終えたプロパティが参照するオブジェクトも残る
        assertEquals(listOf("3", "false"), output(boxes(100)))
    }

    // Box の初期化式が Robot を 3 つ作る。満杯になると Box の初期化の途中で回収が走る
    private fun boxes(count: Int) = "class Robot { var tag = 1 }\nclass Box {\n  val a = Robot()\n  val b = Robot()\n  val c = Robot()\n  val total = a.tag + b.tag + c.tag\n}\n" +
        "var keep = Box()\nfor (i in 0 until $count) { keep = Box() }\nprintln(keep.total)\nprintln(keep.a == keep.b)"

    // ---- 18. 回収できるオブジェクトが無ければ、回収を 1 回だけ行って CS_RUN_TOO_MANY_OBJECTS で止まる ----

    @Test
    fun nothingToCollectStopsAfterOneCollection() {
        val locals = (0 until 20).joinToString("") { "  val r$it = Robot()\n" }
        val result = scheduled("class Robot\nfun hoard(n: Int): Int {\n$locals  if (n == 0) { return 0 }\n  return hoard(n - 1)\n}\nprintln(hoard(12))")
        assertEquals(VmState.ERROR, result.vm.state)
        assertEquals(DiagnosticCode.RUN_TOO_MANY_OBJECTS, result.vm.error?.code)
        assertEquals(1, result.collections)
        assertTrue(result.longestCollection > 1)
    }

    // ---- 11・12. 回収の各単位（MARK の途中・SWEEP の途中）を含むすべての境界で保存・復元しても、結果は中断しない実行と同じ ----

    // Scheduler と同じ進め方の1歩（待機・回収の1単位・1命令）
    private fun step(vm: CsVm) {
        when {
            vm.state == VmState.WAITING -> vm.countWaitTick()
            vm.isCollecting -> vm.collectGarbage(1)
            else -> vm.run(1)
        }
    }

    private fun finish(vm: CsVm): List<String> {
        var steps = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            step(vm)
            check(++steps < 100_000) { "did not finish" }
        }
        assertEquals(VmState.HALTED, vm.state, "${vm.error}")
        return vm.console.history
    }

    @Test
    fun everyCollectionStepRestores() {
        val sources = listOf(boxes(70), churn.replace("300", "270"))
        for (source in sources) {
            val program = compile(source)
            val expected = finish(CsVm(program))
            val original = CsVm(program)
            var marking = 0
            var sweeping = 0
            while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
                val snapshot = original.snapshot()!!
                // 回収中の保存: ヒープがまだ満杯なら MARK（または SWEEP の始め）、減っていれば SWEEP の途中
                if (original.isCollecting) if (snapshot.objects.size == VmLimits.MAX_OBJECTS) marking++ else sweeping++
                assertNull(SnapshotVerifier.verify(program, snapshot), source)
                val restored = CsVm.restore(program, snapshot)
                assertNotNull(restored)
                // 回収の状態は保存しない: 復元した VM は NEW の直前から（もう一度回収してから）続ける
                assertFalse(restored!!.isCollecting)
                assertEquals(snapshot, restored.snapshot())
                assertEquals(expected, finish(restored), source)
                step(original)
            }
            assertTrue(marking > 0 && sweeping > 0, "marking $marking, sweeping $sweeping")
        }
    }

    // ---- 10・23・24. 複数の VM が同時に回収を待っても、予算・公平性を守り、すべての VM が進む ----

    // 254 個（大域変数 2 + 6 段 × 42）を参照したまま作り直す。満杯の状態まで進めた 1 台を保存し、各 VM はそこから復元する
    private val held = (0 until 42).joinToString("") { "  val r$it = Robot()\n" }
    private val worst = compile("class Robot\nval g0 = Robot()\nval g1 = Robot()\nfun hold(n: Int) {\n$held  if (n > 0) { hold(n - 1) }\n  var t = Robot()\n  while (true) { t = Robot() }\n}\nhold(5)")

    private fun steadyState(program: CompiledProgram): VmSnapshot = CsVm(program).also { while (!it.isCollecting) it.run(1000) }.snapshot()!!

    @Test
    fun manyCollectingVmsShareTheBudgetFairly() {
        val steady = steadyState(worst)
        val vms = List(300) { CsVm.restore(worst, steady)!! }
        val scheduler = Scheduler<Int>(4096)
        vms.forEachIndexed { index, vm -> scheduler.register(index, vm) }
        scheduler.tick { fast }
        repeat(300) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.gcBudgetUsed <= Scheduler.GC_BUDGET_PER_TICK, "${result.gcBudgetUsed}")
            assertTrue(result.collected.values.all { it <= Scheduler.GC_BUDGET_PER_VM })
            assertTrue(result.finished.isEmpty())
        }
        // どの VM も生成を進め（id が増え）、進み方の差は小さい
        val created = vms.map { it.snapshot()!!.nextObjectId - steady.nextObjectId }
        assertTrue(created.min() > 0 && created.max() - created.min() <= 2, "created ${created.min()}..${created.max()}")
    }

    @Test
    fun collectionNeverStallsForever() {
        // 全体の回収の予算が小さくても、300 台すべてが順に回収を終える（巡回の開始位置が回るため、待ち続ける VM は無い）
        val steady = steadyState(worst)
        val vms = List(300) { CsVm.restore(worst, steady)!! }
        val perGc = CsVm.restore(worst, steady)!!.also { it.run(1) }.collectGarbage(Int.MAX_VALUE)
        val scheduler = Scheduler<Int>(4096, 512, 4096)
        vms.forEachIndexed { index, vm -> scheduler.register(index, vm) }
        var ticks = 0
        while (vms.any { it.snapshot()!!.nextObjectId == steady.nextObjectId }) {
            scheduler.tick { fast }
            check(++ticks <= 2 * (300 * perGc / 512 + 300)) { "stalled after $ticks ticks" }
        }
        // 動力が無い・読み込まれていない間は回収も止まり（予算を使わない）、戻れば続きから終える
        val paused = CsVm.restore(worst, steady)!!.also { it.run(1) }
        val stopped = Scheduler<Int>(4096, 16_384, 100)
        stopped.register(0, paused)
        stopped.tick { fast }
        stopped.tick { fast }
        assertTrue(paused.isCollecting)
        for (condition in listOf(ExecutionCondition(PowerLevel.NONE, true), ExecutionCondition(PowerLevel.FAST, false))) {
            repeat(10) { assertEquals(0, stopped.tick { condition }.gcBudgetUsed) }
            assertTrue(paused.isCollecting)
        }
        var resumed = 0
        while (paused.isCollecting) {
            stopped.tick { fast }
            check(++resumed < 1000)
        }
        // STOP・カードの交換（登録を外して別の VM を登録する）: 回収中の VM は捨てられ、新しい VM は回収の状態を持たない
        stopped.unregister(0)
        val replacement = CsVm(compile("println(1)"))
        stopped.register(0, replacement)
        stopped.tick { fast }
        stopped.tick { fast }
        assertEquals(VmState.HALTED, replacement.state)
        assertFalse(replacement.isCollecting)
    }

    // ---- 25・26. 性能（参考値） ----

    private class TickStats(val average: Double, val p50: Double, val p95: Double, val p99: Double, val max: Double) {
        override fun toString() = "avg %.3f / p50 %.3f / p95 %.3f / p99 %.3f / max %.3f ms".format(average, p50, p95, p99, max)
    }

    private fun measure(scheduler: Scheduler<Int>, ticks: Int, onTick: (Int, Int) -> Unit = { _, _ -> }): TickStats {
        repeat(300) { scheduler.tick { fast } }
        val times = DoubleArray(ticks)
        for (index in 0 until ticks) {
            val start = System.nanoTime()
            val result = scheduler.tick { fast }
            times[index] = (System.nanoTime() - start) / 1_000_000.0
            onTick(result.gcBudgetUsed, result.budgetUsed)
        }
        val sorted = times.sorted()
        return TickStats(times.average(), sorted[ticks / 2], sorted[ticks * 95 / 100], sorted[ticks * 99 / 100], sorted.last())
    }

    @Test
    fun measureCollection() {
        // 最悪の形: 16 個のオブジェクトの参照のプロパティを持つ 254 個を連結したまま、プロパティの無い Leaf を作り直す（生成のたびに満杯）
        val fields = (0 until 16).joinToString("\n") { "  var p$it: Node = this" }
        val links = (0 until 16).joinToString("\n") { "  n.p$it = head" }
        val linked = compile("class Node {\n$fields\n}\nclass Leaf\nvar head = Node()\nfor (i in 1 until 254) {\n  val n = Node()\n$links\n  head = n\n}\nvar t = Leaf()\nwhile (true) { t = Leaf() }")
        val steady = steadyState(linked)
        val perGc = CsVm.restore(linked, steady)!!.also { it.run(1) }.collectGarbage(Int.MAX_VALUE)
        // 1 単位あたりの時間（回収だけを 2000 回）
        val samples = List(2500) { CsVm.restore(linked, steady)!!.also { it.run(1) } }
        samples.take(500).forEach { it.collectGarbage(Int.MAX_VALUE) }
        val unitStart = System.nanoTime()
        samples.drop(500).forEach { it.collectGarbage(Int.MAX_VALUE) }
        val nsPerUnit = (System.nanoTime() - unitStart).toDouble() / (2000L * perGc)
        println("[3.9b gc measure] one worst-case collection: $perGc units, %.2f ns per unit".format(nsPerUnit))
        for ((label, scheduler) in listOf(
            "default budgets (16384 / tick, 4096 / VM)" to Scheduler<Int>(4096),
            "no GC budget (whole collection at once, like the previous implementation)" to Scheduler<Int>(4096, Int.MAX_VALUE, Int.MAX_VALUE),
        )) {
            repeat(256) { scheduler.register(it, CsVm.restore(linked, steady)!!) }
            var maxGc = 0
            var instructions = 0L
            val stats = measure(scheduler, 1000) { gc, executed ->
                maxGc = maxOf(maxGc, gc)
                instructions += executed
            }
            println("[3.9b gc measure] worst case, 256 VMs, $label: $stats, max GC work $maxGc units per tick, ${instructions / 1000} instructions per tick")
        }
        // 1 台だけのときと、256 台が同時に回収するときの、回収を始めてから終えるまでの tick 数
        fun collectionLengths(count: Int): List<Int> {
            val vms = List(count) { CsVm.restore(linked, steady)!! }
            val scheduler = Scheduler<Int>(4096)
            vms.forEachIndexed { index, vm -> scheduler.register(index, vm) }
            val started = IntArray(count) { -1 }
            val lengths = ArrayList<Int>()
            for (tick in 0 until 600) {
                scheduler.tick { fast }
                vms.forEachIndexed { index, vm ->
                    if (vm.isCollecting && started[index] < 0) started[index] = tick
                    if (!vm.isCollecting && started[index] >= 0) {
                        lengths.add(tick - started[index] + 1)
                        started[index] = -1
                    }
                }
            }
            return lengths.drop(count)
        }
        val alone = collectionLengths(1)
        val crowded = collectionLengths(256).sorted()
        println("[3.9b gc measure] ticks to finish a collection: alone ${alone.max()}, 256 VMs at once median ${crowded[crowded.size / 2]} / max ${crowded.last()}")
        // 通常の実行（1000 台）
        val methods = compile("class Counter {\n  var count = 0\n  fun increment(): Int {\n    count = count + 1\n    return count\n  }\n}\nval c = Counter()\nwhile (true) {\n  c.increment()\n  if (c.count > 1000) { c.count = 0 }\n}")
        val construction = compile("class Node {\n  var next: Node = this\n  var value = 0\n}\nvar n = Node()\nwhile (true) {\n  val m = Node()\n  m.next = n\n  n = Node()\n}")
        for ((label, program) in listOf("1000 VMs calling methods" to methods, "1000 VMs constructing objects" to construction)) {
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program)) }
            println("[3.9b gc measure] $label: ${measure(scheduler, 1000)}")
        }
        // 回収を待っている状態（ヒープ満杯・プロパティ 16 個 × 254 個）のスナップショットの検証・復元
        repeat(200) { assertNotNull(CsVm.restore(linked, steady)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(linked, steady) }
        println("[3.9b gc measure] verify + restore while waiting for a collection: %.3f ms per VM".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000))
    }

    // 第3.10b段階で見つけた不具合の回帰: ゴミがゴミを参照している（捨てた a → b の組）とき、SWEEP の途中で保存した状態にも
    // 消したゴミへの参照が残らず、どの単位の境界からも復元できて同じ結果になる（以前は SWEEP の途中の状態が INVALID_OBJECTS で拒否された）
    @Test
    fun snapshotsDuringSweepRestore() {
        val program = compile("class Node { var next: Node = this }\nval keep = Node()\nfor (i in 0 until 200) {\n  val a = Node()\n  val b = Node()\n  b.next = a\n  a.next = b\n}\nprintln(keep.next == keep)")
        val vm = CsVm(program)
        var sweepCuts = 0
        while (vm.state == VmState.RUNNING) {
            val snapshot = vm.snapshot()!!
            if (vm.isCollecting) {
                assertNull(SnapshotVerifier.verify(program, snapshot), "pc ${snapshot.pc}")
                if (snapshot.objects.size < 256) sweepCuts++
                val restored = CsVm.restore(program, snapshot)!!
                while (restored.state == VmState.RUNNING) restored.advance(1000)
                assertEquals(listOf("true"), restored.console.history)
                vm.collectGarbage(1)
            } else {
                vm.run(1)
            }
        }
        assertEquals(listOf("true"), vm.console.history)
        assertTrue(sweepCuts > 0)
    }
}
