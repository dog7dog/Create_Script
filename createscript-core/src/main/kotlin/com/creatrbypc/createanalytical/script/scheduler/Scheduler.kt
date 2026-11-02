package com.creatrbypc.createanalytical.script.scheduler

import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.VmState

// 動力の段階と、1台あたりの1tickの実行命令数（SLOW 1 / MEDIUM 4 / FAST 128、NONE は一時停止。FAST は第3.10a-P段階で 16 から変更）。
// Create の SpeedLevel（応力超過時は NONE）からの変換は Integration Adapter が行う
enum class PowerLevel(val instructionsPerTick: Int) {
    NONE(0),
    SLOW(1),
    MEDIUM(4),
    FAST(128),
}

// Phase 1 で Integration Adapter から受け取る、その tick の実行条件（VM の状態とは別に管理する）
data class ExecutionCondition(val power: PowerLevel, val loaded: Boolean)

// tick の結果。executed は実行した命令数、finished はこの tick で Scheduler から外した VM とその最終状態（登録順）。
// collected は回収（GC）に使った作業の単位数、gcBudgetUsed はその合計（命令の予算とは別枠: 第3.9b段階の GC 対策）
data class TickResult<K>(
    val executed: Map<K, Int>,
    val finished: Map<K, VmState>,
    val budgetUsed: Int,
    val collected: Map<K, Int> = emptyMap(),
    val gcBudgetUsed: Int = 0,
)

