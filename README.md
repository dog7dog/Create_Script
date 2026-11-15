# CreateScript

[日本語](README.ja.md)

CreateScript is a small, statically typed scripting language with a Kotlin-like syntax, and a sandboxed
virtual machine that runs it in bounded steps ("ticks"). It is meant to be embedded in host applications
that run untrusted scripts. This repository contains:

- **`createscript-core`**: the lexer, parser, semantic analyzer, bytecode compiler and VM, as a Kotlin/JVM
  library with no dependency other than the Kotlin standard library.
- **`createscript-cli`**: the `createscript` command, which runs `.cscr` files and has an interactive mode
  (REPL) with line editing.
- **`createscript-project`** (in development, not in v0.1.0-alpha): the project files `createscript.json` and
  `createscript.lock` (strict JSON, validation, canonical form), and the formats and signature check of the
  planned package registry ([docs/registry.md](docs/registry.md)), without file or network access.
- **`createscript-registry-tool`** and **`registry-template/`** (in development, not in service): the tool and
  the template for operating the planned registry. Not part of the `createscript` packages.

> **CreateScript-Registry is not published yet, and it is looking for maintainers.** The client side exists
> (`createscript install <name>@<version>` checks an Ed25519-signed index), but there is no registry repository,
> no production signing key and no published index, so that command currently refuses without connecting.
> The findings of the pre-launch security audit (`docs/audits/`) have been addressed in code, but the GitHub
> settings, the production key and at least three maintainers are still needed before any launch.
> Install packages with `createscript install github:owner/repo@v1.2.0`. See [docs/registry.md](docs/registry.md).

