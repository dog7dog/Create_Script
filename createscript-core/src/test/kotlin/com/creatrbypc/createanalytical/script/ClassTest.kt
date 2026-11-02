package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.ClassInfo
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Console
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.ObjectSnapshot
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotProblem
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// 第3.9a段階のクラス宣言・インスタンス生成・オブジェクトの参照を、ソースから CS_VM・Scheduler・SnapshotVerifier まで通して確かめる
class ClassTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する
    private fun runToEnd(vm: CsVm, maxTicks: Int = 100_000): Int {
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.advance(1000)
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

    // ---- 生成・表示・比較 ----

    @Test
    fun instancesAreCreatedAndPrinted() {
        // 表示は「クラス名@id」。id はその VM の中で 1 から順に発行する
        assertEquals(listOf("Robot@1"), output("class Robot {\n}\n\nval robot = Robot()\nprintln(robot)"))
        assertEquals(listOf("Robot@1", "Gear@2", "Robot@3"), output("class Robot\nclass Gear {}\nprintln(Robot())\nprintln(Gear())\nprintln(Robot())"))
        // 生成は NEW 1 命令（引数・結果の積み込みを含む）
        val program = compile("class Robot\nval r = Robot()")
        assertEquals(listOf(Opcode.NEW, Opcode.STORE, Opcode.HALT), program.instructions.map { it.opcode })
        assertEquals(listOf(ClassInfo("Robot")), program.classes)
    }

    @Test
    fun equalityIsReferenceIdentity() {
        assertEquals(listOf("true", "false"), output("class Robot {}\n\nval a = Robot()\nval b = Robot()\nval c = a\n\nprintln(a == c)\nprintln(a == b)"))
        assertEquals(listOf("false", "true", "true"), output("class Robot\nval a = Robot()\nvar b = Robot()\nprintln(a != Robot() && a == b)\nb = a\nprintln(a == b)\nprintln(Robot() != Robot())"))
    }

    // 関数の引数・戻り値・再帰の中でも同じオブジェクトを指す
    @Test
    fun identityThroughFunctionsAndRecursion() {
        val source = "class Robot\nfun same(r: Robot): Robot { return r }\nfun make(): Robot { return Robot() }\n" +
            "fun keep(n: Int, r: Robot): Robot {\n  if (n == 0) { return r }\n  val other = Robot()\n  if (other == r) { return other }\n  return keep(n - 1, r)\n}\n" +
            "fun chooseNew(n: Int, r: Robot): Robot {\n  if (n == 0) { return Robot() }\n  return chooseNew(n - 1, r)\n}\n" +
            "val a = Robot()\nprintln(same(a) == a)\nprintln(make() == make())\nprintln(keep(5, a) == a)\nprintln(chooseNew(3, a) == a)\nprintln(keep(2, a))"
        // keep は各段で別のオブジェクトを作るが、返すのは渡した a（表示は id 1 のまま）
        assertEquals(listOf("true", "false", "true", "false", "Robot@1"), output(source))
        // 待機をまたいでも、関数の中の参照は保たれる
        assertEquals(listOf("true"), output("class Robot\nfun slow(r: Robot): Robot {\n  waitTicks(3)\n  return r\n}\nval a = Robot()\nprintln(slow(a) == a)"))
    }

    // ---- 回収と上限 ----

    // ヒープが満杯になると、どこからも参照されないオブジェクトを回収する（id は再利用しない）。参照されているオブジェクトは残る
    @Test
    fun unreferencedObjectsAreCollected() {
        val source = "class Robot\nval keep = Robot()\nvar last = keep\nfor (i in 0 until 1000) {\n  last = Robot()\n}\nprintln(keep)\nprintln(last)\nprintln(keep == last)\nprintln(Robot())"
        assertEquals(listOf("Robot@1", "Robot@1001", "false", "Robot@1002"), output(source))
        // 回収の後もヒープは上限以内で、保存した状態は検証に通る
        val program = compile("class Robot\nval keep = Robot()\nfor (i in 0 until 600) {\n  val t = Robot()\n}\nwaitTicks(1)\nprintln(keep)")
        val waiting = CsVm(program)
        while (waiting.state != VmState.WAITING) waiting.advance(100)
        val snapshot = waiting.snapshot()!!
        assertTrue(snapshot.objects.size <= VmLimits.MAX_OBJECTS && snapshot.objects.first() == ObjectSnapshot(1, 0), "${snapshot.objects.size}")
        assertEquals(602, snapshot.nextObjectId)
        assertNull(SnapshotVerifier.verify(program, snapshot))
    }

    // 参照されたまま 256 個を超えると CS_RUN_TOO_MANY_OBJECTS（引数: 上限 256。位置は生成）で止まる。サーバーは止まらない
    @Test
    fun liveObjectLimitStopsSafely() {
        // 各段で 20 個を参照し続ける再帰。13 段目の r16 で 257 個目になる（12 段 240 個 + r0〜r15 で 256 個）
        val locals = (0 until 20).joinToString("") { "  val r$it = Robot()\n" }
        val source = "class Robot\nfun hoard(n: Int): Int {\n$locals  if (n == 0) { return 0 }\n  return hoard(n - 1)\n}\nprintln(hoard(11))\nprintln(hoard(12))"
        val vm = run(source)
        assertEquals(VmState.ERROR, vm.state)
        assertEquals(DiagnosticCode.RUN_TOO_MANY_OBJECTS, vm.error?.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(VmLimits.MAX_OBJECTS)), vm.error?.arguments)
        assertEquals(19, vm.error?.position?.line)
        // 12 段（240 個）は収まる
        assertEquals(listOf("0"), vm.console.history)
    }

    // 生成も 1 命令として予算を消費する。生成を繰り返すプログラムを多数並べても、予算・公平性を守る
    @Test
    fun creationStaysWithinBudgetAndFair() {
        val vm = CsVm(compile("class Robot\nwhile (true) { Robot() }"))
        assertEquals(16, vm.run(16))
        val churn = compile("class Robot\nvar r = Robot()\nwhile (true) {\n  r = Robot()\n}")
        val identity = compile("class Robot\nfun same(r: Robot): Robot { return r }\nval a = Robot()\nvar ok = true\nwhile (true) { ok = same(a) == a }")
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) churn else identity)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(600)
        repeat(60) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }

    // ---- 不正なバイトコード ----

    private val origin = SourcePosition(0, 1, 1)

    private fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)

    @Test
    fun invalidClassBytecodeIsRejected() {
        val two = listOf(ClassInfo("A"), ClassInfo("B"))
        fun program(vararg instructions: Instruction, classes: List<ClassInfo> = two) = CompiledProgram(instructions.toList(), emptyList(), 1, 2, classes = classes)
        assertNotNull(SnapshotVerifier.framesOf(program(op(Opcode.NEW, 1), op(Opcode.NEW, 1), op(Opcode.EQUAL), op(Opcode.POP), op(Opcode.HALT))))
        val cases = mapOf(
            // 範囲外のクラス番号・クラスの無いプログラムの NEW
            "class out of range" to program(op(Opcode.NEW, 2), op(Opcode.POP), op(Opcode.HALT)),
            "negative class" to program(op(Opcode.NEW, -1), op(Opcode.POP), op(Opcode.HALT)),
            "no classes" to program(op(Opcode.NEW, 0), op(Opcode.POP), op(Opcode.HALT), classes = emptyList()),
            // 別のクラス・Int との比較、算術・条件に使う
            "compare different classes" to program(op(Opcode.NEW, 0), op(Opcode.NEW, 1), op(Opcode.EQUAL), op(Opcode.POP), op(Opcode.HALT)),
            "compare with bool" to program(op(Opcode.NEW, 0), op(Opcode.PUSH_BOOL, 1), op(Opcode.EQUAL), op(Opcode.POP), op(Opcode.HALT)),
            "object as condition" to program(op(Opcode.NEW, 0), op(Opcode.JUMP_IF_FALSE, 2), op(Opcode.HALT)),
            "object negated" to program(op(Opcode.NEW, 0), op(Opcode.NEGATE), op(Opcode.POP), op(Opcode.HALT)),
            // 合流点で変数のクラスが違う
            "merged classes differ" to program(
                op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, 5), op(Opcode.NEW, 0), op(Opcode.STORE), op(Opcode.JUMP, 7), op(Opcode.NEW, 1), op(Opcode.STORE), op(Opcode.LOAD), op(Opcode.POP), op(Opcode.HALT),
            ),
        )
        for ((label, program) in cases) assertNull(SnapshotVerifier.framesOf(program), label)
        // 検証しないで実行しても（念のため）、VM は範囲外のクラスを拒否する
        val vm = CsVm(cases.getValue("class out of range"))
        vm.run(10)
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code)
    }

    // ---- オブジェクトを含むスナップショット ----

    // a と b が同じオブジェクト、c が別のクラスのオブジェクトを指したまま待機する
    private val waitingProgram = compile("class Robot {}\nclass Gear\nval a = Robot()\nval b = a\nval c = Gear()\n\nwaitTicks(100)\n\nprintln(a == b)\nprintln(Robot())\nprintln(c)")

    private fun waitingSnapshot(): VmSnapshot {
        val vm = CsVm(waitingProgram)
        while (vm.state != VmState.WAITING) vm.advance(1)
        return vm.snapshot()!!
    }

    private fun finish(vm: CsVm): List<String> {
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.advance(1)
        return vm.console.history
    }

    @Test
    fun objectsSurviveSaveAndRestore() {
        val waiting = waitingSnapshot()
        assertEquals(listOf(ObjectSnapshot(1, 0), ObjectSnapshot(2, 1)), waiting.objects)
        assertEquals(3, waiting.nextObjectId)
        assertEquals(listOf<Value?>(Value.ObjectRef(1), Value.ObjectRef(1), Value.ObjectRef(2)), waiting.variables)
        assertNull(SnapshotVerifier.verify(waitingProgram, waiting))
        // 復元後も同一性が保たれ、次の id から続けて発行する。保存・復元を繰り返しても変わらない
        var vm = CsVm.restore(waitingProgram, waiting)!!
        repeat(20) { vm = CsVm.restore(waitingProgram, vm.snapshot()!!)!!.also { it.countWaitTick() } }
        assertEquals(listOf("true", "Robot@3", "Gear@2"), finish(vm))
    }

    @Test
    fun invalidObjectSnapshotsAreRejected() {
        val waiting = waitingSnapshot()
        fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(waitingProgram, snapshot)
        // ヒープの不正: 範囲外のクラス・重複・順序・次の id 以上の id・次の id が 0 以下・上限を超える数
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(objects = listOf(ObjectSnapshot(1, 0), ObjectSnapshot(2, 5)))))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(objects = listOf(ObjectSnapshot(1, 0), ObjectSnapshot(1, 1)))))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(objects = listOf(ObjectSnapshot(2, 1), ObjectSnapshot(1, 0)))))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(nextObjectId = 2)))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(nextObjectId = 0)))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(objects = listOf(ObjectSnapshot(0, 0)) + waiting.objects)))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(waiting.copy(objects = (1..VmLimits.MAX_OBJECTS + 1).map { ObjectSnapshot(it, 0) }, nextObjectId = 1000)))
        // 参照の不正: ヒープに無い id・クラスの違うオブジェクト・オブジェクトの代わりの Int
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(objects = listOf(ObjectSnapshot(2, 1)))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(variables = listOf(Value.ObjectRef(2), Value.ObjectRef(1), Value.ObjectRef(2)))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(variables = listOf(Value.ObjectRef(1), Value.ObjectRef(1), Value.ObjectRef(1)))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(variables = listOf(Value.IntValue(1), Value.ObjectRef(1), Value.ObjectRef(2)))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(waiting.copy(variables = listOf(Value.ObjectRef(7), Value.ObjectRef(1), Value.ObjectRef(2)))))
        // どこからも参照されないオブジェクト（回収前）はあってもよい
        assertNull(problem(waiting.copy(objects = waiting.objects + ObjectSnapshot(5, 0), nextObjectId = 9)))
        // スタック・呼び出しフレームの中の参照も照合する
        val program = compile("class Robot\nfun hold(r: Robot): Robot {\n  waitTicks(5)\n  return r\n}\nval x = Robot()\nprintln(hold(x) == x)")
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.advance(1)
        val inCall = vm.snapshot()!!
        assertNull(SnapshotVerifier.verify(program, inCall))
        val frame = inCall.callStack.single()
        assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(program, inCall.copy(callStack = listOf(frame.copy(locals = listOf(Value.ObjectRef(9)))))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(program, inCall.copy(objects = emptyList())))
    }

    // クラスのあるプログラムだけ、クラスの一覧をハッシュに含める（クラスの無いプログラムのハッシュは existingProgramHashesAreUnchanged で固定）
    @Test
    fun classesAreFingerprinted() {
        val program = compile("class Robot\nprintln(Robot())")
        assertFalse(ProgramFingerprint.ofProgram(program).contentEquals(ProgramFingerprint.ofProgram(program.copy(classes = listOf(ClassInfo("Gear"))))))
    }

    // ---- 性能（参考値） ----

    @Test
    fun measureObjects() {
        // 生成を繰り返す 1000 台（回収を含む）と、255 個を参照したまま生成を繰り返す（毎回回収が走る）最悪の形
        val churn = compile("class Robot\nvar r = Robot()\nwhile (true) {\n  r = Robot()\n}")
        val scheduler = Scheduler<Int>(4096)
        repeat(1000) { scheduler.register(it, CsVm(churn)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(200) { scheduler.tick { fast } }
        val start = System.nanoTime()
        repeat(1000) { scheduler.tick { fast } }
        println("[3.9a measure] scheduler tick with 1000 VMs creating objects (budget 4096): ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 1000)} ms per tick")
        // 254 個（大域変数 2 + 6 段 × 42）を参照したまま t を作り直すと、ヒープは 255 個から毎回満杯になり、生成のたびに回収が走る
        val held = (0 until 42).joinToString("") { "  val r$it = Robot()\n" }
        val worst = compile("class Robot\nval g0 = Robot()\nval g1 = Robot()\nfun hold(n: Int) {\n$held  if (n > 0) { hold(n - 1) }\n  var t = Robot()\n  while (true) { t = Robot() }\n}\nhold(5)")
        val worstScheduler = Scheduler<Int>(4096)
        repeat(256) { worstScheduler.register(it, CsVm(worst)) }
        repeat(400) { worstScheduler.tick { fast } }
        val probe = CsVm(worst).also { repeat(400) { _ -> it.advance(16) } }.snapshot()!!
        // エラーで止まっていない（snapshot が null でない）まま、ヒープは満杯近くを保つ
        assertTrue(probe.objects.size >= VmLimits.MAX_OBJECTS - 1, "objects ${probe.objects.size}")
        val worstStart = System.nanoTime()
        repeat(200) { worstScheduler.tick { fast } }
        println("[3.9a measure] scheduler tick with 256 VMs collecting on every creation (255 live objects each, budget 4096): ${"%.3f".format((System.nanoTime() - worstStart) / 1_000_000.0 / 200)} ms per tick")
        // 上限近くのヒープ（254 個）と 6 フレームを持つスナップショットの検証・復元
        val full = compile("class Robot\nval g0 = Robot()\nval g1 = Robot()\nfun hold(n: Int) {\n$held  if (n > 0) { hold(n - 1) } else { waitTicks(1) }\n}\nhold(5)")
        val vm = CsVm(full)
        while (vm.state != VmState.WAITING) vm.advance(100)
        val snapshot = vm.snapshot()!!
        assertEquals(254, snapshot.objects.size)
        repeat(200) { assertNotNull(CsVm.restore(full, snapshot)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(full, snapshot) }
        println("[3.9a measure] verify + restore with 254 objects and 6 frames: ${"%.3f".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000)} ms per VM")
    }

    // ---- 無作為に作った参照のプログラムと、Kotlin で書いた参照の実行器の比較 ----

    // 変数 r0〜r2 は Robot、g0・g1 は Gear。値は作った順の id
    private sealed interface Stmt

    private data class Create(val target: String) : Stmt
    private data class Copy(val target: String, val source: String) : Stmt
    private data class ThroughFunction(val target: String, val source: String) : Stmt
    private data class ThroughRecursion(val target: String, val source: String, val depth: Int) : Stmt
    private data class MakeInFunction(val target: String) : Stmt
    private data class Compare(val left: String, val right: String, val negate: Boolean) : Stmt
    private data class Print(val name: String) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class Loop(val count: Int, val body: List<Stmt>) : Stmt

    private fun classOf(name: String) = if (name.startsWith("r")) "Robot" else "Gear"

    private class Reference {
        val output = ArrayList<String>()
        val values = HashMap<String, Int>()
        var next = 1
        var waited = 0

        fun create() = next++

        fun run(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Create -> values[statement.target] = create()
                    is Copy -> values[statement.target] = values.getValue(statement.source)
                    is ThroughFunction -> values[statement.target] = values.getValue(statement.source)
                    // 再帰の各段で 1 つずつ作って捨てる（id を消費する）
                    is ThroughRecursion -> {
                        repeat(statement.depth) { create() }
                        values[statement.target] = values.getValue(statement.source)
                    }
                    is MakeInFunction -> values[statement.target] = create()
                    is Compare -> output.add(((values.getValue(statement.left) == values.getValue(statement.right)) != statement.negate).toString())
                    is Print -> output.add("${if (statement.name.startsWith("r")) "Robot" else "Gear"}@${values.getValue(statement.name)}")
                    is Wait -> waited += statement.ticks
                    is Loop -> repeat(statement.count) { run(statement.body) }
                }
            }
        }
    }

    private val robots = listOf("r0", "r1", "r2")
    private val gears = listOf("g0", "g1")

    private fun generate(random: Random, depth: Int): List<Stmt> = List(random.nextInt(1, 6)) {
        val group = if (random.nextInt(3) == 0) gears else robots
        val target = group.random(random)
        val source = group.random(random)
        when (random.nextInt(if (depth < 2) 10 else 9)) {
            0 -> Create(target)
            1 -> Copy(target, source)
            2 -> if (group == robots) ThroughFunction(target, source) else Copy(target, source)
            3 -> if (group == robots) ThroughRecursion(target, source, random.nextInt(0, 6)) else Create(target)
            4 -> if (group == robots) MakeInFunction(target) else Create(target)
            5, 6 -> Compare(target, source, random.nextBoolean())
            7 -> Print(target)
            8 -> Wait(random.nextInt(0, 3))
            else -> Loop(random.nextInt(0, 4), generate(random, depth + 1))
        }
    }

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        indent + when (statement) {
            is Create -> "${statement.target} = ${classOf(statement.target)}()\n"
            is Copy -> "${statement.target} = ${statement.source}\n"
            is ThroughFunction -> "${statement.target} = same(${statement.source})\n"
            is ThroughRecursion -> "${statement.target} = keep(${statement.depth}, ${statement.source})\n"
            is MakeInFunction -> "${statement.target} = make()\n"
            is Compare -> "println(${statement.left} ${if (statement.negate) "!=" else "=="} ${statement.right})\n"
            is Print -> "println(${statement.name})\n"
            is Wait -> "waitTicks(${statement.ticks})\n"
            is Loop -> "for (i in 0 until ${statement.count}) {\n" + render(statement.body, "$indent  ") + "$indent}\n"
        }
    }

    private val prelude = "class Robot\nclass Gear {}\nfun same(r: Robot): Robot { return r }\nfun make(): Robot { return Robot() }\n" +
        "fun keep(n: Int, r: Robot): Robot {\n  if (n == 0) { return r }\n  val spare = Robot()\n  if (spare == r) { return spare }\n  return keep(n - 1, r)\n}\n"

    @Test
    fun randomReferenceProgramsMatchReference() {
        val random = Random(39)
        var compared = 0
        var restored = 0
        repeat(300) { index ->
            val statements = generate(random, 0)
            val declarations = (robots + gears).joinToString("") { "var $it = ${classOf(it)}()\n" }
            val source = prelude + declarations + render(statements, "") + "println(r0 == r1)"
            if (source.length > 4096) return@repeat
            val program = compile(source)
            val reference = Reference()
            (robots + gears).forEach { reference.values[it] = reference.create() }
            reference.run(statements)
            reference.output.add((reference.values.getValue("r0") == reference.values.getValue("r1")).toString())
            val vm = CsVm(program)
            val ticks = runToEnd(vm)
            assertEquals(VmState.HALTED, vm.state, "${vm.error} in $source")
            assertEquals(reference.output.takeLast(Console.MAX_LINES), vm.console.history, source)
            assertTrue(ticks >= reference.waited, source)
            compared++
            if (index % 20 == 0) {
                restored++
                restoreAtEveryBoundary(program, source)
            }
        }
        assertTrue(compared >= 280, "compared $compared")
        println("[3.9a random] $compared programs compared, $restored restored at every boundary")
    }

    // 1歩ずつ進め、各境界のスナップショットから復元して最後まで実行した結果が、中断しない実行と一致する
    private fun restoreAtEveryBoundary(program: CompiledProgram, source: String) {
        fun step(vm: CsVm) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.advance(1)
        }
        val expected = finish(CsVm(program))
        val original = CsVm(program)
        var cut = 0
        while ((original.state == VmState.RUNNING || original.state == VmState.WAITING) && cut < 2000) {
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
