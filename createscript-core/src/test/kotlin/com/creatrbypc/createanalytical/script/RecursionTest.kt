package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Console
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.FunctionInfo
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.SnapshotProblem
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// 第3.8e段階の再帰（直接・相互）を、ソースから CS_VM・Scheduler・SnapshotVerifier まで通して確かめる
class RecursionTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する
    private fun runToEnd(vm: CsVm, maxTicks: Int = 100_000): Int {
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1000)
            check(++ticks < maxTicks) { "did not finish" }
        }
        return ticks
    }

    private fun run(source: String): CsVm = CsVm(compile(source)).also { runToEnd(it) }

    private fun output(source: String): List<String> {
        val vm = run(source)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private val factorial = "fun factorial(n: Int): Int {\n    if (n <= 1) {\n        return 1\n    }\n    return n * factorial(n - 1)\n}\n"
    private val parity = "fun isEven(n: Int): Boolean {\n  if (n == 0) { return true }\n  return isOdd(n - 1)\n}\nfun isOdd(n: Int): Boolean {\n  if (n == 0) { return false }\n  return isEven(n - 1)\n}\n"

    // 再帰の深さ n の関数（sum(n) は n 段の呼び出し）
    private val sum = "fun sum(n: Int): Int {\n  if (n == 0) { return 0 }\n  return n + sum(n - 1)\n}\n"

    // ---- 正常系 ----

    @Test
    fun factorialAndParity() {
        assertEquals(listOf("120"), output(factorial + "println(factorial(5))"))
        assertEquals(listOf("1", "1", "479001600"), output(factorial + "println(factorial(0))\nprintln(factorial(1))\nprintln(factorial(12))"))
        assertEquals(listOf("true", "false", "false", "true"), output(parity + "println(isEven(10))\nprintln(isEven(7))\nprintln(isOdd(4))\nprintln(isOdd(13))"))
    }

    // 各段のローカル変数・引数は独立する（深い段から戻った後も、その段の mine は変わらない。共有していれば 10, 10, 10 になる）
    @Test
    fun localsAreIndependentAtEveryLevel() {
        assertEquals(
            listOf("3", "2", "1", "10", "20", "30"),
            output("fun visit(n: Int) {\n  if (n == 0) { return }\n  val mine = n * 10\n  println(n)\n  visit(n - 1)\n  println(mine)\n}\nvisit(3)"),
        )
    }

    // 再帰の戻り値を式の中で使う・別の再帰関数の引数にする
    @Test
    fun recursiveResultsInExpressions() {
        val fib = "fun fib(n: Int): Int {\n  if (n < 2) { return n }\n  return fib(n - 1) + fib(n - 2)\n}\n"
        // fib(5) * 2 + fib(fib(5)) = 10 + fib(5) = 15
        assertEquals(listOf("55", "15"), output(fib + "println(fib(10))\nprintln(fib(5) * 2 + fib(fib(5)))"))
        assertEquals(listOf("6", "true"), output(factorial + parity + "println(factorial(factorial(3)) / 120)\nprintln(isEven(factorial(3)))"))
    }

    // Unit の関数の再帰・再帰とループ・条件分岐の組み合わせ・再帰の中の待機
    @Test
    fun unitRecursionLoopsAndWaits() {
        assertEquals(listOf("3", "2", "1", "go"), output("fun countdown(n: Int) {\n  if (n == 0) { return }\n  println(n)\n  countdown(n - 1)\n}\ncountdown(3)\nprintln(\"go\")"))
        // 各段で for を回し、ループの中から再帰する（2 + 2 * (2 + 2 * 2) = 14 回の出力）
        assertEquals(
            List(14) { "x" },
            output("fun tree(depth: Int) {\n  for (i in 0 until 2) {\n    println(\"x\")\n    if (depth > 0) { tree(depth - 1) }\n  }\n}\ntree(2)"),
        )
        // while と早期 return の組み合わせ
        assertEquals(listOf("4"), output("fun digits(n: Int): Int {\n  var m = n\n  while (m >= 10) {\n    return 1 + digits(m / 10)\n  }\n  return 1\n}\nprintln(digits(1234))"))
        val vm = CsVm(compile("fun slow(n: Int): Int {\n  if (n == 0) { return 0 }\n  waitTicks(2)\n  return 1 + slow(n - 1)\n}\nprintln(slow(5))"))
        val ticks = runToEnd(vm)
        assertEquals(listOf("5"), vm.console.history)
        assertTrue(ticks >= 10, "ticks $ticks")
    }

    // ---- 深さ・スタックの上限 ----

    @Test
    fun depthLimitStopsRecursionSafely() {
        // 16 段まで実行でき、17 段目の呼び出しで CS_RUN_CALL_DEPTH_EXCEEDED（引数: 上限 16。位置は呼び出し）
        assertEquals(listOf("120"), output(sum + "println(sum(15))"))
        assertEquals(VmLimits.MAX_CALL_DEPTH, 16)
        val atLimit = CsVm(compile(sum + "println(sum(16))"))
        runToEnd(atLimit)
        assertEquals(VmState.ERROR, atLimit.state)
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, atLimit.error?.code)
        assertEquals(3, atLimit.error?.position?.line)
        assertEquals(listOf(16), atLimit.error?.arguments?.map { (it as com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument.IntArgument).value })
        // 終了条件の無い再帰（直接・相互・Unit）も、16 段で止まる。それまでの命令数はわずか
        for (source in listOf("fun f() { f() }\nf()", "fun a(n: Int): Int { return b(n) }\nfun b(n: Int): Int { return a(n + 1) }\nprintln(a(0))", "fun g(): Int { return 1 + g() }\nprintln(g())")) {
            val vm = CsVm(compile(source))
            val executed = vm.run(10_000)
            assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, vm.error?.code, source)
            assertTrue(executed < 200, "$source: $executed")
        }
        // 実行時エラーの後は何も実行しない
        val stopped = CsVm(compile("fun f() { f() }\nf()"))
        stopped.run(10_000)
        assertEquals(0, stopped.run(10_000))
    }

    // 各段で呼び出し元の計算途中の値が残る再帰は、VM のスタックの上限 64 で CS_RUN_STACK_OVERFLOW になる（呼び出しの深さより先に）
    @Test
    fun stackLimitStopsRecursionSafely() {
        val heavy = "fun heavy(n: Int): Int {\n  if (n == 0) { return 0 }\n  return 1 + (1 + (1 + (1 + (1 + (1 + heavy(n - 1))))))\n}\n"
        // 6 値 × 9 段 = 54 は収まる。12 段では 72 になり上限を超える
        assertEquals(listOf("54"), output(heavy + "println(heavy(9))"))
        val vm = run(heavy + "println(heavy(12))")
        assertEquals(DiagnosticCode.RUN_STACK_OVERFLOW, vm.error?.code)
        // 再帰に入らない呼び出しの連鎖は、従来どおりコンパイル時に深さ・スタックを確かめる（再帰の関数から呼ばれていても）
        val chain = (1..17).joinToString("") { "fun c$it() {\n" + (if (it < 17) "c${it + 1}()\n" else "println(1)\n") + "}\n" }
        assertEquals(listOf(DiagnosticCode.COMP_CALL_TOO_DEEP), Compiler.compileSource(chain + "fun r(n: Int) {\n  if (n > 0) { r(n - 1) }\n  c1()\n}\nr(1)").diagnostics.map { it.code })
    }

    // 命令予算: 無限の再帰も 1 回の実行で予算の数しか進まない。再帰するプログラムを多数並べても公平に配分される
    @Test
    fun recursionStaysWithinBudgetAndFair() {
        val vm = CsVm(compile("fun f() { f() }\nf()"))
        assertEquals(5, vm.run(5))
        assertEquals(VmState.RUNNING, vm.state)
        val fibLoop = compile("fun fib(n: Int): Int {\n  if (n < 2) { return n }\n  return fib(n - 1) + fib(n - 2)\n}\nwhile (true) { fib(10) }")
        val parityLoop = compile(parity + "var k = 0\nwhile (true) {\n  isEven(k)\n  k = (k + 1) % 15\n}")
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) fibLoop else parityLoop)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(600)
        repeat(30) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }

    // 呼び出しの関係が循環しても、意味解析・コンパイル・検証は止まる。ソースの上限（4096 単位）近くの 190 個の関数の
    // 長い連鎖・大きな循環でも JVM のスタックを使い切らない
    @Test
    fun analysisTerminatesOnLargeCallGraphs() {
        fun functions(count: Int, closed: Boolean) =
            (1..count).joinToString("") { "fun c$it() { " + (if (it < count) "c${it + 1}()" else if (closed) "c1()" else "") + " }\n" } + "c1()"
        // 再帰に入らない連鎖はコンパイル時の深さの確認で拒否する
        assertEquals(listOf(DiagnosticCode.COMP_CALL_TOO_DEEP), Compiler.compileSource(functions(190, closed = false)).diagnostics.map { it.code })
        // 190 個の循環はコンパイルでき、実行すると 16 段で止まる
        val cycle = Compiler.compileSource(functions(190, closed = true))
        assertNotNull(cycle.program, "${cycle.diagnostics.map { it.code }}")
        val vm = CsVm(cycle.program!!)
        vm.run(10_000)
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, vm.error?.code)
    }

    // ---- 手で組んだ不正なバイトコード（循環する呼び出し） ----

    private val origin = SourcePosition(0, 1, 1)

    private fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)

    @Test
    fun invalidCyclicBytecodeIsUnverifiable() {
        val constants = listOf(Constant.IntConstant(1))
        fun program(vararg instructions: Instruction, functions: List<FunctionInfo>) = CompiledProgram(instructions.toList(), constants, 0, 4, functions)
        // 正しい形: 引数 1 つの関数が自分を呼び、結果の Int を返す
        val valid = program(
            op(Opcode.PUSH_CONST), op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT),
            op(Opcode.LOAD_LOCAL), op(Opcode.CALL), op(Opcode.RETURN, 1),
            functions = listOf(FunctionInfo(4, 1, 1)),
        )
        assertNotNull(SnapshotVerifier.framesOf(valid))
        val cases = mapOf(
            // 自分を呼ぶときに引数の型が変わる（トップレベルは Int、自分の中では Boolean）
            "argument type changes in recursion" to program(
                op(Opcode.PUSH_CONST), op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT),
                op(Opcode.PUSH_BOOL, 1), op(Opcode.CALL), op(Opcode.RETURN, 1),
                functions = listOf(FunctionInfo(4, 1, 1)),
            ),
            // 相互再帰（f1 は条件で f0 を呼ぶか、そのまま戻る）で、戻った値をスタックに残したまま Unit を返す
            "mutual recursion leaves values" to program(
                op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT),
                op(Opcode.CALL, 1), op(Opcode.POP), op(Opcode.RETURN),
                op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 11), op(Opcode.PUSH_CONST), op(Opcode.CALL), op(Opcode.RETURN), op(Opcode.RETURN),
                functions = listOf(FunctionInfo(3, 0), FunctionInfo(6, 0)),
            ),
            // 自分の結果（Int）を、自分の中で Boolean として使う（条件でそのまま Int を返す経路がある）
            "result type conflicts with itself" to program(
                op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT),
                op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 10), op(Opcode.CALL), op(Opcode.NOT), op(Opcode.POP), op(Opcode.PUSH_CONST), op(Opcode.RETURN, 1),
                op(Opcode.PUSH_CONST), op(Opcode.RETURN, 1),
                functions = listOf(FunctionInfo(3, 0)),
            ),
        )
        for ((label, program) in cases) assertNull(SnapshotVerifier.framesOf(program), label)
        // 戻る経路の無い相互再帰では、呼び出しの後の命令に到達しない（実際に戻らない）ため、その先の不正な命令は検証の対象にならない
        val neverReturns = program(
            op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT),
            op(Opcode.CALL, 1), op(Opcode.RETURN),
            op(Opcode.PUSH_CONST), op(Opcode.CALL), op(Opcode.RETURN),
            functions = listOf(FunctionInfo(3, 0), FunctionInfo(5, 0)),
        )
        val frames = SnapshotVerifier.framesOf(neverReturns)!!
        // CALL の次（POP・HALT・RETURN）にはどれも到達しない
        assertEquals(listOf(1, 2, 4, 7), frames.indices.filter { frames[it] == null })
        // 検証しないで実行しても（念のため）、VM は呼び出しの深さで止まる
        val runaway = CsVm(cases.getValue("mutual recursion leaves values"))
        runaway.run(10_000)
        assertEquals(VmState.ERROR, runaway.state)
    }

    // 循環する呼び出しでも、検証の処理回数は上限以内（上限を小さくすると打ち切る）
    @Test
    fun verificationOfRecursionStopsWithinTheStepLimit() {
        val program = compile(parity + factorial + "println(isEven(factorial(4)))")
        val frames = SnapshotVerifier.framesOf(program)
        assertNotNull(frames)
        var low = 0L
        var high = SnapshotVerifier.maxSteps(program)
        while (low < high) {
            val middle = (low + high) / 2
            if (SnapshotVerifier.framesOf(program, middle) != null) high = middle else low = middle + 1
        }
        assertTrue(low <= SnapshotVerifier.maxSteps(program))
        assertNull(SnapshotVerifier.framesOf(program, low - 1))
    }

    // ---- 再帰のフレームを含むスナップショット ----

    // factorial(4) の最も深い段の待機中（同じ関数のフレームが 4 つ。呼び出し元の区間はそれぞれ [n]）
    private val waitingFactorial = compile("fun fact(n: Int): Int {\n  if (n <= 1) {\n    waitTicks(3)\n    return 1\n  }\n  return n * fact(n - 1)\n}\nprintln(fact(4))")

    private fun deepestWait(): VmSnapshot {
        val vm = CsVm(waitingFactorial)
        while (vm.state != VmState.WAITING) vm.run(1)
        return vm.snapshot()!!
    }

    private fun finish(vm: CsVm): List<String> {
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1)
        return vm.console.history
    }

    @Test
    fun recursiveFramesAreVerified() {
        val waiting = deepestWait()
        assertEquals(listOf(0, 0, 0, 0), waiting.callStack.map { it.function })
        assertEquals(listOf(4, 3, 2, 1), waiting.callStack.map { (it.locals[0] as Value.IntValue).value })
        assertEquals(listOf<Value>(Value.IntValue(4), Value.IntValue(3), Value.IntValue(2)), waiting.stack)
        assertNull(SnapshotVerifier.verify(waitingFactorial, waiting))
        assertEquals(listOf("24"), finish(CsVm.restore(waitingFactorial, waiting)!!))
        fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(waitingFactorial, snapshot)
        val frames = waiting.callStack
        // トップレベルからの呼び出しを指すフレームが途中にある・自分の中からの呼び出しを指すフレームが先頭にある
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(waiting.copy(callStack = listOf(frames[0], frames[0], frames[2], frames[3]))))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(waiting.copy(callStack = listOf(frames[1], frames[1], frames[2], frames[3]))))
        // 戻り先が CALL の次ではない
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(waiting.copy(callStack = frames.toMutableList().also { it[2] = it[2].copy(returnPc = it[2].returnPc + 1) })))
        // フレームを 1 つ除く・足す（スタックの区間も合わなくなる）、上限 16 を超える
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(callStack = frames.drop(1).let { listOf(frames[0]) + it.drop(1) })))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(callStack = frames + frames[3])))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(waiting.copy(callStack = listOf(frames[0]) + List(VmLimits.MAX_CALL_DEPTH) { frames[1] })))
        // 引数が未初期化・型が違う、呼び出し元の値の型が違う・足りない
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(callStack = frames.toMutableList().also { it[1] = it[1].copy(locals = listOf(null)) })))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(callStack = frames.toMutableList().also { it[3] = it[3].copy(locals = listOf(Value.BoolValue(true))) })))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(stack = listOf(Value.IntValue(4), Value.BoolValue(false), Value.IntValue(2)))))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(stack = waiting.stack.dropLast(1))))
    }

    // 保存・復元を繰り返しても結果は変わらない（各命令の境界で保存 → 復元したものをさらに進めて保存 → 復元）
    @Test
    fun repeatedSaveAndRestoreKeepsTheResult() {
        for (source in listOf(factorial + "println(factorial(6))", parity + "println(isEven(9))", "fun countdown(n: Int) {\n  if (n == 0) { return }\n  waitTicks(1)\n  println(n)\n  countdown(n - 1)\n}\ncountdown(4)")) {
            val program = compile(source)
            val expected = finish(CsVm(program))
            var vm = CsVm(program)
            var steps = 0
            while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
                vm = CsVm.restore(program, vm.snapshot()!!) ?: error("rejected at $steps of $source")
                if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1)
                steps++
            }
            assertEquals(expected, vm.console.history, source)
        }
    }

    // ---- 性能（参考値） ----

    @Test
    fun measureRecursion() {
        val fibLoop = compile("fun fib(n: Int): Int {\n  if (n < 2) { return n }\n  return fib(n - 1) + fib(n - 2)\n}\nwhile (true) { fib(12) }")
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(fibLoop)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(200) { scheduler.tick { fast } }
        val start = System.nanoTime()
        repeat(1000) { scheduler.tick { fast } }
        println("[3.8e measure] scheduler tick with 1000 VMs running recursive fib (budget 4096): ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 1000)} ms per tick")
        // 1 台で fib(15)（呼び出し 1973 回）を最後まで実行する時間
        val fib15 = compile("fun fib(n: Int): Int {\n  if (n < 2) { return n }\n  return fib(n - 1) + fib(n - 2)\n}\nprintln(fib(15))")
        repeat(20) { runToEnd(CsVm(fib15)) }
        val runStart = System.nanoTime()
        repeat(100) { runToEnd(CsVm(fib15)) }
        val vm = CsVm(fib15).also { runToEnd(it) }
        assertEquals(listOf("610"), vm.console.history)
        println("[3.8e measure] fib(15) to the end on one VM: ${"%.3f".format((System.nanoTime() - runStart) / 1_000_000.0 / 100)} ms")
        // 相互再帰を含む関数の多いプログラムのコンパイル・検証
        val many = parity + factorial + (1..40).joinToString("") { "fun m$it(n: Int): Int {\n  if (n <= 0) { return $it }\n  return m${it % 40 + 1}(n - 1) + 1\n}\n" } + "println(m1(10))\n"
        val program = compile(many)
        repeat(50) { compile(many) }
        val compileStart = System.nanoTime()
        repeat(200) { compile(many) }
        val compileMillis = (System.nanoTime() - compileStart) / 1_000_000.0 / 200
        val verifyStart = System.nanoTime()
        repeat(200) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0 / 200
        println(
            "[3.8e measure] ${program.functions.size} recursive functions (cycle of 40), ${program.instructions.size} instructions, ${many.length} UTF-16 units: " +
                "compile ${"%.3f".format(compileMillis)} ms (verification included), verification ${"%.3f".format(verifyMillis)} ms",
        )
        // 16 段の再帰の途中（待機中）のスナップショットの保存・検証・復元
        val deep = compile("fun dive(n: Int): Int {\n  if (n == 0) {\n    waitTicks(1)\n    return 0\n  }\n  return 1 + dive(n - 1)\n}\nprintln(dive(15))")
        val diving = CsVm(deep)
        while (diving.state != VmState.WAITING) diving.run(1)
        assertEquals(16, diving.snapshot()!!.callStack.size)
        repeat(200) { assertNotNull(CsVm.restore(deep, diving.snapshot()!!)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(deep, diving.snapshot()!!) }
        println("[3.8e measure] snapshot + verify + restore with 16 recursive frames: ${"%.3f".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000)} ms per VM")
    }

    // ---- 無作為に作った再帰のプログラムと、Kotlin で書いた参照の実行器の比較 ----

    private sealed interface Expr

    private data class Literal(val value: Int) : Expr
    private data class Name(val name: String) : Expr
    private data class Plus(val isPlus: Boolean, val left: Expr, val right: Expr) : Expr

    // f番号(n - 1, 値)。どの関数も（自分・番号の小さい関数も）呼べる。1つ目の引数が必ず減るため、深さは n + 1 段まで
    private data class Call(val function: Int, val value: Expr) : Expr

    private sealed interface Stmt

    private data class AddTotal(val value: Expr) : Stmt
    private data class Print(val value: Expr) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class When(val threshold: Int, val then: List<Stmt>, val otherwise: List<Stmt>?) : Stmt
    private data class Repeat(val variable: String, val count: Int, val body: List<Stmt>) : Stmt
    private data class Return(val value: Expr) : Stmt

    // fun f番号(n: Int, a: Int): Int { if (n <= 0) { return a + 番号 }; 本体; return 結果 }
    private data class Function(val body: List<Stmt>, val result: Expr)

    private class ReturnSignal(val value: Int) : RuntimeException(null, null, false, false)

    // VM の呼び出しの深さの上限（17 段目の呼び出しで止まる）
    private class DepthExceeded : RuntimeException(null, null, false, false)

    private class Reference(private val functions: List<Function>) {
        val output = ArrayList<String>()
        var total = 1
        var waited = 0
        private var depth = 0

        private fun lookup(scopes: List<Map<String, Int>>, name: String) = if (name == "total") total else scopes.asReversed().firstNotNullOf { it[name] }

        fun eval(expression: Expr, scopes: List<Map<String, Int>>): Int = when (expression) {
            is Literal -> expression.value
            is Name -> lookup(scopes, expression.name)
            is Plus -> {
                val left = eval(expression.left, scopes)
                val right = eval(expression.right, scopes)
                if (expression.isPlus) Math.addExact(left, right) else Math.subtractExact(left, right)
            }
            is Call -> {
                val n = Math.subtractExact(lookup(scopes, "n"), 1)
                call(expression.function, n, eval(expression.value, scopes))
            }
        }

        fun call(function: Int, n: Int, a: Int): Int {
            if (depth == VmLimits.MAX_CALL_DEPTH) throw DepthExceeded()
            depth++
            try {
                if (n <= 0) return Math.addExact(a, function)
                val scopes = arrayListOf<MutableMap<String, Int>>(hashMapOf("n" to n, "a" to a))
                return try {
                    block(functions[function].body, scopes)
                    eval(functions[function].result, scopes)
                } catch (signal: ReturnSignal) {
                    signal.value
                }
            } finally {
                depth--
            }
        }

        fun block(statements: List<Stmt>, scopes: ArrayList<MutableMap<String, Int>>) {
            scopes.add(HashMap())
            try {
                for (statement in statements) {
                    when (statement) {
                        is AddTotal -> {
                            val before = total
                            total = Math.addExact(before, eval(statement.value, scopes))
                        }
                        is Print -> output.add(eval(statement.value, scopes).toString())
                        is Wait -> waited += statement.ticks
                        is When -> (if (lookup(scopes, "a") > statement.threshold) statement.then else statement.otherwise)?.let { block(it, scopes) }
                        is Repeat -> for (value in 0 until statement.count) {
                            scopes.add(hashMapOf(statement.variable to value))
                            try {
                                block(statement.body, scopes)
                            } finally {
                                scopes.removeAt(scopes.lastIndex)
                            }
                        }
                        is Return -> throw ReturnSignal(eval(statement.value, scopes))
                    }
                }
            } finally {
                scopes.removeAt(scopes.lastIndex)
            }
        }
    }

    // 呼び出しの回数が爆発しないよう、ループの中では呼び出さず、本体ごとの呼び出しは 2 つまで
    private class Generator(private val random: Random, private val functionCount: Int) {
        private var names = 0
        var callBudget = 0

        fun expression(depth: Int, visible: List<String>, allowCalls: Boolean): Expr = when (random.nextInt(if (depth < 2) 6 else 3)) {
            0 -> Literal(random.nextInt(-3, 10))
            1, 2 -> Name(visible.random(random))
            3, 4 -> Plus(random.nextBoolean(), expression(depth + 1, visible, allowCalls), expression(depth + 1, visible, allowCalls))
            else -> if (allowCalls && callBudget > 0) {
                callBudget--
                Call(random.nextInt(functionCount), expression(2, visible, false))
            } else {
                Literal(1)
            }
        }

        fun block(depth: Int, visible: List<String>, inLoop: Boolean = false): List<Stmt> {
            val statements = ArrayList<Stmt>()
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(7)) {
                    0 -> statements.add(AddTotal(expression(1, visible, !inLoop)))
                    1 -> statements.add(Print(expression(1, visible, !inLoop)))
                    2 -> statements.add(Wait(random.nextInt(0, 3)))
                    3 -> if (depth < 2) statements.add(When(random.nextInt(-3, 6), block(depth + 1, visible, inLoop), if (random.nextBoolean()) block(depth + 1, visible, inLoop) else null))
                    4 -> if (depth < 2) {
                        val variable = "i${names++}"
                        statements.add(Repeat(variable, random.nextInt(0, 3), block(depth + 1, visible + variable, inLoop = true)))
                    }
                    else -> statements.add(Return(expression(1, visible, !inLoop)))
                }
            }
            return statements
        }
    }

    private fun render(expression: Expr): String = when (expression) {
        is Literal -> if (expression.value < 0) "(${expression.value})" else "${expression.value}"
        is Name -> expression.name
        is Plus -> "(${render(expression.left)} ${if (expression.isPlus) "+" else "-"} ${render(expression.right)})"
        is Call -> "f${expression.function}(n - 1, ${render(expression.value)})"
    }

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        when (statement) {
            is AddTotal -> "${indent}total = total + ${render(statement.value)}\n"
            is Print -> "${indent}println(${render(statement.value)})\n"
            is Wait -> "${indent}waitTicks(${statement.ticks})\n"
            is When -> "${indent}if (a > ${statement.threshold}) {\n" + render(statement.then, "$indent  ") + "$indent}" +
                (statement.otherwise?.let { " else {\n" + render(it, "$indent  ") + "$indent}" } ?: "") + "\n"
            is Repeat -> "${indent}for (${statement.variable} in 0 until ${statement.count}) {\n" + render(statement.body, "$indent  ") + "$indent}\n"
            is Return -> "${indent}return ${render(statement.value)}\n"
        }
    }

    // トップレベルからは f番号(深さ, 値) を呼ぶ。深さは多くが 0〜5、一部は上限を超える 16〜20（呼び出しの深さ・スタックの上限で止まることを確かめる）
    @Test
    fun randomRecursiveProgramsMatchReference() {
        val random = Random(2026)
        var compared = 0
        var depthErrors = 0
        var overflows = 0
        var restored = 0
        var mutual = 0
        repeat(300) { index ->
            val functionCount = random.nextInt(1, 5)
            val generator = Generator(random, functionCount)
            val functions = (0 until functionCount).map {
                generator.callBudget = 2
                val body = generator.block(0, listOf("n", "a", "total"))
                Function(body, generator.expression(0, listOf("n", "a", "total"), true))
            }
            val calls = (0 until random.nextInt(1, 4)).map {
                val depth = if (random.nextInt(4) == 0) random.nextInt(16, 21) else random.nextInt(0, 6)
                random.nextInt(functionCount) to depth
            }
            val definitions = functions.mapIndexed { k, function ->
                "fun f$k(n: Int, a: Int): Int {\n  if (n <= 0) { return a + $k }\n" + render(function.body, "  ") + "  return ${render(function.result)}\n}\n"
            }
            val source = definitions.joinToString("") + "var total = 1\n" + calls.joinToString("") { (f, depth) -> "println(f$f($depth, total))\n" } + "println(total)"
            if (source.length > 4096) return@repeat
            val result = Compiler.compileSource(source)
            val limits = setOf(DiagnosticCode.LEX_TOO_MANY_TOKENS, DiagnosticCode.PARSE_TOO_MANY_NODES)
            if (result.diagnostics.any { it.code in limits }) return@repeat
            val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }}\n$source")
            val reference = Reference(functions)
            val outcome = try {
                for ((f, depth) in calls) reference.output.add(reference.call(f, depth, reference.total).toString())
                null
            } catch (exceeded: DepthExceeded) {
                DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED
            } catch (overflow: ArithmeticException) {
                DiagnosticCode.RUN_INTEGER_OVERFLOW
            }
            val vm = CsVm(program)
            val ticks = runToEnd(vm, 1_000_000)
            when (outcome) {
                null -> {
                    assertEquals(VmState.HALTED, vm.state, "${vm.error} in $source")
                    assertEquals((reference.output + reference.total.toString()).takeLast(Console.MAX_LINES), vm.console.history, source)
                    assertTrue(ticks >= reference.waited, source)
                }
                // 深すぎる再帰は、呼び出しの深さ（16 段）か、その手前でスタック（64 値）の上限に達して止まる
                DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED -> {
                    assertTrue(vm.error?.code == DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED || vm.error?.code == DiagnosticCode.RUN_STACK_OVERFLOW, "${vm.error} in $source")
                    if (vm.error?.code == DiagnosticCode.RUN_STACK_OVERFLOW) overflows++ else depthErrors++
                }
                else -> assertEquals(outcome, vm.error?.code, source)
            }
            compared++
            if (functions.withIndex().any { (k, function) -> Regex("f(?!$k\\()\\d\\(").containsMatchIn(render(function.body, "") + render(function.result)) }) mutual++
            if (index % 20 == 0) {
                restored++
                restoreAtEveryBoundary(program, source)
            }
        }
        assertTrue(compared >= 250 && depthErrors + overflows >= 15 && mutual >= 50, "compared $compared, depth $depthErrors, stack $overflows, mutual $mutual")
        println("[3.8e random] $compared programs compared, $mutual with mutual recursion, $depthErrors stopped at call depth 16, $overflows at stack 64, $restored restored at every boundary")
    }

    // 1歩ずつ進め、各境界のスナップショットから復元して最後まで実行した結果（出力・エラー）が、中断しない実行と一致する
    private fun restoreAtEveryBoundary(program: CompiledProgram, source: String) {
        fun step(vm: CsVm) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1)
        }
        fun finished(vm: CsVm) = vm.state == VmState.HALTED || vm.state == VmState.ERROR
        fun end(vm: CsVm): Pair<List<String>, DiagnosticCode?> {
            var steps = 0
            while (!finished(vm)) {
                step(vm)
                check(++steps < 500_000) { "did not finish: $source" }
            }
            return vm.console.history to vm.error?.code
        }
        val expected = end(CsVm(program))
        val original = CsVm(program)
        var cut = 0
        while (!finished(original) && cut < 2000) {
            val snapshot = original.snapshot()!!
            val restored = CsVm.restore(program, snapshot)
            assertNotNull(restored, "rejected at $cut: ${SnapshotVerifier.verify(program, snapshot)} in $source")
            assertEquals(snapshot, restored!!.snapshot())
            assertEquals(expected, end(restored), "cut $cut of $source")
            step(original)
            cut++
        }
    }
}
