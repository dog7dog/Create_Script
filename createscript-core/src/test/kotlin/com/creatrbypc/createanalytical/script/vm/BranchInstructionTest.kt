package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.BranchPrograms.op
import com.creatrbypc.createanalytical.script.vm.BranchPrograms.program
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.SlotType
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.VariableState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

// 第3.7a段階の VM 基盤（Boolean・比較・分岐命令と、その検証）。構文は未対応のため、バイトコードを手で組んで確かめる
class BranchInstructionTest {
    private val ints = listOf(Constant.IntConstant(Int.MIN_VALUE), Constant.IntConstant(0), Constant.IntConstant(Int.MAX_VALUE))
    private val printlnId = HostFunction.PRINTLN.id

    // 式を1つ評価して表示するプログラム
    private fun printed(vararg expression: Instruction, constants: List<Constant> = ints): List<String> {
        val vm = CsVm(program(*expression, op(Opcode.CALL_HOST, printlnId), op(Opcode.POP), op(Opcode.HALT), constants = constants))
        vm.run(100)
        assertEquals(VmState.HALTED, vm.state, "error: ${vm.error?.code}")
        return vm.console.history
    }

    private fun runtimeError(vararg instructions: Instruction, constants: List<Constant> = ints): DiagnosticCode? {
        val vm = CsVm(program(*instructions, constants = constants))
        vm.run(100)
        assertEquals(VmState.ERROR, vm.state)
        return vm.error?.code
    }

    private fun compare(opcode: Opcode, left: Int, right: Int) = printed(op(Opcode.PUSH_CONST, left), op(Opcode.PUSH_CONST, right), op(opcode)).single()

    @Test
    fun booleanValuesAndNot() {
        assertEquals(listOf("true"), printed(op(Opcode.PUSH_BOOL, 1)))
        assertEquals(listOf("false"), printed(op(Opcode.PUSH_BOOL, 0)))
        assertEquals(listOf("false"), printed(op(Opcode.PUSH_BOOL, 1), op(Opcode.NOT)))
        // !!x（3.7b で !(!x) として生成する予定）
        assertEquals(listOf("true"), printed(op(Opcode.PUSH_BOOL, 1), op(Opcode.NOT), op(Opcode.NOT)))
    }

    // 大小比較は Int の全範囲で正しい（オーバーフローしない）
    @Test
    fun intComparisons() {
        val min = 0
        val zero = 1
        val max = 2
        for ((opcode, expected) in listOf(
            Opcode.LESS to listOf("true", "false", "false"),
            Opcode.LESS_EQUAL to listOf("true", "true", "false"),
            Opcode.GREATER to listOf("false", "false", "true"),
            Opcode.GREATER_EQUAL to listOf("false", "true", "true"),
        )) {
            assertEquals(expected, listOf(compare(opcode, min, max), compare(opcode, zero, zero), compare(opcode, max, min)), opcode.name)
        }
    }

