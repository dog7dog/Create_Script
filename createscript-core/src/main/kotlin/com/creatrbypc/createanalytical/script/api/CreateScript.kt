package com.creatrbypc.createanalytical.script.api

import com.creatrbypc.createanalytical.script.compiler.CompileResult
import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState

// Minecraft 以外のアプリケーション（CLI など）から CreateScript を使うための入口（第3.12段階 S4）。
// コンパイルは Compiler.compileSource と同じ。実行は 1 台の VM を tick 単位で進める ScriptRun を返す
object CreateScript {
    fun compile(source: String): CompileResult = Compiler.compileSource(source)

    // onPrint は println の出力を1行ずつ受け取る（履歴の 32 行を超えた分も含む）。tick の中の VM の処理が終わった後に呼ぶ
    fun start(program: CompiledProgram, options: RunOptions, onPrint: ((String) -> Unit)? = null): ScriptRun =
        ScriptRun(CsVm(program, options.randomSeed), options, onPrint)

    // 実行の途中の状態（ScriptRun.snapshot）から続きを実行する（S5.1）。保存・復元と同じく SnapshotVerifier で検証し、通らなければ null。
    // 乱数の状態はスナップショットのものを使う（options.randomSeed は使わない）。onPrint にはこの後に出力した行だけを通知する
    fun resume(program: CompiledProgram, snapshot: VmSnapshot, options: RunOptions, onPrint: ((String) -> Unit)? = null): ScriptRun? =
        CsVm.restore(program, snapshot)?.let { ScriptRun(it, options, onPrint) }
}

// 実行の設定。Minecraft の回転速度（PowerLevel）とは別に、1 tick の命令数と回収（GC）の作業量を直接指定する。
// randomSeed は乱数の最初の状態（Minecraft ではホストが渡す値。プログラムの randomSeed(…) で置き換えられる）
data class RunOptions(
    val instructionsPerTick: Int,
    val randomSeed: Long,
    val gcBudgetPerTick: Int = Scheduler.GC_BUDGET_PER_VM,
) {
    init {
        require(instructionsPerTick > 0) { "instructionsPerTick must be positive: $instructionsPerTick" }
        require(gcBudgetPerTick > 0) { "gcBudgetPerTick must be positive: $gcBudgetPerTick" }
    }
}

// 1 tick の結果。executed は実行した命令数、collected は回収に使った作業の単位数
data class TickReport(val executed: Int, val collected: Int)

// 1 台の VM を tick 単位で進める。1 tick の手順は Scheduler の 1 台分と同じ（待機のカウント → 命令 → 回収）で、
// 命令は instructionsPerTick まで、回収は gcBudgetPerTick まで進める。VM そのものは外へ渡さない
class ScriptRun internal constructor(
    private val vm: CsVm,
    private val options: RunOptions,
    private val onPrint: ((String) -> Unit)?,
) {
    private var ticking = false

    init {
        if (onPrint != null) vm.console.startCapture()
    }

    val state: VmState get() = vm.state

    // ERROR になった原因（CS_RUN_*）
    val error: Diagnostic? get() = vm.error

    // コンソールの履歴（Minecraft の表示と同じ最大 32 行）
    val console: List<String> get() = vm.console.history

    val isFinished: Boolean get() = vm.state == VmState.HALTED || vm.state == VmState.ERROR

    // 実行の途中の状態の写し（RUNNING・WAITING 以外は null。VM の状態は変えない）。CreateScript.resume で続きを実行できる
    fun snapshot(): VmSnapshot? = vm.snapshot()

    // onPrint の中から tick を呼ぶことはできない（VM の処理が終わってから呼ぶため VM は壊れないが、1 tick の上限を守るため拒否する）。
    // onPrint が例外を投げると、その tick の残りの行は通知せずに例外をそのまま伝える（VM の状態はその tick の処理を終えたまま）
    fun tick(): TickReport {
        check(!ticking) { "ScriptRun.tick must not be called re-entrantly" }
        ticking = true
        try {
            vm.countWaitTick()
            val executed = vm.run(options.instructionsPerTick)
            val collected = vm.collectGarbage(options.gcBudgetPerTick)
            val lines = vm.console.drainCaptured()
            onPrint?.let { lines.forEach(it) }
            return TickReport(executed, collected)
        } finally {
            ticking = false
        }
    }
}
