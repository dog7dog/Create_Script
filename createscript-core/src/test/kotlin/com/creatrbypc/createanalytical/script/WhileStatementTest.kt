package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Console
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// 第3.7d段階の while を、ソースから CS_VM・Scheduler まで通して確かめる
class WhileStatementTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は Scheduler と同じく1 tick ずつ数え、それ以外は予算 1000 ずつ実行する。かかった tick 数も返す
    private fun runToEnd(source: String): Pair<CsVm, Int> {
        val vm = CsVm(compile(source))
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1000)
            check(++ticks < 10_000) { "did not finish: $source" }
        }
        return vm to ticks
    }

    private fun output(source: String): List<String> {
        val (vm, _) = runToEnd(source)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    @Test
    fun whileFalseAndEmptyBodies() {
        assertEquals(listOf("after"), output("while (false) {\n  println(\"never\")\n}\nprintln(\"after\")"))
        // 空の本体: 条件が最初から false なら何もしない
        assertEquals(listOf("0"), output("var i = 0\nwhile (i > 0) { }\nprintln(i)"))
        // 条件の中で短絡評価された右辺は評価されない
        assertEquals(listOf("done"), output("val z = 0\nwhile (z != 0 && 1 / z > 0) { }\nprintln(\"done\")"))
    }

    @Test
    fun finiteLoops() {
        assertEquals(listOf("0", "1", "2"), output("var i = 0\nwhile (i < 3) {\n  println(i)\n  i = i + 1\n}"))
        // 10 までの和、Int の範囲の端まで数える
        assertEquals(listOf("55"), output("var i = 1\nvar sum = 0\nwhile (i <= 10) { sum = sum + i\ni = i + 1 }\nprintln(sum)"))
        assertEquals(listOf("2147483647"), output("var n = 2147483640\nwhile (n < 2147483647) { n = n + 1 }\nprintln(n)"))
        // ループ中のオーバーフローは実行時エラー（行はループの中）
        val (overflow, _) = runToEnd("var n = 2147483646\nwhile (true) {\n  n = n + 1\n}")
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, overflow.error?.code)
        assertEquals(3, overflow.error?.position?.line)
        // Boolean の変数を条件に使う
        assertEquals(listOf("3"), output("var running = true\nvar k = 0\nwhile (running) { k = k + 1\nrunning = k < 3 }\nprintln(k)"))
    }

    @Test
    fun nestedLoopsAndIfInside() {
        assertEquals(
            listOf("11", "12", "21", "22", "even", "odd", "even"),
            output(
                "var a = 1\nwhile (a <= 2) {\n  var b = 1\n  while (b <= 2) {\n    println(a * 10 + b)\n    b = b + 1\n  }\n  a = a + 1\n}\n" +
                    "var n = 2\nwhile (n <= 4) {\n  if (n % 2 == 0) {\n    println(\"even\")\n  } else {\n    println(\"odd\")\n  }\n  n = n + 1\n}",
            ),
        )
    }

    @Test
    fun loopBodyScopes() {
        // 本体の変数は反復ごとに宣言の初期化式で入れ直す。外側の var への代入は残る
        assertEquals(listOf("0", "10", "20", "3"), output("var i = 0\nwhile (i < 3) {\n  val x = i * 10\n  println(x)\n  i = i + 1\n}\nprintln(i)"))
        assertEquals(listOf("1", "1", "1"), output("var i = 0\nwhile (i < 3) {\n  var count = 0\n  count = count + 1\n  println(count)\n  i = i + 1\n}"))
        // 本体での同名の宣言は外側を隠し、ループの後は外側に戻る
        assertEquals(listOf("inner", "inner", "outer"), output("val s = \"outer\"\nvar i = 0\nwhile (i < 2) {\n  val s = \"inner\"\n  println(s)\n  i = i + 1\n}\nprintln(s)"))
    }

    // waitTicks はループの中でも従来どおり（待機を数えた tick 数ぶん進む）
    @Test
    fun waitTicksInsideLoops() {
        val (vm, ticks) = runToEnd("var i = 0\nwhile (i < 3) {\n  println(i)\n  waitTicks(5)\n  i = i + 1\n}\nprintln(\"end\")")
        assertEquals(listOf("0", "1", "2", "end"), vm.console.history)
        assertTrue(ticks >= 15, "ticks $ticks")
        // waitTicks(0) のループも予算の範囲で進む
        assertEquals(listOf("3"), output("var i = 0\nwhile (i < 3) { waitTicks(0)\ni = i + 1 }\nprintln(i)"))
    }

    // ---- 無限ループと Scheduler ----

    @Test
    fun infiniteLoopsStayWithinBudget() {
        // while (true) { } は1反復 3 命令。予算ちょうどで制御を返し、状態は RUNNING のまま
        val spin = CsVm(compile("while (true) { }"))
        assertEquals(16, spin.run(16))
        assertEquals(VmState.RUNNING, spin.state)
        assertEquals(1, spin.run(1))
        assertEquals(VmState.RUNNING, spin.state)
        // 出力し続けるループも、コンソールは最大 32 行で止まらない
        val printer = CsVm(compile("var i = 0\nwhile (true) { println(i)\ni = i + 1 }"))
        repeat(100) { assertEquals(16, printer.run(16)) }
        assertEquals(Console.MAX_LINES, printer.console.history.size)
        assertEquals(VmState.RUNNING, printer.state)
    }

    // コンパイルした無限ループを多数登録しても、全体予算・動力段階の上限・公平性が保たれ、終わるプログラムは予定どおり終わる
    @Test
    fun infiniteLoopsShareSchedulerFairly() {
        val spin = compile("while (true) { }")
        val busy = compile("var i = 0\nwhile (true) { i = i + 1\nif (i > 1000) { i = 0 } }")
        val scheduler = Scheduler<Int>(4096)
        repeat(999) { scheduler.register(it, CsVm(if (it % 2 == 0) spin else busy)) }
        val finite = CsVm(compile("var i = 0\nwhile (i < 10) { i = i + 1 }\nprintln(i)"))
        scheduler.register(999, finite)
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(1000)
        var finishedAt = -1
        repeat(40) { tick ->
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            if (999 in result.finished) finishedAt = tick
            // 終わったプログラム以外は、毎 tick の累計の差が 1 命令以内
            val running = totals.toList().dropLast(1)
            assertTrue(running.max() - running.min() <= 1, "unfair at $tick: ${running.min()}..${running.max()}")
        }
        // 10 回のループ（約 70 命令）は、1000 台で 4096 を分けても数十 tick で終わる
        assertTrue(finishedAt in 0 until 40, "finite program finished at $finishedAt")
        assertEquals(listOf("10"), finite.console.history)
    }

    // 1 tick あたりの Scheduler の処理時間（1000 台の無限ループ、参考値）
    @Test
    fun measureInfiniteLoopTicks() {
        val busy = compile("var i = 0\nwhile (true) { i = i + 1\nif (i > 1000) { i = 0 } }")
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(busy)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(200) { scheduler.tick { fast } }
        val start = System.nanoTime()
        repeat(1000) { scheduler.tick { fast } }
        val millis = (System.nanoTime() - start) / 1_000_000.0 / 1000
        println("[3.7d measure] scheduler tick with 1000 infinite loops (budget 4096): ${"%.3f".format(millis)} ms per tick")
        // 最大規模の while の入れ子（ブロック 30 段。最も内側の val v29 = n + 1 で深さの合計 32）のコンパイル（検証込み）と検証
        val nested = "var n = 0\n" + (0 until 30).joinToString("") { "while (n < $it) {\nval v$it = n + 1\n" } + "n = v29" + "\n}".repeat(30)
        val program = compile(nested)
        repeat(50) { compile(nested) }
        val compileStart = System.nanoTime()
        repeat(200) { compile(nested) }
        val compileMillis = (System.nanoTime() - compileStart) / 1_000_000.0 / 200
        val verifyStart = System.nanoTime()
        repeat(200) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0 / 200
        println(
            "[3.7d measure] nested while (30 blocks): ${program.instructions.size} instructions, ${program.variableCount} variables, " +
                "compile ${"%.3f".format(compileMillis)} ms (verification included), verification ${"%.3f".format(verifyMillis)} ms",
        )
    }

    // ---- 無作為に作った while・if・break・continue のプログラムと、Kotlin で書いた参照の実行器の比較 ----

    private sealed interface Stmt

    private data class Add(val amount: Int) : Stmt
    private data class Print(val name: String) : Stmt
    private data class Local(val name: String, val add: Int) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class When(val threshold: Int, val then: List<Stmt>, val otherwise: List<Stmt>?) : Stmt

    // counter はこのループ専用の変数名（ループの直前に var counter = 0 を置き、本体の先頭で 1 増やす。continue で増やし忘れない）
    private data class Loop(val counter: String, val count: Int, val body: List<Stmt>) : Stmt

    // for (variable in total + startOffset kind total + endOffset) { body }（第3.8b段階。kind は ".." / "until" / "downTo"）
    private data class RangeLoop(val variable: String, val kind: String, val startOffset: Int, val endOffset: Int, val body: List<Stmt>) : Stmt

    // if (total > threshold) { break / continue }（第3.8a段階。ループの中にだけ置く）
    private data class Exit(val isBreak: Boolean, val threshold: Int) : Stmt

    private class BreakSignal : RuntimeException(null, null, false, false)

    private class ContinueSignal : RuntimeException(null, null, false, false)

    private class Reference {
        val output = ArrayList<String>()
        var total = 1
        var waited = 0
        private val scopes = ArrayList<HashMap<String, Int>>().also { it.add(HashMap()) }

        private fun lookup(name: String) = if (name == "total") total else scopes.asReversed().firstNotNullOf { it[name] }

        fun run(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Add -> total += statement.amount
                    is Print -> output.add(lookup(statement.name).toString())
                    is Local -> scopes.last()[statement.name] = total + statement.add
                    is Wait -> waited += statement.ticks
                    is When -> (if (total > statement.threshold) statement.then else statement.otherwise)?.let(::inBlock)
                    is Loop -> {
                        scopes.last()[statement.counter] = 0
                        while (lookup(statement.counter) < statement.count) {
                            scopes.asReversed().first { statement.counter in it }[statement.counter] = lookup(statement.counter) + 1
                            try {
                                inBlock(statement.body)
                            } catch (signal: BreakSignal) {
                                break
                            } catch (signal: ContinueSignal) {
                                continue
                            }
                        }
                    }
                    // 参照の実行器は Kotlin 自身の範囲（.. / until / downTo）を使う。端点はループの開始時に1回だけ計算する
                    is RangeLoop -> {
                        val start = total + statement.startOffset
                        val end = total + statement.endOffset
                        val range = when (statement.kind) {
                            ".." -> start..end
                            "until" -> start until end
                            else -> start downTo end
                        }
                        for (value in range) {
                            scopes.add(hashMapOf(statement.variable to value))
                            try {
                                inBlock(statement.body)
                            } catch (signal: BreakSignal) {
                                break
                            } catch (signal: ContinueSignal) {
                                continue
                            } finally {
                                scopes.removeAt(scopes.lastIndex)
                            }
                        }
                    }
                    is Exit -> if (total > statement.threshold) throw if (statement.isBreak) BreakSignal() else ContinueSignal()
                }
            }
        }

        // break / continue でブロックを途中で抜けても、ブロックのスコープは閉じる
        private fun inBlock(statements: List<Stmt>) {
            scopes.add(HashMap())
            try {
                run(statements)
            } finally {
                scopes.removeAt(scopes.lastIndex)
            }
        }
    }

    private class Generator(private val random: Random) {
        private var counters = 0

        fun block(depth: Int, visible: Set<String>, inLoop: Boolean = false): List<Stmt> {
            val names = visible.toMutableSet()
            val declared = HashSet<String>()
            val statements = ArrayList<Stmt>()
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(if (inLoop) 11 else 9)) {
                    0 -> statements.add(Add(random.nextInt(-3, 4)))
                    1 -> statements.add(Print(names.random(random)))
                    2 -> listOf("a", "b").filter { it !in declared }.randomOrNull(random)?.let {
                        declared.add(it)
                        names.add(it)
                        statements.add(Local(it, random.nextInt(-2, 3)))
                    }
                    3 -> statements.add(Wait(random.nextInt(0, 3)))
                    4 -> if (depth < 3) {
                        statements.add(When(random.nextInt(-3, 4), block(depth + 1, names, inLoop), if (random.nextBoolean()) block(depth + 1, names, inLoop) else null))
                    }
                    5, 6 -> if (depth < 3 && counters < 20) {
                        val counter = "i${counters++}"
                        statements.add(Loop(counter, random.nextInt(0, 4), block(depth + 1, names + counter, inLoop = true)))
                        names.add(counter)
                    }
                    7, 8 -> if (depth < 3 && counters < 20) {
                        val variable = "i${counters++}"
                        val kind = listOf("..", "until", "downTo").random(random)
                        statements.add(RangeLoop(variable, kind, random.nextInt(-2, 3), random.nextInt(-2, 3), block(depth + 1, names + variable, inLoop = true)))
                    }
                    else -> statements.add(Exit(random.nextBoolean(), random.nextInt(-3, 6)))
                }
            }
            return statements
        }
    }

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        when (statement) {
            is Add -> "${indent}total = total + ${statement.amount}\n"
            is Print -> "${indent}println(${statement.name})\n"
            is Local -> "${indent}val ${statement.name} = total + ${statement.add}\n"
            is Wait -> "${indent}waitTicks(${statement.ticks})\n"
            is When -> "${indent}if (total > ${statement.threshold}) {\n" + render(statement.then, "$indent  ") + "$indent}" +
                (statement.otherwise?.let { " else {\n" + render(it, "$indent  ") + "$indent}" } ?: "") + "\n"
            is Loop -> "${indent}var ${statement.counter} = 0\n${indent}while (${statement.counter} < ${statement.count}) {\n" +
                "$indent  ${statement.counter} = ${statement.counter} + 1\n" + render(statement.body, "$indent  ") + "$indent}\n"
            is RangeLoop -> "${indent}for (${statement.variable} in total + ${statement.startOffset} ${statement.kind} total + ${statement.endOffset}) {\n" +
                render(statement.body, "$indent  ") + "$indent}\n"
            is Exit -> "${indent}if (total > ${statement.threshold}) { ${if (statement.isBreak) "break" else "continue"} }\n"
        }
    }.replace("+ -", "- ")

    @Test
    fun randomLoopProgramsMatchReference() {
        val random = Random(43)
        var compiled = 0
        var withLoops = 0
        var withBreak = 0
        var withContinue = 0
        var withFor = 0
        repeat(600) {
            val statements = Generator(random).block(0, setOf("total"))
            val source = "var total = 1\n" + render(statements, "") + "println(total)"
            if (source.length > 4096) return@repeat
            val result = Compiler.compileSource(source)
            // 字句・構文・変数の数の上限を超えたものは対象外
            val limits = setOf(DiagnosticCode.LEX_TOO_MANY_TOKENS, DiagnosticCode.PARSE_TOO_MANY_NODES, DiagnosticCode.COMP_TOO_MANY_VARIABLES)
            if (result.diagnostics.any { it.code in limits }) return@repeat
            val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }}\n$source")
            assertNotNull(SnapshotVerifier.framesOf(program), source)
            val expected = Reference().also { it.run(statements) }
            val (vm, ticks) = runToEnd(source)
            assertEquals(VmState.HALTED, vm.state, source)
            assertEquals((expected.output + expected.total.toString()).takeLast(Console.MAX_LINES), vm.console.history, source)
            assertTrue(ticks >= expected.waited, source)
            compiled++
            // 入れ子の中を含め、ループを1つ以上持つプログラム
            if ("while (" in source) withLoops++
            if ("{ break }" in source) withBreak++
            if ("{ continue }" in source) withContinue++
            if ("for (" in source) withFor++
        }
        assertTrue(compiled >= 500, "compiled $compiled")
        assertTrue(withLoops >= 200, "programs with loops: $withLoops")
        assertTrue(withBreak >= 50 && withContinue >= 50 && withFor >= 100, "break $withBreak, continue $withContinue, for $withFor")
        println("[3.8b random] $compiled programs compared, $withLoops with while, $withFor with for, $withBreak with break, $withContinue with continue")
    }
}
