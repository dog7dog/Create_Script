# API surface of `createscript-core` and `createscript-project`

Status: **applied, checked and documented (stage A4)**. This document classifies every public top-level declaration
of the two library modules. Stage A1 made the classification; stage A2 applied it with Kotlin's explicit API mode
and two opt-in annotations (see "Applied in A2"); stage A3 checks it with a committed API listing (see "API
listing"); stage A4 documented the Stable part with KDoc (see "KDoc (A4)").

Inventory taken on `main` at `b80f73d` (2026-11-15). 172 public top-level declarations were found
(core 122, project 50); members are classified with their declaration unless a row says otherwise.

## Tiers

| Tier | Meaning | How it will be marked (A2) |
|---|---|---|
| **Stable** | Supported for hosts and tools. Changes follow the compatibility rules below. | Plain `public`, KDoc required (A4), in the API listing (A3). |
| **Internal API** | Public on the JVM because other modules of this repository use it, or because it is part of `v0.1.0-alpha`. No compatibility promise; it can change in any release. | `@InternalCreateScriptApi` (core) or `@InternalCreateScriptProjectApi` (project): `@RequiresOptIn` annotations, level `WARNING`. The CLI, install and registry-tool modules opt in. |
| **Module-private** | Used only inside its own module and not part of `v0.1.0-alpha`. | The `internal` modifier. |

Kotlin's `internal` hides a declaration from every other module, including the ones in this repository. That is
why declarations used by `createscript-cli`, `createscript-install` or `createscript-registry-tool` are
**Internal API** and not `internal`.

## Compatibility rules

- Before 1.0, a release can break the Stable API, but only when the break is listed under a `### Changed` or
  `### Removed` heading of `CHANGELOG.md` with what to use instead.
- Stable declarations that were in `v0.1.0-alpha` keep their JVM signatures (binary compatibility), as the
  existing `Program` and `CompiledProgram` compatibility constructors do (`@Deprecated(level = HIDDEN)`).
- New properties of Stable data classes get default values and go after the existing ones, so constructors
  and `copy` keep working; a hidden overload keeps the old JVM signature.
- Saved states: `VmSnapshot` made by an earlier release must still be accepted by `CreateScript.resume`
  (this is already a rule of the Core; see `docs/language.md` and stage 9.2).
- Diagnostic code ids (`CS_…`, `CS_PKG_…`) are Stable: an id is never reused for a different meaning. New codes
  can be added in any release, so a `when` over `DiagnosticCode` or `ProjectDiagnosticCode` needs an `else`.
  The same holds for `DiagnosticPhase` (this was already announced for `DiagnosticPhase.MODULE`).
- Internal API can change in any release without a CHANGELOG entry.

## `createscript-core`

### Stable

| Package | Declarations | Notes |
|---|---|---|
| `script.api` | `CreateScript`, `RunOptions`, `TickReport`, `ScriptRun` | The entry point. `ScriptRun` keeps its `internal` constructor. |
| `script.compiler` | `CompileResult`, `CompileOptions` | `CompileOptions.DEFAULT`, `EMBEDDED`, `STANDALONE` and the constructor parameters. The defaults name Internal limit objects; the values are documented on `CompileOptions` instead. |
| `script.module` | `ModuleResolver`, `ModuleLookup`, `ModuleAccess`, `ScopedModuleResolver`, `ScopedModuleLookup`, `ModuleDiagnostic`, `ModuleCompileResult` | |
| `script.diagnostic` | `Diagnostic`, `DiagnosticCode`, `DiagnosticPhase`, `DiagnosticSeverity`, `DiagnosticArgument`, `SourcePosition` | |
| `script.vm` | `CompiledProgram` (the type only), `VmState`, `ProgramFingerprint` | `CompiledProgram` is an opaque handle: its constructor, `copy` and properties (`instructions`, `constants`, `functions`, `handlers` …) are Internal API. |
| `script.vm` (saved state) | `VmSnapshot`, `ObjectSnapshot`, `ListSnapshot`, `CallFrameSnapshot`, `Value`, `SnapshotVerifier.SlotType` | Hosts that save runs read and rebuild these field by field (CreateAnalytical does). Stable as a data shape; see open question Q2. |

### Internal API (opt-in)

| Package | Declarations | Used by / reason |
|---|---|---|
| `script.ast` | all 43 declarations (`Program`, `Statement`, `Expression` and their subclasses, `RangeKind`, the operator enums, `ImportDeclaration`, `LambdaExpression`, `LambdaParameter`, `FunctionReference`, `PipeExpression`, `FallbackExpression`, `FUNCTION_TYPE_NAME`) | cli (`ExpressionStatement`, interactive mode); `v0.1.0-alpha`. The tree changes with every language stage. The newer nodes cannot be `internal` because `Program` and the sealed interfaces expose them. |
| `script.lexer` | `Lexer`, `LexResult`, `Token`, `TokenType`, `LexerLimits`, `HARD_KEYWORDS` | cli (interactive mode, line editing); `v0.1.0-alpha` |
| `script.parser` | `Parser`, `ParseResult`, `ParserLimits` | cli; `v0.1.0-alpha` |
| `script.semantic` | all 16 declarations (`SemanticAnalyzer`, `ScriptType`, `BuiltinFunctions` …, including `CollectionOperation`, `InlineFunction`, `CollectionCall`) | cli tests (`BuiltinFunctions`); `v0.1.0-alpha`. The three newer ones are exposed by public members of the others. |
| `script.compiler` | `Compiler` | `v0.1.0-alpha`; embedders should use `CreateScript.compile` |
| `script.module` | `ModuleLimits` | read through `CompileOptions` |
| `script.diagnostic` | `DiagnosticLimits`, `DiagnosticCollector`, `truncateUtf16` | `v0.1.0-alpha` |
| `script.scheduler` | `Scheduler`, `PowerLevel`, `ExecutionCondition`, `TickResult` | `v0.1.0-alpha`; the game host's scheduler. `RunOptions.gcBudgetPerTick` defaults to `Scheduler.GC_BUDGET_PER_VM` (the value will be documented on `RunOptions`). |
| `script.vm` | `CsVm`, `Console`, `Opcode`, `Instruction`, `Constant`, `HostFunction`, `FunctionInfo`, `ClassInfo`, `FieldInfo`, `ListShape`, `ErrorHandler`, `VmLimits`, `NumericKind`, `MathFunction`, `LinalgFunction`, `MathResult`, `SnapshotVerifier` (except `SlotType`), `SnapshotProblem` | cli (`Opcode`, `Constant`, `HostFunction`, `SnapshotVerifier`); `v0.1.0-alpha`. `ErrorHandler` is exposed by `CompiledProgram.handlers`. |

### Module-private

None. Every public core declaration that no other module uses is either in `v0.1.0-alpha` or exposed by a
public declaration (for example `ErrorHandler` by `CompiledProgram.handlers` and the stage 9 AST nodes by
`Program`), so making it `internal` would be a compile error or a binary break. A2 will look again at
members (functions and properties inside classes), where `internal` may still apply.

## `createscript-project`

The module did not exist in `v0.1.0-alpha`, so nothing here has a binary compatibility obligation yet.

### Stable

| Package | Declarations | Notes |
|---|---|---|
| `project` | `Manifests`, `Manifest`, `ManifestResult`, `Dependency`, `PackageKind` | `Manifests.parse` / `write` and the constants (`FILE_NAME`, schema versions, size limits). See Q3 for the position fields. |
| `project` | `Lockfiles`, `Lockfile`, `LockfileResult`, `LockRoot`, `LockedPackage` | `Lockfiles.parse` / `write` / `check`. |
| `project` | `ProjectDiagnostic`, `ProjectDiagnosticCode` | |
| `project` | `ProjectRules` | The validity checks for names, versions, licenses, sources, SHA-256 values and paths, and their limits. |
| `project` | `FilePosition` | Added in A2 (Q3): the positions of `ManifestResult` and `LockfileResult`. |

### Internal API (opt-in)

| Package | Declarations | Used by / reason |
|---|---|---|
| `project` | `JsonValue`, `JsonObject`, `JsonArray`, `JsonString`, `JsonInteger`, `JsonBoolean`, `JsonNull`, `JsonParser`, `JsonResult`, `JsonWriter`, `CanonicalJson` | install, registry-tool. A strict JSON reader for these formats, not a general JSON library. |
| `project` | `ProjectDiagnostics` | Its `add(code, at: JsonValue?, …)` takes an Internal type. |
| `project` | `PackageArchives` (with `Archive`, `ArchiveResult`, `ArchiveDiagnostic`) | cli, install, registry-tool. Internal until its rules are settled (Q4); a candidate for Stable later. |
| `project.registry` | all 24 declarations (`IndexVerifier`, `IndexSignatures`, `RegistryIndex`, `RegistryKeys`, `TrustedKey`, `RegistryRules`, `RegistryConfig` …) | install, registry-tool. The signed registry is not launched (NO-GO) and its formats can still change. Stable when the registry is launched. |

### Module-private

None at the top level (every candidate is used by install or registry-tool). A2 will look at members.

## Decisions (A1 review, 2026-11-15)

- **Q1.** `WARNING`: using Internal API without opting in gives a warning, never a compile error, so code
  written against `v0.1.0-alpha` keeps compiling.
- **Q2.** `VmSnapshot`, `ObjectSnapshot`, `ListSnapshot`, `CallFrameSnapshot`, `Value` and
  `SnapshotVerifier.SlotType` are Stable. Saved states of earlier releases must restore; compatibility tests come
  first (see "Applied in A2").
- **Q3.** `FilePosition(line, column)` replaces `Pair<Int, Int>` in `ManifestResult` and `LockfileResult`.
- **Q4.** `PackageArchives` stays Internal API for now; it can become Stable when its rules are settled.
- **Q5.** Checked with the compiler: marking `SnapshotVerifier` makes `SnapshotVerifier.SlotType` need opt-in
  too. So the object is not marked; each of its other members is.
- **Q6.** CreateAnalytical is not changed. It builds its own copy of the Core; if it later builds against this
  Core, it opts in.

## Method

The inventory was taken with a small script outside the repository. It lists top-level `class`, `interface`,
`object`, `fun`, `val` and `typealias` declarations of `createscript-core/src/main` and
`createscript-project/src/main`. For each one it records whether another module's `src/main` or `src/test`
refers to the name (with its package), and whether a declaration of the same name is public in the
`v0.1.0-alpha` sources (`git archive v0.1.0-alpha`). The use check matches names and can over-report.
Member-level classification is done in A2 with the compiler.

## Applied in A2

Checked with the compiler and with `javap` on the built jars (2026-11-15, on top of `b80f73d`):

- `explicitApi()` is on in `createscript-core` and `createscript-project`: every public declaration says `public`
  (or `internal`) and gives its type.
- The annotations `com.creatrbypc.createanalytical.script.InternalCreateScriptApi` and
  `com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi` (`@RequiresOptIn`, level `WARNING`)
  mark the 132 Internal API top-level declarations of the tables above (core 95, project 37), the members of
  `SnapshotVerifier` other than `SlotType` (Q5), and the constructor, the properties and the hidden compatibility
  constructor and `copy` of `CompiledProgram`. The two modules opt in for themselves (tests included); the CLI
  opts in to both, install and registry-tool to the project one.
- 57 members (core 20, project 37) became `internal`: members of Internal API types that no other module uses
  (found from the references in the compiled classes of cli, install and registry-tool, main and tests) and that
  were not public in `v0.1.0-alpha`. Constants (`const val`) are copied into the code that uses them, so they were
  checked in the sources instead. Members of interfaces cannot be `internal` and stay public.
- `FilePosition` replaces `Pair<Int, Int>` in `ManifestResult.entryPosition`, `dependencyPositions`,
  `modulePositions` and `LockfileResult.rootPosition`, `packagePositions`. `createscript-project` was not in
  `v0.1.0-alpha`, so no released code used these; the CLI and one test were updated.
- JVM signatures: every public member of the `v0.1.0-alpha` Core is still there (the same 11 lines as before A2
  are hidden from `javap -public`: the hidden compatibility members of `Program` and `CompiledProgram`, which
  `javap -p` shows, and Kotlin's synthetic constructors). The other changes are the positions above, the members
  that became `internal` (their JVM names now end in `$createscript_core` or `$createscript_project`), the two
  annotation classes and `FilePosition`.
- Use from outside, compiled in a temporary module that does not opt in: a file using only Stable API (the
  README example, rebuilding a `VmSnapshot` field by field, a `when` over `SlotType`, the project files API)
  compiles without warnings; each of ten uses of Internal API (`Compiler`, `Opcode`, `VmLimits`,
  `CompiledProgram.instructions`, `copy()`, `component1()`, `SnapshotVerifier.maxSteps`,
  `PackageArchives.FILE_NAME`, `JsonParser`, the `CompiledProgram` constructor) gives a warning and compiles.
- Saved states (Q2): `BinaryCompatibilityTest.snapshotHostCompiledAgainstV010AlphaStillSavesAndRestores` runs a
  host compiled against the `v0.1.0-alpha` Core (`compat/v010alpha/OldSnapshotHost`). It saves every 4, 5, 7 and
  11 ticks, rebuilds the state with the `v0.1.0-alpha` constructors, resumes and gets the output of a run without
  interruptions. An instruction that spans ticks (`vector(...)` and the other linear algebra operations) is not
  saved half done: after a restore it starts again. A host that restores after every single instruction would not
  get past it; this is existing behaviour, to be documented on `CreateScript.resume` in A4.

## API listing (A3)

`createscript-core/api/createscript-core.api` and `createscript-project/api/createscript-project.api` list the
public API of the two modules as the JVM sees it. `ApiListingTest` in each module makes the listing again from the
compiled classes and fails when it differs, with the lines that were removed (`-`) and added (`+`). The code that
makes the listing is shared: `test-support/api-listing/.../ApiListing.kt`, added to the test sources of both modules.
It uses only the JDK (`javap -v -p` through `java.util.spi.ToolProvider`, or `$JAVA_HOME/bin/javap` when the tool
provider is missing).

To change the API on purpose, make the change, then run

```
CREATESCRIPT_API_UPDATE=1 ./gradlew :createscript-core:test --tests '*ApiListingTest*' :createscript-project:test --tests '*ApiListingTest*'
```

(`--tests` applies to the task before it) and review the diff of the `.api` files with the change. The files are written only when `CREATESCRIPT_API_UPDATE` is
`1`; otherwise a missing or different listing is a test failure. The listing is made with JDK 21 (the test fails on
another JDK version), and it is the same on macOS and Linux.

What a listing contains:

- Public classes whose outer classes are public too (not synthetic), with their public and protected fields,
  constructors and methods that are not synthetic, as `javap` prints them, sorted. A constant field shows its value,
  because code that uses it copies the value.
- After `//`: `opt-in` when the declaration needs opt-in (it, a class around it, or for a getter its property, has
  `@InternalCreateScriptApi` or `@InternalCreateScriptProjectApi`); `returns !` or `returns ?` for a result marked
  `@NotNull` or `@Nullable` by Kotlin; `params` with one mark per parameter (`!`, `?`, or `-` for neither). So a change
  of nullability, which the JVM descriptors do not show, also changes the listing.

Kotlin `internal` and the JVM: an `internal` function or property accessor is public on the JVM with a name that ends
in `$createscript_core` or `$createscript_project`; these are left out. An `internal` class, constructor or constant is
public on the JVM without such a name, and `javap` cannot tell it from a public one (that needs the Kotlin metadata,
which the JDK cannot read). They are listed like the other JVM-public declarations: after A2 there are 14
`internal` classes, objects and interfaces, 3 `internal` constructors and 24 `internal` constants in the two modules.
A change to them shows in the listing too.

What the listing does not show:

- Synthetic members, among them the hidden compatibility constructors and `copy` of `Program` and `CompiledProgram`
  (`@Deprecated(level = HIDDEN)`). `BinaryCompatibilityTest` keeps checking those.
- Default values of parameters and other facts kept only in the Kotlin metadata.
- `copy` and `componentN` of `CompiledProgram` are not marked `opt-in` in the listing (the compiler adds no marker to
  them), although using them needs opt-in because their types do.

## KDoc (A4)

Every Stable declaration has English KDoc: 285 declarations, counting each public class, function, property and
enum entry and each property of a primary constructor (`@property`), except the entries of `DiagnosticCode` and
`ProjectDiagnosticCode`, which are documented at the type (how ids are kept, and that a `when` over them needs an
`else`). The Japanese comments that described these declarations were replaced by the KDoc; comments about the
implementation, about Internal API and about groups of diagnostic codes were kept.

The KDoc states only what the code does, checked in the code: purpose, exceptions (`IllegalArgumentException` from
constructors that check their values, `IllegalStateException` from a re-entrant `ScriptRun.tick`, exceptions of host
callbacks passed on), limits (the defaults and `CompileOptions.STANDALONE`, the console's 32 lines of 64 UTF-16 code
units, the limits of project files) and threads: the Core keeps no shared mutable state (no object or companion
object has a mutable member, and each compilation makes its own lexer, parser and compiler), so compilations and
different runs can use different threads at once, while one `ScriptRun` is not thread-safe.

`CreateScript.resume` and `ScriptRun.snapshot` state the limits of saved states:

- A saved state is between two instructions. An instruction that takes several ticks (the vector and matrix
  operations) is saved as not yet started, so it starts again after resuming, with the same result. Saving and
  resuming after every tick with very few instructions per tick can repeat it without end.
- A garbage collection in progress is not saved; the resumed run starts it again.
- `resume` checks the shape of a state, not which program it came from; hosts compare
  `ProgramFingerprint.ofProgram` and the source fingerprint.
- The random state and the console history come from the saved state; `onPrint` gets only later lines.

`CreateScriptTest.resumeStartsAMultiTickInstructionAgain` checks the first point with a bounded number of ticks: a
state saved one step into `vector(3.0, 4.0)` needs one more tick than the original run to finish that instruction,
saving and resuming after each of 50 ticks stays on it, and both runs print what a run without saving prints.

Checks: the one code example (in the KDoc of `CreateScript`) was compiled and run in a temporary module without
opt-in (no warnings; it prints `42`); the API listings did not change; the compiled bytecode of both modules is the
same as before A4 apart from line numbers (`javap -c -p`); the cited `CS_...` ids exist.
