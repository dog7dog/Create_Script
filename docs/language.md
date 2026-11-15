# The CreateScript language (basics)

CreateScript is a small, statically typed language with a Kotlin-like syntax. Programs are compiled to
bytecode and run on a sandboxed virtual machine that advances in logical **ticks**. This repository contains
the language itself (`createscript-core`) and a command-line tool (`createscript-cli`).

This page covers the basic features. Every example below was run with `createscript run` and shows its
actual output. Source files use the extension `.cscr` and the UTF-8 encoding.

## Values and variables

```kotlin
val answer = 42           // Int (32-bit)
var name = "CreateScript" // String
val ready = true          // Boolean
val half = 0.5            // Double
val small = 1.5f          // Float
var count: Int = 0
count = count + 1
println(answer)
println(name)
println(!ready)
println(half * 3)
println(small + 1)
println(count)
println(7 / 2)
println(7 % 3)
println(7.0 / 2)
println(answer.toDouble() / 8)
```

```text
42
CreateScript
false
1.5
2.5
1
3
1
3.5
5.25
```

- `val` declares a read-only variable and `var` a variable that can be assigned again. The type can be written
  (`var count: Int = 0`) or inferred from the initial value.
- Types: `Int` (32-bit signed), `Float`, `Double`, `Boolean`, `String`, `List<T>`, `MutableList<T>`, `Vector`,
  `Matrix` and user-defined classes.
- Mixed `Int` / `Float` / `Double` arithmetic works as in Kotlin. Explicit conversions are `x.toInt()`,
  `x.toFloat()` and `x.toDouble()`.
- `Int` overflow is a runtime error (`CS_RUN_INTEGER_OVERFLOW`), not a silent wrap-around.
- Strings are printed with `println`. There is no string concatenation with `+`. Escapes in string literals:
  `\"`, `\\`, `\$` and `\'`.
- Comments start with `//`. Block comments (`/* */`) and character literals (`'a'`) are not supported.

## Control flow

```kotlin
var total = 0
for (i in 0 until 10) {
  if (i % 2 == 0) {
    continue
  }
  total = total + i
}
println(total)
var n = 10
while (n > 0) {
  n = n - 3
  if (n < 4) { break }
}
println(n)
if (total > 20 && n != 0) {
  println("big")
} else {
  println("small")
}
```

```text
25
1
big
```

`if` / `else`, `while`, `for (i in a until b)` and `for (i in a..b)` (integer ranges; `until` excludes `b`,
`..` includes it), `break`, `continue`, and the operators
`&&`, `||`, `!`, `==`, `!=`, `<`, `<=`, `>`, `>=`.

## Functions

```kotlin
fun square(x: Int): Int {
  return x * x
}
fun fib(n: Int): Int {
  if (n < 2) { return n }
  return fib(n - 1) + fib(n - 2)
}
fun greet(who: String) {
  println(who)
}
println(square(7))
println(fib(10))
greet("there")
```

```text
49
55
there
```

Functions can be called before their definition and can be recursive (up to the call-depth limit below).
Parameters and return values can use the other types too, for example `Double`, `List<Int>` or a class.

## Modules (local `import`)

