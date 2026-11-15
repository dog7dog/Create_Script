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

/**
 * The entry point of the CreateScript Core for hosts: compile CreateScript source and run the program one tick at a time.
 *
 * ```kotlin
 * val result = CreateScript.compile("println(6 * 7)")
 * val program = result.program ?: error(result.diagnostics)
 * val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
 * while (!run.isFinished) run.tick()
 * ```
 *
 * Problems in the program (syntax, types, limits) are reported as diagnostics in the result, not thrown. Exceptions
 * that a host callback throws ([ModuleResolver], [ModuleAccess], `onPrint`) are passed on to the caller.
 *
 * Thread safety: the Core keeps no shared mutable state, so compilations and different [ScriptRun]s can run on
 * different threads at the same time. One [ScriptRun] must not be used by several threads at once.
 */
public object CreateScript {
    /**
     * Compiles a program without `import`, with [CompileOptions.DEFAULT] (at most 4096 instructions, as an embedded
     * host uses it). A program with `import` gets `CS_MODULE_IMPORT_UNAVAILABLE`; use the overload with a
     * [ModuleResolver] for those.
     */
    public fun compile(source: String): CompileResult = Compiler.compileSource(source)

    /**
     * Compiles a program without `import` with the given limits. [CompileOptions.EMBEDDED] is the same as
     * [compile]`(source)`; [CompileOptions.STANDALONE] has no instruction limit and larger input limits. The options
     * only decide which programs are accepted: an accepted program gets the same instructions with any options.
     */
    public fun compile(source: String, options: CompileOptions): CompileResult = Compiler.compileSource(source, options)

    /**
     * Compiles a program that can `import` modules. [source] is the entry file; [resolver] gives the source of each
     * module (the Core never reads files). [entryModule] is the module name of the entry file itself, if it has one,
     * so that importing it is reported as a cycle. A program without `import` gets the same instructions and
     * diagnostics as [compile]`(source, options)`. The default [options] keep the 4096-instruction limit.
     *
     * Diagnostics name the file they belong to
     * ([com.creatrbypc.createanalytical.script.module.ModuleDiagnostic.module]). Hosts that save runs compare
     * [ModuleCompileResult.sourceFingerprint] instead of the fingerprint of the entry file alone.
     */
    public fun compile(source: String, resolver: ModuleResolver, entryModule: String? = null, options: CompileOptions = CompileOptions.DEFAULT): ModuleCompileResult =
        ModuleCompiler.compile(source, resolver, entryModule, options)

    /**
     * Like the other `compile` with a [ModuleResolver], and [access] decides which file may import which module:
     * an `import` it does not allow gives `CS_MODULE_NOT_VISIBLE`. [access] is asked for every `import`
     * declaration, before the resolver.
     */
    public fun compile(source: String, resolver: ModuleResolver, entryModule: String?, options: CompileOptions, access: ModuleAccess): ModuleCompileResult =
        ModuleCompiler.compile(source, resolver, entryModule, options, access)

    /**
     * Compiles a program whose modules are looked up per importing file. [resolver] is asked with the id of the
     * importing module and the imported name, and names each module by an id ([com.creatrbypc.createanalytical.script.module.ScopedModuleLookup.Found.id]), so
     * two modules with the same name (for example private modules of different packages) can be in one program.
     * [entryModule] is the id of the entry file. [ModuleCompileResult.modules], [com.creatrbypc.createanalytical.script.module.ModuleDiagnostic.module] and
     * [ModuleCompileResult.sourceFingerprint] use these ids.
     */
    public fun compileScoped(source: String, resolver: ScopedModuleResolver, entryModule: String?, options: CompileOptions): ModuleCompileResult =
        ModuleCompiler.compileScoped(source, resolver, entryModule, options)

    /**
     * Starts a run of [program] from the beginning. Nothing runs until [ScriptRun.tick] is called.
     *
     * [onPrint] receives every line that `println` writes, in order, also lines that no longer fit in the console
     * history of [ScriptRun.console]. It is called at the end of [ScriptRun.tick], after the VM has finished the
     * tick. [RunOptions.randomSeed] is the first state of the random number generator.
     */
    public fun start(program: CompiledProgram, options: RunOptions, onPrint: ((String) -> Unit)? = null): ScriptRun =
        ScriptRun(CsVm(program, options.randomSeed), options, onPrint)