> **Status: 0.1.0-alpha.** The language and the APIs can still change.
> [v0.1.0-alpha](https://github.com/dog7dog/Create_Script/releases/tag/v0.1.0-alpha) is published as a
> pre-release on GitHub Releases (2026-11-04), with packages for Linux x86_64 and aarch64 (`.tar.gz`), an
> unsigned macOS arm64 `.pkg` and `SHA256SUMS`. See [Platform status](#platform-status).

```kotlin
class Counter {
  var value = 0
  fun add(step: Int): Int {
    value = value + step
    return value
  }
}
fun fib(n: Int): Int {
  if (n < 2) { return n }
  return fib(n - 1) + fib(n - 2)
}
val c = Counter()
for (i in 1..3) { c.add(i) }
println(c.value)   // 6
println(fib(10))   // 55
```

## Features

- Types `Int` (32-bit), `Float`, `Double`, `Boolean`, `String`, lists, `Vector`, `Matrix` and classes, checked at compile
  time.
- `val` / `var`, `if` / `else`, `while`, `for` over integer ranges, `break` / `continue`, functions with
  recursion, classes with properties and methods, nested lists.
- Math functions as in `kotlin.math`, deterministic random numbers, small vectors and matrices.
- Local modules (in development, not in v0.1.0-alpha): `import geometry` uses the functions of
  `geometry.cscr` in the same project, as `geometry.square(5)`, with type checks across files. Modules are only read from your project directory;
  modules of installed packages are used by `createscript run [directory]` (see [Modules](docs/language.md#modules-local-import)).
- Project files (in development, not in v0.1.0-alpha): `createscript init` creates a project with a
  `createscript.json` manifest; `createscript manifest check` and `createscript lock check` check the manifest
  and the lock file; `createscript pack` makes a package archive (`createscript-package.zip`, the same bytes for
  the same files) from a library project; `createscript install github:owner/repo@v1.2.0` installs a package and its dependencies from
  GitHub Releases with SHA-256 checks, and `createscript run` runs the project with them, without the network; only
  public modules of direct dependencies can be imported (see [Project files](docs/packages.md)).
  `createscript install <name>@<version>` will install reviewed packages by name from the signed CreateScript-Registry
  index once the registry is published (not yet; see [docs/registry.md](docs/registry.md)).
- A safe execution model: fixed limits on stack, call depth, objects and list elements; an instruction budget
  per tick; input and program size limits chosen by the host: small for embedding hosts (4096 characters and
  4096 instructions), large for the `createscript` command (1,000,000 characters per file, 1024 modules, no
  fixed instruction limit; in development, see [Limits and safety](docs/language.md#limits-and-safety)); no access to files, the network, the operating system or the JVM.
- Diagnostics with stable codes (`CS_SEM_TYPE_MISMATCH`, …) and line/column positions.

## Documentation

- [Language basics](docs/language.md)
- [The `createscript` command, REPL and line editing](docs/cli.md)
- [Samples](docs/samples.md)
- [Project files: `createscript.json` and `createscript.lock`](docs/packages.md)
- Detailed CLI manual in Japanese: [createscript-cli/README.md](createscript-cli/README.md)

## Building

Requirements: JDK 21 (Gradle can download one through the Java toolchain support). The Gradle wrapper
(Gradle 8.8) is included.

```bash
./gradlew build                                # compile and run all tests
./gradlew :createscript-cli:installDist        # build the command
createscript-cli/build/install/createscript/bin/createscript run createscript-cli/samples/hello.cscr
createscript-cli/build/install/createscript/bin/createscript      # interactive mode
```

`installDist` makes a launcher that needs Java 21 on `JAVA_HOME` or `PATH`. Scripts in
`createscript-cli/packaging/` build packages with a bundled Java runtime (see [docs/cli.md](docs/cli.md)).

## Using the Core library

The Core is not published to a Maven repository yet. Its entry point is
`com.creatrbypc.createanalytical.script.api.CreateScript` (the package name comes from the project where
CreateScript started):

```kotlin
val result = CreateScript.compile("println(6 * 7)")
val program = result.program ?: error(result.diagnostics)
val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
while (!run.isFinished) run.tick()
```

For programs with `import` (in development, not in v0.1.0-alpha), the host gives the module sources to the compiler; the Core never reads files
itself. `ModuleResolver` receives a module name (one identifier) and returns its source, or the reason it
cannot be used:

```kotlin
val modules = mapOf("geometry" to "fun square(x: Int): Int {\n  return x * x\n}\n")
val result = CreateScript.compile("import geometry\nprintln(geometry.square(5))", { name ->
    modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
})
result.diagnostics.forEach { println("${it.module ?: "main"}: ${it.diagnostic.code.id}") }
val program = result.program ?: error("compile failed")
```

`CreateScript.compile(source, CompileOptions.STANDALONE)` compiles with the large standalone limits (in
development, not in v0.1.0-alpha); `CreateScript.compile(source)` keeps the small limits, as does
`CompileOptions.EMBEDDED`. The runtime limits do not change.

`CreateScript.compile(source)` works as before; it reports `CS_MODULE_IMPORT_UNAVAILABLE` for a program with
`import`. `result.locate(run.error!!)` tells in which file a runtime error happened.

`CreateScript.resume` checks only the shape of a saved state, not which program it came from. A host that
saves and restores runs must compare two fingerprints before resuming: `result.sourceFingerprint` (all
source files; for a program without `import` it equals `ProgramFingerprint.ofSource(source)`) and
`ProgramFingerprint.ofProgram(program)`. Then a state is not restored after only an imported file changed.

## Platform status

The table below is for the released v0.1.0-alpha (CI on its tag). The features in development (local modules,
project files, `pack`, `install` and running projects with packages) have so far been tested on macOS arm64 only,
locally (all unit tests, and the command run by hand); they have not run in CI or on Linux or Windows, and
`install` has not yet downloaded from a real GitHub release (it was tried with archives in the cache and a test
server). Their file locks, file identities (`fileKey`), atomic renames and Windows name rules are therefore not
verified on Linux and Windows.

| Platform | Status (v0.1.0-alpha) |
|---|---|
| Ubuntu x86_64 (`ubuntu-24.04`) | Verified in CI: build, all tests, samples, REPL, and the `.tar.gz` installed, run and uninstalled |
| Ubuntu aarch64 (`ubuntu-24.04-arm`) | Verified in CI: the `.tar.gz` built, installed, run and uninstalled (the unit tests run on x86_64 and macOS only) |
| macOS arm64 (Apple Silicon) | Verified in CI (`macos-15`): build, all tests, samples, REPL, and the `.pkg` installed, run and uninstalled. Locally, the build, the tests and the `.pkg` contents were also checked (the `.pkg` was not installed locally) |
| macOS x86_64 (Intel) | Not verified (the packaging script supports it, but it has never been run) |
| Windows | Not verified (`installDist` makes a `createscript.bat`, but it has never been run) |
| macOS signing and notarization | Not done. The `.pkg` is unsigned, so it is not suitable for public distribution |

All CI jobs use Temurin 21.

## Security

Please report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## License

CreateScript is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE). The binary
packages also contain third-party components under their own licenses (the Kotlin standard library,
JetBrains annotations, JLine and an OpenJDK runtime); see
[THIRD-PARTY-NOTICES.md](createscript-cli/packaging/common/THIRD-PARTY-NOTICES.md).

## Use of AI

This project was developed with the help of AI coding assistants, mainly
[Claude Code](https://claude.com/claude-code). Commits made with its help carry a `Co-Authored-By` trailer.
