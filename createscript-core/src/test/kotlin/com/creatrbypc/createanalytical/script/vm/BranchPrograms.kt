package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition

// 分岐・ループを含むバイトコード（第3.7a段階。Parser・Compiler は未対応のため手で組む）。
// 3.7b 以降の Compiler が出す予定の形（11.5）に合わせている
internal object BranchPrograms {
    private val origin = SourcePosition(0, 1, 1)

    fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)

    fun program(vararg instructions: Instruction, constants: List<Constant> = emptyList(), variables: Int = 0) =
        CompiledProgram(instructions.toList(), constants, variables, 8)

    private val println = HostFunction.PRINTLN.id
    private val waitTicks = HostFunction.WAIT_TICKS.id

    // var i = 3; while (i > 0) { println(i); waitTicks(1); i = i - 1 }  → 3 2 1
    val COUNTDOWN = program(
        op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0),
        op(Opcode.LOAD, 0), op(Opcode.PUSH_CONST, 1), op(Opcode.GREATER), op(Opcode.JUMP_IF_FALSE, 17),
        op(Opcode.LOAD, 0), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.PUSH_CONST, 2), op(Opcode.CALL_HOST, waitTicks), op(Opcode.POP),
        op(Opcode.LOAD, 0), op(Opcode.PUSH_CONST, 2), op(Opcode.SUBTRACT), op(Opcode.STORE, 0),
        op(Opcode.JUMP, 2),
        op(Opcode.HALT),
        constants = listOf(Constant.IntConstant(3), Constant.IntConstant(0), Constant.IntConstant(1)),
        variables = 1,
    )

    // val flag = "a" == "a"（別の定数番号で同じ内容）; if (flag) { val x = 5; println(x) } else { val y = "yes"; println(y) }
    // println(false && 5 / 0 == 0); println(!flag); println(5 <= 0)  → 5 false false false
    // 合流点（17）では x・y とも MaybeInitialized になる
    val BRANCHES = program(
        op(Opcode.PUSH_CONST, 1), op(Opcode.PUSH_CONST, 2), op(Opcode.EQUAL), op(Opcode.STORE, 0),
        op(Opcode.LOAD, 0), op(Opcode.JUMP_IF_FALSE, 12),
        op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 1), op(Opcode.LOAD, 1), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.JUMP, 17),
        op(Opcode.PUSH_CONST, 4), op(Opcode.STORE, 2), op(Opcode.LOAD, 2), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        // false && 5 / 0 == 0（右辺は評価しない）
        op(Opcode.PUSH_BOOL, 0), op(Opcode.JUMP_IF_FALSE, 25),
        op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 3), op(Opcode.DIVIDE), op(Opcode.PUSH_CONST, 3), op(Opcode.EQUAL),
        op(Opcode.JUMP, 26),
        op(Opcode.PUSH_BOOL, 0),
        op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.LOAD, 0), op(Opcode.NOT), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 3), op(Opcode.LESS_EQUAL), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.HALT),
        constants = listOf(Constant.IntConstant(5), Constant.StringConstant("a"), Constant.StringConstant("a"), Constant.IntConstant(0), Constant.StringConstant("yes")),
        variables = 3,
    )

    // var j = 0; while (j < 3) { val sq = j * j; println(sq); waitTicks(0); j = j + 1 }  → 0 1 4
    // ループの先頭（2）では sq が MaybeInitialized になる
    val SQUARES = program(
        op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0),
        op(Opcode.LOAD, 0), op(Opcode.PUSH_CONST, 1), op(Opcode.LESS), op(Opcode.JUMP_IF_FALSE, 21),
        op(Opcode.LOAD, 0), op(Opcode.LOAD, 0), op(Opcode.MULTIPLY), op(Opcode.STORE, 1),
        op(Opcode.LOAD, 1), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.PUSH_CONST, 0), op(Opcode.CALL_HOST, waitTicks), op(Opcode.POP),
        op(Opcode.LOAD, 0), op(Opcode.PUSH_CONST, 2), op(Opcode.ADD), op(Opcode.STORE, 0),
        op(Opcode.JUMP, 2),
        op(Opcode.HALT),
        constants = listOf(Constant.IntConstant(0), Constant.IntConstant(3), Constant.IntConstant(1)),
        variables = 2,
    )

    // var k = 2; while (true) { println(6 / k); k = k - 1 }  → 3 6 の後に 0 除算
    val FAILS_IN_LOOP = program(
        op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0),
        op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 15),
        op(Opcode.PUSH_CONST, 1), op(Opcode.LOAD, 0), op(Opcode.DIVIDE), op(Opcode.CALL_HOST, println), op(Opcode.POP),
        op(Opcode.LOAD, 0), op(Opcode.PUSH_CONST, 2), op(Opcode.SUBTRACT), op(Opcode.STORE, 0),
        op(Opcode.JUMP, 2),
        op(Opcode.HALT),
        op(Opcode.HALT),
        constants = listOf(Constant.IntConstant(2), Constant.IntConstant(6), Constant.IntConstant(1)),
        variables = 1,
    )

    val ALL = mapOf("countdown" to COUNTDOWN, "branches" to BRANCHES, "squares" to SQUARES, "fails in loop" to FAILS_IN_LOOP)
}
