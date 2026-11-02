package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.Frame
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.SlotType
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.VariableState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmSnapshotTest {
    private fun compile(source: String) = Compiler.compileSource(source).program ?: error("compile failed: $source")

    // Scheduler と同じ進め方の1歩: 待機中なら待機を1 tick 数え、回収中なら回収を1単位進め（第3.9b段階の GC 対策）、そうでなければ1命令実行する。
    // 回収の1単位と、回収を始めた（実行しなかった）NEW は false（保存・復元すると回収は最初からやり直すため、回収の歩数は比べない）
    private fun step(vm: CsVm): Boolean = when {
        vm.state == VmState.WAITING -> {
            vm.countWaitTick()
            true
        }
        vm.isCollecting -> {
            vm.collectGarbage(1)
            false
        }
        else -> vm.run(1) > 0
    }

    private fun finished(vm: CsVm) = vm.state == VmState.HALTED || vm.state == VmState.ERROR

    // steps は回収の単位を除いた歩数（命令・待機）
    private data class Outcome(val state: VmState, val console: List<String>, val error: Any?, val steps: Int)

    private fun runToEnd(vm: CsVm, alreadyTaken: Int = 0): Outcome {
        var steps = alreadyTaken
        var all = 0
        while (!finished(vm)) {
            if (step(vm)) steps++
            check(++all < 20_000) { "did not finish" }
        }
        return Outcome(vm.state, vm.console.history, vm.error?.let { it.code to it.position }, steps)
    }

    private val programs = listOf(
        "println(1)\nval x = 2\nvar y = x * 3\ny = y + 1\nprintln(y)\nprintln(\"hi\")\nwaitTicks(2)\nprintln(\"after\")\nwaitTicks(0)\nprintln()\nprintln(-y)",
        "val s = \"text\"\nvar n = 10\nprintln(s)\nwaitTicks(3)\nn = n % 3\nprintln(n)\nprintln(s)",
        "val z = 0\nprintln(1)\nwaitTicks(1)\nprintln(1 / z)",
        "1 + 2\nprintln(1)",
        // 第3.7b段階: 待機をまたぐ Boolean の変数と、短絡評価の途中（分岐の前後・合流点）での保存
        "val z = 0\nvar b = 3 > 2 && \"a\" == \"a\"\nprintln(b)\nwaitTicks(2)\nb = !b || z != 0 && 1 / z == 0\nprintln(b)\n" +
            "val c = false || z == 0\nwaitTicks(1)\nprintln(c && b == false)\nprintln(true && 1 / z == 0)",
        // 第3.7c段階: if / else if / else のブロックの中での待機と、ブロックの変数（合流点で MaybeInitialized になる）
        "var n = 0\nif (n == 0) {\n  val t = n + 1\n  waitTicks(1)\n  n = t\n  println(n)\n} else {\n  println(\"no\")\n}\n" +
            "if (n > 5) { println(\"big\") } else if (n == 1 && true) {\n  val s = \"one\"\n  waitTicks(0)\n  if (s == \"one\") { println(s) }\n}\nprintln(n)",
        // 第3.7d段階: 入れ子の while の中での待機・if、本体の変数（ループの先頭で MaybeInitialized）、ループの中の実行時エラー
        "var i = 0\nwhile (i < 3) {\n  val sq = i * i\n  var j = 0\n  while (j < i) {\n    if (j % 2 == 0) { waitTicks(1) } else { println(sq) }\n    j = j + 1\n  }\n" +
            "  println(i)\n  waitTicks(0)\n  i = i + 1\n}\nwhile (false) { }\nprintln(\"end\")",
        "var k = 2\nwhile (true) {\n  println(6 / k)\n  waitTicks(1)\n  k = k - 1\n}",
        // 第3.8a段階: 入れ子の while の break / continue の直前・直後、待機と組み合わせた保存
        "var a = 0\nwhile (a < 3) {\n  a = a + 1\n  var b = 0\n  while (true) {\n    b = b + 1\n    if (b > a) { break }\n    waitTicks(1)\n    if (b == 2) { continue }\n" +
            "    println(a * 10 + b)\n  }\n  if (a == 2) {\n    val skipped = a\n    waitTicks(0)\n    continue\n  }\n  println(-a)\n}\nprintln(\"end\")",
        // 第3.8b段階: for の 3 種類の範囲と Int の端、入れ子・break / continue の直前・直後、待機（ループ変数・終端の番号を保存）
        "var n = 2\nfor (i in 2147483645..2147483647) {\n  if (i == 2147483646) { continue }\n  println(i)\n  waitTicks(1)\n}\n" +
            "for (i in 0 until n) {\n  n = n + 5\n  for (j in i + 1 downTo 0) {\n    if (j == 0) { break }\n    val v = i * 10 + j\n    waitTicks(0)\n    println(v)\n  }\n}\n" +
            "for (i in 5..0) { println(\"never\") }\nprintln(n)",
        // 第3.8c段階: 入れ子の関数の呼び出しの中の待機・for・continue・早期 return、ローカル変数と大域変数、CALL / RETURN の直前・直後
        "var total = 0\nfun leaf() {\n  val step = total + 1\n  waitTicks(1)\n  total = step\n}\nfun middle() {\n  for (i in 0 until 3) {\n    if (i == 1) { continue }\n" +
            "    var local = i * 10\n    leaf()\n    println(local + total)\n    if (total > 1) { return }\n  }\n  println(\"unreachable\")\n}\nmiddle()\nleaf()\nprintln(total)",
        "val zero = 0\nfun boom() {\n  waitTicks(1)\n  println(1 / zero)\n}\nfun call() {\n  println(\"c\")\n  boom()\n}\ncall()",
        // 第3.8d段階: 引数の評価中の呼び出し（呼び出し元のスタックに計算途中の値）・待機中の引数・戻り値の計算途中・Boolean / String の引数
        "fun twice(n: Int): Int {\n  waitTicks(1)\n  return n * 2\n}\nfun pick(c: Boolean, s: String): String {\n  if (c) { return s }\n  return \"no\"\n}\n" +
            "fun add(a: Int, b: Int): Int { return a + b }\nvar total = 10 + add(twice(1), twice(add(1, 1)))\nfor (i in 0 until 2) {\n  println(pick(i == 0, \"yes\"))\n" +
            "  total = total + twice(i)\n}\nprintln(total)",
        // 第3.8e段階: 直接再帰の中の待機・戻り値の計算途中（同じ関数のフレームが積み重なる）、相互再帰、Unit の再帰とループ、深さの上限の実行時エラー
        "fun fact(n: Int): Int {\n  if (n <= 1) {\n    waitTicks(1)\n    return 1\n  }\n  return n * fact(n - 1)\n}\n" +
            "fun isEven(n: Int): Boolean {\n  if (n == 0) { return true }\n  return isOdd(n - 1)\n}\nfun isOdd(n: Int): Boolean {\n  if (n == 0) { return false }\n  waitTicks(0)\n  return isEven(n - 1)\n}\n" +
            "fun tree(d: Int) {\n  for (i in 0 until 2) {\n    if (d > 0) { tree(d - 1) } else { println(i) }\n  }\n}\nprintln(fact(4))\nprintln(isEven(5))\ntree(2)",
        "fun dive(n: Int): Int {\n  waitTicks(0)\n  return 1 + dive(n + 1)\n}\nprintln(dive(0))",
        // 第3.9a段階: オブジェクトの生成・参照の受け渡し（関数・再帰・待機）・比較・表示、満杯のヒープの回収
        "class Robot\nclass Gear {}\nfun keep(n: Int, r: Robot): Robot {\n  if (n == 0) { return r }\n  waitTicks(0)\n  return keep(n - 1, r)\n}\n" +
            "val a = Robot()\nvar b = keep(3, a)\nprintln(a == b)\nval g = Gear()\nfor (i in 0 until 300) { b = Robot() }\nwaitTicks(1)\nprintln(a != b)\nprintln(b)\nprintln(g)",
    )

    // すべての命令の境界（待機中を含む）で保存・復元して続けた結果が、中断なしの実行と一致する。
    // 分岐・ループのプログラム（第3.7a段階。構文が未対応のため手で組んだバイトコード: BranchPrograms）を含む
    @Test
    fun restoreAtEveryBoundaryMatchesUninterruptedRun() {
        val cases = programs.map { it to compile(it) } + BranchPrograms.ALL.toList()
        for ((source, program) in cases) {
            val expected = runToEnd(CsVm(program))
            // 回収の各単位の後も含めて、すべての境界で保存・復元する
            var boundaries = 0
            CsVm(program).also { while (!finished(it)) step(it).also { boundaries++ } }
            for (cut in 0 until boundaries) {
                val original = CsVm(program)
                var taken = 0
                repeat(cut) { if (step(original)) taken++ }
                if (finished(original)) break
                val snapshot = original.snapshot()!!
                // 保存しても VM の状態は変わらない
                assertEquals(snapshot, original.snapshot())
                val restored = CsVm.restore(program, snapshot)
                assertNotNull(restored, "rejected at $cut: ${SnapshotVerifier.verify(program, snapshot)} in $source")
                assertEquals(snapshot, restored!!.snapshot())
                assertEquals(expected, runToEnd(restored, taken), "cut $cut of $source")
                assertEquals(expected, runToEnd(original, taken), "original changed at $cut of $source")
            }
        }
    }

    @Test
    fun finishedVmHasNoSnapshot() {
        val halted = CsVm(compile("println(1)")).also { it.run(100) }
        assertEquals(VmState.HALTED, halted.state)
        assertNull(halted.snapshot())
        val failed = CsVm(compile("val z = 0\nprintln(1 / z)")).also { it.run(100) }
        assertNull(failed.snapshot())
    }

    // waitTicks(5) の後で待機中のスナップショット
    private val waitingProgram = compile("val s = \"a\"\nvar n = 1\nprintln(s)\nwaitTicks(5)\nn = n + 1\nprintln(n)")

    private fun waitingSnapshot(): VmSnapshot {
        val vm = CsVm(waitingProgram)
        while (vm.state != VmState.WAITING) vm.run(1)
        return vm.snapshot()!!
    }

    private fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(waitingProgram, snapshot)

    @Test
    fun validSnapshotsPass() {
        val waiting = waitingSnapshot()
        assertNull(problem(waiting))
        assertEquals(VmState.WAITING, waiting.state)
        assertTrue(waiting.resumeWithUnit)
        // 待機が明けて Unit を積む前（RUNNING・resumeWithUnit）も正しい状態
        assertNull(problem(waiting.copy(state = VmState.RUNNING, waitRemaining = 0)))
    }

    @Test
    fun invalidStateAndPcAreRejected() {
        val base = waitingSnapshot()
        assertEquals(SnapshotProblem.INVALID_STATE, problem(base.copy(state = VmState.HALTED)))
        assertEquals(SnapshotProblem.INVALID_STATE, problem(base.copy(state = VmState.ERROR)))
        assertEquals(SnapshotProblem.INVALID_PC, problem(base.copy(pc = -1)))
        assertEquals(SnapshotProblem.INVALID_PC, problem(base.copy(pc = waitingProgram.instructions.size)))
        assertEquals(SnapshotProblem.INVALID_PC, problem(base.copy(pc = Int.MAX_VALUE)))
    }

    @Test
    fun waitAndResumeCombinationsAreChecked() {
        val base = waitingSnapshot()
        assertEquals(SnapshotProblem.INVALID_WAIT, problem(base.copy(waitRemaining = -1)))
        assertEquals(SnapshotProblem.INVALID_WAIT, problem(base.copy(state = VmState.RUNNING, waitRemaining = 3)))
        assertNull(problem(base.copy(waitRemaining = Int.MAX_VALUE)))
        assertEquals(SnapshotProblem.INVALID_RESUME, problem(base.copy(resumeWithUnit = false)))
        // waitTicks の直後でない位置での resumeWithUnit
        val running = CsVm(waitingProgram).also { it.run(1) }.snapshot()!!
        assertEquals(SnapshotProblem.INVALID_RESUME, problem(running.copy(resumeWithUnit = true)))
    }

    @Test
    fun stackDepthAndTypesAreChecked() {
        val base = waitingSnapshot()
        assertEquals(SnapshotProblem.INVALID_STACK, problem(base.copy(stack = base.stack + Value.IntValue(1))))
        assertEquals(SnapshotProblem.INVALID_STACK, problem(base.copy(stack = List(65) { Value.IntValue(0) })))
        // 式の途中（PUSH_CONST の後）は Int が1つ積まれている
        val vm = CsVm(compile("println(1 + 2)"))
        vm.run(1)
        val middle = vm.snapshot()!!
        assertNull(SnapshotVerifier.verify(compile("println(1 + 2)"), middle))
        val program = compile("println(1 + 2)")
        assertEquals(SnapshotProblem.INVALID_STACK, SnapshotVerifier.verify(program, middle.copy(stack = listOf(Value.UnitValue))))
        assertEquals(SnapshotProblem.INVALID_STACK, SnapshotVerifier.verify(program, middle.copy(stack = listOf(Value.StringValue(0)))))
        assertEquals(SnapshotProblem.INVALID_STACK, SnapshotVerifier.verify(program, middle.copy(stack = emptyList())))
    }

    @Test
    fun variablesAndConstantIndicesAreChecked() {
        val base = waitingSnapshot()
        val (s, n) = base.variables
        assertTrue(s is Value.StringValue && n is Value.IntValue)
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = base.variables.dropLast(1))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = base.variables + null)))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(s, null))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(s, Value.UnitValue))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(n, s))))
        // String が Int の定数・範囲外の定数を指している
        val intConstant = waitingProgram.constants.indexOfFirst { it is Constant.IntConstant }
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(Value.StringValue(intConstant), n))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(Value.StringValue(999), n))))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(base.copy(variables = listOf(Value.StringValue(-1), n))))
        // 宣言前の位置では未初期化でなければならない
        val start = CsVm(waitingProgram).snapshot()!!
        assertNull(problem(start))
        assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(start.copy(variables = listOf(s, null))))
    }

    @Test
    fun consoleLimitsAreChecked() {
        val base = waitingSnapshot()
        assertNull(problem(base.copy(consoleLines = List(32) { "x".repeat(64) })))
        assertEquals(SnapshotProblem.INVALID_CONSOLE, problem(base.copy(consoleLines = List(33) { "x" })))
        assertEquals(SnapshotProblem.INVALID_CONSOLE, problem(base.copy(consoleLines = listOf("x".repeat(65)))))
        assertEquals(SnapshotProblem.INVALID_CONSOLE, problem(base.copy(consoleLines = listOf("a\uD800"))))
    }

    // バイトコードから状態を求められないプログラム（未初期化の変数を読む・スタックが足りない・終わりに HALT が無い）
    @Test
    fun unverifiableProgramsAreRejected() {
        val origin = SourcePosition(0, 1, 1)
        fun program(vararg instructions: Instruction, constants: List<Constant> = emptyList(), variables: Int = 0) =
            CompiledProgram(instructions.toList(), constants, variables, 4)
        val snapshot = VmSnapshot(0, VmState.RUNNING, 0, false, emptyList(), emptyList(), emptyList())
        val readsUninitialized = program(Instruction(Opcode.LOAD, 0, origin), Instruction(Opcode.HALT, 0, origin), variables = 1)
        assertEquals(SnapshotProblem.UNVERIFIABLE_PROGRAM, SnapshotVerifier.verify(readsUninitialized, snapshot.copy(variables = listOf(null))))
        val underflow = program(Instruction(Opcode.POP, 0, origin), Instruction(Opcode.HALT, 0, origin))
        assertEquals(SnapshotProblem.UNVERIFIABLE_PROGRAM, SnapshotVerifier.verify(underflow, snapshot))
        val noHalt = program(Instruction(Opcode.PUSH_CONST, 0, origin), constants = listOf(Constant.IntConstant(1)))
        assertEquals(SnapshotProblem.UNVERIFIABLE_PROGRAM, SnapshotVerifier.verify(noHalt, snapshot))
        assertNull(CsVm.restore(underflow, snapshot))
    }

    // 合流点の規則（将来の分岐命令のため）。スタックは完全一致、変数は片方だけ初期化済みなら MaybeInitialized、型違いは合流できない
    @Test
    fun mergeRulesAtJoinPoints() {
        val int = VariableState.Initialized(SlotType.INT)
        val frame = Frame(listOf(SlotType.INT), listOf(int, VariableState.Uninitialized))
        assertEquals(frame, SnapshotVerifier.merge(frame, frame))
        assertNull(SnapshotVerifier.merge(frame, frame.copy(stack = emptyList())))
        assertNull(SnapshotVerifier.merge(frame, frame.copy(stack = listOf(SlotType.STRING))))
        val other = Frame(listOf(SlotType.INT), listOf(VariableState.Uninitialized, int))
        assertEquals(
            Frame(listOf(SlotType.INT), listOf(VariableState.MaybeInitialized(SlotType.INT), VariableState.MaybeInitialized(SlotType.INT))),
            SnapshotVerifier.merge(frame, other),
        )
        assertNull(SnapshotVerifier.merge(frame, frame.copy(variables = listOf(VariableState.Initialized(SlotType.STRING), VariableState.Uninitialized))))
    }

    @Test
    fun fingerprints() {
        val source = programs[0]
        assertEquals(ProgramFingerprint.SIZE, ProgramFingerprint.ofSource(source).size)
        assertArrayEquals(ProgramFingerprint.ofProgram(compile(source)), ProgramFingerprint.ofProgram(compile(source)))
        assertFalse(ProgramFingerprint.ofProgram(compile("println(1)")).contentEquals(ProgramFingerprint.ofProgram(compile("println(2)"))))
        // 位置だけが違う（空白の追加）場合もバイトコードの位置情報が変わるため別のプログラムとして扱う
        assertFalse(ProgramFingerprint.ofProgram(compile("println(1)")).contentEquals(ProgramFingerprint.ofProgram(compile(" println(1)"))))
        assertFalse(ProgramFingerprint.ofSource("println(1)").contentEquals(ProgramFingerprint.ofSource("println(1) ")))
        // 関数表（第3.8c段階）もハッシュに含める（関数の無いプログラムのハッシュは existingProgramHashesAreUnchanged で固定）
        val withFunction = compile("fun f() { }\nf()")
        assertFalse(ProgramFingerprint.ofProgram(withFunction).contentEquals(ProgramFingerprint.ofProgram(withFunction.copy(functions = listOf(FunctionInfo(3, 1))))))
    }

    // 既存（3.5〜3.8c）のプログラムのバイトコードのハッシュは変わらない（D14 で保存した実行状態を更新後も復元できる）。
    // 期待値は第3.7a段階の変更前の jar で計算した値と一致することを確認済み
    @Test
    fun existingProgramHashesAreUnchanged() {
        val expected = mapOf(
            programs[0] to "bb8f361652db528e2da75c5a865b54f7ed59c3af7fda14b2c8f337e338c20406",
            programs[1] to "bc0e5bbf993c037c3d2b6eef74c6196e7ab949e3c6ed4e7848dea1abc86465cb",
            "println(-2147483648)\nprintln(1 + 2 * 3 - 10 / 3 % 2)" to "f5d38ddd71ac6a028005c6028e93aeb7d1e5674a24331ba2204c666dae3d4453",
            // 第3.8c段階の引数の無い関数（期待値は第3.8d段階の変更前に計算した値）
            "fun greet() {\n    println(\"Hello\")\n}\n\ngreet()\ngreet()" to "fd501b49e82ad3b236583f5781b5dd0a99caa945aa192b733e338bfd26b275ff",
            "var total = 0\nfun leaf() {\n  val step = total + 1\n  waitTicks(2)\n  total = step\n}\nwork()\nleaf()\nprintln(total)\nprintln(\"end\")\n" +
                "fun work() {\n  for (i in 1..4) {\n    if (i == 2) { continue }\n    leaf()\n    println(i * 10 + total)\n    if (i == 3) { return }\n  }\n}" to
                "1d6838a0c605c97a2366d41a6036beca0c91e98ada78c7d25320fe52ae04f5f2",
        )
        for ((source, hash) in expected) {
            assertEquals(hash, ProgramFingerprint.ofProgram(compile(source)).joinToString("") { "%02x".format(it) }, source)
        }
    }

    // 多数の VM を同時に復元して登録しても、全体予算の下での配分の公平さは変わらない（各 tick の累計の差は 1 命令以内）
    @Test
    fun restoredVmsShareBudgetFairly() {
        val program = compile((1..200).joinToString("\n") { "println($it)" })
        val scheduler = Scheduler<Int>(4096)
        val vms = List(1000) { index ->
            val original = CsVm(program)
            original.run(index % 7)
            CsVm.restore(program, original.snapshot()!!)!!
        }
        vms.forEachIndexed { index, vm -> scheduler.register(index, vm) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        // 登録した tick は実行しない（A24）
        assertEquals(0, scheduler.tick { fast }.budgetUsed)
        val totals = IntArray(vms.size)
        repeat(20) {
            val result = scheduler.tick { fast }
            assertEquals(4096, result.budgetUsed)
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }

    // 復元処理（コンパイル・ハッシュ・意味検証・復元）の所要時間。JUnit の環境（ゲームサーバー外）での参考値として出力する
    @Test
    fun measureRestorePipeline() {
        // 上限 4096 単位に近い長さのプログラム（変数は上限 64 個の範囲で使う）
        val source = "var v = 0\n" + (1..400).joinToString("\n") { "v = v + $it * 3\nprintln(v)" }.take(3990).substringBeforeLast('\n')
        val warmup = compile(source)
        val snapshot = CsVm(warmup).also { it.run(50) }.snapshot()!!
        val sourceHash = ProgramFingerprint.ofSource(source)
        val programHash = ProgramFingerprint.ofProgram(warmup)
        fun restoreOnce() {
            assertTrue(ProgramFingerprint.ofSource(source).contentEquals(sourceHash))
            val program = compile(source)
            assertTrue(ProgramFingerprint.ofProgram(program).contentEquals(programHash))
            assertNotNull(CsVm.restore(program, snapshot))
        }
        repeat(200) { restoreOnce() }
        for (count in listOf(10, 100, 1000)) {
            val start = System.nanoTime()
            repeat(count) { restoreOnce() }
            val millis = (System.nanoTime() - start) / 1_000_000.0
            println("[D14 measure] JUnit restore pipeline: $count programs (${source.length} UTF-16 units each) in ${"%.2f".format(millis)} ms")
        }
    }
}
