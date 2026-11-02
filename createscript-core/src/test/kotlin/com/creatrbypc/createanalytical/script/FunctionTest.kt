package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.CallFrameSnapshot
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

// 第3.8c段階のユーザー定義関数を、ソースから CS_VM・Scheduler・SnapshotVerifier まで通して確かめる
class FunctionTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する
    private fun runToEnd(source: String, maxTicks: Int = 10_000): Pair<CsVm, Int> {
        val vm = CsVm(compile(source))
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1000)
            check(++ticks < maxTicks) { "did not finish: $source" }
        }
        return vm to ticks
    }

    private fun output(source: String): List<String> {
        val (vm, _) = runToEnd(source)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    @Test
    fun definitionAndCalls() {
        assertEquals(listOf("Hello", "Hello"), output("fun greet() {\n    println(\"Hello\")\n}\n\ngreet()\ngreet()"))
        // 早期 return の後の文は実行しない
        assertEquals(listOf("1"), output("fun work() {\n    println(1)\n    return\n    println(2)\n}\n\nwork()"))
        // 定義だけでは実行されない・return を省略しても末尾で戻る
        assertEquals(listOf("main"), output("fun never() {\n  println(\"never\")\n}\nprintln(\"main\")"))
        assertEquals(listOf("a", "b", "after"), output("fun two() {\n  println(\"a\")\n  println(\"b\")\n}\ntwo()\nprintln(\"after\")"))
        assertEquals(listOf("x"), output("fun empty() { }\nempty()\nprintln(\"x\")"))
    }

    // 定義の順に関係なく呼び出せる（定義の前の呼び出し・文の間の定義・後で定義した関数を呼ぶ関数）
    @Test
    fun callsDoNotDependOnDefinitionOrder() {
        assertEquals(listOf("late"), output("late()\nfun late() {\n  println(\"late\")\n}"))
        assertEquals(listOf("1", "f", "2", "g"), output("println(1)\nfun f() { println(\"f\") }\nf()\nprintln(2)\nfun g() { println(\"g\") }\ng()"))
        assertEquals(listOf("outer", "inner"), output("fun outer() {\n  println(\"outer\")\n  inner()\n}\nouter()\nfun inner() {\n  println(\"inner\")\n}"))
    }

    // 関数から別の関数を呼び、戻った後は呼び出しの次の文から続ける
    @Test
    fun functionsCallOtherFunctions() {
        assertEquals(
            listOf("a1", "b1", "c", "b2", "a2", "c", "end"),
            output("fun a() {\n  println(\"a1\")\n  b()\n  println(\"a2\")\n  c()\n}\nfun b() {\n  println(\"b1\")\n  c()\n  println(\"b2\")\n}\nfun c() { println(\"c\") }\na()\nprintln(\"end\")"),
        )
    }

    // ローカル変数は呼び出しごとに独立し、呼び出し先の同じ番号のローカル変数とも混ざらない（呼び出しフレーム）
    @Test
    fun localsAreIndependentPerCall() {
        assertEquals(listOf("1", "1", "1"), output("fun count() {\n  var n = 0\n  n = n + 1\n  println(n)\n}\ncount()\ncount()\ncount()"))
        assertEquals(listOf("2", "1", "20", "10"), output("fun inner() {\n  val x = 2\n  println(x)\n}\nfun outer() {\n  var x = 1\n  inner()\n  println(x)\n}\nouter()\nfun both() {\n  var y = 10\n  var z = 20\n  println(z)\n  println(y)\n}\nboth()"))
        // 呼び出しの途中（待機中）でも、呼び出し元の関数のローカル変数・ループ変数（どちらも番号 0 の k と同じ番号）は保たれる
        assertEquals(
            listOf("5", "15", "25"),
            output("fun deep() {\n  var k = 9\n  waitTicks(1)\n  k = k + 1\n}\nfun loop() {\n  for (i in 0..2) {\n    val keep = 5\n    deep()\n    println(i * 10 + keep)\n  }\n}\nloop()"),
        )
    }

    // 関数の中からトップレベルの変数（大域変数）を読み書きできる。関数の変数はトップレベルの同名の変数を隠す
    @Test
    fun globalsAreSharedWithFunctions() {
        assertEquals(listOf("3"), output("var count = 0\nfun inc() {\n  count = count + 1\n}\ninc()\ninc()\ninc()\nprintln(count)"))
        // 宣言が関数の定義より後でも、呼び出しより前なら使える
        assertEquals(listOf("5"), output("fun show() {\n  println(total)\n}\nval total = 5\nshow()"))
        assertEquals(listOf("local", "global"), output("val name = \"global\"\nfun f() {\n  val name = \"local\"\n  println(name)\n}\nf()\nprintln(name)"))
        // 関数の中のブロックの変数は、関数の外から見えない（トップレベルの block の変数も関数から見えない）
        assertEquals(listOf("ok"), output("var flag = false\nfun set() {\n  if (true) {\n    val inner = true\n    flag = inner\n  }\n}\nset()\nif (flag) { println(\"ok\") }"))
    }

    // 関数の中の if・while・for・break・continue と、ループの中からの return
    @Test
    fun controlFlowInsideFunctions() {
        assertEquals(
            listOf("1", "3", "5", "w2", "w1", "even"),
            output(
                "fun odds() {\n  for (i in 1..6) {\n    if (i % 2 == 0) { continue }\n    if (i > 5) { break }\n    println(i)\n  }\n}\n" +
                    "fun down() {\n  var n = 2\n  while (n > 0) {\n    if (n == 2) { println(\"w2\") } else { println(\"w1\") }\n    n = n - 1\n  }\n}\n" +
                    "fun parity() {\n  if (4 % 2 == 0) {\n    println(\"even\")\n    return\n  }\n  println(\"odd\")\n}\nodds()\ndown()\nparity()",
            ),
        )
        // return は入れ子のループ（for の中の while）を抜けて呼び出し元へ戻る
        assertEquals(
            listOf("0", "1", "10", "back"),
            output(
                "fun search() {\n  for (i in 0..3) {\n    var j = 0\n    while (j < 3) {\n      if (i == 1 && j == 1) { return }\n      println(i * 10 + j)\n" +
                    "      j = j + 1\n      if (j == 2) { break }\n    }\n  }\n}\nsearch()\nprintln(\"back\")",
            ),
        )
    }

    @Test
    fun waitTicksInsideFunctions() {
        val (vm, ticks) = runToEnd("fun pause() {\n  println(\"before\")\n  waitTicks(5)\n  println(\"after\")\n}\nfun twice() {\n  pause()\n  waitTicks(2)\n  pause()\n}\ntwice()\nprintln(\"done\")")
        assertEquals(listOf("before", "after", "before", "after", "done"), vm.console.history)
        assertTrue(ticks >= 12, "ticks $ticks")
    }

    // 実行時エラーは関数の中の位置で報告され、そこで止まる
    @Test
    fun runtimeErrorsInsideFunctions() {
        val (vm, _) = runToEnd("val zero = 0\nfun fail() {\n  println(1)\n  println(1 / zero)\n}\nfail()\nprintln(\"never\")")
        assertEquals(VmState.ERROR, vm.state)
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, vm.error?.code)
        assertEquals(4, vm.error?.position?.line)
        assertEquals(listOf("1"), vm.console.history)
    }

    // CALL・RETURN もそれぞれ1命令として予算を消費する
    @Test
    fun callAndReturnUseTheBudget() {
        val program = compile("fun f() { }\nf()")
        assertEquals(listOf(Opcode.CALL, Opcode.POP, Opcode.HALT, Opcode.RETURN), program.instructions.map { it.opcode })
        val vm = CsVm(program)
        assertEquals(1, vm.run(1))
        assertEquals(3, vm.snapshot()!!.pc)
        assertEquals(1, vm.snapshot()!!.callStack.size)
        assertEquals(1, vm.run(1))
        assertEquals(listOf(Value.UnitValue), vm.snapshot()!!.stack)
        assertEquals(2, vm.run(100))
        assertEquals(VmState.HALTED, vm.state)
    }

    // 呼び出しを繰り返す終わらないプログラムも、1 tick の予算どおりに制御を返し、Scheduler の上限・公平性を守る
    @Test
    fun callsStayWithinBudgetAndFair() {
        val looping = compile("var n = 0\nfun step() {\n  n = n + 1\n  if (n > 1000) { n = 0 }\n}\nwhile (true) { step() }")
        val nested = compile("fun a() { b() }\nfun b() { c() }\nfun c() { for (i in 0..3) { continue } }\nwhile (true) { a() }")
        val vm = CsVm(looping)
        assertEquals(16, vm.run(16))
        assertEquals(VmState.RUNNING, vm.state)
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) looping else nested)) }
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

    // 呼び出しの連鎖は 16 段まで。17 段はコンパイル時に拒否する
    @Test
    fun callDepthLimit() {
        fun chain(depth: Int) = (1..depth).joinToString("") { "fun f$it() {\n" + (if (it < depth) "f${it + 1}()\n" else "println($it)\n") + "}\n" } + "f1()"
        assertEquals(listOf("16"), output(chain(VmLimits.MAX_CALL_DEPTH)))
        val vm = CsVm(compile(chain(VmLimits.MAX_CALL_DEPTH)))
        var deepest = 0
        while (vm.state == VmState.RUNNING) {
            vm.run(1)
            deepest = maxOf(deepest, vm.snapshot()?.callStack?.size ?: 0)
        }
        assertEquals(VmLimits.MAX_CALL_DEPTH, deepest)
        val result = Compiler.compileSource(chain(VmLimits.MAX_CALL_DEPTH + 1))
        assertNull(result.program)
        assertEquals(listOf(DiagnosticCode.COMP_CALL_TOO_DEEP), result.diagnostics.map { it.code })
        assertEquals(1, result.diagnostics.single().position.line)
        // 深い連鎖が無い関数がいくつあっても、深さには数えない
        assertEquals(listOf("ok"), output((1..40).joinToString("") { "fun g$it() { }\n" } + (1..40).joinToString("") { "g$it()\n" } + "println(\"ok\")"))
    }

    // ---- 手で組んだ不正なバイトコード（Compiler は出さない） ----

    private val origin = SourcePosition(0, 1, 1)

    private fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)

    // 自分自身を呼ぶ関数。VM は上限の深さで CS_RUN_CALL_DEPTH_EXCEEDED（第3.8e段階で CS_RUN_STACK_OVERFLOW から分けた）にし、サーバーを止めない
    @Test
    fun vmStopsRunawayCallsAtTheDepthLimit() {
        val recursive = CompiledProgram(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.CALL), op(Opcode.POP), op(Opcode.RETURN)), emptyList(), 0, 1, listOf(FunctionInfo(3, 0)))
        val vm = CsVm(recursive)
        vm.run(10_000)
        assertEquals(VmState.ERROR, vm.state)
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, vm.error?.code)
    }

    @Test
    fun invalidFunctionBytecodeIsUnverifiable() {
        val one = listOf(Constant.IntConstant(1))
        fun program(instructions: List<Instruction>, functions: List<FunctionInfo>, variables: Int = 0) = CompiledProgram(instructions, one, variables, 2, functions)
        val valid = program(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_CONST), op(Opcode.STORE_LOCAL), op(Opcode.RETURN)), listOf(FunctionInfo(3, 1)))
        assertNotNull(SnapshotVerifier.framesOf(valid))
        val cases = mapOf(
            // トップレベルのローカル変数・RETURN、関数の中の HALT
            "local at top level" to program(listOf(op(Opcode.PUSH_CONST), op(Opcode.STORE_LOCAL), op(Opcode.HALT)), emptyList()),
            "return at top level" to program(listOf(op(Opcode.RETURN), op(Opcode.HALT)), emptyList()),
            "halt in function" to program(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.HALT)), listOf(FunctionInfo(3, 0))),
            // 関数の末尾から次の関数へ落ちる・関数の外へ分岐する
            "falls into next function" to program(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_BOOL, 1), op(Opcode.POP), op(Opcode.RETURN)), listOf(FunctionInfo(3, 0), FunctionInfo(4, 0))),
            "jumps out of function" to program(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.JUMP, 2)), listOf(FunctionInfo(3, 0))),
            // RETURN の時点でスタックが残る（呼び出し元のスタックが空でない CALL は第3.8d段階から正しい形: FunctionArgumentTest）
            "return with stack" to program(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_CONST), op(Opcode.RETURN)), listOf(FunctionInfo(3, 0))),
            // 呼び出しごとに未初期化のローカル変数・未知の関数・関数表の不正
            "uninitialized local" to program(listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.LOAD_LOCAL), op(Opcode.POP), op(Opcode.RETURN)), listOf(FunctionInfo(3, 1))),
            "unknown function" to program(listOf(op(Opcode.CALL, 1), op(Opcode.POP), op(Opcode.HALT), op(Opcode.RETURN)), listOf(FunctionInfo(3, 0))),
            "entry at 0" to program(listOf(op(Opcode.RETURN)), listOf(FunctionInfo(0, 0))),
            "entries out of order" to program(listOf(op(Opcode.HALT), op(Opcode.RETURN), op(Opcode.RETURN)), listOf(FunctionInfo(2, 0), FunctionInfo(1, 0))),
            "entry out of range" to program(listOf(op(Opcode.HALT)), listOf(FunctionInfo(1, 0))),
            "too many locals" to program(listOf(op(Opcode.HALT), op(Opcode.RETURN)), listOf(FunctionInfo(1, VmLimits.MAX_VARIABLES + 1))),
            // 関数が大域変数を別の型で初期化する（呼び出し元の型と合わない）
            "global type changed" to program(
                listOf(op(Opcode.PUSH_CONST), op(Opcode.STORE), op(Opcode.CALL), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_BOOL, 1), op(Opcode.STORE), op(Opcode.RETURN)),
                listOf(FunctionInfo(5, 0)), variables = 1,
            ),
        )
        for ((label, program) in cases) assertNull(SnapshotVerifier.framesOf(program), label)
    }

    // 関数が大域変数を初期化すると、戻った後は初期化済みとして読める。呼び出し前に初期化済みの変数は戻った後も初期化済み
    @Test
    fun globalStatesFlowThroughCalls() {
        val one = listOf(Constant.IntConstant(1))
        val initializes = CompiledProgram(
            listOf(op(Opcode.CALL), op(Opcode.POP), op(Opcode.LOAD), op(Opcode.POP), op(Opcode.HALT), op(Opcode.PUSH_CONST), op(Opcode.STORE), op(Opcode.RETURN)),
            one, 1, 1, listOf(FunctionInfo(5, 0)),
        )
        assertNotNull(SnapshotVerifier.framesOf(initializes))
        // 一部の経路でだけ初期化する関数の後では読めない
        val maybe = CompiledProgram(
            listOf(
                op(Opcode.CALL), op(Opcode.POP), op(Opcode.LOAD), op(Opcode.POP), op(Opcode.HALT),
                op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 9), op(Opcode.PUSH_CONST), op(Opcode.STORE), op(Opcode.RETURN),
            ),
            one, 1, 1, listOf(FunctionInfo(5, 0)),
        )
        assertNull(SnapshotVerifier.framesOf(maybe))
        // 2 か所から呼ばれる関数: 一方では初期化前、もう一方では初期化後。関数の中では読めないが、初期化後の呼び出し元は戻った後も読める
        val source = "fun f() { println(1) }\nf()\nval x = 2\nf()\nprintln(x)"
        assertEquals(listOf("1", "1", "2"), output(source))
    }

    // ---- 呼び出しフレームを含むスナップショットの検証 ----

    private val nestedProgram = compile("var g = 1\nfun inner() {\n  val t = g + 1\n  waitTicks(3)\n  g = t\n}\nfun outer() {\n  val s = \"s\"\n  inner()\n  println(s)\n}\nouter()\nprintln(g)")

    // inner の待機中（呼び出しフレーム 2 つ）
    private fun nestedWaiting(): VmSnapshot {
        val vm = CsVm(nestedProgram)
        while (vm.state != VmState.WAITING) vm.run(1)
        return vm.snapshot()!!
    }

    private fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(nestedProgram, snapshot)

    @Test
    fun validCallStacksPass() {
        val waiting = nestedWaiting()
        assertNull(problem(waiting))
        assertEquals(2, waiting.callStack.size)
        assertEquals(listOf(1, 0), waiting.callStack.map { it.function })
        // 定数は 1・3・"s" の順（関数はトップレベルの後にコンパイルする）
        assertEquals(listOf(listOf<Value?>(Value.StringValue(2)), listOf<Value?>(Value.IntValue(2))), waiting.callStack.map { it.locals })
        assertEquals(listOf("s", "2"), runToEnd(CsVm.restore(nestedProgram, waiting)!!).console.history)
    }

    private fun runToEnd(vm: CsVm): CsVm {
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1)
        return vm
    }

    @Test
    fun invalidCallStacksAreRejected() {
        val base = nestedWaiting()
        val (outerFrame, innerFrame) = base.callStack
        fun with(vararg frames: CallFrameSnapshot) = base.copy(callStack = frames.toList())
        // フレームが足りない・多すぎる・関数の番号や戻り先が CALL と合わない
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame)))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with()))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(innerFrame, outerFrame)))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame, innerFrame, innerFrame)))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(base.copy(callStack = List(VmLimits.MAX_CALL_DEPTH + 1) { outerFrame })))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame.copy(function = 0), innerFrame)))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame.copy(function = 5), innerFrame)))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame, innerFrame.copy(returnPc = innerFrame.returnPc + 1))))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame, innerFrame.copy(returnPc = 0))))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame, innerFrame.copy(returnPc = Int.MIN_VALUE))))
        // ローカル変数の数・型・初期化状態
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(with(outerFrame, innerFrame.copy(locals = emptyList()))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(with(outerFrame, innerFrame.copy(locals = listOf(null)))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(with(outerFrame, innerFrame.copy(locals = listOf(Value.BoolValue(true))))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(with(outerFrame.copy(locals = listOf(null)), innerFrame)))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(with(outerFrame.copy(locals = listOf(Value.IntValue(1))), innerFrame)))
        // CALL の時点で初期化済みの大域変数は、関数の中でも値が入っている
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(null))))
        // トップレベルの位置に呼び出しフレームがある・関数の中の位置にフレームが無い
        // g = 1 の後、outer() を呼ぶ CALL の直前
        val top = CsVm(nestedProgram).also { it.run(2) }.snapshot()!!
        assertNull(problem(top))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(top.copy(callStack = listOf(outerFrame))))
        assertEquals(SnapshotProblem.INVALID_CALL_STACK, problem(base.copy(callStack = emptyList(), variables = base.variables)))
        assertNull(CsVm.restore(nestedProgram, with(innerFrame, outerFrame)))
    }

    // 呼び出しフレームの関数の先頭（CALL の直後）と、RETURN の直後（呼び出し元に Unit が積まれた状態）の保存・復元
    @Test
    fun snapshotsAroundCallAndReturn() {
        val program = compile("var g = 0\nfun f() {\n  var local = 7\n  g = local\n}\nf()\nprintln(g)")
        val vm = CsVm(program)
        vm.run(3)
        val atEntry = vm.snapshot()!!
        assertEquals(program.functions[0].entry, atEntry.pc)
        assertEquals(listOf(CallFrameSnapshot(0, 3, listOf(null))), atEntry.callStack)
        assertEquals(listOf("7"), runToEnd(CsVm.restore(program, atEntry)!!).console.history)
        while (vm.snapshot()!!.callStack.isNotEmpty()) vm.run(1)
        val afterReturn = vm.snapshot()!!
        assertEquals(listOf(Value.UnitValue), afterReturn.stack)
        assertEquals(3, afterReturn.pc)
        assertEquals(listOf("7"), runToEnd(CsVm.restore(program, afterReturn)!!).console.history)
    }

    // ---- 性能（参考値） ----

    @Test
    fun measureFunctions() {
        val looping = compile("var n = 0\nfun a() { b() }\nfun b() {\n  n = n + 1\n  if (n > 1000) { n = 0 }\n}\nwhile (true) { a() }")
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(looping)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(200) { scheduler.tick { fast } }
        val start = System.nanoTime()
        repeat(1000) { scheduler.tick { fast } }
        println("[3.8c measure] scheduler tick with 1000 VMs calling functions in a loop (budget 4096): ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 1000)} ms per tick")
        // 16 段の呼び出しの連鎖（それぞれ for を持つ）と、while を持つ関数 30 個（ソースの上限 4096 単位以内）
        val chain = (1..16).joinToString("") { "fun c$it() {\n  var v = $it\n  for (i in 0..2) {\n    v = v + i\n  }\n" + (if (it < 16) "  c${it + 1}()\n" else "  waitTicks(1)\n") + "}\n" }
        val many = (1..30).joinToString("") { "fun m$it() {\n  var w = 0\n  while (w < $it) { w = w + 1 }\n}\n" }
        val source = "var total = 0\n" + chain + many + "c1()\n" + (1..30).joinToString("") { "m$it()\n" }
        val program = compile(source)
        repeat(50) { compile(source) }
        val compileStart = System.nanoTime()
        repeat(200) { compile(source) }
        val compileMillis = (System.nanoTime() - compileStart) / 1_000_000.0 / 200
        val verifyStart = System.nanoTime()
        repeat(200) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0 / 200
        println(
            "[3.8c measure] ${program.functions.size} functions (call chain 16), ${program.instructions.size} instructions, ${source.length} UTF-16 units: " +
                "compile ${"%.3f".format(compileMillis)} ms (verification included), verification ${"%.3f".format(verifyMillis)} ms",
        )
        // 16 段の呼び出しの途中（待機中）のスナップショットの検証と復元
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertEquals(16, snapshot.callStack.size)
        repeat(200) { assertNotNull(CsVm.restore(program, snapshot)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(program, snapshot) }
        println("[3.8c measure] verify + restore with 16 call frames: ${"%.3f".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000)} ms per VM")
    }

    // ---- 無作為に作った関数のプログラムと、Kotlin で書いた参照の実行器の比較 ----

    private sealed interface Stmt

    private data class Add(val amount: Int) : Stmt
    private data class Print(val name: String) : Stmt
    private data class Local(val name: String, val add: Int) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class When(val threshold: Int, val then: List<Stmt>, val otherwise: List<Stmt>?) : Stmt
    private data class RangeLoop(val variable: String, val count: Int, val body: List<Stmt>) : Stmt
    private data class Exit(val isBreak: Boolean, val threshold: Int) : Stmt
    private data class Call(val function: Int) : Stmt

    // if (total > threshold) { return }（関数の中にだけ置く）
    private data class Return(val threshold: Int) : Stmt

    private class BreakSignal : RuntimeException(null, null, false, false)

    private class ContinueSignal : RuntimeException(null, null, false, false)

    private class ReturnSignal : RuntimeException(null, null, false, false)

    private class Reference(private val functions: List<List<Stmt>>) {
        val output = ArrayList<String>()
        var total = 1
        var waited = 0
        private var scopes = ArrayList<HashMap<String, Int>>()

        private fun lookup(name: String) = if (name == "total") total else scopes.asReversed().firstNotNullOf { it[name] }

        fun runMain(statements: List<Stmt>) = inBlock(statements)

        private fun run(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Add -> total += statement.amount
                    is Print -> output.add(lookup(statement.name).toString())
                    is Local -> scopes.last()[statement.name] = total + statement.add
                    is Wait -> waited += statement.ticks
                    is When -> (if (total > statement.threshold) statement.then else statement.otherwise)?.let(::inBlock)
                    is RangeLoop -> for (value in 0 until statement.count) {
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
                    is Exit -> if (total > statement.threshold) throw if (statement.isBreak) BreakSignal() else ContinueSignal()
                    // 関数の中ではトップレベルのスコープだけが見える（呼び出し元のローカル変数は見えない）
                    is Call -> {
                        val saved = scopes
                        scopes = arrayListOf(saved[0])
                        try {
                            inBlock(functions[statement.function])
                        } catch (signal: ReturnSignal) {
                            // 呼び出し元へ戻る
                        } finally {
                            scopes = saved
                        }
                    }
                    is Return -> if (total > statement.threshold) throw ReturnSignal()
                }
            }
        }

        private fun inBlock(statements: List<Stmt>) {
            scopes.add(HashMap())
            try {
                run(statements)
            } finally {
                scopes.removeAt(scopes.lastIndex)
            }
        }
    }

    // function は生成中の関数の番号（トップレベルは -1）。関数 k は番号の大きい関数だけを呼ぶ（再帰しない）
    private class Generator(private val random: Random, private val functionCount: Int) {
        private var counters = 0

        fun block(depth: Int, visible: Set<String>, function: Int, inLoop: Boolean = false): List<Stmt> {
            val names = visible.toMutableSet()
            val declared = HashSet<String>()
            val statements = ArrayList<Stmt>()
            var calls = 0
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(11)) {
                    0 -> statements.add(Add(random.nextInt(-3, 4)))
                    1 -> statements.add(Print(names.random(random)))
                    2 -> listOf("a", "b").filter { it !in declared }.randomOrNull(random)?.let {
                        declared.add(it)
                        names.add(it)
                        statements.add(Local(it, random.nextInt(-2, 3)))
                    }
                    3 -> statements.add(Wait(random.nextInt(0, 3)))
                    4 -> if (depth < 2) {
                        statements.add(When(random.nextInt(-3, 4), block(depth + 1, names, function, inLoop), if (random.nextBoolean()) block(depth + 1, names, function, inLoop) else null))
                    }
                    5 -> if (depth < 2 && counters < 20) {
                        val variable = "i${counters++}"
                        statements.add(RangeLoop(variable, random.nextInt(0, 4), block(depth + 1, names + variable, function, inLoop = true)))
                    }
                    6 -> if (inLoop) statements.add(Exit(random.nextBoolean(), random.nextInt(-3, 6)))
                    7 -> if (function >= 0) statements.add(Return(random.nextInt(-3, 6)))
                    else -> if (calls < 2 && function + 1 < functionCount) {
                        statements.add(Call(random.nextInt(function + 1, functionCount)))
                        calls++
                    }
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
            is RangeLoop -> "${indent}for (${statement.variable} in 0 until ${statement.count}) {\n" + render(statement.body, "$indent  ") + "$indent}\n"
            is Exit -> "${indent}if (total > ${statement.threshold}) { ${if (statement.isBreak) "break" else "continue"} }\n"
            is Call -> "${indent}f${statement.function}()\n"
            is Return -> "${indent}if (total > ${statement.threshold}) { return }\n"
        }
    }.replace("+ -", "- ")

    // 関数の定義はトップレベルの文の前後に無作為に置く（定義の順に依存しない）。一部はすべての命令の境界で保存・復元して比べる
    @Test
    fun randomFunctionProgramsMatchReference() {
        val random = Random(38)
        var compared = 0
        var withNestedCalls = 0
        var withReturn = 0
        var restoredPrograms = 0
        repeat(400) { index ->
            val functionCount = random.nextInt(1, 6)
            val generator = Generator(random, functionCount)
            val bodies = (0 until functionCount).map { generator.block(0, setOf("total"), it) }
            val main = generator.block(0, setOf("total"), -1) + (0 until functionCount).map { Call(it) }
            val definitions = bodies.mapIndexed { k, body -> "fun f$k() {\n" + render(body, "  ") + "}\n" }
            val (before, after) = definitions.partition { random.nextBoolean() }
            val source = before.joinToString("") + "var total = 1\n" + render(main, "") + after.joinToString("") + "println(total)"
            if (source.length > 4096) return@repeat
            val result = Compiler.compileSource(source)
            val limits = setOf(DiagnosticCode.LEX_TOO_MANY_TOKENS, DiagnosticCode.PARSE_TOO_MANY_NODES)
            if (result.diagnostics.any { it.code in limits }) return@repeat
            val program = result.program ?: error("compile failed: ${result.diagnostics.map { it.code }}\n$source")
            val expected = Reference(bodies).also { it.runMain(main) }
            val (vm, ticks) = runToEnd(source, 1_000_000)
            assertEquals(VmState.HALTED, vm.state, source)
            assertEquals((expected.output + expected.total.toString()).takeLast(Console.MAX_LINES), vm.console.history, source)
            assertTrue(ticks >= expected.waited, source)
            compared++
            if (bodies.any { render(it, "").contains(Regex("f\\d\\(\\)")) }) withNestedCalls++
            if ("{ return }" in source) withReturn++
            if (index % 25 == 0) {
                restoredPrograms++
                restoreAtEveryBoundary(program, source)
            }
        }
        assertTrue(compared >= 350, "compared $compared")
        assertTrue(withNestedCalls >= 100 && withReturn >= 100, "nested calls $withNestedCalls, return $withReturn")
        println("[3.8c random] $compared programs compared, $withNestedCalls with calls inside functions, $withReturn with return, $restoredPrograms restored at every boundary")
    }

    // 1歩ずつ進め、各境界のスナップショットから復元して最後まで実行した結果が、中断しない実行と一致する
    private fun restoreAtEveryBoundary(program: CompiledProgram, source: String) {
        fun step(vm: CsVm) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(1)
        }
        fun finished(vm: CsVm) = vm.state == VmState.HALTED || vm.state == VmState.ERROR
        fun finish(vm: CsVm): List<String> {
            var steps = 0
            while (!finished(vm)) {
                step(vm)
                check(++steps < 200_000) { "did not finish: $source" }
            }
            return vm.console.history
        }
        val expected = finish(CsVm(program))
        val original = CsVm(program)
        var cut = 0
        while (!finished(original) && cut < 3000) {
            val snapshot = original.snapshot()!!
            val restored = CsVm.restore(program, snapshot)
            assertNotNull(restored, "rejected at $cut: ${SnapshotVerifier.verify(program, snapshot)} in $source")
            assertEquals(snapshot, restored!!.snapshot())
            assertEquals(expected, finish(restored), "cut $cut of $source")
            step(original)
            cut++
        }
    }
}
