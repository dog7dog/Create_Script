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
import com.creatrbypc.createanalytical.script.vm.HostFunction
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

// 第3.8d段階のユーザー定義関数の引数・戻り値を、ソースから CS_VM・Scheduler・SnapshotVerifier まで通して確かめる
class FunctionArgumentTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する
    private fun runToEnd(vm: CsVm, maxTicks: Int = 10_000): Int {
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1000)
            check(++ticks < maxTicks) { "did not finish" }
        }
        return ticks
    }

    private fun output(source: String): List<String> {
        val vm = CsVm(compile(source))
        runToEnd(vm)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    @Test
    fun intArgumentsAndReturnValue() {
        assertEquals(listOf("30"), output("fun add(a: Int, b: Int): Int {\n    return a + b\n}\n\nval result = add(10, 20)\nprintln(result)"))
        // 引数の順（左が 0 番）・式の引数・戻り値を式の中で使う
        assertEquals(listOf("7", "-7", "23"), output("fun sub(a: Int, b: Int): Int { return a - b }\nprintln(sub(10, 3))\nprintln(sub(3, 10))\nprintln(1 + sub(2 * 10, 3) + 5)"))
        assertEquals(listOf("2147483647", "-2147483648"), output("fun id(n: Int): Int { return n }\nprintln(id(2147483647))\nprintln(id(-2147483648))"))
    }

    @Test
    fun booleanAndStringArguments() {
        assertEquals(
            listOf("big", "small", "true", "false"),
            output("fun label(big: Boolean): String {\n  if (big) {\n    return \"big\"\n  } else {\n    return \"small\"\n  }\n}\nfun isBig(n: Int): Boolean { return n > 10 }\n" +
                "println(label(isBig(20)))\nprintln(label(false))\nprintln(isBig(11))\nprintln(isBig(10) || false)"),
        )
        assertEquals(listOf("Hello, CreateScript", "Hello, CreateScript"), output("fun greet(name: String) {\n  println(name)\n}\nval who = \"Hello, CreateScript\"\ngreet(who)\ngreet(\"Hello, CreateScript\")"))
        // String の引数・戻り値の比較（内容で比べる）
        assertEquals(listOf("true"), output("fun same(a: String, b: String): Boolean { return a == b }\nprintln(same(\"x\", \"x\"))"))
    }

    // 戻り値の無い関数（Unit）。return・末尾での自動復帰。戻り値を使わない呼び出しの値は捨て、スタックに残らない
    @Test
    fun unitFunctionsAndDiscardedResults() {
        assertEquals(listOf("3", "end"), output("fun show(n: Int): Unit {\n  if (n < 0) { return }\n  println(n)\n}\nshow(-1)\nshow(3)\nprintln(\"end\")"))
        val program = compile("var calls = 0\nfun count(): Int {\n  calls = calls + 1\n  return calls\n}\nwhile (calls < 100) { count() }\nprintln(calls)")
        val vm = CsVm(program)
        var deepest = 0
        while (vm.state == VmState.RUNNING) {
            vm.run(1)
            deepest = maxOf(deepest, vm.snapshot()?.stack?.size ?: 0)
        }
        assertEquals(listOf("100"), vm.console.history)
        // 捨てた戻り値は蓄積しない（while の条件の比較で最大 2 個）
        assertTrue(deepest <= 2, "stack grew to $deepest")
    }

    // 関数の戻り値を別の関数へ渡す・入れ子の呼び出し・関数の中から別の関数を呼ぶ。引数は左から右へ評価する
    @Test
    fun nestedCallsAndEvaluationOrder() {
        assertEquals(listOf("21"), output("fun add(a: Int, b: Int): Int { return a + b }\nfun twice(n: Int): Int { return add(n, n) }\nprintln(add(twice(5), add(1, twice(5))))"))
        assertEquals(
            listOf("L", "R", "1"),
            output("fun left(): Int {\n  println(\"L\")\n  return 3\n}\nfun right(): Int {\n  println(\"R\")\n  return 2\n}\nfun sub(a: Int, b: Int): Int { return a - b }\nprintln(sub(left(), right()))"),
        )
        // 呼び出しの副作用（大域変数の変更）も左から順に見える
        assertEquals(listOf("12"), output("var n = 1\nfun bump(): Int {\n  n = n + 1\n  return n\n}\nfun pair(a: Int, b: Int): Int { return a * 10 + b }\nprintln(pair(n, bump()))"))
    }

    // 早期 return・if / else の両方からの return・while / for の中からの return
    @Test
    fun returnsFromEveryKindOfBlock() {
        assertEquals(listOf("neg", "zero", "pos"), output("fun sign(n: Int): String {\n  if (n < 0) { return \"neg\" }\n  if (n == 0) {\n    return \"zero\"\n  } else {\n    return \"pos\"\n  }\n}\nprintln(sign(-5))\nprintln(sign(0))\nprintln(sign(9))"))
        assertEquals(
            listOf("3", "-1", "4", "-1"),
            output("fun findFor(target: Int): Int {\n  for (i in 0..5) {\n    if (i * i == target) { return i }\n  }\n  return -1\n}\n" +
                "fun findWhile(target: Int): Int {\n  var i = 0\n  while (true) {\n    if (i * i == target) { return i }\n    if (i > 5) { break }\n    i = i + 1\n  }\n  return -1\n}\n" +
                "println(findFor(9))\nprintln(findFor(8))\nprintln(findWhile(16))\nprintln(findWhile(15))"),
        )
        // return は現在の関数だけを終える（呼び出し元のループは続く）
        assertEquals(listOf("0", "1", "2"), output("fun first(n: Int): Int {\n  for (i in 0..9) { return n }\n  return -1\n}\nfor (k in 0..2) { println(first(k)) }"))
    }

    // 引数は呼び出しごとのローカル変数（値渡し）。本体の中の同名の宣言はシャドーイング。大域変数は共有する
    @Test
    fun parameterScopesAndGlobals() {
        assertEquals(listOf("s", "1"), output("fun f(a: Int) {\n  if (true) {\n    val a = \"s\"\n    println(a)\n  }\n  println(a)\n}\nf(1)"))
        // 値渡し: 呼び出し元の変数は変わらない。同じ番号の引数を持つ関数を入れ子に呼んでも混ざらない
        assertEquals(listOf("5", "5", "9"), output("fun inner(a: Int): Int { return a + 4 }\nfun outer(a: Int): Int {\n  val r = inner(a)\n  println(a)\n  return r\n}\nvar x = 5\nval y = outer(x)\nprintln(x)\nprintln(y)"))
        assertEquals(listOf("global", "3"), output("val a = \"global\"\nfun f(a: Int): Int { return a + 1 }\nval r = f(2)\nprintln(a)\nprintln(r)"))
        assertEquals(listOf("15"), output("var total = 0\nfun addTo(n: Int) { total = total + n }\nfor (i in 1..5) { addTo(i) }\nprintln(total)"))
    }

    @Test
    fun waitTicksInsideFunctionsWithArguments() {
        val vm = CsVm(compile("fun slow(n: Int): Int {\n  waitTicks(n)\n  return n * 2\n}\nprintln(slow(3) + slow(4))"))
        val ticks = runToEnd(vm)
        assertEquals(listOf("14"), vm.console.history)
        assertTrue(ticks >= 7, "ticks $ticks")
    }

    // 実行時エラー（0 除算・オーバーフロー）は関数の中の位置で止まる
    @Test
    fun runtimeErrorsInsideFunctions() {
        val vm = CsVm(compile("fun div(a: Int, b: Int): Int {\n  return a / b\n}\nprintln(div(4, 2))\nprintln(div(1, 0))"))
        runToEnd(vm)
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, vm.error?.code)
        assertEquals(2, vm.error?.position?.line)
        assertEquals(listOf("2"), vm.console.history)
    }

    // 引数・戻り値のある呼び出しを繰り返す終わらないプログラムも、予算どおりに制御を返し、公平に配分される
    @Test
    fun callsWithArgumentsStayWithinBudgetAndFair() {
        val adding = compile("fun add(a: Int, b: Int): Int { return a + b }\nvar n = 0\nwhile (true) {\n  n = add(n, 1) % 1000\n}")
        val nested = compile("fun inc(a: Int): Int { return a + 1 }\nfun both(a: Int, b: Boolean): Int {\n  if (b) { return inc(inc(a)) }\n  return inc(a)\n}\nvar m = 0\nwhile (true) { m = both(m, m % 2 == 0) % 999 }")
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) adding else nested)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(600)
        repeat(20) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }

    // ---- 手で組んだ不正なバイトコード（Compiler は出さない） ----

    private val origin = SourcePosition(0, 1, 1)

    private fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)

    @Test
    fun invalidArgumentAndReturnBytecodeIsUnverifiable() {
        val constants = listOf(Constant.IntConstant(1))
        fun program(vararg instructions: Instruction, functions: List<FunctionInfo>) = CompiledProgram(instructions.toList(), constants, 0, 2, functions)
        // 正しい形: 引数 1 つを積んで呼び、値を返す
        val valid = program(op(Opcode.PUSH_CONST), op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.LOAD_LOCAL), op(Opcode.RETURN, 1), functions = listOf(FunctionInfo(4, 1, 1)))
        assertNotNull(SnapshotVerifier.framesOf(valid))
        val cases = mapOf(
            // 引数が足りない・Unit を引数に渡す
            "missing argument" to program(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.RETURN), functions = listOf(FunctionInfo(3, 1, 1))),
            "unit argument" to program(
                op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.RETURN), functions = listOf(FunctionInfo(4, 1, 1)),
            ),
            // 呼び出し元ごとに引数の型が違う
            "argument types differ" to program(
                op(Opcode.PUSH_CONST), op(Opcode.CALL), op(Opcode.POP), op(Opcode.PUSH_BOOL, 1), op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.RETURN),
                functions = listOf(FunctionInfo(7, 1, 1)),
            ),
            // 引数の数がローカル変数の数・上限を超える
            "parameters over locals" to program(op(Opcode.HALT), op(Opcode.RETURN), functions = listOf(FunctionInfo(1, 1, 2))),
            "parameters over limit" to program(op(Opcode.HALT), op(Opcode.RETURN), functions = listOf(FunctionInfo(1, 20, VmLimits.MAX_PARAMETERS + 1))),
            // 値を返す RETURN のスタックが空・2 つ・Unit、未知の operand、Unit と値を混ぜて返す
            "return value from empty stack" to program(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.RETURN, 1), functions = listOf(FunctionInfo(3, 0))),
            "return two values" to program(
                op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_CONST), op(Opcode.PUSH_CONST), op(Opcode.RETURN, 1), functions = listOf(FunctionInfo(3, 0)),
            ),
            "return unit as value" to program(
                op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.RETURN, 1), functions = listOf(FunctionInfo(3, 0)),
            ),
            "unknown return kind" to program(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.RETURN, 2), functions = listOf(FunctionInfo(3, 0))),
            "mixed returns" to program(
                op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 6), op(Opcode.RETURN), op(Opcode.PUSH_CONST), op(Opcode.RETURN, 1),
                functions = listOf(FunctionInfo(3, 0)),
            ),
            // 値を返す関数の結果を、呼び出し元が別の型として使う
            "result used as boolean" to program(op(Opcode.CALL), op(Opcode.NOT), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_CONST), op(Opcode.RETURN, 1), functions = listOf(FunctionInfo(4, 0))),
        )
        for ((label, program) in cases) assertNull(SnapshotVerifier.framesOf(program), label)
        // VM も実行時に拒否する（検証を通らないプログラムは実行されないが、念のため）
        val vm = CsVm(cases.getValue("return value from empty stack"))
        vm.run(100)
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code)
    }

    // ---- 呼び出し元の計算途中の値を含むスナップショット ----

    // 10 + add(twice(1), twice(2)) の twice(2) の待機中: スタックは [10, 2]（呼び出し元の区間）、twice のスタックは空
    private val pending = compile("fun twice(n: Int): Int {\n  waitTicks(2)\n  return n * 2\n}\nfun add(a: Int, b: Int): Int { return a + b }\nprintln(10 + add(twice(1), twice(2)))")

    private fun secondWait(): VmSnapshot {
        val vm = CsVm(pending)
        var waits = 0
        while (true) {
            vm.run(1)
            if (vm.state == VmState.WAITING && ++waits == 2) return vm.snapshot()!!
            if (vm.state == VmState.WAITING) while (vm.state == VmState.WAITING) vm.countWaitTick()
        }
    }

    private fun finish(vm: CsVm): List<String> {
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1)
        return vm.console.history
    }

    @Test
    fun snapshotsWithCallerValuesOnTheStack() {
        val waiting = secondWait()
        assertEquals(listOf<Value>(Value.IntValue(10), Value.IntValue(2)), waiting.stack)
        assertEquals(listOf(listOf<Value?>(Value.IntValue(2))), waiting.callStack.map { it.locals })
        assertNull(SnapshotVerifier.verify(pending, waiting))
        assertEquals(listOf("16"), finish(CsVm.restore(pending, waiting)!!))
        fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(pending, snapshot)
        // 呼び出し元の値が足りない・多い・型が違う
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(stack = listOf(Value.IntValue(10)))))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(stack = waiting.stack + Value.IntValue(0))))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(stack = listOf(Value.IntValue(10), Value.BoolValue(true)))))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(stack = emptyList())))
        // 引数（ローカル変数 0）が未初期化・型が違う・数が違う
        val frame = waiting.callStack.single()
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(callStack = listOf(frame.copy(locals = listOf(null))))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(callStack = listOf(frame.copy(locals = listOf(Value.StringValue(0)))))))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(waiting.copy(callStack = listOf(frame.copy(locals = emptyList())))))
        // 戻り先が 1 つ目の twice の呼び出し（呼び出し元のスタックは [10]）を指す
        val firstCall = pending.instructions.indexOfFirst { it.opcode == Opcode.CALL } + 1
        assertEquals(SnapshotProblem.INVALID_STACK, problem(waiting.copy(callStack = listOf(frame.copy(returnPc = firstCall)))))
    }

    // 戻り値を計算している途中（RETURN の直前・値を積んだ後）と、RETURN 直後（呼び出し元に値が積まれた状態）
    @Test
    fun snapshotsAroundValueReturns() {
        val program = compile("fun mul(a: Int, b: Int): Int {\n  return a * b\n}\nprintln(1 + mul(6, 7))")
        val vm = CsVm(program)
        val seen = ArrayList<VmSnapshot>()
        while (vm.state == VmState.RUNNING) {
            seen.add(vm.snapshot()!!)
            vm.run(1)
        }
        assertEquals(listOf("43"), vm.console.history)
        // RETURN の直前（関数のスタックに 42）と、RETURN の直後（呼び出し元のスタックに 1, 42）
        val beforeReturn = seen.single { it.callStack.isNotEmpty() && program.instructions[it.pc].opcode == Opcode.RETURN }
        assertEquals(listOf<Value>(Value.IntValue(1), Value.IntValue(42)), beforeReturn.stack)
        val afterReturn = seen[seen.indexOf(beforeReturn) + 1]
        assertEquals(emptyList<Any>(), afterReturn.callStack)
        assertEquals(listOf<Value>(Value.IntValue(1), Value.IntValue(42)), afterReturn.stack)
        for (snapshot in seen) assertEquals(listOf("43"), finish(CsVm.restore(program, snapshot)!!))
    }

    // ---- 性能（参考値） ----

    @Test
    fun measureArguments() {
        val looping = compile("fun add(a: Int, b: Int): Int { return a + b }\nfun step(n: Int): Int { return add(n, 1) % 1000 }\nvar n = 0\nwhile (true) { n = step(n) }")
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(looping)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(200) { scheduler.tick { fast } }
        val start = System.nanoTime()
        repeat(1000) { scheduler.tick { fast } }
        println("[3.8d measure] scheduler tick with 1000 VMs calling functions with arguments (budget 4096): ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 1000)} ms per tick")
        // 16 段の呼び出しの連鎖（各段が引数 2 つを受け取り、値を返す）と、引数を持つ関数 15 個（ソースの上限 4096 単位以内）
        val chain = (1..16).joinToString("") {
            "fun c$it(a: Int, b: Int): Int {\n  var v = a\n  for (i in 0..2) { v = v + b }\n" + (if (it < 16) "  return c${it + 1}(v, b) + 1\n" else "  waitTicks(1)\n  return v\n") + "}\n"
        }
        val many = (1..15).joinToString("") { "fun m$it(x: Int, ok: Boolean): Int {\n  if (ok) { return x + $it }\n  return x\n}\n" }
        val source = chain + many + "println(c1(1, 2))\n" + (1..15).joinToString("") { "println(m$it($it, true))\n" }
        val program = compile(source)
        repeat(50) { compile(source) }
        val compileStart = System.nanoTime()
        repeat(200) { compile(source) }
        val compileMillis = (System.nanoTime() - compileStart) / 1_000_000.0 / 200
        val verifyStart = System.nanoTime()
        repeat(200) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0 / 200
        println(
            "[3.8d measure] ${program.functions.size} functions with arguments (call chain 16), ${program.instructions.size} instructions, ${source.length} UTF-16 units: " +
                "compile ${"%.3f".format(compileMillis)} ms (verification included), verification ${"%.3f".format(verifyMillis)} ms",
        )
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertEquals(16, snapshot.callStack.size)
        repeat(200) { assertNotNull(CsVm.restore(program, snapshot)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(program, snapshot) }
        println("[3.8d measure] verify + restore with 16 call frames: ${"%.3f".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000)} ms per VM")
    }

    // ---- 無作為に作った引数・戻り値のある関数のプログラムと、Kotlin で書いた参照の実行器の比較 ----

    private sealed interface Expr

    private data class Literal(val value: Int) : Expr
    private data class Name(val name: String) : Expr
    private data class Plus(val isPlus: Boolean, val left: Expr, val right: Expr) : Expr

    // f番号(引数 2 つ)。関数 k は番号の大きい関数だけを呼ぶ（再帰しない）
    private data class Call(val function: Int, val arguments: List<Expr>) : Expr

    private sealed interface Stmt

    private data class AddTotal(val value: Expr) : Stmt
    private data class Print(val value: Expr) : Stmt
    private data class Local(val name: String, val value: Expr) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class Discard(val call: Call) : Stmt
    private data class When(val name: String, val threshold: Int, val then: List<Stmt>, val otherwise: List<Stmt>?) : Stmt
    private data class Repeat(val variable: String, val count: Int, val body: List<Stmt>) : Stmt
    private data class Return(val value: Expr) : Stmt

    // 関数の本体と、本体の後の最後の return の値
    private data class Function(val body: List<Stmt>, val result: Expr)

    private class ReturnSignal(val value: Int) : RuntimeException(null, null, false, false)

    // 呼び出しごとのスコープの並び（先頭が引数）。大域変数は total だけ
    private class Reference(private val functions: List<Function>) {
        val output = ArrayList<String>()
        var total = 1
        var waited = 0

        private fun lookup(scopes: List<Map<String, Int>>, name: String) = if (name == "total") total else scopes.asReversed().firstNotNullOf { it[name] }

        fun eval(expression: Expr, scopes: List<Map<String, Int>>): Int = when (expression) {
            is Literal -> expression.value
            is Name -> lookup(scopes, expression.name)
            is Plus -> {
                val left = eval(expression.left, scopes)
                val right = eval(expression.right, scopes)
                if (expression.isPlus) Math.addExact(left, right) else Math.subtractExact(left, right)
            }
            is Call -> call(expression.function, expression.arguments.map { eval(it, scopes) })
        }

        private fun call(function: Int, arguments: List<Int>): Int {
            val scopes = arrayListOf<MutableMap<String, Int>>(hashMapOf("a" to arguments[0], "b" to arguments[1]))
            return try {
                block(functions[function].body, scopes)
                eval(functions[function].result, scopes)
            } catch (signal: ReturnSignal) {
                signal.value
            }
        }

        fun block(statements: List<Stmt>, scopes: ArrayList<MutableMap<String, Int>>) {
            scopes.add(HashMap())
            try {
                for (statement in statements) {
                    when (statement) {
                        // total = total + 値 は左の total を先に読む（値の中の呼び出しが total を変えても、足すのは元の値）
                        is AddTotal -> {
                            val before = total
                            total = Math.addExact(before, eval(statement.value, scopes))
                        }
                        is Print -> output.add(eval(statement.value, scopes).toString())
                        is Local -> scopes.last()[statement.name] = eval(statement.value, scopes)
                        is Wait -> waited += statement.ticks
                        is Discard -> eval(statement.call, scopes)
                        is When -> (if (lookup(scopes, statement.name) > statement.threshold) statement.then else statement.otherwise)?.let { block(it, scopes) }
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

    // 呼び出しの回数が爆発しないよう、ループの中では呼び出さず、本体ごとの呼び出しの数を callBudget までにする（関数は 2、トップレベルは 4）
    private class Generator(private val random: Random, private val functionCount: Int) {
        private var names = 0
        var callBudget = 0

        fun expression(depth: Int, visible: List<String>, function: Int, allowCalls: Boolean): Expr = when (random.nextInt(if (depth < 2) 6 else 3)) {
            0 -> Literal(random.nextInt(-3, 10))
            1, 2 -> Name(visible.random(random))
            3, 4 -> Plus(random.nextBoolean(), expression(depth + 1, visible, function, allowCalls), expression(depth + 1, visible, function, allowCalls))
            else -> if (allowCalls && callBudget > 0 && function + 1 < functionCount) call(depth, visible, function) else Literal(1)
        }

        fun call(depth: Int, visible: List<String>, function: Int): Call {
            callBudget--
            return Call(random.nextInt(function + 1, functionCount), List(2) { expression(depth + 1, visible, function, true) })
        }

        fun block(depth: Int, visible: List<String>, function: Int, inLoop: Boolean = false): List<Stmt> {
            val names = visible.toMutableList()
            val statements = ArrayList<Stmt>()
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(9)) {
                    0 -> statements.add(AddTotal(expression(1, names, function, !inLoop)))
                    1 -> statements.add(Print(expression(0, names, function, !inLoop)))
                    2 -> {
                        val name = "x${this.names++}"
                        statements.add(Local(name, expression(0, names, function, !inLoop)))
                        names.add(name)
                    }
                    3 -> statements.add(Wait(random.nextInt(0, 3)))
                    4 -> if (!inLoop && callBudget > 0 && function + 1 < functionCount) statements.add(Discard(call(0, names, function)))
                    5 -> if (depth < 2) {
                        statements.add(When(names.random(random), random.nextInt(-3, 6), block(depth + 1, names, function, inLoop), if (random.nextBoolean()) block(depth + 1, names, function, inLoop) else null))
                    }
                    6 -> if (depth < 2) {
                        val variable = "i${this.names++}"
                        statements.add(Repeat(variable, random.nextInt(0, 4), block(depth + 1, names + variable, function, inLoop = true)))
                    }
                    else -> if (function >= 0) statements.add(Return(expression(1, names, function, !inLoop)))
                }
            }
            return statements
        }
    }

    private fun render(expression: Expr): String = when (expression) {
        is Literal -> if (expression.value < 0) "(${expression.value})" else "${expression.value}"
        is Name -> expression.name
        is Plus -> "(${render(expression.left)} ${if (expression.isPlus) "+" else "-"} ${render(expression.right)})"
        is Call -> "f${expression.function}(${expression.arguments.joinToString(", ") { render(it) }})"
    }

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        when (statement) {
            is AddTotal -> "${indent}total = total + ${render(statement.value)}\n"
            is Print -> "${indent}println(${render(statement.value)})\n"
            is Local -> "${indent}val ${statement.name} = ${render(statement.value)}\n"
            is Wait -> "${indent}waitTicks(${statement.ticks})\n"
            is Discard -> "$indent${render(statement.call)}\n"
            is When -> "${indent}if (${statement.name} > ${statement.threshold}) {\n" + render(statement.then, "$indent  ") + "$indent}" +
                (statement.otherwise?.let { " else {\n" + render(it, "$indent  ") + "$indent}" } ?: "") + "\n"
            is Repeat -> "${indent}for (${statement.variable} in 0 until ${statement.count}) {\n" + render(statement.body, "$indent  ") + "$indent}\n"
            is Return -> "${indent}return ${render(statement.value)}\n"
        }
    }

    // 関数の定義はトップレベルの文の前後に無作為に置く。一部はすべての命令の境界で保存・復元して比べる
    @Test
    fun randomArgumentProgramsMatchReference() {
        val random = Random(77)
        var compared = 0
        var overflows = 0
        var restored = 0
        var withNestedCallArguments = 0
        repeat(400) { index ->
            val functionCount = random.nextInt(1, 6)
            val generator = Generator(random, functionCount)
            val functions = (0 until functionCount).map { k ->
                generator.callBudget = 2
                val body = generator.block(0, listOf("a", "b", "total"), k)
                Function(body, generator.expression(0, listOf("a", "b", "total"), k, true))
            }
            generator.callBudget = 4
            val main = generator.block(0, listOf("total"), -1) + (0 until functionCount).map { Print(Call(it, listOf(Name("total"), Literal(it)))) }
            val definitions = functions.mapIndexed { k, function -> "fun f$k(a: Int, b: Int): Int {\n" + render(function.body, "  ") + "  return ${render(function.result)}\n}\n" }
            val (before, after) = definitions.partition { random.nextBoolean() }
            val source = before.joinToString("") + "var total = 1\n" + render(main, "") + after.joinToString("") + "println(total)"
            if (source.length > 4096) return@repeat
            val result = Compiler.compileSource(source)
            val limits = setOf(DiagnosticCode.LEX_TOO_MANY_TOKENS, DiagnosticCode.PARSE_TOO_MANY_NODES)
            if (result.diagnostics.any { it.code in limits }) return@repeat
            val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }}\n$source")
            val reference = Reference(functions)
            val overflowed = try {
                reference.block(main, arrayListOf())
                false
            } catch (overflow: ArithmeticException) {
                true
            }
            val vm = CsVm(program)
            val ticks = runToEnd(vm, 1_000_000)
            if (overflowed) {
                assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, vm.error?.code, source)
                overflows++
            } else {
                assertEquals(VmState.HALTED, vm.state, "${vm.error} in $source")
                assertEquals((reference.output + reference.total.toString()).takeLast(Console.MAX_LINES), vm.console.history, source)
                assertTrue(ticks >= reference.waited, source)
            }
            compared++
            if (Regex("f\\d\\([^()]*f\\d\\(").containsMatchIn(source)) withNestedCallArguments++
            if (index % 25 == 0) {
                restored++
                restoreAtEveryBoundary(program, source)
            }
        }
        assertTrue(compared >= 350 && withNestedCallArguments >= 50, "compared $compared, nested call arguments $withNestedCallArguments")
        println("[3.8d random] $compared programs compared ($overflows overflowed), $withNestedCallArguments with calls inside arguments, $restored restored at every boundary")
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
                check(++steps < 200_000) { "did not finish: $source" }
            }
            return vm.console.history to vm.error?.code
        }
        val expected = end(CsVm(program))
        val original = CsVm(program)
        var cut = 0
        while (!finished(original) && cut < 3000) {
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