// 複数の VM を、サーバー全体で共有する予算の範囲でラウンドロビン方式で実行する。Minecraft に依存しない純粋な Kotlin。
// K は VM を識別するキー（Integration 側ではディメンションと座標）。
// 回収中の VM の回収（GC）は、命令とは別枠の、サーバー全体で共有する回収の予算（gcBudgetPerTick）と 1 台あたりの上限（gcBudgetPerVm）で進める
class Scheduler<K : Any>(
    private val globalBudgetPerTick: Int,
    private val gcBudgetPerTick: Int = GC_BUDGET_PER_TICK,
    private val gcBudgetPerVm: Int = GC_BUDGET_PER_VM,
) {
    private class Entry(val vm: CsVm) {
        // Phase 0 で登録した tick は Phase 2・3 の対象にしない（A5・A24）
        var registeredThisTick = true
        var condition = ExecutionCondition(PowerLevel.NONE, false)
    }

    private sealed interface Change<K> {
        data class Register<K>(val key: K, val vm: CsVm) : Change<K>
        data class Unregister<K>(val key: K) : Change<K>
    }

    // 登録順を実行順の基準にする（決定的な順序）
    private val entries = LinkedHashMap<K, Entry>()
    private val pendingChanges = ArrayList<Change<K>>()

    // 次の tick で最初に実行機会を得る位置。前の tick で最後に実行した VM の次から始め、特定の VM の独占を防ぐ
    private var cursor = 0

    // 回収の巡回の開始位置（命令の巡回とは別に回す）
    private var gcCursor = 0
    private var ticking = false

    init {
        require(globalBudgetPerTick > 0) { "globalBudgetPerTick must be positive: $globalBudgetPerTick" }
        require(gcBudgetPerTick > 0 && gcBudgetPerVm > 0) { "GC budgets must be positive: $gcBudgetPerTick, $gcBudgetPerVm" }
    }

    val registeredKeys: Set<K> get() = entries.keys.toSet()

    // 登録・解除は次の tick の Phase 0 で反映する。同じ VM を別のキーで二重に登録することはできない（同一 tick の二重実行を防ぐ）
    fun register(key: K, vm: CsVm) {
        val registeredElsewhere = entries.any { (otherKey, entry) -> otherKey != key && entry.vm === vm } ||
            pendingChanges.any { it is Change.Register && it.key != key && it.vm === vm }
        require(!registeredElsewhere) { "VM is already registered under another key" }
        pendingChanges.add(Change.Register(key, vm))
    }

    fun unregister(key: K) {
        pendingChanges.add(Change.Unregister(key))
    }

    // サーバーの1tickにつき1回呼ぶ。conditionOf は Phase 1 で登録済みの各 VM について1回ずつ呼ばれる
    fun tick(conditionOf: (K) -> ExecutionCondition): TickResult<K> {
        check(!ticking) { "Scheduler.tick must not be called re-entrantly" }
        ticking = true
        try {
            applyChanges()
            takeConditionSnapshot(conditionOf)
            countWaitTicks()
            val runnable = selectRunnable()
            val executed = runRoundRobin(runnable)
            val collected = collectRoundRobin()
            val finished = removeFinished()
            return TickResult(executed, finished, executed.values.sum(), collected, collected.values.sum())
        } finally {
            ticking = false
        }
    }

    // Phase 0: 登録・解除。同じキーの再登録は前の VM を置き換える
    private fun applyChanges() {
        entries.values.forEach { it.registeredThisTick = false }
        for (change in pendingChanges) {
            when (change) {
                is Change.Register -> {
                    entries.remove(change.key)
                    entries[change.key] = Entry(change.vm)
                }
                is Change.Unregister -> entries.remove(change.key)
            }
        }
        pendingChanges.clear()
    }

    // Phase 1: 動力とチャンクの状態のスナップショット。この tick の間は変わらない
    private fun takeConditionSnapshot(conditionOf: (K) -> ExecutionCondition) {
        for ((key, entry) in entries) entry.condition = conditionOf(key)
    }

    // Phase 2: 待機カウント。動力があり読み込まれている VM だけ進める（D6）
    private fun countWaitTicks() {
        for (entry in entries.values) {
            if (entry.isEligible() && entry.vm.state == VmState.WAITING) entry.vm.countWaitTick()
        }
    }

    // Phase 2.5: この tick に実行する VM を確定する。Phase 3 の途中で実行可能になった VM は次の tick まで実行しない
    private fun selectRunnable(): List<Pair<K, Entry>> {
        val ordered = entries.entries.map { it.key to it.value }
        if (ordered.isEmpty()) return emptyList()
        val start = cursor % ordered.size
        return (ordered.subList(start, ordered.size) + ordered.subList(0, start))
            .filter { (_, entry) -> entry.isEligible() && entry.vm.state == VmState.RUNNING }
    }

    // Phase 3: 1回の訪問で1命令ずつ、各 VM の上限（動力の段階）と全体の予算の範囲で巡回する
    private fun runRoundRobin(runnable: List<Pair<K, Entry>>): Map<K, Int> {
        val executed = LinkedHashMap<K, Int>()
        val active = runnable.toMutableList()
        var budget = globalBudgetPerTick
        var lastServed: K? = null
        while (budget > 0 && active.isNotEmpty()) {
            val iterator = active.iterator()
            while (iterator.hasNext() && budget > 0) {
                val (key, entry) = iterator.next()
                val count = entry.vm.run(1)
                budget -= count
                val total = executed.getOrDefault(key, 0) + count
                executed[key] = total
                if (count > 0) lastServed = key
                // 待機・終了・エラーになった VM と、この tick の上限に達した VM は、この tick ではもう実行しない
                if (count == 0 || entry.vm.state != VmState.RUNNING || total >= entry.condition.power.instructionsPerTick) iterator.remove()
            }
        }
        if (lastServed != null) cursor = entries.keys.indexOf(lastServed) + 1
        return executed
    }

    // Phase 3.5: 回収中の VM（この tick の Phase 3 で回収を始めたものを含む）の回収を、1回の訪問で最大 GC_CHUNK 単位ずつ、
    // 各 VM の上限（gcBudgetPerVm）と全体の回収の予算の範囲で巡回して進める。動力が無い・読み込まれていない VM は進めない（再開を待つ）。
    // 開始位置は前の tick で最後に回収を進めた VM の次からにし、多数の VM が回収を待つときも順に進む（特定の VM の独占・待ち続けを防ぐ）
    private fun collectRoundRobin(): Map<K, Int> {
        val ordered = entries.entries.map { it.key to it.value }
        if (ordered.isEmpty()) return emptyMap()
        val start = gcCursor % ordered.size
        val active = (ordered.subList(start, ordered.size) + ordered.subList(0, start))
            .filter { (_, entry) -> entry.isEligible() && entry.vm.isCollecting }
            .toMutableList()
        val used = LinkedHashMap<K, Int>()
        var budget = gcBudgetPerTick
        var lastServed: K? = null
        while (budget > 0 && active.isNotEmpty()) {
            val iterator = active.iterator()
            while (iterator.hasNext() && budget > 0) {
                val (key, entry) = iterator.next()
                val total = used.getOrDefault(key, 0)
                val count = entry.vm.collectGarbage(minOf(GC_CHUNK, budget, gcBudgetPerVm - total))
                budget -= count
                used[key] = total + count
                if (count > 0) lastServed = key
                if (count == 0 || !entry.vm.isCollecting || total + count >= gcBudgetPerVm) iterator.remove()
            }
        }
        if (lastServed != null) gcCursor = entries.keys.indexOf(lastServed) + 1
        return used
    }

    // Phase 4: 終了・エラーの VM を Scheduler から外す（自動では再実行しない）
    private fun removeFinished(): Map<K, VmState> {
        val finished = LinkedHashMap<K, VmState>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            val state = entry.vm.state
            if (state == VmState.HALTED || state == VmState.ERROR) {
                finished[key] = state
                iterator.remove()
            }
        }
        return finished
    }

    private fun Entry.isEligible() = !registeredThisTick && condition.loaded && condition.power != PowerLevel.NONE

    companion object {
        // 回収の予算（作業の単位。第3.9b段階の GC 対策の暫定値）。1 単位はおよそ 5 ns（開発機の計測）で、全体 16384 単位は 1 tick あたり約 0.1 ms。
        // 1 台あたり 4096 単位は、最大の回収（根 1152 + 取り出し 256 × 2 + プロパティ 4096 + リストの要素 4096 + SWEEP 257 + 1 = 10114 単位: 第3.10a段階）を、
        // その VM だけなら 3 tick で終える量。
        // 1回の訪問で進める量（GC_CHUNK）は、多数の VM が回収を待つときに順番を細かく回すための単位
        const val GC_BUDGET_PER_TICK = 16_384
        const val GC_BUDGET_PER_VM = 4_096
        const val GC_CHUNK = 256
    }
}
