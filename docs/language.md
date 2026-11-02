# The CreateScript language (basics)

CreateScript is a small, statically typed language with a Kotlin-like syntax. Programs are compiled to
bytecode and run on a sandboxed virtual machine that advances in logical **ticks**. The language was first
made for the programmable block of the Minecraft mod *Create: Analytical*. This repository contains the
language itself (`createscript-core`) and a command-line tool (`createscript-cli`); neither needs Minecraft.

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

A program runs a limited number of instructions per tick (the CLI's default is 128). `waitTicks(n)` pauses
the program for `n` ticks. The CLI counts ticks logically and does not wait in real time.

## Limits and safety

Every program runs inside fixed limits, so a script can never take unbounded memory or time:

| Limit | Value |
|---|---|
| Source length | 4096 characters |
| Tokens | 2048 |
| Bytecode instructions | 4096 |
| Constants | 256 |
| Variables | 64 |
| Operand stack depth | 64 |
| Call depth | 16 |
| Parameters per function | 16 |
| Objects alive at once | 256 |
| Properties per class | 16 |
| Elements per list / in all lists | 256 / 4096 |
| Vector size / matrix size | 16 / 8 × 8 |
| `println` line length | 64 UTF-16 units (longer lines are cut with "…") |

The language has no access to files, the network, the operating system, Java/Kotlin code or reflection.
Errors are reported as diagnostic codes such as `CS_SEM_TYPE_MISMATCH` with a line and column.
