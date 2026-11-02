# CreateScript

[日本語](README.ja.md)

CreateScript is a small, statically typed scripting language with a Kotlin-like syntax, and a sandboxed
virtual machine that runs it in bounded steps ("ticks"). It was first written for the programmable
block of the Minecraft mod *Create: Analytical*. This repository contains the language on its own, without
Minecraft:

- **`createscript-core`**: the lexer, parser, semantic analyzer, bytecode compiler and VM, as a Kotlin/JVM
  library with no dependency other than the Kotlin standard library.
- **`createscript-cli`**: the `createscript` command, which runs `.cscr` files and has an interactive mode
  (REPL) with line editing.

> **Status: 0.1.0-alpha.** The language and the APIs can still change. No release packages are published
> yet. See [Platform status](#platform-status).

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
- A safe execution model: fixed limits on source size, instructions, stack, call depth, objects and list
  elements; an instruction budget per tick; no access to files, the network, the operating system or the JVM.
- Diagnostics with stable codes (`CS_SEM_TYPE_MISMATCH`, …) and line/column positions.

## Documentation

- [Language basics](docs/language.md)
- [The `createscript` command, REPL and line editing](docs/cli.md)
- [Samples](docs/samples.md)
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
`com.creatrbypc.createanalytical.script.api.CreateScript` (the package name comes from the Minecraft mod
where CreateScript started):

```kotlin
val result = CreateScript.compile("println(6 * 7)")
val program = result.program ?: error(result.diagnostics)
val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
while (!run.isFinished) run.tick()
```

## Platform status

| Platform | Status |
|---|---|
| Build and tests on macOS (Apple Silicon, Temurin 21) | Verified locally |
| `createscript` from `installDist` on macOS | Verified locally |
| macOS `.pkg` (unsigned) | Built and inspected locally. Not signed or notarized, so it is not suitable for public distribution |
| Build and tests on Linux | Configured in CI; not verified yet |
| Linux `.tar.gz` and `install.sh` | Configured in CI; not verified on a Linux machine yet |
| Windows | Not tested (`installDist` makes a `createscript.bat`, but it has never been run) |

## Security

Please report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## License

CreateScript is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE). The binary
packages also contain third-party components under their own licenses (the Kotlin standard library,
JetBrains annotations, JLine and an OpenJDK runtime); see
[THIRD-PARTY-NOTICES.md](createscript-cli/packaging/common/THIRD-PARTY-NOTICES.md).

The Minecraft mod *Create: Analytical* is a separate project and is not covered by this repository.

## Use of AI

This project was developed with the help of AI coding assistants, mainly
[Claude Code](https://claude.com/claude-code). Commits made with its help carry a `Co-Authored-By` trailer.