A program can use functions from other `.cscr` files of the same project. This feature is in development
and is not in v0.1.0-alpha. Modules are files of your project, or modules of packages installed with
`createscript install` (see [Modules from packages](#modules-from-packages)).

```text
my-project/
  main.cscr
  geometry.cscr
```

`geometry.cscr`:

```kotlin
fun square(x: Int): Int {
  return x * x
}
```

`main.cscr`:

```kotlin
import geometry

println(geometry.square(5))
```

```text
$ createscript run my-project/main.cscr
25
```

Rules:

- `import name` loads the module `name` from the file `name.cscr` directly in the project root. With the
  `createscript` command the project root is the directory of the file you run, or `--project-root <dir>`
  (see [cli.md](cli.md)). The name is one identifier; the file name must match exactly, including upper and
  lower case. Names reserved on Windows (`con`, `prn`, `aux`, `nul`, `com0`–`com9`, `lpt0`–`lpt9`, in any case)
  cannot be imported with the `createscript` command (`CS_MODULE_UNREADABLE`; see
  [packages.md](packages.md#names-reserved-on-windows)).
- `import` lines come first in a file, before any other statement or declaration.
- Call a module's functions with the module name: `geometry.square(5)`. Imported functions are not added to
  your own names, so a `square` of your own and `geometry.square` are different functions.
- Arguments and return values are type-checked across files, in the same way as for your own functions.
- A module file contains only `import` lines and `fun` declarations. Inside a module, a call without a module
  name (`helper()`) calls a function of the same module, and `other.f()` calls a module that this module
  imports. A module cannot see the variables, functions or classes of the file that imports it.
- Each module is loaded once, even if several files import it. Importing a module twice in the same file
  only gives a warning.
- All files together make one program, so the limits below (constants, variables, call depth, …) apply
  to the whole program, and each file has its own source length and token limits. At most 16 modules can be
  imported with the embedded limits, and 1024 with the `createscript` command (see
  [Limits and safety](#limits-and-safety)).
- `import` is not a reserved word: a variable called `import` still works.

Not supported yet: `import a.b`, `import a.*`, `import a as b`, paths (`import "../a"`), classes, top-level
statements or variables in modules, using a module name as a value (`println(geometry)`), imports in the
interactive mode, and imports of Java/Kotlin classes. Imports are resolved when the program is compiled;
there is no dynamic import.

Errors (shown with the file, line and column):

| Code | Example |
|---|---|
| `CS_MODULE_NOT_FOUND` | `import geometry` without a `geometry.cscr` in the project root |
| `CS_MODULE_CYCLE` | `a.cscr` imports `b`, and `b.cscr` imports `a` (the argument shows `a -> b -> a`) |
| `CS_MODULE_AMBIGUOUS` | both `Geometry.cscr` and `geometry.cscr` exist |
| `CS_MODULE_OUTSIDE_PROJECT` | `geometry.cscr` is a symbolic link to a file outside the project root |
| `CS_MODULE_UNREADABLE` | the module file is not valid UTF-8, too large, a directory or a broken link |
| `CS_MODULE_TOO_MANY` | more than 16 modules (1024 with the `createscript` command) |
| `CS_MODULE_PROGRAM_TOO_LARGE` | all files together have more than 8,000,000 characters or 1,000,000 tokens (`createscript` command) |
| `CS_MODULE_BUILTIN_NAME` | `import println` |
| `CS_MODULE_TOP_LEVEL_STATEMENT` / `CS_MODULE_CLASS_UNSUPPORTED` | a module with `println(1)` or `class Point` at the top level |
| `CS_MODULE_IMPORT_UNAVAILABLE` | `import` where modules cannot be loaded (the interactive mode, `CreateScript.compile(source)`) |
| `CS_MODULE_NOT_VISIBLE` | an `import` that the host does not allow (a private or transitive package module; see [Modules from packages](#modules-from-packages)) |
| `CS_PARSE_INVALID_IMPORT` | `import geometry.square`, `import geometry as g`, `import "../geometry"` |
| `CS_PARSE_MISPLACED_IMPORT` | `import` after another statement, or inside a block |
| `CS_PARSE_DUPLICATE_IMPORT` (warning) | the same `import` twice in one file |
| `CS_PARSE_IMPORT_NAME_CONFLICT` | `import geometry` and `val geometry = 1` in the same file |
| `CS_PARSE_INVALID_MODULE_REFERENCE` | `println(geometry)`, `geometry.PI` |

Type errors in calls use the usual codes, for example `geometry.square("5")` gives
`CS_SEM_ARGUMENT_TYPE_MISMATCH` and `geometry.cube(2)` gives `CS_SEM_UNKNOWN_FUNCTION`.

### Modules from packages

In development (S8.2 Phase 4, not in v0.1.0-alpha). When a project is run with `createscript run [directory]`
(see [cli.md](cli.md#running-a-project)), `import name` finds `name` in the directory of the entry file, as above,
or in the packages listed in `createscript.lock` and installed in `.createscript/packages/`
(see [Installing packages](packages.md#installing-packages-s82-phase-3)).

- A package's **public modules** are the ones in its `modules` list; its other files in `modules/` are **private**.
- The project (its entry file and local modules) can import the public modules of its **direct** dependencies,
  the packages in its own `dependencies`.
- A package can import its own modules (public and private) and the public modules of its own direct
  dependencies. It cannot import the project's local modules.
- A package that is only a dependency of a dependency (a transitive dependency) cannot be imported directly; add
  it to `dependencies` to use it.
- Each file sees its own **scope**: for the project, its local modules and the public modules of its direct
  dependencies; for a package, its own modules and the public modules of its direct dependencies. An `import` is
  looked up only in the scope of the file that contains it, so modules with the same name in different packages
  (for example a private `util` in two packages), or a local module and a private module of a package, can be used
  in one program. Only a name that exists twice in one scope (a local file and a public module of a direct
  dependency, two direct dependencies with the same public module, or a package's own module and a public module of
  its dependency) is an error (`CS_MODULE_AMBIGUOUS`); nothing is chosen for you. As for local files, names that
  differ only in case count as the same name.
- A package module with the name of the entry file (for example a private `main`) is a different module, not an
  import cycle. Cycles are reported with the modules in the path, package modules as `package/module`.
- Private only limits `import`; the source of private modules is in the package archive and can be read by
  anyone who has the package.

| Code | Example |
|---|---|
| `CS_MODULE_NOT_VISIBLE` | `import vm_internal` (a private module of a package), or `import numbers` when `numbers` is only a dependency of a dependency |
| `CS_MODULE_AMBIGUOUS` | `src/vector_math.cscr` exists and a direct dependency also has a public module `vector_math` |

For hosts, the Core has two ways to control imports; the existing `compile` functions and `ModuleResolver` are
unchanged and allow every import.

- `CreateScript.compileScoped(source, resolver, entryModule, options)` with a `ScopedModuleResolver`
  (`resolve(importer, name)`, used by `createscript run [directory]`). `importer` is the id of the module that
  contains the `import` (`null` for the entry file). The result is `ScopedModuleLookup.Found(id, source)`,
  `Failed(lookup)` (a `ModuleLookup` failure: not found, ambiguous, outside the project, unreadable) or
  `NotVisible` (`CS_MODULE_NOT_VISIBLE`). The id names the module in the program: modules with the same id are one
  module, and functions are named `id.function`, so different ids let modules with the same name coexist. An id is
  1 to 128 characters `A-Z a-z 0-9 _ / : @ + -` starting with a letter, digit or `_` (no `.`). Cycles are checked
  by id after the resolver is asked; return the id `entryModule` for a name that means the entry file itself.
  `ModuleCompileResult.modules`, `ModuleDiagnostic.module` and `sourceFingerprint` use the ids. With ids equal to
  the names, the result is the same as the existing `compile` (instructions, diagnostics and fingerprints).
- `CreateScript.compile(source, resolver, entryModule, options, access)` with a `ModuleAccess`
  (`allows(importer, module)`, where `importer` is the module that contains the `import`, or `null` for the entry
  file). It is checked for every `import` before the resolver is asked; module names stay one namespace.

## Classes

```kotlin
class Counter {
  var value = 0
  fun add(step: Int): Int {
    value = value + step
    return value
  }
}
val c = Counter()
c.add(2)
println(c.add(3))
println(c.value)
```

```text
5
5
```

A class has properties (`val` / `var` with an initial value) and methods. `Counter()` creates an instance.
Unused objects are collected by an incremental garbage collector.

## Lists

```kotlin
val xs = mutableListOf(3, 1, 4)
xs.add(1)
xs[0] = 9
println(xs)
println(xs.size)
xs.removeAt(1)
println(xs)
val grid = listOf(listOf(1, 2), listOf(3, 4))
println(grid[1][0])
```

```text
[9, 1, 4, 1]
4
[9, 4, 1]
3
```

`listOf(...)` makes a read-only list and `mutableListOf(...)` a list that supports `add`, `removeAt`, `clear`
and assignment by index. Lists can be nested.

## List functions and lambdas

```kotlin
val readings = listOf(3, 8, 1, 6, 5)
println(readings.where { it > 4 })
println(readings.map { it * 10 })
println(readings.group { it % 2 })
readings.each { println(it) }
println(readings.average())
```

```text
[8, 6, 5]
[30, 80, 10, 60, 50]
[[3, 1, 5], [8, 6]]
3
8
1
6
5
4.6
```

- `where { … }` keeps the elements for which the lambda is `true`; `map { … }` makes a list of the results.
- `group { … }` makes a list of groups (`List<List<T>>`) by the key the lambda returns, in the order the keys
  first appear. Keys are `Int`, `String` or `Boolean`.
- `each { … }` runs the lambda for every element. `average()` works on lists of `Int`, `Float` and `Double`,
  adds in `Double` and gives `NaN` for an empty list, like Kotlin.
- The functions work on `List` and `MutableList` and do not change the receiver; the result is a new `List`.

A lambda is `{ parameters -> statements }`. A lambda with one parameter can leave it out and use `it`. The value of
the last expression is the result. Parameter types come from where the lambda is used, and the result type is
inferred from the body. A lambda after a call or a member name is passed as the last argument
(`xs.map { … }` is `xs.map({ … })`). `return` is not allowed in a lambda; `break` and `continue` only apply to loops
inside the lambda.

Lambdas can be kept in a `val` and called, and a function can be passed by reference with `::`:

```kotlin
fun twice(x: Int): Int {
    return x * 2
}

val square = { x: Int -> x * x }
val isSmall: (Int) -> Boolean = { it < 3 }
val numbers: MutableList<Int> = mutableListOf()
numbers.add(1)
numbers.add(4)
println(numbers.map(square))
println(numbers.map(::twice))
println(numbers.where(isSmall))
println(square(5))
```

```text
[1, 16]
[2, 8]
[1]
25
```

Without a function type annotation, the parameters of a lambda in a `val` need types. An empty `listOf()` or
`mutableListOf()` takes its element type from a declared type, an assignment target or a return type.

Lambdas are expanded where they are used (like Kotlin `inline`), so the program and the saved state stay the
same kind of bytecode as before. A lambda value is a `val`, can be used only in the function (or the top level)
that declares it, and cannot be stored in a list or property, returned, printed or passed to a user function.
Function types are allowed only on such a `val`. Lambdas are not allowed in property initializers.

## Pipelines `|>`

```kotlin
fun scale(x: Int, k: Int): Int {
    return x * k
}

val result = listOf(3, 8, 1, 6, 5)
    |> where { it > 2 }
    |> map { scale(it, 10) }
println(result)
9.0 |> sqrt |> println
```

```text
[30, 80, 60, 50]
3.0
```

`x |> f` is `f(x)` and `x |> f(a, b)` is `f(x, a, b)`. When the name is a list function (`where`, `map`, `group`,
`each`, `average`) and no function, method or lambda value has that name, `xs |> map { … }` is `xs.map { … }`.
A module function needs parentheses: `xs |> stats.odds()`. `|>` binds more loosely than `||`, groups from the
left, and a line that starts with `|>` continues the previous line.

## Error fallback `?!`

```kotlin
val values = listOf(4, 0, 2)
println(values.map { 8 / it ?! -1 })
println(values[10] ?! 0)
waitTicks(-1) ?! println("negative wait")
```

```text
[2, -1, 4]
0
negative wait
```

`a ?! b` is `a`, but if a runtime error happens while `a` is evaluated (also inside the functions it calls), the
partial work of `a` is dropped and the result is `b`. Both sides have the same type; a statement can use two `Unit`
calls. `?!` binds more loosely than every other operator and groups from the right (`a ?! b ?! c` is
`a ?! (b ?! c)`). Side effects that happened before the error (printed lines, assignments) stay.

The errors that `?!` recovers from come from values: integer overflow, division by zero, a negative `waitTicks`,
an index out of range, and the math, random and vector errors (`CS_RUN_EMPTY_COERCE_RANGE`, `CS_RUN_ROUND_NAN`,
`CS_RUN_INVALID_RANDOM_RANGE`, `CS_RUN_INVALID_TOLERANCE`, `CS_RUN_DIMENSION_MISMATCH`, `CS_RUN_INVALID_DIMENSION`,
`CS_RUN_ZERO_VECTOR`, `CS_RUN_RAGGED_MATRIX`). Limits (stack, call depth, objects, list sizes), invalid bytecode and
reading a property before it is initialized still stop the program, and an error inside a constructor is not
recovered outside that constructor. The ranges are a table in the compiled program; a run that is saved and
restored behaves the same.

## Math, random numbers, vectors and matrices

```kotlin
println(abs(-3))
println(max(2, 5))
println(sqrt(2.0))
println(2.0.pow(10))
println(sin(PI / 2))
println(ln(E))
println(hypot(3.0, 4.0))
println(3.7.roundToInt())
println(15.coerceIn(0, 10))
println(isClose(0.1 + 0.2, 0.3))
randomSeed(7)
println(randomInt(1, 7))
val v = vector(1.0, 2.0, 2.0)
println(length(v))
println(v * 2.0)
println(dot(v, v))
val m = matrix(vector(1.0, 0.0), vector(0.0, 2.0))
println(matmul(m, vector(3.0, 4.0)))
println(m.rowCount)
```

```text
3
5
1.4142135623730951
1024.0
1.0
1.0
5.0
4
10
true
2
3.0
[2.0, 4.0, 4.0]
9.0
[3.0, 8.0]
2
```

- Functions: `abs`, `min`, `max`, `sqrt`, `floor`, `ceil`, `round`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`,
  `atan2`, `sinh`, `cosh`, `tanh`, `ln`, `log10`, `exp`, `hypot`, `sign`, `isClose`. Methods: `x.pow(y)`,
  `x.roundToInt()`, `x.coerceIn(min, max)`, `x.isNaN()`, `x.isInfinite()`. Constants: `PI`, `E`. The results
  are the same as Kotlin's `kotlin.math`.
- Random numbers: `random()`, `random(min, max)`, `randomInt(min, max)` (the maximum is excluded) and
  `randomSeed(seed)`. The sequence is deterministic for a given seed.
- `Vector` (1 to 16 `Double` values) and `Matrix` (up to 8 × 8), both immutable: `vector`, `matrix`, `dot`,
  `length`, `normalize`, `transpose`, `matmul`, `m.rowCount` and `m.columnCount`. Vectors support `+ - * /`
  and unary `-`; matrices support `+ - *`. Scalars must be `Double`.

## Ticks and `waitTicks`

```kotlin
println("tick 0")
waitTicks(20)
println("tick 20")
```

A program runs a limited number of instructions per tick (the CLI's default is 128, at most 4096 with
`--instructions-per-tick`). This budget is separate from the size of the program (the number of bytecode
instructions, see below): a loop can run many more instructions than the program has, over many ticks. `waitTicks(n)` pauses
the program for `n` ticks. The CLI counts ticks logically and does not wait in real time.

## Limits and safety

Every program runs inside fixed limits, so a script can never take unbounded memory or time. There are two
sets of compile-time limits, chosen by the host with `CompileOptions`: **embedded** (`CompileOptions.EMBEDDED`,
the default of `CreateScript.compile(source)`, used by hosts such as the Minecraft mod Create: Analytical) and
**standalone** (`CompileOptions.STANDALONE`, used by the `createscript` command for `run` and the interactive
mode). The standalone limits are in development and not in v0.1.0-alpha, which uses the embedded limits.

| Limit | Embedded | Standalone |
|---|---|---|
| Source length per file | 4096 characters | 1,000,000 characters |
| Tokens per file | 2048 | 250,000 |
| Syntax tree nodes per file | 2048 | 250,000 |
| Imported modules (not counting the file you run) | 16 | 1024 |
| Source length of all files together | 69,632 characters (not a real limit) | 8,000,000 characters |
| Tokens of all files together | 34,816 (not a real limit) | 1,000,000 |
| Bytecode instructions (program size) | 4096 | no fixed limit (see below) |
| Work of the bytecode verification | no extra limit | 100,000,000 steps |
| Chain of properties without a type annotation whose types depend on each other | no extra limit | 128 |

These limits are the same in both settings:

| Limit | Value |
|---|---|
| Nesting of blocks and expressions | 32 |
| Identifier / string literal / integer literal | 32 characters / 256 characters / 10 digits |
| Constants | 256 |
| Variables (top-level, and locals per function) | 64 |
| Operand stack depth | 64 |
| Call depth | 16 |
| Parameters per function | 16 |
| Objects alive at once | 256 |
| Properties per class | 16 |
| Elements per list / in all lists | 256 / 4096 |
| Vector size / matrix size | 16 / 8 × 8 |
| `println` line length | 64 UTF-16 units (longer lines are cut with "…") |

The constants, variables, stack, call depth, parameters, objects, properties and list limits belong to the
virtual machine: the bytecode verifier and the saved-state format of embedded hosts rely on them, so they are
not raised for standalone programs. A large standalone program therefore still uses at most 256 different
constant values and 64 top-level variables.

### Program size

The number of bytecode instructions in a compiled program is a compile-time limit, not a runtime one: the
virtual machine does not check it while running, and the per-tick budget, `--max-ticks`, the stack, the call
depth and the object and list limits work in the same way with any setting. Functions that are never called
are compiled too and count towards the program size.

No fixed limit does not mean that compiling may use unbounded time or memory. In the standalone setting the
program size is bounded by the input limits above, mainly the total of 1,000,000 tokens (the densest code we
found compiles to about 2 instructions per token, so about 2,000,000 instructions). The compiler keeps the syntax tree and the
analysis results of all files until the end, about 330 bytes per token at most. Measured on a development
machine with the `createscript` command:

| Program | Instructions | Time | Memory (resident) |
|---|---|---|---|
| One file of 1,000,000 characters (mostly a comment) | few | 0.07 s | 62 MiB |
| 1024 small modules | a few thousand | 0.6 s | 290 MiB |
| 160 modules of 1000 lines | about 640,000 | 0.4 s | 300 MiB |
| 4 files near the token limits (about 960,000 tokens) | about 1,890,000 | 0.6 s | 530–590 MiB |

These ran with a Java heap of 512 MiB. Larger programs within the limits can need more memory; the Java heap
can be set with `JAVA_OPTS`, for example `JAVA_OPTS=-Xmx2g createscript run main.cscr`.

The bytecode verification checks every compiled program. In the standalone setting it keeps the state only at
the start of each basic block, and it stops with `CS_COMP_PROGRAM_TOO_COMPLEX` after 100,000,000 steps. Normal
programs need a few steps per instruction; a synthetic program shaped to be as slow as possible to verify reaches
the limit at about 2,000,000 instructions after about 2.3 s. Long chains of calls, recursion, imports and
class constructions are handled without deep recursion in the compiler.

If the input limits are raised in the future (longer files, more modules, packages), the limits on compile time
and memory must be designed again together with them.

The language has no access to files, the network, the operating system, Java/Kotlin code or reflection.
For `import`, the host (for example the `createscript` command) reads the module files and gives their text
to the compiler; programs cannot open files themselves.
Errors are reported as diagnostic codes such as `CS_SEM_TYPE_MISMATCH` with a line and column.
