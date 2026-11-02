package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CsVmTest {
    private val position = SourcePosition(0, 1, 1)

    private fun vm(source: String): CsVm {
        val result = Compiler.compileSource(source)
        return CsVm(result.program ?: error("compile failed: ${result.diagnostics.map { it.code }}"))
    }

    // 待機を挟まずに最後まで実行する
    private fun runToEnd(source: String): CsVm {
        val machine = vm(source)
        machine.run(BUDGET)
        return machine
    }

    private fun output(source: String): List<String> {
        val machine = runToEnd(source)
        assertEquals(VmState.HALTED, machine.state, "error: ${machine.error}")
        return machine.console.history
    }

    private fun runtimeError(source: String): DiagnosticCode {
        val machine = runToEnd(source)
        assertEquals(VmState.ERROR, machine.state)
        assertEquals(DiagnosticPhase.RUNTIME, machine.error!!.phase)
        return machine.error!!.code
    }

    private fun raw(vararg instructions: Instruction, constants: List<Constant> = emptyList(), variables: Int = 0) =
        CsVm(CompiledProgram(instructions.toList(), constants, variables, 0))

    private fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, position)

    // ---- 基本 ----

    @Test
    fun variablesAndPrintln() {
        assertEquals(listOf("1", "Hi", ""), output("val a = 1\nval s = \"Hi\"\nprintln(a)\nprintln(s)\nprintln()"))
        assertEquals(listOf("42", "b"), output("var x = 1\nx = x + 41\nprintln(x)\nvar s = \"a\"\ns = \"b\"\nprintln(s)"))
    }

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(listOf("6"), output("println(1 + 2 * 3 - 10 / 3 % 2)"))
        assertEquals(listOf("-6", "9", "-3", "-1", "1"), output("println(-2 * 3)\nprintln((1 + 2) * 3)\nprintln(-7 / 2)\nprintln(-7 % 2)\nprintln(7 % -2)"))
    }

    @Test
    fun int32Bounds() {
        assertEquals(listOf("2147483647", "-2147483648", "0"), output("println(2147483647)\nprintln(-2147483648)\nprintln(-2147483648 % -1)"))
    }

    // ---- 実行時エラー ----

    @Test
    fun overflow() {
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, runtimeError("val x = 2147483647 + 1"))
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, runtimeError("val x = -2147483648 - 1"))
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, runtimeError("val x = 65536 * 65536"))
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, runtimeError("val x = - -2147483648"))
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, runtimeError("val x = -2147483648 / -1"))
        // エラーの位置は演算子
        val machine = runToEnd("val a = 1\nval x = 2147483647 + a")
        assertEquals(SourcePosition(29, 2, 20), machine.error!!.position)
    }

    @Test
    fun divisionByZero() {
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, runtimeError("val x = 1 / 0"))
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, runtimeError("val z = 0\nval x = 5 % z"))
    }

    @Test
    fun errorStopsExecution() {
        val machine = runToEnd("println(1)\nval x = 1 / 0\nprintln(2)")
        assertEquals(listOf("1"), machine.console.history)
        assertEquals(0, machine.run(BUDGET))
    }

    // ---- waitTicks（D6・A23） ----

    @Test
    fun waitTicksZeroYieldsUntilNextTick() {
        val machine = vm("println(1)\nwaitTicks(0)\nprintln(2)")
        machine.run(BUDGET)
        assertEquals(VmState.WAITING, machine.state)
        assertEquals(listOf("1"), machine.console.history)
        // 同じ tick にもう一度 run しても進まない
        assertEquals(0, machine.run(BUDGET))
        machine.countWaitTick()
        assertEquals(VmState.RUNNING, machine.state)
        machine.run(BUDGET)
        assertEquals(VmState.HALTED, machine.state)
        assertEquals(listOf("1", "2"), machine.console.history)
    }

    @Test
    fun waitTicksPositive() {
        val machine = vm("waitTicks(3)\nprintln(\"done\")")
        machine.run(BUDGET)
        assertEquals(3, machine.waitRemaining)
        machine.countWaitTick()
        machine.countWaitTick()
        assertEquals(VmState.WAITING, machine.state)
        assertEquals(1, machine.waitRemaining)
        machine.countWaitTick()
        assertEquals(VmState.RUNNING, machine.state)
        machine.run(BUDGET)
        assertEquals(listOf("done"), machine.console.history)
        assertEquals(VmState.HALTED, machine.state)
    }

    @Test
    fun resumePushesUnitExactlyOnce() {
        // 再開時の Unit は直後の POP で捨てられる。二重に積まれると後続の STORE/演算でスタックが崩れる
        val machine = vm("val a = 2\nwaitTicks(1)\nwaitTicks(1)\nval b = a * 21\nprintln(b)")
        machine.run(BUDGET)
        machine.countWaitTick()
        machine.run(BUDGET)
        assertEquals(VmState.WAITING, machine.state)
        machine.countWaitTick()
        machine.run(BUDGET)
        assertEquals(VmState.HALTED, machine.state)
        assertEquals(listOf("42"), machine.console.history)
    }

    @Test
    fun negativeWait() {
        val machine = runToEnd("val t = -5\nwaitTicks(t)")
        assertEquals(DiagnosticCode.RUN_NEGATIVE_WAIT, machine.error!!.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(-5)), machine.error!!.arguments)
    }

    @Test
    fun countWaitTickIgnoredWhenNotWaiting() {
        val machine = vm("println(1)")
        machine.countWaitTick()
        assertEquals(VmState.RUNNING, machine.state)
    }

    // ---- 命令予算（A26） ----

    @Test
    fun instructionBudget() {
        val machine = vm("println(1)\nprintln(2)\nprintln(3)")
        assertEquals(0, machine.run(0))
        assertEquals(4, machine.run(4))
        assertEquals(VmState.RUNNING, machine.state)
        assertEquals(listOf("1"), machine.console.history)
        // 全10命令（PUSH・CALL・POP ×3 と HALT）のうち残り 6 命令
        assertEquals(6, machine.run(BUDGET))
        assertEquals(VmState.HALTED, machine.state)
        assertEquals(0, machine.run(BUDGET))
    }

    // ---- コンソール（D5・A3） ----

    @Test
    fun consoleHistoryAndTruncation() {
        val machine = runToEnd((1..33).joinToString("\n") { "println($it)" })
        assertEquals(32, machine.console.history.size)
        assertEquals("2", machine.console.history.first())
        assertEquals("a".repeat(64), Console.fit("a".repeat(64)))
        assertEquals("a".repeat(63) + "…", Console.fit("a".repeat(65)))
        // 切る位置がサロゲートペアの間なら、ペアの手前で切る
        assertEquals("a".repeat(62) + "…", Console.fit("a".repeat(62) + "😀" + "b"))
        val long = "x".repeat(100)
        assertEquals(listOf("x".repeat(63) + "…"), output("val s = \"$long\"\nprintln(s)"))
    }

    // ---- 不正なバイトコード ----

    @Test
    fun invalidBytecodeIsRejectedSafely() {
        fun code(machine: CsVm): DiagnosticCode? {
            machine.run(BUDGET)
            return machine.error?.code
        }
        val invalid = DiagnosticCode.RUN_INVALID_BYTECODE
        assertEquals(invalid, code(raw(op(Opcode.POP))))
        assertEquals(invalid, code(raw(op(Opcode.PUSH_CONST, 3))))
        assertEquals(invalid, code(raw(op(Opcode.LOAD, 0), variables = 1)))
        assertEquals(invalid, code(raw(op(Opcode.LOAD, 5), variables = 1)))
        assertEquals(invalid, code(raw(op(Opcode.CALL_HOST, 99))))
        // 型タグの不一致（String の加算）
        assertEquals(invalid, code(raw(op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 0), op(Opcode.ADD), constants = listOf(Constant.StringConstant("a")))))
        // Unit を変数に入れる
        assertEquals(invalid, code(raw(op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.STORE, 0), variables = 1)))
        // HALT が無く、命令列の外へ出る
        assertEquals(invalid, code(raw(op(Opcode.PUSH_CONST, 0), op(Opcode.POP), constants = listOf(Constant.IntConstant(1)))))
        // 変数の数が上限外
        val tooMany = raw(op(Opcode.HALT), variables = VmLimits.MAX_VARIABLES + 1)
        assertEquals(VmState.ERROR, tooMany.state)
        assertEquals(invalid, tooMany.error!!.code)
        // スタックの上限
        val pushes = Array(VmLimits.MAX_STACK_DEPTH + 1) { op(Opcode.PUSH_CONST, 0) }
        assertEquals(DiagnosticCode.RUN_STACK_OVERFLOW, code(raw(*pushes, constants = listOf(Constant.IntConstant(1)))))
        assertNull(runToEnd("println(1)").error)
    }

    private companion object {
        const val BUDGET = 100_000
    }
}