    /**
     * Continues a run from a saved state taken with [ScriptRun.snapshot]. Returns `null` when [snapshot] does not
     * fit [program] (its stack, variables, call frames, heap and console lines are checked against the program).
     *
     * - This check is about shape only: it does not tell which program the state came from. A host that saves runs
     *   must also keep and compare [com.creatrbypc.createanalytical.script.vm.ProgramFingerprint.ofProgram] and the source fingerprint
     *   ([com.creatrbypc.createanalytical.script.vm.ProgramFingerprint.ofSource], or [ModuleCompileResult.sourceFingerprint] for programs with `import`), and
     *   not resume when either differs.
     * - The random number state comes from [snapshot]; [RunOptions.randomSeed] of [options] is not used.
     * - The console history comes from [snapshot]; [onPrint] receives only the lines written after resuming.
     * - A saved state is always between two instructions. An instruction that takes several ticks (the vector and
     *   matrix operations, for example `vector(...)`, `dot` and `matmul`) is saved as not yet
     *   started, so after resuming it starts again from its first step; its operands are still on the stack, so the
     *   result is the same. A host that saves and resumes after every tick with a very small
     *   [RunOptions.instructionsPerTick] can therefore repeat such an instruction without end.
     * - A garbage collection in progress is not saved either: the resumed run starts the collection again.
     */
    public fun resume(program: CompiledProgram, snapshot: VmSnapshot, options: RunOptions, onPrint: ((String) -> Unit)? = null): ScriptRun? =
        CsVm.restore(program, snapshot)?.let { ScriptRun(it, options, onPrint) }
}

/**
 * How much a [ScriptRun] does per [ScriptRun.tick].
 *
 * @property instructionsPerTick the most instructions one tick runs; must be positive.
 * @property randomSeed the first state of the random number generator for [CreateScript.start] (the program can
 *   replace it with `randomSeed(...)`); [CreateScript.resume] ignores it.
 * @property gcBudgetPerTick the most garbage collection work units one tick does; must be positive. The default is
 *   4096, the budget of one program in the game host.
 * @throws IllegalArgumentException if a value is not positive.
 */
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

/**
 * What one [ScriptRun.tick] did.
 *
 * @property executed the number of instructions run.
 * @property collected the garbage collection work units used.
 */
public data class TickReport(val executed: Int, val collected: Int)

/**
 * One run of a program, advanced by [tick]. Made by [CreateScript.start] or [CreateScript.resume].
 *
 * Not thread-safe: use one run from one thread at a time.
 */
public class ScriptRun internal constructor(
    private val vm: CsVm,
    private val options: RunOptions,
    private val onPrint: ((String) -> Unit)?,
) {
    private var ticking = false

    init {
        if (onPrint != null) vm.console.startCapture()
    }

    /** The state of the run: running, waiting in `waitTicks`, finished, or stopped by a runtime error. */
    public val state: VmState get() = vm.state

    /**
     * The runtime error (`CS_RUN_...`) when [state] is [VmState.ERROR], otherwise `null`. For a program with
     * `import`, [ModuleCompileResult.locate] tells the file.
     */
    public val error: Diagnostic? get() = vm.error

    /**
     * The console history: the last 32 lines written by `println`, oldest first. A line longer than 64 UTF-16 code
     * units is cut and ends with `…`. A copy; it does not change later.
     */
    public val console: List<String> get() = vm.console.history

    /** `true` when the program has ended ([VmState.HALTED]) or stopped with an error ([VmState.ERROR]). */
    public val isFinished: Boolean get() = vm.state == VmState.HALTED || vm.state == VmState.ERROR

    /**
     * A copy of the state of the run between two instructions, to save and continue later with
     * [CreateScript.resume]. Returns `null` when the run is finished ([isFinished]). Does not change the run.
     *
     * Not saved: the progress of an instruction that takes several ticks (such as `vector(...)`) and of a garbage
     * collection; both start again after [CreateScript.resume] (see there).
     */
    public fun snapshot(): VmSnapshot? = vm.snapshot()

    /**
     * Runs one tick: counts down `waitTicks`, runs up to [RunOptions.instructionsPerTick] instructions, does up to
     * [RunOptions.gcBudgetPerTick] units of garbage collection, then passes the new lines to `onPrint`. Runs no
     * instructions when the run is finished.
     *
     * @throws IllegalStateException when called from `onPrint` of the same run.
     * Exceptions thrown by `onPrint` are passed on; the lines after that one are not passed, and the run keeps the
     * state at the end of the tick.
     */
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
