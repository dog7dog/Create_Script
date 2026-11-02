package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.semantic.ScriptType
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Console
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.ListShape
import com.creatrbypc.createanalytical.script.vm.ListSnapshot
import com.creatrbypc.createanalytical.script.vm.ObjectSnapshot
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotProblem
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.SlotType
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

// 第3.10a段階の 1 次元リスト（List<T> / MutableList<T>）を、ソースから CS_VM・分割 GC・Scheduler・SnapshotVerifier まで通して確かめる
class ListTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = Compiler.compileSource(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    // Scheduler と同じ進め方の1歩（待機・回収の1単位・1命令）。歩くたびにリストの要素数の合計を数え直して確かめる
    private fun step(vm: CsVm) {
        when {
            vm.state == VmState.WAITING -> vm.countWaitTick()
            vm.isCollecting -> vm.collectGarbage(1)
            else -> vm.run(1)
        }
    }

    // VM が数えているリストの要素数の合計が、ヒープのリストの要素数を数え直した値と同じ（回収中を除く）
    private fun assertCounted(vm: CsVm) {
        val snapshot = vm.snapshot() ?: return
        val counted = snapshot.objects.sumOf { it.list?.elements?.size ?: 0 }
        // 回収の途中のスナップショットは、根からたどれるリストだけを含む（まだ消していないゴミの要素は VM の合計にだけ残る: 第3.10b段階）
        if (vm.isCollecting) assertTrue(vm.listElementCount >= counted) else assertEquals(counted, vm.listElementCount)
    }

    private fun run(source: String, checkEveryStep: Boolean = false): CsVm {
        val vm = CsVm(compile(source))
        var steps = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (checkEveryStep) {
                step(vm)
                assertCounted(vm)
            } else {
                vm.advance(1000).also { if (vm.state == VmState.WAITING) vm.countWaitTick() }
            }
            check(++steps < 2_000_000) { "did not finish" }
        }
        return vm
    }

    private fun output(source: String): List<String> {
        val vm = run(source)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private fun runtimeError(source: String): CsVm {
        val vm = run(source)
        assertEquals(VmState.ERROR, vm.state, source)
        return vm
    }

    // ---- 1〜10. 生成・取得・更新・追加・削除・clear・size ----

    @Test
    fun listsAreCreatedReadAndChanged() {
        assertEquals(listOf("10", "3", "[10, 20, 30]"), output("val numbers = listOf(10, 20, 30)\n\nprintln(numbers[0])\nprintln(numbers.size)\nprintln(numbers)"))
        // 指示書の例（removeAt は取り除いた要素を返す）
        assertEquals(
            listOf("3", "[50, 30, 40]", "10"),
            output("val numbers = mutableListOf(10, 20, 30)\n\nnumbers[1] = 50\nnumbers.add(40)\nval first = numbers.removeAt(0)\n\nprintln(numbers.size)\nprintln(numbers)\nprintln(first)"),
        )
        // 空のリスト（型引数）・clear の後の追加
        assertEquals(
            listOf("0", "0", "[5]", "[]", "[]", "[7, 8]"),
            output("val a = mutableListOf<Int>()\nval names = listOf<String>()\nprintln(a.size)\nprintln(names.size)\na.add(5)\nprintln(a)\nprintln(names)\na.clear()\nprintln(a)\na.add(7)\na.add(8)\nprintln(a)"),
        )
        // String・Boolean・クラスの要素（文字列は引用符なしで表示する）
        assertEquals(
            // 先に作った 2 つのリストが id 1・2 を使う
            listOf("[a, b, c]", "[true, false]", "[Robot@3, Robot@4]", "false"),
            output("class Robot\nval names = mutableListOf(\"a\", \"b\")\nnames.add(\"c\")\nprintln(names)\nprintln(listOf(true, false))\nval robots = listOf(Robot(), Robot())\nprintln(robots)\nprintln(robots[0] == robots[1])"),
        )
        // 表示はコンソールの 1 行（64 単位）で切る
        val long = output("val a = mutableListOf<Int>()\nfor (i in 0 until 256) { a.add(1000000 + i) }\nprintln(a)").single()
        assertEquals(Console.MAX_LINE_LENGTH, long.length)
        assertTrue(long.startsWith("[1000000, 1000001, ") && long.endsWith("…"), long)
        // 生成は要素を積んで NEW_LIST 1 命令。同じ形はリストの形の表で 1 つにまとめる
        val program = compile("val a = listOf(1, 2)\nval b = listOf(3, 4)\nval c = mutableListOf<Int>()")
        assertEquals(listOf(ListShape(SlotType.INT, false, 2), ListShape(SlotType.INT, true, 0)), program.lists)
        assertEquals(listOf(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.NEW_LIST, Opcode.STORE), program.instructions.take(4).map { it.opcode })
    }

    // ---- 4. 型推論と型の不一致 ----

    @Test
    fun typesAreInferredAndChecked() {
        val analyzed = SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize("val a = listOf(1, 2, 3)\nval b = mutableListOf(true, false)\nval c: List<String> = mutableListOf(\"x\")"))).program!!
        fun typeOf(name: String) = analyzed.declarations.values.single { it.name == name }.type
        assertEquals(ScriptType.ListType(ScriptType.INT, false), typeOf("a"))
        assertEquals(ScriptType.ListType(ScriptType.BOOLEAN, true), typeOf("b"))
        assertEquals(ScriptType.ListType(ScriptType.STRING, false), typeOf("c"))
        val cases = mapOf(
            // 異なる型の要素の混在（Any は無い）・型引数と合わない要素
            "val a = listOf(1, true)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "class Robot\nclass Gear\nval a = listOf(Robot(), Gear())" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a = listOf<Int>(1, \"a\")" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            // 要素の追加・代入・添字の型
            "val a = mutableListOf(10, 20)\n\na.add(true)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val a = mutableListOf(1)\na[0] = \"s\"" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a = listOf(1)\nprintln(a[true])" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val a = mutableListOf(1)\na.removeAt(\"0\")" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(1[0])" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            // 型を決められない空のリスト・型引数の数
            "val a = listOf()" to DiagnosticCode.SEM_LIST_TYPE_REQUIRED,
            "val a = mutableListOf()" to DiagnosticCode.SEM_LIST_TYPE_REQUIRED,
            "val a = listOf<Int, Int>()" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "val a: List = listOf(1)" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "val a: Int<String> = 1" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "fun f() { }\nf<Int>()" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "val a: List<Unit> = listOf(1)" to DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED,
            // 入れ子のリスト（第3.10b段階）でも、内側の型は完全に一致しなければならない（共変性は無い）
            "val a: List<List<Int>> = mutableListOf(mutableListOf(1))" to DiagnosticCode.SEM_TYPE_MISMATCH,
            // メンバー（size は読み取り専用・() の無いメソッド・存在しないメンバー・Unit の結果）
            "val a = mutableListOf(1)\na.size = 3" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "val a = mutableListOf(1)\nprintln(a.add)" to DiagnosticCode.SEM_FUNCTION_AS_VALUE,
            "val a = mutableListOf(1)\nprintln(a.size())" to DiagnosticCode.SEM_NOT_CALLABLE,
            "val a = mutableListOf(1)\nprintln(a.length)" to DiagnosticCode.SEM_UNKNOWN_PROPERTY,
            "val a = mutableListOf(1)\na.sort()" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "val a = mutableListOf(1)\na.add()" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "val a = mutableListOf(1)\nval u = a.add(2)" to DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED,
            // 組み込みの名前（第3.10a段階で予約）
            "val listOf = 1" to DiagnosticCode.SEM_BUILTIN_REDECLARATION,
            "fun mutableListOf() { }" to DiagnosticCode.SEM_BUILTIN_REDECLARATION,
            "class List" to DiagnosticCode.SEM_CLASS_NAME_CONFLICT,
            "class MutableList" to DiagnosticCode.SEM_CLASS_NAME_CONFLICT,
        )
        for ((source, code) in cases) assertEquals(listOf(code), codes(source), source)
    }

    // ---- 11・19. val / var と読み取り専用 ----

    @Test
    fun readOnlyListsCannotBeChanged() {
        assertEquals(listOf("[1, 2, 3]"), output("val a = mutableListOf(1, 2)\na.add(3)\nprintln(a)"))
        for (change in listOf("numbers[0] = 99", "numbers.add(40)", "numbers.removeAt(0)", "numbers.clear()")) {
            assertEquals(listOf(DiagnosticCode.SEM_READ_ONLY_LIST), codes("val numbers = listOf(10, 20, 30)\n\n$change"), change)
        }
        // var でも読み取り専用のリストは変更できない。MutableList から List への代入は可、逆は不可
        assertEquals(listOf(DiagnosticCode.SEM_READ_ONLY_LIST), codes("var a = listOf(1, 2)\na.add(3)"))
        assertEquals(listOf(DiagnosticCode.SEM_READ_ONLY_LIST), codes("val a = mutableListOf(1)\nval r: List<Int> = a\nr.add(2)"))
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("val a = mutableListOf(1)\na = mutableListOf(2)"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var a = mutableListOf(1)\na = listOf(2)"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val a: MutableList<Int> = listOf(1)"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("fun f(a: MutableList<Int>) { }\nf(listOf(1))"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("fun f(): MutableList<Int> { return listOf(1) }"))
        assertEquals(listOf("[2]", "[3]"), output("var a = mutableListOf(1)\na = mutableListOf(2)\nprintln(a)\nvar r: List<Int> = a\nr = listOf(3)\nprintln(r)"))
        // listOf のリストは実行時にも変更できない（型の検査を通る手組みのバイトコードでも、VM が拒否する）
        val origin = SourcePosition(0, 1, 1)
        val forged = CompiledProgram(
            listOf(
                Instruction(Opcode.NEW_LIST, 0, origin), Instruction(Opcode.PUSH_CONST, 0, origin), Instruction(Opcode.LIST_ADD, 0, origin),
                Instruction(Opcode.POP, 0, origin), Instruction(Opcode.HALT, 0, origin),
            ),
            listOf(com.creatrbypc.createanalytical.script.vm.Constant.IntConstant(1)), 0, 2, lists = listOf(ListShape(SlotType.INT, false, 0)),
        )
        assertNotNull(SnapshotVerifier.framesOf(forged))
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, CsVm(forged).also { it.run(10) }.error?.code)
    }

    // ---- 12〜15. 関数の引数・戻り値、クラスのプロパティ、メソッドの中の操作 ----

    @Test
    fun listsWorkWithFunctionsAndClasses() {
        val functions = "fun total(xs: List<Int>): Int {\n  var sum = 0\n  for (i in 0 until xs.size) { sum = sum + xs[i] }\n  return sum\n}\n" +
            "fun make(n: Int): MutableList<Int> {\n  val out = mutableListOf<Int>()\n  for (i in 1..n) { out.add(i * i) }\n  return out\n}\nval squares = make(4)\nprintln(squares)\nprintln(total(squares))\nprintln(total(listOf(5, 6)))"
        assertEquals(listOf("[1, 4, 9, 16]", "30", "11"), output(functions))
        val fleet = "class Robot { var power = 100 }\nclass Fleet {\n  val robots = mutableListOf<Robot>()\n  fun add(r: Robot) { robots.add(r) }\n  fun total(): Int {\n    var sum = 0\n" +
            "    for (i in 0 until robots.size) { sum = sum + robots[i].power }\n    return sum\n  }\n}\nval fleet = Fleet()\nfleet.add(Robot())\nfleet.add(Robot())\nfleet.robots[1].power = 50\nprintln(fleet.total())\nprintln(fleet.robots.size)"
        assertEquals(listOf("150", "2"), output(fleet))
    }

    // ---- 16・17. 範囲外の添字 ----

    @Test
    fun indexesAreBoundsChecked() {
        fun error(source: String) = runtimeError(source).error!!
        val outside = error("val a = listOf(10, 20)\n\nprintln(a[2])")
        assertEquals(DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS, outside.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(2), DiagnosticArgument.IntArgument(2)), outside.arguments)
        assertEquals(SourcePosition(33, 3, 10), outside.position)
        val negative = error("val i = -1\nprintln(listOf(1)[i])")
        assertEquals(listOf(DiagnosticArgument.IntArgument(-1), DiagnosticArgument.IntArgument(1)), negative.arguments)
        for (source in listOf("val a = mutableListOf(1)\na[1] = 2", "val a = mutableListOf(1)\na[-1] = 2", "val a = mutableListOf<Int>()\na.removeAt(0)", "val a = mutableListOf(1, 2)\na.removeAt(2)")) {
            assertEquals(DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS, error(source).code, source)
        }
        assertEquals(listOf(DiagnosticArgument.IntArgument(0), DiagnosticArgument.IntArgument(0)), error("val a = mutableListOf<Int>()\na.removeAt(0)").arguments)
        // 範囲外でエラーになった操作は何も変えない（エラーの前の出力のまま）
        val vm = runtimeError("val a = mutableListOf(1, 2)\nprintln(a)\na.removeAt(5)\nprintln(a)")
        assertEquals(listOf("[1, 2]"), vm.console.history)
    }

    // ---- 21. 参照の共有と同一性 ----

    @Test
    fun listsAreSharedByReference() {
        val source = "val a = mutableListOf(1, 2)\nval b = a\n\nb.add(3)\n\nprintln(a.size)\nval r: List<Int> = a\na.add(4)\nprintln(r.size)\nprintln(r == a)\nprintln(r)\n" +
            "val c = mutableListOf(1, 2, 3, 4)\nprintln(c == a)\nprintln(listOf(1) == listOf(1))"
        assertEquals(listOf("3", "4", "true", "[1, 2, 3, 4]", "false", "false"), output(source))
        // List<Int> と MutableList<Int> は比べられる（同一性）。要素の型が違うリストは比べられない
        assertEquals(listOf(DiagnosticCode.SEM_INCOMPARABLE_TYPES), codes("println(listOf(1) == listOf(true))"))
    }

    // ---- 20. 要素数の上限 ----

    @Test
    fun elementLimitsAreEnforced() {
        val single = runtimeError("val a = mutableListOf<Int>()\nfor (i in 0 until 256) { a.add(i) }\nprintln(a.size)\na.add(1)")
        assertEquals(DiagnosticCode.RUN_LIST_TOO_LARGE, single.error?.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(VmLimits.MAX_LIST_SIZE)), single.error?.arguments)
        assertEquals(listOf("256"), single.console.history)
        // 16 個 × 256 要素（合計 4096）を参照したまま、もう 1 つ作ると、回収してから CS_RUN_TOO_MANY_LIST_ELEMENTS
        val holders = (0 until 16).joinToString("") { "val h$it = fill()\n" }
        val total = runtimeError("fun fill(): MutableList<Int> {\n  val a = mutableListOf<Int>()\n  for (i in 0 until 256) { a.add(i) }\n  return a\n}\n${holders}println(\"full\")\nval extra = mutableListOf(1)")
        assertEquals(DiagnosticCode.RUN_TOO_MANY_LIST_ELEMENTS, total.error?.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(VmLimits.MAX_LIST_ELEMENTS)), total.error?.arguments)
        assertEquals(listOf("full"), total.console.history)
        assertEquals(VmLimits.MAX_LIST_ELEMENTS, total.listElementCount)
        // 捨てたリストの要素は回収され、合計 8000 要素を作っても続く（オブジェクトの数ではなく要素数の上限で回収が始まる）
        assertEquals(listOf("200"), output("var t = mutableListOf<Int>()\nfor (k in 0 until 40) {\n  t = mutableListOf<Int>()\n  for (i in 0 until 200) { t.add(i) }\n}\nprintln(t.size)"))
    }

    // ---- 22・23. リストの中のオブジェクトの保護と、循環の回収 ----

    @Test
    fun collectionTracesListElements() {
        val protected = "class Robot {\n    var power = 100\n}\n\nval robots = mutableListOf(Robot(), Robot())\nrobots[1].power = 7\nfor (i in 0 until 600) { val junk = Robot() }\nprintln(robots[0].power + robots[1].power)\nprintln(robots)"
        assertEquals(listOf("107", "[Robot@1, Robot@2]"), output(protected))
        // ノードとそのリストが互いに参照し合う組を捨て続ける
        val cycles = "class Node { val links = mutableListOf<Node>() }\nval keep = Node()\nkeep.links.add(keep)\nfor (i in 0 until 300) {\n  val a = Node()\n  val b = Node()\n  a.links.add(b)\n  b.links.add(a)\n}\nprintln(keep.links[0] == keep)\nprintln(keep.links.size)"
        assertEquals(listOf("true", "1"), output(cycles))
        // 小さな回収の予算（多数の tick に分かれる）でも同じ
        val vm = CsVm(compile(cycles))
        val scheduler = Scheduler<Int>(4096, 97, 13)
        scheduler.register(0, vm)
        var ticks = 0
        while (vm.state == VmState.RUNNING) {
            scheduler.tick { ExecutionCondition(PowerLevel.FAST, true) }
            check(++ticks < 1_000_000)
        }
        assertEquals(listOf("true", "1"), vm.console.history)
    }

    // ---- GC の後の再実行: 要素の二重の追加・スタックの破損・副作用の重複が無い ----

    @Test
    fun retriedListInstructionsRunOnce() {
        // 要素数の合計が満杯の add は実行せずに回収を待ち、回収の後に 1 回だけ実行する
        val program = compile("var t = mutableListOf<Int>()\nfor (k in 0 until 20) {\n  t = mutableListOf<Int>()\n  for (i in 0 until 250) { t.add(i) }\n}\nprintln(t.size)")
        val vm = CsVm(program)
        var before: VmSnapshot
        while (true) {
            before = vm.snapshot()!!
            vm.run(1)
            if (vm.isCollecting) break
        }
        val during = vm.snapshot()!!
        assertEquals(before, during)
        assertEquals(Opcode.LIST_ADD, program.instructions[during.pc].opcode)
        assertEquals(VmLimits.MAX_LIST_ELEMENTS, vm.listElementCount)
        assertEquals(0, vm.run(10))
        vm.collectGarbage(Int.MAX_VALUE)
        assertCounted(vm)
        val collected = vm.snapshot()!!
        val target = (collected.stack[collected.stack.size - 2] as Value.ObjectRef).id
        val sizeBefore = collected.objects.single { it.id == target }.list!!.elements.size
        assertEquals(1, vm.run(1))
        val after = vm.snapshot()!!
        assertEquals(sizeBefore + 1, after.objects.single { it.id == target }.list!!.elements.size)
        assertEquals(collected.stack.dropLast(2) + Value.UnitValue, after.stack)
        assertEquals(collected.nextObjectId, after.nextObjectId)
        assertEquals(during.pc + 1, after.pc)
        assertCounted(vm)
        while (vm.state == VmState.RUNNING) vm.advance(1000)
        assertEquals(listOf("250"), vm.console.history)
        // オブジェクトの数が満杯の NEW_LIST も同じ（要素を取り出す前に待ち、1 回だけ作る）
        val creation = compile("val keep = mutableListOf(7)\nvar t = listOf(1, 2)\nfor (i in 0 until 300) { t = listOf(i, i + 1) }\nprintln(t)\nprintln(keep)")
        val creator = CsVm(creation)
        while (true) {
            before = creator.snapshot()!!
            creator.run(1)
            if (creator.isCollecting) break
        }
        assertEquals(before, creator.snapshot())
        assertEquals(Opcode.NEW_LIST, creation.instructions[before.pc].opcode)
        creator.collectGarbage(Int.MAX_VALUE)
        creator.run(1)
        assertEquals(before.nextObjectId + 1, creator.snapshot()!!.nextObjectId)
        assertEquals(before.stack.dropLast(2) + Value.ObjectRef(before.nextObjectId), creator.snapshot()!!.stack)
        while (creator.state == VmState.RUNNING) creator.advance(1000)
        assertEquals(listOf("[299, 300]", "[7]"), creator.console.history)
    }

    // ---- 要素数の合計の整合性: 追加・削除・clear・生成・回収・保存と復元のすべての歩みで数え直した値と一致する ----

    @Test
    fun elementCounterStaysConsistent() {
        // 41 回のうち clear しない 27 回で 182 要素ずつ捨てる（合計 4096 を超えて回収が走る）
        val source = "val keep = mutableListOf(1, 2, 3)\nvar t = mutableListOf<Int>()\nfor (k in 0 until 41) {\n  t = mutableListOf(k, k, k)\n  for (i in 0 until 180) { t.add(i) }\n" +
            "  t.removeAt(0)\n  t[0] = 5\n  if (k % 3 == 0) { t.clear() }\n  keep.add(k)\n  keep.removeAt(0)\n}\nprintln(keep)\nprintln(t.size)"
        val vm = run(source, checkEveryStep = true)
        assertEquals(VmState.HALTED, vm.state, "${vm.error}")
        assertEquals(listOf("[38, 39, 40]", "182"), vm.console.history)
        // 途中の各段階（回収の途中を含む）から復元した VM も、数え直した値と一致し、同じ結果になる
        val program = compile(source)
        val original = CsVm(program)
        var cut = 0
        var restoredDuringCollection = 0
        while (original.state == VmState.RUNNING) {
            if (cut % 97 == 0 || original.isCollecting && cut % 7 == 0) {
                val restored = CsVm.restore(program, original.snapshot()!!)!!
                assertCounted(restored)
                if (original.isCollecting) restoredDuringCollection++
                while (restored.state == VmState.RUNNING) {
                    step(restored)
                    assertCounted(restored)
                }
                assertEquals(vm.console.history, restored.console.history)
            }
            step(original)
            cut++
        }
        assertTrue(restoredDuringCollection > 0)
    }

    // ---- 24. 回収の各単位を含むすべての境界で保存・復元しても、結果は中断しない実行と同じ ----

    @Test
    fun everyBoundaryRestores() {
        val sources = listOf(
            "val keep = mutableListOf(1, 2)\nval view: List<Int> = keep\nvar t = listOf(0)\nfor (k in 0 until 260) { t = listOf(k, k) }\nkeep.add(t[1])\nwaitTicks(2)\nprintln(view)\nprintln(view == keep)",
            "class Robot { var power = 1 }\nval robots = mutableListOf<Robot>()\nfor (i in 0 until 3) { robots.add(Robot()) }\nfor (i in 0 until 260) { val junk = Robot() }\nrobots[2].power = 9\nprintln(robots[2].power + robots.removeAt(0).power)\nprintln(robots)",
        )
        for (source in sources) {
            val program = compile(source)
            val expected = CsVm(program).also { while (it.state == VmState.RUNNING || it.state == VmState.WAITING) step(it) }.console.history
            val original = CsVm(program)
            var collectingCuts = 0
            while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
                val snapshot = original.snapshot()!!
                if (original.isCollecting) collectingCuts++
                val restored = CsVm.restore(program, snapshot)
                assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, snapshot)} in $source")
                assertEquals(snapshot, restored!!.snapshot())
                while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
                assertEquals(expected, restored.console.history, source)
                step(original)
            }
            assertTrue(collectingCuts > 0, source)
        }
    }

    // ---- 26. 不正なスナップショット ----

    // nums と view が同じ MutableList を、robots が Robot の List を指したまま待機する
    private val waitingProgram = compile("class Robot\nval nums = mutableListOf(1, 2, 3)\nval view: List<Int> = nums\nval robots = listOf(Robot())\nwaitTicks(100)\nnums.add(4)\nprintln(view)\nprintln(robots)")

    private fun waitingSnapshot(): VmSnapshot {
        val vm = CsVm(waitingProgram)
        while (vm.state != VmState.WAITING) vm.run(1)
        return vm.snapshot()!!
    }

    @Test
    fun listsSurviveSaveAndRestore() {
        val waiting = waitingSnapshot()
        assertEquals(
            listOf(
                ObjectSnapshot(1, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(SlotType.INT, true, listOf(Value.IntValue(1), Value.IntValue(2), Value.IntValue(3)))),
                ObjectSnapshot(2, 0),
                ObjectSnapshot(3, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(SlotType.OBJECT(0), false, listOf(Value.ObjectRef(2)))),
            ),
            waiting.objects,
        )
        assertEquals(listOf<Value?>(Value.ObjectRef(1), Value.ObjectRef(1), Value.ObjectRef(3)), waiting.variables)
        var vm = CsVm.restore(waitingProgram, waiting)!!
        assertEquals(4, vm.listElementCount)
        repeat(20) { vm = CsVm.restore(waitingProgram, vm.snapshot()!!)!!.also { it.countWaitTick() } }
        while (vm.state == VmState.WAITING || vm.state == VmState.RUNNING) step(vm)
        assertEquals(listOf("[1, 2, 3, 4]", "[Robot@2]"), vm.console.history)
    }

    @Test
    fun invalidListSnapshotsAreRejected() {
        val waiting = waitingSnapshot()
        val nums = waiting.objects[0]
        val robots = waiting.objects[2]
        fun withNums(list: ListSnapshot?, classId: Int = ObjectSnapshot.LIST_CLASS, fields: List<Value?> = emptyList()) =
            waiting.copy(objects = listOf(ObjectSnapshot(1, classId, fields, list), waiting.objects[1], robots))
        fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(waitingProgram, snapshot)
        val numbers = nums.list!!
        val invalidObjects = listOf(
            // 要素の型・要素の値の型・参照の先・要素数・要素の型の範囲
            withNums(numbers.copy(elements = listOf(Value.BoolValue(true)))),
            withNums(numbers.copy(elements = listOf(Value.UnitValue))),
            withNums(numbers.copy(elements = listOf(Value.StringValue(9)))),
            withNums(numbers.copy(elements = List(VmLimits.MAX_LIST_SIZE + 1) { Value.IntValue(0) })),
            withNums(numbers, classId = 0),
            withNums(numbers, fields = listOf(Value.IntValue(1))),
            waiting.copy(objects = listOf(nums, waiting.objects[1], robots.copy(list = robots.list!!.copy(elements = listOf(Value.ObjectRef(9)))))),
            waiting.copy(objects = listOf(nums, waiting.objects[1], robots.copy(list = robots.list!!.copy(elements = listOf(Value.ObjectRef(1)))))),
            waiting.copy(objects = listOf(nums, waiting.objects[1], robots.copy(list = ListSnapshot(SlotType.OBJECT(7), false, emptyList())))),
            waiting.copy(objects = listOf(nums, waiting.objects[1], robots.copy(list = ListSnapshot(SlotType.UNIT, false, emptyList())))),
            // 要素数の合計が上限を超える（参照されないリストを含めて数える）
            waiting.copy(objects = waiting.objects + (10..26).map { ObjectSnapshot(it, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(SlotType.INT, true, List(256) { Value.IntValue(0) })) }, nextObjectId = 30),
        )
        for (snapshot in invalidObjects) assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(snapshot), "${snapshot.objects.take(3)}")
        // 変数の型と合わないリスト（要素の型が違う・インスタンスを指す・リストの代わりの Int）
        val invalidVariables = listOf(
            waiting.copy(variables = listOf(Value.ObjectRef(3), Value.ObjectRef(1), Value.ObjectRef(3))),
            waiting.copy(variables = listOf(Value.ObjectRef(2), Value.ObjectRef(1), Value.ObjectRef(3))),
            waiting.copy(variables = listOf(Value.IntValue(1), Value.ObjectRef(1), Value.ObjectRef(3))),
            waiting.copy(variables = listOf(Value.ObjectRef(1), Value.ObjectRef(1), Value.ObjectRef(2))),
            withNums(numbers.copy(element = SlotType.BOOLEAN, elements = emptyList())),
        )
        for (snapshot in invalidVariables) assertEquals(SnapshotProblem.INVALID_VARIABLES, problem(snapshot), "${snapshot.variables}")
        // どこからも参照されないリスト・空のリストはあってよい
        assertNull(problem(waiting.copy(objects = waiting.objects + ObjectSnapshot(5, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(SlotType.STRING, false, emptyList())), nextObjectId = 6)))
    }

    // ---- 15・不正なバイトコード ----

    @Test
    fun invalidListBytecodeIsRejected() {
        val origin = SourcePosition(0, 1, 1)
        fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)
        val shapes = listOf(ListShape(SlotType.INT, true, 0), ListShape(SlotType.BOOLEAN, true, 1))
        fun program(vararg instructions: Instruction, lists: List<ListShape> = shapes) =
            CompiledProgram(instructions.toList(), listOf(com.creatrbypc.createanalytical.script.vm.Constant.IntConstant(1)), 0, 3, lists = lists)
        assertNotNull(SnapshotVerifier.framesOf(program(op(Opcode.NEW_LIST), op(Opcode.PUSH_CONST), op(Opcode.LIST_ADD), op(Opcode.POP), op(Opcode.HALT))))
        val cases = mapOf(
            "shape out of range" to program(op(Opcode.NEW_LIST, 2), op(Opcode.POP), op(Opcode.HALT)),
            "element of wrong type" to program(op(Opcode.PUSH_CONST), op(Opcode.NEW_LIST, 1), op(Opcode.POP), op(Opcode.HALT)),
            "missing element" to program(op(Opcode.NEW_LIST, 1), op(Opcode.POP), op(Opcode.HALT)),
            "get from int" to program(op(Opcode.PUSH_CONST), op(Opcode.PUSH_CONST), op(Opcode.LIST_GET), op(Opcode.POP), op(Opcode.HALT)),
            "boolean index" to program(op(Opcode.NEW_LIST), op(Opcode.PUSH_BOOL, 1), op(Opcode.LIST_GET), op(Opcode.POP), op(Opcode.HALT)),
            "add wrong type" to program(op(Opcode.NEW_LIST), op(Opcode.PUSH_BOOL, 1), op(Opcode.LIST_ADD), op(Opcode.POP), op(Opcode.HALT)),
            "set wrong type" to program(op(Opcode.NEW_LIST), op(Opcode.PUSH_CONST), op(Opcode.PUSH_BOOL, 0), op(Opcode.LIST_SET), op(Opcode.HALT)),
            "size of int" to program(op(Opcode.PUSH_CONST), op(Opcode.LIST_SIZE), op(Opcode.POP), op(Opcode.HALT)),
            "clear without list" to program(op(Opcode.LIST_CLEAR), op(Opcode.POP), op(Opcode.HALT)),
            "unit elements" to program(op(Opcode.HALT), lists = listOf(ListShape(SlotType.UNIT, true, 0))),
            "too many initial elements" to program(op(Opcode.HALT), lists = listOf(ListShape(SlotType.INT, true, VmLimits.MAX_LIST_SIZE + 1))),
            "element class out of range" to program(op(Opcode.HALT), lists = listOf(ListShape(SlotType.OBJECT(0), true, 0))),
        )
        for ((label, forged) in cases) assertNull(SnapshotVerifier.framesOf(forged), label)
        // 検証しないで実行しても（念のため）、VM はクラッシュせずに止まる
        for (label in listOf("shape out of range", "element of wrong type", "get from int", "boolean index", "add wrong type", "size of int", "clear without list")) {
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, CsVm(cases.getValue(label)).also { it.run(10) }.error?.code, label)
        }
    }

    // ---- 27. 予算と公平性 ----

    @Test
    fun listProgramsStayWithinBudgetAndFair() {
        val operations = compile("val a = mutableListOf<Int>()\nfor (i in 0 until 256) { a.add(i) }\nwhile (true) {\n  a.removeAt(0)\n  a.add(7)\n  a[100] = a[200]\n}")
        val churn = compile("var t = mutableListOf(1, 2, 3)\nwhile (true) {\n  t = mutableListOf(4, 5, 6)\n  t.add(t.size)\n}")
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) operations else churn)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(600)
        repeat(100) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096 && result.gcBudgetUsed <= Scheduler.GC_BUDGET_PER_TICK)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            assertTrue(result.finished.isEmpty(), "${result.finished}")
            result.executed.forEach { (key, count) -> totals[key] += count }
        }
        // 回収を待つ tick がある VM を除き、命令の進み方の差は 1 以内
        val running = totals.filterIndexed { index, _ -> index % 2 == 0 }
        assertTrue(running.max() - running.min() <= 1, "unfair: ${running.min()}..${running.max()}")
    }

    // ---- 互換性 ----

    @Test
    fun listShapesAreFingerprinted() {
        val program = compile("val a = mutableListOf(1)\nprintln(a)")
        val base = ProgramFingerprint.ofProgram(program)
        for (changed in listOf(ListShape(SlotType.INT, false, 1), ListShape(SlotType.BOOLEAN, true, 1), ListShape(SlotType.INT, true, 2))) {
            assertFalse(base.contentEquals(ProgramFingerprint.ofProgram(program.copy(lists = listOf(changed)))), "$changed")
        }
    }

    // ---- 性能（実測） ----

    private fun perTick(scheduler: Scheduler<Int>, ticks: Int = 1000): String {
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(300) { scheduler.tick { fast } }
        val times = DoubleArray(ticks)
        for (index in 0 until ticks) {
            val start = System.nanoTime()
            scheduler.tick { fast }
            times[index] = (System.nanoTime() - start) / 1_000_000.0
        }
        val sorted = times.sorted()
        return "avg %.3f / p50 %.3f / p95 %.3f / p99 %.3f / max %.3f ms".format(times.average(), sorted[ticks / 2], sorted[ticks * 95 / 100], sorted[ticks * 99 / 100], sorted.last())
    }

    @Test
    fun measureLists() {
        // 1 命令あたりの時間: 256 要素のリストの removeAt(0)（255 要素の移動）と add、clear と 256 回の add、要素の読み書き
        fun nanosPerInstruction(source: String): Double {
            val program = compile(source)
            val vms = List(20) { CsVm(program) }
            vms.forEach { it.run(200_000) }
            val start = System.nanoTime()
            var executed = 0L
            vms.forEach { executed += it.run(1_000_000) }
            return (System.nanoTime() - start).toDouble() / executed
        }
        val fill = "val a = mutableListOf<Int>()\nfor (i in 0 until 256) { a.add(i) }\n"
        println("[3.10a measure] removeAt(0) + add on 256 elements: %.1f ns per instruction".format(nanosPerInstruction(fill + "while (true) {\n  a.removeAt(0)\n  a.add(7)\n}")))
        println("[3.10a measure] clear + 256 adds: %.1f ns per instruction".format(nanosPerInstruction(fill + "while (true) {\n  a.clear()\n  for (i in 0 until 256) { a.add(i) }\n}")))
        println("[3.10a measure] get / set: %.1f ns per instruction".format(nanosPerInstruction(fill + "while (true) { a[100] = a[200] + 1 }")))
        println("[3.10a measure] integer loop without lists: %.1f ns per instruction".format(nanosPerInstruction("var n = 0\nwhile (true) { n = n + 1 - 1 }")))
        // 1 命令の最悪: 256 要素のリストの removeAt(0) を 1 回だけ実行する時間（その直前の状態から復元して 1 命令ずつ測る）
        val program = compile(fill + "a.removeAt(0)\nprintln(a.size)")
        val before = CsVm(program).also { vm -> while (program.instructions[vm.snapshot()!!.pc].opcode != Opcode.LIST_REMOVE_AT) vm.run(1) }.snapshot()!!
        val samples = LongArray(20_000)
        for (index in samples.indices) {
            val vm = CsVm.restore(program, before)!!
            val start = System.nanoTime()
            vm.run(1)
            samples[index] = System.nanoTime() - start
        }
        samples.sort()
        println("[3.10a measure] one removeAt(0) on 256 elements: median %d / p99 %d / max %d ns".format(samples[samples.size / 2], samples[samples.size * 99 / 100], samples.last()))
        // Scheduler の tick（1000 台）
        val operations = compile(fill + "while (true) {\n  a.removeAt(0)\n  a.add(7)\n  a[100] = a[200]\n}")
        val churn = compile("var t = mutableListOf(1, 2, 3)\nwhile (true) {\n  t = mutableListOf(4, 5, 6)\n  t.add(t.size)\n}")
        val methods = compile("class Counter {\n  var count = 0\n  fun increment(): Int {\n    count = count + 1\n    return count\n  }\n}\nval c = Counter()\nwhile (true) {\n  c.increment()\n  if (c.count > 1000) { c.count = 0 }\n}")
        for ((label, source) in listOf("1000 VMs doing removeAt(0) / add / get / set on 256 elements" to operations, "1000 VMs creating lists" to churn, "1000 VMs calling methods (no lists)" to methods)) {
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(source).also { vm -> vm.run(3000) }) }
            println("[3.10a measure] $label: ${perTick(scheduler)}")
        }
        // GC の最悪: 236 個の Robot と、その参照の 15 個 × 256 要素のリスト（要素の合計 4076）を持ったまま、満杯のヒープで Leaf を作り直す
        val holders = (0 until 15).joinToString("") { "val h$it = fill()\n" }
        val worst = compile("class Robot\nclass Leaf\nval pool = mutableListOf<Robot>()\nfor (i in 0 until 236) { pool.add(Robot()) }\n" +
            "fun fill(): MutableList<Robot> {\n  val a = mutableListOf<Robot>()\n  for (i in 0 until 256) { a.add(pool[i % 236]) }\n  return a\n}\n${holders}var t = Leaf()\nwhile (true) { t = Leaf() }")
        val probe = CsVm(worst)
        while (!probe.isCollecting) probe.run(1)
        val steady = probe.snapshot()!!
        assertEquals(236 + 15 * 256, steady.objects.sumOf { it.list?.elements?.size ?: 0 })
        val units = CsVm.restore(worst, steady)!!.also { it.run(1) }.collectGarbage(Int.MAX_VALUE)
        val alone = Scheduler<Int>(4096)
        val lone = CsVm.restore(worst, steady)!!
        alone.register(0, lone)
        alone.tick { ExecutionCondition(PowerLevel.FAST, true) }
        var ticks = 0
        while (true) {
            ticks++
            alone.tick { ExecutionCondition(PowerLevel.FAST, true) }
            if (!lone.isCollecting) break
        }
        println("[3.10a measure] worst-case collection with 4096 list references: $units units, $ticks ticks alone")
        val crowded = Scheduler<Int>(4096)
        val crowd = List(256) { CsVm.restore(worst, steady)!! }
        crowd.forEachIndexed { index, vm -> crowded.register(index, vm) }
        println("[3.10a measure] worst case GC, 256 VMs with full lists: ${perTick(crowded)}")
        assertTrue(crowd.all { it.state == VmState.RUNNING }, "${crowd.first().error}")
        // スナップショットの検証・復元（要素 4096 個）
        repeat(200) { assertNotNull(CsVm.restore(worst, steady)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(worst, steady) }
        println("[3.10a measure] verify + restore with 4096 list elements: %.3f ms per VM".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000))
        // メモリ: Int の要素 4096 個を持つ VM 200 台（参考値。JVM の使用量の差）
        val full = compile("fun fill(): MutableList<Int> {\n  val a = mutableListOf<Int>()\n  for (i in 0 until 256) { a.add(100000 + i) }\n  return a\n}\n" +
            (0 until 16).joinToString("") { "val g$it = fill()\n" } + "waitTicks(1000000)")
        val runtime = Runtime.getRuntime()
        System.gc()
        val baseline = runtime.totalMemory() - runtime.freeMemory()
        // 復元すると要素の値を保存データと共有するため、各 VM で実際に要素を作る
        val held = List(200) { CsVm(full).also { vm -> while (vm.state != VmState.WAITING) vm.run(100_000) } }
        System.gc()
        val used = runtime.totalMemory() - runtime.freeMemory()
        println("[3.10a measure] memory per VM with 4096 Int list elements: about %.0f KB (%d VMs)".format((used - baseline) / 1024.0 / held.size, held.size))
    }

    // ---- 無作為に作ったリストのプログラムと、Kotlin で書いた参照の実行器の比較 ----

    // a・b は MutableList<Int>、v は List<Int>（a か b と同じリストを指す）
    private sealed interface Stmt

    private data class Add(val target: String, val value: Int) : Stmt
    private data class Set(val target: String, val slot: Int, val value: Int) : Stmt
    private data class RemoveAt(val target: String, val slot: Int) : Stmt
    private data class Clear(val target: String) : Stmt
    private data class Alias(val target: String, val source: String) : Stmt
    private data class Fresh(val target: String, val values: List<Int>) : Stmt
    private data class PrintList(val source: String) : Stmt
    private data class PrintSize(val source: String) : Stmt
    private data class PrintElement(val source: String, val slot: Int) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class Loop(val count: Int, val body: List<Stmt>) : Stmt

    private class Reference {
        val output = ArrayList<String>()
        val values = HashMap<String, MutableList<Int>>()
        var waited = 0

        fun run(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Add -> values.getValue(statement.target).let { if (it.size < 200) it.add(statement.value) }
                    is Set -> values.getValue(statement.target).let { if (it.isNotEmpty()) it[statement.slot % it.size] = statement.value }
                    is RemoveAt -> values.getValue(statement.target).let { if (it.isNotEmpty()) it.removeAt(statement.slot % it.size) }
                    is Clear -> values.getValue(statement.target).clear()
                    is Alias -> values[statement.target] = values.getValue(statement.source)
                    is Fresh -> values[statement.target] = statement.values.toMutableList()
                    is PrintList -> output.add(Console.fit(values.getValue(statement.source).joinToString(", ", "[", "]")))
                    is PrintSize -> output.add(values.getValue(statement.source).size.toString())
                    is PrintElement -> values.getValue(statement.source).let { output.add(if (it.isEmpty()) "-" else it[statement.slot % it.size].toString()) }
                    is Wait -> waited += statement.ticks
                    is Loop -> repeat(statement.count) { run(statement.body) }
                }
            }
        }
    }

    private val mutables = listOf("a", "b")

    private fun generate(random: Random, depth: Int): List<Stmt> = List(random.nextInt(1, 6)) {
        val target = mutables.random(random)
        val readable = (mutables + "v").random(random)
        when (random.nextInt(if (depth < 2) 12 else 11)) {
            0, 1 -> Add(target, random.nextInt(-50, 100))
            2 -> Set(target, random.nextInt(0, 300), random.nextInt(-9, 9))
            3 -> RemoveAt(target, random.nextInt(0, 300))
            4 -> if (random.nextInt(4) == 0) Clear(target) else Add(target, 1)
            5 -> Alias(if (random.nextBoolean()) "v" else target, mutables.random(random))
            6 -> Fresh(target, List(random.nextInt(0, 4)) { random.nextInt(0, 10) })
            7 -> PrintList(readable)
            8 -> PrintSize(readable)
            9 -> PrintElement(readable, random.nextInt(0, 300))
            10 -> Wait(random.nextInt(0, 3))
            else -> Loop(random.nextInt(0, 5), generate(random, depth + 1))
        }
    }

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        indent + when (statement) {
            is Add -> "if (${statement.target}.size < 200) { ${statement.target}.add(${statement.value}) }\n"
            is Set -> "if (${statement.target}.size > 0) { ${statement.target}[${statement.slot} % ${statement.target}.size] = ${statement.value} }\n"
            is RemoveAt -> "if (${statement.target}.size > 0) { ${statement.target}.removeAt(${statement.slot} % ${statement.target}.size) }\n"
            is Clear -> "${statement.target}.clear()\n"
            is Alias -> "${statement.target} = ${statement.source}\n"
            is Fresh -> "${statement.target} = mutableListOf<Int>(${statement.values.joinToString(", ")})\n"
            is PrintList -> "println(${statement.source})\n"
            is PrintSize -> "println(${statement.source}.size)\n"
            is PrintElement -> "if (${statement.source}.size > 0) { println(${statement.source}[${statement.slot} % ${statement.source}.size]) } else { println(\"-\") }\n"
            is Wait -> "waitTicks(${statement.ticks})\n"
            is Loop -> "for (i in 0 until ${statement.count}) {\n" + render(statement.body, "$indent  ") + "$indent}\n"
        }
    }

    @Test
    fun randomListProgramsMatchReference() {
        val random = Random(310)
        var compared = 0
        var restored = 0
        repeat(300) { index ->
            val statements = generate(random, 0)
            val source = "var a = mutableListOf(1, 2, 3)\nvar b = mutableListOf<Int>()\nvar v: List<Int> = a\n" + render(statements, "") + "println(a.size + b.size)"
            if (source.length > 4096) return@repeat
            val reference = Reference()
            reference.values["a"] = mutableListOf(1, 2, 3)
            reference.values["b"] = mutableListOf()
            reference.values["v"] = reference.values.getValue("a")
            reference.run(statements)
            reference.output.add((reference.values.getValue("a").size + reference.values.getValue("b").size).toString())
            val program = compile(source)
            val vm = CsVm(program)
            var ticks = 0
            while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
                if (vm.state == VmState.WAITING) {
                    vm.countWaitTick()
                    ticks++
                } else {
                    vm.advance(1000)
                }
                assertCounted(vm)
            }
            assertEquals(VmState.HALTED, vm.state, "${vm.error} in $source")
            assertEquals(reference.output.takeLast(Console.MAX_LINES), vm.console.history, source)
            assertTrue(ticks >= reference.waited, source)
            compared++
            if (index % 20 == 0) {
                restored++
                val original = CsVm(program)
                while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
                    val copy = CsVm.restore(program, original.snapshot()!!)!!
                    while (copy.state == VmState.RUNNING || copy.state == VmState.WAITING) step(copy)
                    assertEquals(vm.console.history, copy.console.history, source)
                    step(original)
                }
            }
        }
        assertTrue(compared >= 280, "compared $compared")
        println("[3.10a random] $compared programs compared, $restored restored at every boundary")
    }
}
