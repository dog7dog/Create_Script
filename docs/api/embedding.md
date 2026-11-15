# Embedding the CreateScript Core

This guide shows how a host application uses `createscript-core` to compile and run CreateScript programs. The
reference is the KDoc of the Stable API (listed in [api-surface.md](api-surface.md)); the rules for changes are in
[compatibility.md](compatibility.md).

## Getting the library

The Core is not published to a Maven repository yet. Build it from this repository with JDK 21:

```bash
./gradlew :createscript-core:jar
```

and use `createscript-core/build/libs/createscript-core.jar`. Its only dependency is the Kotlin standard library
2.0.0 (with the JetBrains annotations that it uses). The package names start with `com.creatrbypc.createanalytical.script` (from the project where CreateScript
started).

## Compiling and running

```kotlin
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions

fun runOnce() {
    val result = CreateScript.compile("println(6 * 7)")
    val program = result.program ?: error(result.diagnostics)
    val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
    while (!run.isFinished) run.tick()
}
```

- `CreateScript.compile` never throws for problems in the program: they are in `result.diagnostics`, each with a
  stable code (`diagnostic.code.id`, for example `CS_SEM_TYPE_MISMATCH`) and a position. `result.program` is
  `null` when there is an error.
- A run does nothing until you call `tick()`. Each tick runs at most `instructionsPerTick` instructions and a
  bounded amount of garbage collection, so the host decides how much work a script may do per step.
- `onPrint` receives every line the program prints, after the tick. `run.console` keeps the last 32 lines.
- `run.state`, `run.error` and `run.isFinished` tell whether the program waits, finished or stopped with a runtime
  error (`CS_RUN_...`).

## Limits

`CreateScript.compile(source)` uses `CompileOptions.DEFAULT`: small limits for hosts that run many untrusted
scripts (4096 UTF-16 code units of source, 2048 tokens and syntax tree nodes, 4096 instructions).
`CompileOptions.STANDALONE` is what the `createscript` command uses (1,000,000 code units per file, no instruction
limit, 1024 modules). The options decide only which programs are accepted; the runtime limits (stack, call depth,
objects, list elements) are the same for every option, and scripts have no access to files, the network, the
operating system or the JVM.

## Modules

The Core never reads files. For a program with `import`, the host gives the module sources through a
`ModuleResolver`:

```kotlin
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.module.ModuleLookup

fun compileWithModules() {
    val modules = mapOf("geometry" to "fun square(x: Int): Int {\n  return x * x\n}\n")
    val result = CreateScript.compile("import geometry\nprintln(geometry.square(5))", { name ->
        modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
    })
    result.diagnostics.forEach { println("${it.module ?: "main"}: ${it.diagnostic.code.id}") }
    val program = result.program ?: error("compile failed")
}
```

- `CreateScript.compile(source)` without a resolver reports `CS_MODULE_IMPORT_UNAVAILABLE` for a program with
  `import`.
- `ModuleAccess` decides which file may import which module (`CS_MODULE_NOT_VISIBLE` otherwise).
- `CreateScript.compileScoped` with a `ScopedModuleResolver` looks modules up per importing file and names them by
  an id, so modules with the same name from different packages can be in one program.
- A runtime error of a program with modules has a position in the whole program; `result.locate(run.error!!)`
  tells the file.

## Saving and resuming runs

A host that keeps runs across restarts takes a saved state with `run.snapshot()` and continues it later with
`CreateScript.resume`. `VmSnapshot` and its parts are plain data; the host stores them field by field in its own
format.

```kotlin
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.api.ScriptRun
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.VmSnapshot

class SavedRun(val state: VmSnapshot, val source: ByteArray, val program: ByteArray)

fun save(run: ScriptRun, source: String, program: CompiledProgram): SavedRun? =
    run.snapshot()?.let { SavedRun(it, ProgramFingerprint.ofSource(source), ProgramFingerprint.ofProgram(program)) }

fun resume(saved: SavedRun, source: String, program: CompiledProgram): ScriptRun? {
    // resume checks only the shape of the state; the host checks that it belongs to this program
    if (!saved.source.contentEquals(ProgramFingerprint.ofSource(source))) return null
    if (!saved.program.contentEquals(ProgramFingerprint.ofProgram(program))) return null
    return CreateScript.resume(program, saved.state, RunOptions(instructionsPerTick = 128, randomSeed = 0))
}
```

- For a program with `import`, store `ModuleCompileResult.sourceFingerprint` instead of `ofSource`, so a state is not
  resumed after only a module changed.
- `resume` returns `null` when the state does not fit the program.
- The random state and the console history come from the saved state; `onPrint` gets only later lines.
- A saved state is always between two instructions. An instruction that takes several ticks (the vector and matrix
  operations, such as `vector(...)`) is saved as not yet started and starts again after resuming, with the same
  result. A garbage collection in progress is not saved either. Saving and resuming after every tick with very few
  instructions per tick can therefore keep repeating such an instruction.
- States saved by earlier releases still resume (see [compatibility.md](compatibility.md)).

## Threads

The Core keeps no shared mutable state: compilations and different runs can use different threads at the same
time. One `ScriptRun` is not thread-safe; use it from one thread at a time. `tick()` must not be called from its own
`onPrint`.

## Internal API

Some public classes (the lexer, parser, compiler, VM, bytecode and AST types, `CompiledProgram`'s contents) are
public only because other modules of this repository use them. They are marked `@InternalCreateScriptApi`; using
them gives a warning unless you opt in, and they can change in any release. See [api-surface.md](api-surface.md).
