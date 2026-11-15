package com.creatrbypc.createanalytical.script.api

import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.compiler.CompileResult
import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.module.ModuleAccess
import com.creatrbypc.createanalytical.script.module.ModuleCompileResult
import com.creatrbypc.createanalytical.script.module.ModuleCompiler
import com.creatrbypc.createanalytical.script.module.ModuleResolver
import com.creatrbypc.createanalytical.script.module.ScopedModuleResolver
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState

// Minecraft 以外のアプリケーション（CLI など）から CreateScript を使うための入口（第3.12段階 S4）。
// コンパイルは Compiler.compileSource と同じ。実行は 1 台の VM を tick 単位で進める ScriptRun を返す
public object CreateScript {
    public fun compile(source: String): CompileResult = Compiler.compileSource(source)

    // コンパイルの設定（第8.0.1段階）を指定する。CompileOptions.EMBEDDED は 4096 命令（compile(source) と同じ）、STANDALONE は命令数の上限なし
    public fun compile(source: String, options: CompileOptions): CompileResult = Compiler.compileSource(source, options)

    // import を含むプログラムのコンパイル（第8.0段階）。source はエントリーのファイル、resolver はホストがモジュールのソースを探す方法
    // （Core はファイルを読まない）。entryModule はエントリーのファイル自身のモジュール名（分かれば。その名前の import は循環として報告する）。
    // import の無いプログラムは compile(source, options) と同じ命令・診断になる。options の既定は compile(source) と同じ 4096 命令
    public fun compile(source: String, resolver: ModuleResolver, entryModule: String? = null, options: CompileOptions = CompileOptions.DEFAULT): ModuleCompileResult =
        ModuleCompiler.compile(source, resolver, entryModule, options)

    // import できる範囲を制御するコンパイル（S8.2 Phase 4）。access が許さない import は CS_MODULE_NOT_VISIBLE になる。それ以外は上の compile と同じ。
    // 上の compile は変えない（新しい関数として足す。既定の引数を持たせないので、既存の呼び出しがこちらに変わることは無い）
    public fun compile(source: String, resolver: ModuleResolver, entryModule: String?, options: CompileOptions, access: ModuleAccess): ModuleCompileResult =
        ModuleCompiler.compile(source, resolver, entryModule, options, access)

    // import 元ごとにモジュールを探すコンパイル（S8.2 の H-1）。モジュールを ScopedModuleLookup.Found の id で区別するので、別のパッケージの
    // 同じ名前の（公開しない）モジュールを 1 つのプログラムに入れられる。import の書き方は同じ。entryModule はエントリーのファイルのモジュールの ID。
    // ModuleCompileResult の modules・ModuleDiagnostic の module・sourceFingerprint はモジュールの ID を使う。
    // 既存の compile（ModuleResolver）と区別するため、別の名前にする（ラムダで resolver を渡す既存の呼び出しが曖昧にならない）
    public fun compileScoped(source: String, resolver: ScopedModuleResolver, entryModule: String?, options: CompileOptions): ModuleCompileResult =
        ModuleCompiler.compileScoped(source, resolver, entryModule, options)

    // onPrint は println の出力を1行ずつ受け取る（履歴の 32 行を超えた分も含む）。tick の中の VM の処理が終わった後に呼ぶ
    public fun start(program: CompiledProgram, options: RunOptions, onPrint: ((String) -> Unit)? = null): ScriptRun =
        ScriptRun(CsVm(program, options.randomSeed), options, onPrint)

    // 実行の途中の状態（ScriptRun.snapshot）から続きを実行する（S5.1）。保存・復元と同じく SnapshotVerifier で検証し、通らなければ null。
    // 乱数の状態はスナップショットのものを使う（options.randomSeed は使わない）。onPrint にはこの後に出力した行だけを通知する
    public fun resume(program: CompiledProgram, snapshot: VmSnapshot, options: RunOptions, onPrint: ((String) -> Unit)? = null): ScriptRun? =
        CsVm.restore(program, snapshot)?.let { ScriptRun(it, options, onPrint) }
}

// 実行の設定。Minecraft の回転速度（PowerLevel）とは別に、1 tick の命令数と回収（GC）の作業量を直接指定する。
// randomSeed は乱数の最初の状態（Minecraft ではホストが渡す値。プログラムの randomSeed(…) で置き換えられる）
public data class RunOptions(
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
public data class TickReport(val executed: Int, val collected: Int)

// 1 台の VM を tick 単位で進める。1 tick の手順は Scheduler の 1 台分と同じ（待機のカウント → 命令 → 回収）で、
// 命令は instructionsPerTick まで、回収は gcBudgetPerTick まで進める。VM そのものは外へ渡さない
public class ScriptRun internal constructor(
    private val vm: CsVm,
    private val options: RunOptions,
    private val onPrint: ((String) -> Unit)?,
) {
    private var ticking = false

    init {
        if (onPrint != null) vm.console.startCapture()
    }

    public val state: VmState get() = vm.state

    // ERROR になった原因（CS_RUN_*）
    public val error: Diagnostic? get() = vm.error

    // コンソールの履歴（Minecraft の表示と同じ最大 32 行）
    public val console: List<String> get() = vm.console.history

    public val isFinished: Boolean get() = vm.state == VmState.HALTED || vm.state == VmState.ERROR

    // 実行の途中の状態の写し（RUNNING・WAITING 以外は null。VM の状態は変えない）。CreateScript.resume で続きを実行できる
    public fun snapshot(): VmSnapshot? = vm.snapshot()

    // onPrint の中から tick を呼ぶことはできない（VM の処理が終わってから呼ぶため VM は壊れないが、1 tick の上限を守るため拒否する）。
    // onPrint が例外を投げると、その tick の残りの行は通知せずに例外をそのまま伝える（VM の状態はその tick の処理を終えたまま）
    public fun tick(): TickReport {
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