    // == / != は Int・Boolean・String で使え、String は定数番号ではなく内容で比べる
    @Test
    fun equalityByValue() {
        assertEquals("true", compare(Opcode.EQUAL, 0, 0))
        assertEquals("false", compare(Opcode.EQUAL, 0, 2))
        assertEquals("true", compare(Opcode.NOT_EQUAL, 0, 2))
        assertEquals(listOf("true"), printed(op(Opcode.PUSH_BOOL, 0), op(Opcode.PUSH_BOOL, 0), op(Opcode.EQUAL)))
        assertEquals(listOf("true"), printed(op(Opcode.PUSH_BOOL, 0), op(Opcode.PUSH_BOOL, 1), op(Opcode.NOT_EQUAL)))
        val strings = listOf(Constant.StringConstant("a"), Constant.StringConstant("a"), Constant.StringConstant("b"))
        assertEquals(listOf("true"), printed(op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 1), op(Opcode.EQUAL), constants = strings))
        assertEquals(listOf("false"), printed(op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 2), op(Opcode.EQUAL), constants = strings))
        assertEquals(listOf("false"), printed(op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 1), op(Opcode.NOT_EQUAL), constants = strings))
    }

    // 型の合わない値・範囲外の operand・範囲外の移り先は、例外を出さず CS_RUN_INVALID_BYTECODE で止まる
    @Test
    fun invalidOperandsStopWithInvalidBytecode() {
        val invalid = DiagnosticCode.RUN_INVALID_BYTECODE
        val stringAndInt = listOf(Constant.StringConstant("1"), Constant.IntConstant(1))
        val cases = listOf(
            listOf(op(Opcode.PUSH_BOOL, 2)),
            listOf(op(Opcode.PUSH_BOOL, -1)),
            listOf(op(Opcode.PUSH_CONST, 1), op(Opcode.NOT)),
            listOf(op(Opcode.PUSH_BOOL, 1), op(Opcode.PUSH_CONST, 1), op(Opcode.EQUAL)),
            listOf(op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.EQUAL)),
            listOf(op(Opcode.PUSH_BOOL, 1), op(Opcode.PUSH_BOOL, 0), op(Opcode.LESS)),
            listOf(op(Opcode.PUSH_CONST, 1), op(Opcode.JUMP_IF_FALSE, 0)),
            listOf(op(Opcode.JUMP_IF_FALSE, 0)),
            listOf(op(Opcode.JUMP, -1)),
            listOf(op(Opcode.JUMP, 99)),
            listOf(op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 99)),
        )
        for (case in cases) {
            assertEquals(invalid, runtimeError(*case.toTypedArray(), op(Opcode.HALT)), case.toString())
        }
        // String と Int の比較（定数の型違い）
        assertEquals(invalid, runtimeError(op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_CONST, 1), op(Opcode.EQUAL), op(Opcode.HALT), constants = stringAndInt))
    }

    @Test
    fun loopsAndShortCircuit() {
        fun output(program: CompiledProgram, budget: Int = 1000): CsVm {
            val vm = CsVm(program)
            var guard = 0
            while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
                if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.run(budget)
                check(++guard < 1000)
            }
            return vm
        }
        assertEquals(listOf("3", "2", "1"), output(BranchPrograms.COUNTDOWN).console.history)
        // false && 5 / 0 == 0 の右辺は評価されない（0 除算にならない）
        assertEquals(listOf("5", "false", "false", "false"), output(BranchPrograms.BRANCHES).console.history)
        assertEquals(listOf("0", "1", "4"), output(BranchPrograms.SQUARES).console.history)
        val failed = output(BranchPrograms.FAILS_IN_LOOP)
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, failed.error?.code)
        assertEquals(listOf("3", "6"), failed.console.history)
    }

    // 分岐も1命令＝1単位（A26）。無限ループも予算の分だけ進んで戻る
    @Test
    fun jumpsCountAgainstBudget() {
        val spin = CsVm(program(op(Opcode.JUMP, 0)))
        assertEquals(16, spin.run(16))
        assertEquals(VmState.RUNNING, spin.state)
        assertEquals(1, spin.run(1))
        val loop = CsVm(program(op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 3), op(Opcode.JUMP, 0), op(Opcode.HALT)))
        assertEquals(3000, loop.run(3000))
        assertEquals(VmState.RUNNING, loop.state)
    }

    // 無限ループの VM を多数登録しても、全体予算と動力段階の上限の下で公平に配分される（各 tick の累計の差は 1 命令以内）
    @Test
    fun infiniteLoopsShareBudgetFairly() {
        val spin = program(op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 3), op(Opcode.JUMP, 0), op(Opcode.HALT))
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(spin)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(1000)
        repeat(20) {
            val result = scheduler.tick { fast }
            assertEquals(4096, result.budgetUsed)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
        // 少数なら各 VM が動力段階の上限（128）ちょうどで止まる
        val few = Scheduler<Int>(4096)
        repeat(3) { few.register(it, CsVm(spin)) }
        few.tick { fast }
        val limit = PowerLevel.FAST.instructionsPerTick
        assertEquals(mapOf(0 to limit, 1 to limit, 2 to limit), few.tick { fast }.executed)
    }

    // ---- SnapshotVerifier ----

    private fun frames(program: CompiledProgram) = SnapshotVerifier.framesOf(program)

    // 合流点では、すべての経路で初期化済みの変数だけを読める
    @Test
    fun joinPointsAllowOnlyDefinitelyInitializedReads() {
        val int = listOf(Constant.IntConstant(1))
        // if (c) { v0 = 1 }; println(v0) は拒否
        val onlyOneBranch = program(
            op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 4), op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0),
            op(Opcode.LOAD, 0), op(Opcode.POP), op(Opcode.HALT),
            constants = int, variables = 1,
        )
        assertNull(frames(onlyOneBranch))
        // 両方の経路で代入すれば読める
        val bothBranches = program(
            op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 5), op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0), op(Opcode.JUMP, 7),
            op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0),
            op(Opcode.LOAD, 0), op(Opcode.POP), op(Opcode.HALT),
            constants = int, variables = 1,
        )
        assertEquals(VariableState.Initialized(SlotType.INT), frames(bothBranches)!![7]!!.variables[0])
        // 経路によって型が違う変数は合流できない
        val differentTypes = program(
            op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 5), op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, 0), op(Opcode.JUMP, 7),
            op(Opcode.PUSH_BOOL, 0), op(Opcode.STORE, 0),
            op(Opcode.HALT),
            constants = int, variables = 1,
        )
        assertNull(frames(differentTypes))
        // ループの先頭では本体で初期化する変数は MaybeInitialized
        assertEquals(VariableState.MaybeInitialized(SlotType.INT), frames(BranchPrograms.SQUARES)!![2]!!.variables[1])
        assertEquals(VariableState.Initialized(SlotType.INT), frames(BranchPrograms.SQUARES)!![2]!!.variables[0])
    }

    // 移り先・スタックの深さと型・命令の operand を厳格に確かめる。どれも例外を出さずに null（検証できない）
    @Test
    fun malformedBranchesAreUnverifiable() {
        val int = listOf(Constant.IntConstant(1))
        val cases = mapOf(
            "jump out of range" to program(op(Opcode.JUMP, 5), op(Opcode.HALT)),
            "negative jump" to program(op(Opcode.JUMP, -1), op(Opcode.HALT)),
            "conditional out of range" to program(op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 9), op(Opcode.HALT)),
            "condition is int" to program(op(Opcode.PUSH_CONST, 0), op(Opcode.JUMP_IF_FALSE, 2), op(Opcode.HALT), constants = int),
            "condition missing" to program(op(Opcode.JUMP_IF_FALSE, 1), op(Opcode.HALT)),
            "push bool 2" to program(op(Opcode.PUSH_BOOL, 2), op(Opcode.POP), op(Opcode.HALT)),
            "not of int" to program(op(Opcode.PUSH_CONST, 0), op(Opcode.NOT), op(Opcode.POP), op(Opcode.HALT), constants = int),
            "equal int and bool" to program(op(Opcode.PUSH_CONST, 0), op(Opcode.PUSH_BOOL, 1), op(Opcode.EQUAL), op(Opcode.POP), op(Opcode.HALT), constants = int),
            "equal units" to program(
                op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.CALL_HOST, HostFunction.PRINTLN_EMPTY.id), op(Opcode.EQUAL), op(Opcode.POP), op(Opcode.HALT),
            ),
            "less of bools" to program(op(Opcode.PUSH_BOOL, 1), op(Opcode.PUSH_BOOL, 1), op(Opcode.LESS), op(Opcode.POP), op(Opcode.HALT)),
            // 合流点でスタックの深さが違う（片方の経路だけ値を積む）
            "unbalanced join" to program(op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 3), op(Opcode.PUSH_CONST, 0), op(Opcode.HALT), constants = int),
            // 合流点でスタックの型が違う
            "mismatched join" to program(
                op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 4), op(Opcode.PUSH_CONST, 0), op(Opcode.JUMP, 5), op(Opcode.PUSH_BOOL, 1), op(Opcode.POP), op(Opcode.HALT),
                constants = int,
            ),
            // 1周ごとに値を積むループ（深さが合わず、深くなり続けることもない）
            "growing loop" to program(op(Opcode.PUSH_CONST, 0), op(Opcode.JUMP, 0), constants = int),
            // 最後の命令から先へ進む
            "falls off the end" to program(op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 3), op(Opcode.HALT), op(Opcode.PUSH_BOOL, 1)),
        )
        for ((label, program) in cases) assertNull(frames(program), label)
        // 自分へ戻るだけのループ・到達しない命令があっても検証できる
        val spin = frames(program(op(Opcode.JUMP, 0), op(Opcode.HALT)))
        assertNotNull(spin)
        assertNull(spin!![1])
    }

    // 復元するスナップショットも新しい型で照合する。到達しない位置の pc は拒否する
    @Test
    fun snapshotsInBranchProgramsAreChecked() {
        val program = BranchPrograms.BRANCHES
        val atJoin = VmSnapshot(17, VmState.RUNNING, 0, false, emptyList(), listOf(Value.BoolValue(true), Value.IntValue(5), null), emptyList())
        assertNull(SnapshotVerifier.verify(program, atJoin))
        // 合流点の MaybeInitialized は未初期化でもよいが、型は合っていなければならない
        assertNull(SnapshotVerifier.verify(program, atJoin.copy(variables = listOf(Value.BoolValue(true), null, Value.StringValue(4)))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(program, atJoin.copy(variables = listOf(Value.BoolValue(true), Value.BoolValue(true), null))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(program, atJoin.copy(variables = listOf(Value.IntValue(1), null, null))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(program, atJoin.copy(variables = listOf(null, null, null))))
        // 条件の直前（18）はスタックに Boolean が1つ
        val beforeCondition = atJoin.copy(pc = 18, stack = listOf(Value.BoolValue(false)))
        assertNull(SnapshotVerifier.verify(program, beforeCondition))
        assertEquals(SnapshotProblem.INVALID_STACK, SnapshotVerifier.verify(program, beforeCondition.copy(stack = listOf(Value.IntValue(0)))))
        // 到達しない命令の位置
        val unreachable = VmSnapshot(14, VmState.RUNNING, 0, false, emptyList(), listOf(Value.IntValue(0)), emptyList())
        assertEquals(SnapshotProblem.INVALID_PC, SnapshotVerifier.verify(BranchPrograms.FAILS_IN_LOOP, unreachable))
        assertNull(CsVm.restore(BranchPrograms.FAILS_IN_LOOP, unreachable))
    }

    // 上限の手順数で必ず打ち切る（上限を小さくして打ち切りの経路を確かめる）
    @Test
    fun verificationStopsAtStepLimit() {
        val program = BranchPrograms.SQUARES
        val needed = minimalSteps(program)
        assertTrue(needed <= SnapshotVerifier.maxSteps(program))
        assertNotNull(SnapshotVerifier.framesOf(program, needed))
        assertNull(SnapshotVerifier.framesOf(program, needed - 1))
        assertNull(SnapshotVerifier.framesOf(program, 0))
    }

    // 検証に通る最小の手順数（二分探索）
    private fun minimalSteps(program: CompiledProgram): Long {
        var low = 0L
        var high = SnapshotVerifier.maxSteps(program)
        while (low < high) {
            val middle = (low + high) / 2
            if (SnapshotVerifier.framesOf(program, middle) != null) high = middle else low = middle + 1
        }
        return low
    }

    // ---- 性能 ----

    // 合流の繰り返しが多くなるよう組んだ最大規模（4096 命令以内・変数 64 個）のプログラム。先頭に長い命令列を置き、
    // その後の 64 区間がそれぞれ変数を1つ初期化してから逆向きに分岐する。chained = false なら各区間が先頭へ戻り、
    // true なら1つ前の区間へ戻る（変化が1周につき1区間ずつしか先頭へ伝わらない、周回の方式に不利な形）
    private fun worstCaseProgram(chained: Boolean): CompiledProgram {
        val segmentSize = 4
        val fillerSize = (VmLimits.MAX_INSTRUCTIONS - VmLimits.MAX_VARIABLES * segmentSize - 2) / 2 * 2
        val filler = (0 until fillerSize / 2).flatMap { listOf(op(Opcode.PUSH_BOOL, 1), op(Opcode.POP)) }
        val segments = (0 until VmLimits.MAX_VARIABLES).flatMap {
            val target = if (chained && it > 0) fillerSize + (it - 1) * segmentSize else 0
            listOf(op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, it), op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, target))
        }
        return CompiledProgram(filler + segments + op(Opcode.HALT), listOf(Constant.IntConstant(1)), VmLimits.MAX_VARIABLES, 1)
    }

    // while の入れ子（3.7d の Compiler が出す形: 11.5）で作れる最大規模のもの。入れ子の上限 32 の範囲で 31 段のループを重ね、
    // 各段の本体で変数を2つ宣言し、最も内側に長い命令列を置く
    private fun nestedLoopsProgram(): CompiledProgram {
        val depth = 31
        val instructions = ArrayList<Instruction>()
        fun loop(level: Int) {
            val head = instructions.size
            instructions += op(Opcode.PUSH_BOOL, 1)
            val exit = instructions.size
            instructions += op(Opcode.JUMP_IF_FALSE, -1)
            for (slot in listOf(level * 2, level * 2 + 1)) instructions += listOf(op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, slot))
            if (level < depth - 1) {
                loop(level + 1)
            } else {
                val fillerSize = (VmLimits.MAX_INSTRUCTIONS - instructions.size - depth - 1) / 2 * 2
                repeat(fillerSize / 2) { instructions += listOf(op(Opcode.PUSH_BOOL, 1), op(Opcode.POP)) }
            }
            instructions += op(Opcode.JUMP, head)
            instructions[exit] = op(Opcode.JUMP_IF_FALSE, instructions.size)
        }
        loop(0)
        instructions += op(Opcode.HALT)
        return CompiledProgram(instructions, listOf(Constant.IntConstant(1)), depth * 2, 1)
    }

    private fun allocatedBytes(): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    private fun measure(label: String, program: CompiledProgram, repetitions: Int) {
        repeat(20) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val bytesBefore = allocatedBytes()
        val start = System.nanoTime()
        repeat(repetitions) { assertNotNull(SnapshotVerifier.framesOf(program)) }
        val millis = (System.nanoTime() - start) / 1_000_000.0 / repetitions
        val bytes = (allocatedBytes() - bytesBefore) / repetitions
        println(
            "[3.7a measure] $label: ${program.instructions.size} instructions, ${program.variableCount} variables, " +
                "steps ${minimalSteps(program)} / limit ${SnapshotVerifier.maxSteps(program)}, " +
                "${"%.3f".format(millis)} ms and ${bytes / 1024} KiB allocated per verification",
        )
    }

    @Test
    fun measureVerification() {
        for (chained in listOf(false, true)) {
            val worst = worstCaseProgram(chained)
            assertTrue(worst.instructions.size <= VmLimits.MAX_INSTRUCTIONS)
            measure(if (chained) "adversarial branches (chained back edges)" else "adversarial branches (back edges to start)", worst, 20)
        }
        val nested = nestedLoopsProgram()
        assertTrue(nested.instructions.size <= VmLimits.MAX_INSTRUCTIONS)
        measure("nested while loops (depth 31)", nested, 50)
        // 上限に近い既存（3.5）のプログラム。Compiler の出力に検証をかける場合の追加の負荷を見積もる
        val source = "var v = 0\n" + (1..400).joinToString("\n") { "v = v + $it * 3\nprintln(v)" }.take(3990).substringBeforeLast('\n')
        val compiled = Compiler.compileSource(source).program!!
        measure("largest 3.5 program", compiled, 200)
        repeat(50) { Compiler.compileSource(source) }
        val start = System.nanoTime()
        repeat(200) { Compiler.compileSource(source) }
        println("[3.7a measure] compile of the same source: ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 200)} ms per compile")
    }
}
