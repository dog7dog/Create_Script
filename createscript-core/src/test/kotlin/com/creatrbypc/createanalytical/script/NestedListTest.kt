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

// 第3.10b段階の入れ子のリスト（List<List<Int>>・MutableList<MutableList<Int>>・3 段以上）を、ソースから CS_VM・分割 GC・Scheduler・SnapshotVerifier まで通して確かめる
class NestedListTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = Compiler.compileSource(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    // Scheduler と同じ進め方の1歩（待機・回収の1単位・1命令）
    private fun step(vm: CsVm) {
        when {
            vm.state == VmState.WAITING -> vm.countWaitTick()
            vm.isCollecting -> vm.collectGarbage(1)
            else -> vm.run(1)
        }
    }

    // VM が数えているリストの要素数の合計が、ヒープのリストの要素数（内側のリストへの参照を含む）を数え直した値と同じ（回収中を除く）
    private fun assertCounted(vm: CsVm) {
        val snapshot = vm.snapshot() ?: return
        val counted = snapshot.objects.sumOf { it.list?.elements?.size ?: 0 }
        // 回収の途中のスナップショットは、根からたどれるリストだけを含む（まだ消していないゴミの要素は VM の合計にだけ残る: 第3.10b段階）
        if (vm.isCollecting) assertTrue(vm.listElementCount >= counted) else assertEquals(counted, vm.listElementCount)
    }

    private fun finish(vm: CsVm): CsVm {
        var steps = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            vm.advance(1000).also { if (vm.state == VmState.WAITING) vm.countWaitTick() }
            check(++steps < 2_000_000) { "did not finish" }
        }
        return vm
    }

    private fun run(source: String) = finish(CsVm(compile(source)))

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

    private fun typeOf(source: String, name: String): ScriptType? =
        SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize(source))).program!!.declarations.values.single { it.name == name }.type

    private fun list(element: ScriptType, mutable: Boolean) = ScriptType.ListType(element, mutable)

    // ---- 生成・連続した添字の読み書き・内側のリストの操作・表示 ----

    @Test
    fun nestedListsAreCreatedReadAndChanged() {
        val matrix = "val m = mutableListOf(mutableListOf(1, 2), mutableListOf(3, 4))\nm[0][1] = 20\nm[1].add(5)\nprintln(m[0][1] + m[1][2])\nprintln(m.size)\nprintln(m[1].size)\nprintln(m)\n" +
            "m.add(mutableListOf<Int>())\nm[2].add(m[0].removeAt(0))\nm.removeAt(1)\nprintln(m)\nm[1].clear()\nprintln(m)"
        assertEquals(listOf("25", "2", "3", "[[1, 20], [3, 4, 5]]", "[[20], [1]]", "[[20], []]"), output(matrix))
        // 3 段・空の内側・String とクラスの要素
        val cube = "class Robot\nval c = listOf(listOf(listOf(1), listOf<Int>()), listOf(listOf(2, 3)))\nprintln(c)\nprintln(c[1][0][1])\nprintln(listOf(listOf(\"a\"), listOf(\"b\", \"c\")))\n" +
            "val g = listOf(listOf(Robot()))\nprintln(g)"
        assertEquals(listOf("[[[1], []], [[2, 3]]]", "3", "[[a], [b, c]]", "[[Robot@10]]"), output(cube))
        // m[i][j] は受け手・添字の LIST_GET の後に添字・値を積んで LIST_SET（新しい命令は無い）
        val program = compile("val m = mutableListOf(mutableListOf(1))\nm[0][0] = 2")
        assertEquals(listOf(Opcode.LOAD, Opcode.PUSH_CONST, Opcode.LIST_GET, Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.LIST_SET), program.instructions.drop(4).take(6).map { it.opcode })
        assertEquals(listOf(ListShape(SlotType.INT, true, 1), ListShape(SlotType.LIST(SlotType.INT), true, 1)), program.lists)
        // 同じ型は同じインスタンス（深い型の比較を同一性で済ませる）
        val shared = compile("val a = mutableListOf(mutableListOf(1))\nval b = listOf(mutableListOf(2))").lists
        assertTrue(shared[1].element === shared[2].element, "$shared")
        // 表示は外側から内側まで通してコンソールの 1 行（64 単位）で切る
        val wide = output("val rows = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 50) { rows.add(mutableListOf(1000000 + i, i)) }\nprintln(rows)").single()
        assertEquals(Console.fit((0 until 50).joinToString(", ", "[", "]") { "[${1000000 + it}, $it]" }), wide)
        assertEquals(Console.MAX_LINE_LENGTH, wide.length)
    }

    // ---- 可変性は段ごとに独立。読み取り専用の段の変更は、その段の型で報告する ----

    @Test
    fun mutabilityIsIndependentPerLevel() {
        // List<MutableList<Int>>: 外側は変更できず、内側は変更できる
        assertEquals(listOf("[[5, 2]]"), output("val m: List<MutableList<Int>> = listOf(mutableListOf(1))\nm[0][0] = 5\nm[0].add(2)\nprintln(m)"))
        // MutableList<List<Int>>: 外側は変更でき、内側は変更できない
        assertEquals(listOf("[[9], [1]]"), output("val m: MutableList<List<Int>> = mutableListOf(listOf(1))\nm.add(listOf(1))\nm[0] = listOf(9)\nm.removeAt(2 - 1)\nm.add(listOf(1))\nprintln(m)"))
        val outerReadOnly = "val m: List<MutableList<Int>> = listOf(mutableListOf(1))\n"
        val innerReadOnly = "val m: MutableList<List<Int>> = mutableListOf(listOf(1))\n"
        // 2 行目の何文字目（"[" かメンバー名）で報告するか
        val rejected = mapOf(
            outerReadOnly + "m[0] = mutableListOf(2)" to 2,
            outerReadOnly + "m.add(mutableListOf(2))" to 3,
            outerReadOnly + "m.clear()" to 3,
            innerReadOnly + "m[0][0] = 2" to 5,
            innerReadOnly + "m[0].add(2)" to 6,
            innerReadOnly + "m[0].removeAt(0)" to 6,
            "val c: MutableList<MutableList<List<Int>>> = mutableListOf(mutableListOf(listOf(1)))\nc[0][0][0] = 2" to 8,
        )
        for ((source, column) in rejected) {
            val result = Compiler.compileSource(source)
            assertEquals(listOf(DiagnosticCode.SEM_READ_ONLY_LIST), result.diagnostics.map { it.code }, source)
            assertEquals(SourcePosition(source.indexOf('\n') + column, 2, column), result.diagnostics.single().position, source)
        }
        // 報告する型はその段の型
        val inner = Compiler.compileSource("val m: MutableList<List<Int>> = mutableListOf(listOf(1))\nm[0][0] = 2").diagnostics.single()
        assertEquals(listOf(DiagnosticArgument.StringArgument("List<Int>")), inner.arguments)
        // listOf の外側は実行時にも変更できない（内側の mutableListOf は変更できる）
        assertEquals(listOf("[[1, 2]]"), output("val m = listOf(mutableListOf(1))\nm[0].add(2)\nprintln(m)"))
    }

    // ---- 型: 最上位だけの MutableList → List、内側は完全一致（共変性は無い）。最上位の可変性だけが違う要素は List<T> にそろえる（順序によらない） ----

    @Test
    fun typesAreStrictAndInferredIndependentlyOfOrder() {
        assertEquals(list(list(ScriptType.INT, true), false), typeOf("val a: List<MutableList<Int>> = mutableListOf(mutableListOf(1))", "a"))
        assertEquals(list(list(list(ScriptType.INT, false), true), true), typeOf("val a = mutableListOf(mutableListOf(listOf(1)))", "a"))
        // 要素の型の違いが最上位の可変性だけなら List<T>（どちらの順でも同じ）
        for (source in listOf("val a = listOf(listOf(1), mutableListOf(2))", "val a = listOf(mutableListOf(2), listOf(1))", "val a = listOf(mutableListOf(1), mutableListOf(2), listOf(3))")) {
            assertEquals(list(list(ScriptType.INT, false), false), typeOf(source, "a"), source)
        }
        assertEquals(list(list(ScriptType.INT, false), true), typeOf("val a = mutableListOf(mutableListOf(2), listOf(1))", "a"))
        // 同じ可変性なら変えない
        assertEquals(list(list(ScriptType.INT, true), false), typeOf("val a = listOf(mutableListOf(1), mutableListOf(2))", "a"))
        // 型引数があればその型（要素は値の代入と同じ規則: MutableList<Int> を List<Int> の要素にできる）
        assertEquals(list(list(ScriptType.INT, false), true), typeOf("val r = mutableListOf(1)\nval a = mutableListOf<List<Int>>(r)\na.add(mutableListOf(2))", "a"))
        val mismatches = mapOf(
            // 内側の変換（共変性）は無い
            "val a: List<List<Int>> = mutableListOf(mutableListOf(1))" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a: List<List<Int>> = listOf(mutableListOf(1))" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a = mutableListOf(mutableListOf(1))\nval b: MutableList<List<Int>> = a" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "fun f(a: List<List<Int>>) { }\nf(listOf(mutableListOf(1)))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            // 内側の要素の型・内側の段の可変性が違う要素は混ぜられない
            "val a = listOf(listOf(1), listOf(true))" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a = listOf(listOf(mutableListOf(1)), listOf(listOf(1)))" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a = listOf(listOf(1), 2)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val a = listOf<List<Int>>(listOf(true))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val a = mutableListOf(listOf(1))\na.add(listOf(\"x\"))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val a = mutableListOf(mutableListOf(1))\na[0] = listOf(2)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            // 比較は同じ型か、最上位の可変性だけの違い
            "println(listOf(listOf(1)) == listOf(listOf(true)))" to DiagnosticCode.SEM_INCOMPARABLE_TYPES,
            "println(listOf(listOf(1)) == listOf(mutableListOf(1)))" to DiagnosticCode.SEM_INCOMPARABLE_TYPES,
        )
        for ((source, code) in mismatches) assertEquals(listOf(code), codes(source), source)
        assertEquals(listOf("true", "false"), output("val r = mutableListOf(1)\nval a = mutableListOf(r)\nval v: List<MutableList<Int>> = a\nprintln(v == a)\nprintln(a[0] == mutableListOf(1))"))
    }

    // ---- 診断 ----

    @Test
    fun invalidNestedListsAreDiagnosed() {
        val cases = mapOf(
            "val a: List<List<Int, Int>> = listOf(listOf(1))" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "val a: List<List> = listOf(listOf(1))" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "val a: List<List<Unit>> = listOf(listOf(1))" to DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED,
            "val a: List<List<Robot>> = listOf(listOf(1))" to DiagnosticCode.SEM_UNKNOWN_TYPE,
            "val a: List<List<>> = listOf(listOf(1))" to DiagnosticCode.PARSE_EXPECTED_TYPE,
            "val a = listOf<List<Int>>()\nprintln(a[0][0][0])" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "val a = listOf(listOf(1))\nprintln(a[0][true])" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val a = listOf(listOf(1))\nprintln(a[0].length)" to DiagnosticCode.SEM_UNKNOWN_PROPERTY,
            "val a = mutableListOf(mutableListOf(1))\na[0].size = 2" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "val a = mutableListOf(mutableListOf(1))\nval u = a[0].add(2)" to DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED,
            "val a = mutableListOf(mutableListOf<Int>())\nval b = listOf(a[0].clear())" to DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED,
            "val a = listOf(listOf())" to DiagnosticCode.SEM_LIST_TYPE_REQUIRED,
        )
        for ((source, code) in cases) assertEquals(code, codes(source).first(), source)
        // 内側の範囲外は内側の添字と要素数。位置はその "["
        val outside = runtimeError("val m = listOf(listOf(1, 2), listOf(3))\nprintln(m[1][1])").error!!
        assertEquals(DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS, outside.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(1), DiagnosticArgument.IntArgument(1)), outside.arguments)
        assertEquals(SourcePosition(52, 2, 13), outside.position)
        val outer = runtimeError("val m = mutableListOf(mutableListOf(1))\nm[1][0] = 2").error!!
        assertEquals(listOf(DiagnosticArgument.IntArgument(1), DiagnosticArgument.IntArgument(1)), outer.arguments)
        assertEquals(SourcePosition(41, 2, 2), outer.position)
        // 範囲外でエラーになった操作は何も変えない
        val vm = runtimeError("val m = mutableListOf(mutableListOf(1))\nprintln(m)\nm[0].removeAt(3)\nprintln(m)")
        assertEquals(listOf("[[1]]"), vm.console.history)
        // 深い型の名前は診断の引数の上限（64 単位）で切る
        val deep = "List<".repeat(20) + "Int" + ">".repeat(20)
        val mismatch = Compiler.compileSource("val a: $deep = 1").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_TYPE_MISMATCH, mismatch.code)
        assertEquals(DiagnosticArgument.StringArgument.truncated(deep), mismatch.arguments[0])
    }

    // ---- 参照の共有と同一性 ----

    @Test
    fun innerListsAreSharedByReference() {
        val source = "val row = mutableListOf(1)\nval m = mutableListOf(row, row)\nval n = mutableListOf(m[0])\nm[1].add(2)\nprintln(m)\nprintln(n)\nprintln(row)\n" +
            "println(m[0] == m[1])\nprintln(n[0] == row)\nm[0] = mutableListOf(1, 2)\nprintln(m[0] == m[1])\nprintln(m[0][1] == m[1][1])\nval v: List<MutableList<Int>> = m\nm.add(row)\nprintln(v.size)\nprintln(v == m)"
        assertEquals(listOf("[[1, 2], [1, 2]]", "[[1, 2]]", "[1, 2]", "true", "true", "false", "true", "3", "true"), output(source))
    }

    // ---- 入れ子の深さ: 型の入れ子に固定の上限は無く、ソースの長さ・トークン数の上限までになる。式の入れ子は従来の上限（32）のまま ----

    private fun deepTypeSource(depth: Int) = "val a = mutableListOf<${"List<".repeat(depth)}Int${">".repeat(depth)}>()\nprintln(a.size)"

    // 1 つの式で 25 段ずつ包み、変数を介して 151 段の値を作る（1 段ごとに別のリスト。式の入れ子は上限 32 の内側）
    private val DEEP_VALUE_LEVELS = 150

    private fun deepValueSource() = "val l0 = mutableListOf(7)\nval l1 = listOf(l0)\n" +
        (1..DEEP_VALUE_LEVELS / 25).joinToString("") { "val w$it = ${"listOf(".repeat(25)}${if (it == 1) "l0" else "w${it - 1}"}${")".repeat(25)}\n" } +
        "l0.add(8)\nprintln(w${DEEP_VALUE_LEVELS / 25})\nprintln(l1)"

    private fun maxTypeDepth(): Int {
        var depth = 600
        while (Compiler.compileSource(deepTypeSource(depth + 1)).program != null) depth++
        return depth
    }

    @Test
    fun nestingDepthIsLimitedOnlyBySourceSize() {
        val max = maxTypeDepth()
        assertTrue(max >= 670, "max $max")
        assertEquals(listOf("0"), output(deepTypeSource(max)))
        // その次の深さは型の深さではなく、ソースの長さの上限で止まる
        assertEquals(DiagnosticCode.LEX_SOURCE_TOO_LONG, codes(deepTypeSource(max + 1)).first())
        assertTrue(deepTypeSource(max + 1).length > 4096)
        // 式の入れ子（listOf の中の listOf・連続した添字）は構文の深さの上限 32 のまま
        assertEquals(DiagnosticCode.PARSE_NESTING_TOO_DEEP, codes("val a = ${"listOf(".repeat(40)}1${")".repeat(40)}").first())
        assertEquals(DiagnosticCode.PARSE_NESTING_TOO_DEEP, codes("val a = listOf(1)\nprintln(a${"[0]".repeat(40)})").first())
        // 値の入れ子（151 段）は変数を介して作れる。表示はコンソールの 1 行で切る
        val deepValue = output(deepValueSource())
        assertEquals(Console.fit("[".repeat(DEEP_VALUE_LEVELS + 1) + "7, 8" + "]".repeat(DEEP_VALUE_LEVELS + 1)), deepValue[0])
        assertEquals("[[7, 8]]", deepValue[1])
    }

    // 型・値の深い入れ子を、256KB のスタックのスレッドで字句解析から実行・保存・検証・復元・指紋まで通す（再帰の深さが足りる）
    @Test
    fun deepNestingFitsIn256KbStack() {
        val max = maxTypeDepth()
        val failures = ArrayList<Throwable>()
        fun onSmallStack(work: () -> Unit) {
            val thread = Thread(null, { runCatching(work).onFailure { synchronized(failures) { failures.add(it) } } }, "small-stack", 256L * 1024)
            thread.start()
            thread.join()
        }
        onSmallStack {
            // 別々に書いた同じ深い型（別のインスタンス）どうしの比較を含む
            val half = "List<".repeat(320) + "Int" + ">".repeat(320)
            for (source in listOf(deepTypeSource(max), deepValueSource(), "val e = listOf<$half>()\nval a: List<$half> = e\nprintln(a.size)", "val a: ${"List<".repeat(max - 2)}Int${">".repeat(max - 2)} = listOf(1)")) {
                val result = Compiler.compileSource(source)
                val program = result.program ?: continue
                ProgramFingerprint.ofProgram(program)
                val vm = CsVm(program)
                while (vm.state == VmState.RUNNING) {
                    val snapshot = vm.snapshot()!!
                    check(SnapshotVerifier.verify(program, snapshot) == null)
                    // 保存した型を別のインスタンスにしても（保存データから読んだ型と同じ）、検証・復元できる
                    val copied = snapshot.copy(objects = snapshot.objects.map { entry -> entry.copy(list = entry.list?.let { it.copy(element = rebuilt(it.element)) }) })
                    check(CsVm.restore(program, copied) != null)
                    vm.run(1)
                }
            }
            // 深い型の不一致の診断
            check(Compiler.compileSource("val a: ${"List<".repeat(max - 2)}Int${">".repeat(max - 2)} = 1").diagnostics.single().code == DiagnosticCode.SEM_TYPE_MISMATCH)
        }
        assertEquals(emptyList<Throwable>(), failures)
        // 対照: このスタックの大きさは実際に効いている（深い再帰はあふれる）
        var overflowed = false
        fun recurse(depth: Int): Int = if (depth == 0) 0 else recurse(depth - 1) + 1
        val control = Thread(null, { try { recurse(1_000_000) } catch (overflow: StackOverflowError) { overflowed = true } }, "small-stack-control", 256L * 1024)
        control.start()
        control.join()
        assertTrue(overflowed)
    }

    // 同じ構造の型を、共有しない別のインスタンスで作り直す
    private fun rebuilt(type: SlotType): SlotType {
        val levels = ArrayList<SlotType>()
        var current = type
        while (current is SlotType.LIST) {
            levels.add(current)
            current = current.element
        }
        var result: SlotType = when (current) {
            is SlotType.OBJECT -> SlotType.OBJECT(current.classId)
            else -> current
        }
        repeat(levels.size) { result = SlotType.LIST(result) }
        return result
    }

    // ---- GC: 内側のリストの到達可能性・共有・クラスを介した循環 ----

    @Test
    fun collectionTracesInnerLists() {
        // m（1）と内側（2〜4）、other の外側（6）と [9]（5）。other を空のリスト（7）に置き換えると、5・6 だけが回収される
        val source = "class Leaf\nval m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 3) { m.add(mutableListOf(i)) }\nval shared = m[1]\n" +
            "var other = mutableListOf(shared, mutableListOf(9))\nother = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 600) { val junk = Leaf() }\nm[1].add(5)\nwaitTicks(1)\nprintln(shared)\nprintln(m)"
        val vm = CsVm(compile(source))
        while (vm.state == VmState.RUNNING) vm.advance(1000)
        val ids = vm.snapshot()!!.objects.filter { it.list != null }.map { it.id }
        assertEquals(listOf(1, 2, 3, 4, 7), ids)
        finish(vm)
        assertEquals(listOf("[1, 5]", "[[0], [1, 5], [2]]"), vm.console.history)
        // ノードが、自分を含む内側のリストを持つ（クラスを介した循環）組を捨て続ける。小さな回収の予算でも同じ
        val cycles = "class Node { val groups = mutableListOf<MutableList<Node>>() }\nval keep = Node()\nkeep.groups.add(mutableListOf(keep))\nfor (i in 0 until 200) {\n" +
            "  val a = Node()\n  val b = Node()\n  a.groups.add(mutableListOf(b))\n  b.groups.add(mutableListOf(a, b))\n}\nprintln(keep.groups[0][0] == keep)\nprintln(keep.groups.size)"
        assertEquals(listOf("true", "1"), output(cycles))
        val machine = CsVm(compile(cycles))
        val scheduler = Scheduler<Int>(4096, 97, 13)
        scheduler.register(0, machine)
        var ticks = 0
        while (machine.state == VmState.RUNNING) {
            scheduler.tick { ExecutionCondition(PowerLevel.FAST, true) }
            check(++ticks < 1_000_000)
        }
        assertEquals(listOf("true", "1"), machine.console.history)
    }

    // ---- GC の後の再実行: 外側への追加・内側のリストの生成は 1 回だけ ----

    @Test
    fun retriedNestedInstructionsRunOnce() {
        // 同じ内側のリストへの参照 250 個ずつの外側を作り直す（参照も要素に数えるため、17 回目で要素数の合計が満杯になる）
        val program = compile("val row = mutableListOf(1)\nvar t = mutableListOf<MutableList<Int>>()\nfor (k in 0 until 20) {\n  t = mutableListOf<MutableList<Int>>()\n  for (i in 0 until 250) { t.add(row) }\n}\nprintln(t.size)\nprintln(t[249] == row)")
        val vm = CsVm(program)
        var before: VmSnapshot
        while (true) {
            before = vm.snapshot()!!
            vm.run(1)
            if (vm.isCollecting) break
        }
        assertEquals(before, vm.snapshot())
        assertEquals(Opcode.LIST_ADD, program.instructions[before.pc].opcode)
        assertEquals(VmLimits.MAX_LIST_ELEMENTS, vm.listElementCount)
        vm.collectGarbage(Int.MAX_VALUE)
        assertCounted(vm)
        val collected = vm.snapshot()!!
        val target = (collected.stack[collected.stack.size - 2] as Value.ObjectRef).id
        val elementsBefore = collected.objects.single { it.id == target }.list!!.elements
        assertEquals(1, vm.run(1))
        val after = vm.snapshot()!!
        assertEquals(elementsBefore + Value.ObjectRef(1), after.objects.single { it.id == target }.list!!.elements)
        assertEquals(collected.stack.dropLast(2) + Value.UnitValue, after.stack)
        assertEquals(collected.nextObjectId, after.nextObjectId)
        assertCounted(vm)
        finish(vm)
        assertEquals(listOf("250", "true"), vm.console.history)
        // オブジェクトの数が満杯の内側の生成も同じ（外側の要素を積んだまま待ち、1 回だけ作る）
        val creation = compile("val keep = mutableListOf(mutableListOf(7))\nvar t = listOf(listOf(1))\nfor (i in 0 until 300) { t = listOf(listOf(i), listOf(i + 1)) }\nprintln(t)\nprintln(keep)")
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
        finish(creator)
        assertEquals(listOf("[[299], [300]]", "[[7]]"), creator.console.history)
    }

    // ---- 上限: 1 リスト 256 要素・VM 全体 4096 要素（外側の参照を含む）・オブジェクト 256 個 ----

    @Test
    fun limitsAreKept() {
        val objects = runtimeError("val m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 255) { m.add(mutableListOf<Int>()) }\nprintln(m.size)\nval extra = mutableListOf<Int>()")
        assertEquals(DiagnosticCode.RUN_TOO_MANY_OBJECTS, objects.error?.code)
        assertEquals(listOf("255"), objects.console.history)
        val single = runtimeError("val row = mutableListOf(1)\nval m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 256) { m.add(row) }\nprintln(m.size)\nm.add(row)")
        assertEquals(DiagnosticCode.RUN_LIST_TOO_LARGE, single.error?.code)
        // 15 行 × 256 要素 + 外側の参照 15 + 241 個の要素で 4096。もう 1 つの要素は回収の後に CS_RUN_TOO_MANY_LIST_ELEMENTS
        val total = runtimeError("val m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 15) {\n  val row = mutableListOf<Int>()\n  for (j in 0 until 256) { row.add(j) }\n  m.add(row)\n}\n" +
            "val last = mutableListOf<Int>()\nfor (j in 0 until 240) { last.add(j) }\nm.add(last)\nprintln(\"full\")\nlast.add(1)")
        assertEquals(DiagnosticCode.RUN_TOO_MANY_LIST_ELEMENTS, total.error?.code)
        assertEquals(listOf("full"), total.console.history)
        assertEquals(VmLimits.MAX_LIST_ELEMENTS, total.listElementCount)
    }

    // ---- 回収の各単位を含むすべての境界で保存・復元しても、結果は中断しない実行と同じ ----

    @Test
    fun everyBoundaryRestores() {
        val sources = listOf(
            "val m = mutableListOf(mutableListOf(1, 2), mutableListOf(3))\nval view: List<MutableList<Int>> = m\nvar t = listOf(listOf(0))\nfor (k in 0 until 130) { t = listOf(listOf(k), listOf(k + 1)) }\n" +
                "m[1].add(t[1][0])\nwaitTicks(2)\nm[0][1] = view[1][1] + 1\nprintln(view)\nprintln(t)",
            "class Cell { var v = 0 }\nval cube = mutableListOf<MutableList<MutableList<Cell>>>()\nfor (i in 0 until 2) {\n  val plane = mutableListOf<MutableList<Cell>>()\n" +
                "  for (j in 0 until 2) { plane.add(mutableListOf(Cell(), Cell())) }\n  cube.add(plane)\n}\ncube[1][0][1].v = 7\nfor (i in 0 until 260) { val junk = Cell() }\nprintln(cube[1][0][1].v + cube[0][1].size)\nprintln(cube[0])",
        )
        val expectedOutputs = listOf(listOf("[[1, 131], [3, 130]]", "[[129], [130]]"), listOf("9", "[[Cell@3, Cell@4], [Cell@6, Cell@7]]"))
        for ((source, expected) in sources.zip(expectedOutputs)) {
            val program = compile(source)
            val original = CsVm(program)
            var collectingCuts = 0
            while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
                val snapshot = original.snapshot()!!
                if (original.isCollecting) collectingCuts++
                val restored = CsVm.restore(program, snapshot)
                assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, snapshot)} in $source")
                assertEquals(snapshot, restored!!.snapshot())
                assertCounted(restored)
                while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
                assertEquals(expected, restored.console.history, source)
                step(original)
            }
            assertEquals(expected, original.console.history)
            assertTrue(collectingCuts > 0, source)
        }
    }

    // ---- 不正なスナップショット: 内側の型の不一致・自分自身を含むリスト（循環は型の上で作れない） ----

    // 内側 [1]（1）・[2]（2）、m（3）、r（4: List<List<Int>> で 1 を指す）
    private val waitingProgram = compile("val m = mutableListOf(mutableListOf(1), mutableListOf(2))\nval r = listOf<List<Int>>(m[0])\nwaitTicks(100)\nm[0].add(3)\nprintln(r)\nprintln(m)")

    private fun waitingSnapshot(): VmSnapshot {
        val vm = CsVm(waitingProgram)
        while (vm.state != VmState.WAITING) vm.run(1)
        return vm.snapshot()!!
    }

    @Test
    fun invalidNestedSnapshotsAreRejected() {
        val waiting = waitingSnapshot()
        val inner = SlotType.LIST(SlotType.INT)
        assertEquals(
            listOf(
                ObjectSnapshot(1, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(SlotType.INT, true, listOf(Value.IntValue(1)))),
                ObjectSnapshot(2, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(SlotType.INT, true, listOf(Value.IntValue(2)))),
                ObjectSnapshot(3, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(inner, true, listOf(Value.ObjectRef(1), Value.ObjectRef(2)))),
                ObjectSnapshot(4, ObjectSnapshot.LIST_CLASS, list = ListSnapshot(inner, false, listOf(Value.ObjectRef(1)))),
            ),
            waiting.objects,
        )
        assertEquals(listOf("[[1, 3]]", "[[1, 3], [2]]"), finish(CsVm.restore(waitingProgram, waiting)!!).console.history)
        fun replace(index: Int, list: ListSnapshot) = waiting.copy(objects = waiting.objects.mapIndexed { i, entry -> if (i == index) entry.copy(list = list) else entry })
        val m = waiting.objects[2].list!!
        val invalidObjects = listOf(
            // 自分自身を要素にする（要素の型 List<Int> と、自分の型 List<List<Int>> が合わない）
            replace(2, m.copy(elements = listOf(Value.ObjectRef(3)))),
            // 2 つのリストが互いを含む
            replace(0, ListSnapshot(inner, true, listOf(Value.ObjectRef(3)))),
            // 内側の要素の型が違う・内側の代わりに Int・外側を指す
            replace(2, m.copy(element = SlotType.LIST(SlotType.BOOLEAN))),
            replace(2, m.copy(elements = listOf(Value.IntValue(1)))),
            replace(3, waiting.objects[3].list!!.copy(elements = listOf(Value.ObjectRef(4)))),
            replace(0, ListSnapshot(SlotType.LIST(SlotType.UNIT), true, emptyList())),
            replace(0, ListSnapshot(SlotType.LIST(SlotType.OBJECT(0)), true, emptyList())),
        )
        for (snapshot in invalidObjects) assertEquals(SnapshotProblem.INVALID_OBJECTS, SnapshotVerifier.verify(waitingProgram, snapshot), "${snapshot.objects}")
        // 変数の型と合わないリスト（m が内側を指す・r が m を指す）
        val invalidVariables = listOf(
            waiting.copy(variables = listOf(Value.ObjectRef(1), Value.ObjectRef(4))),
            waiting.copy(variables = listOf(Value.ObjectRef(3), Value.ObjectRef(1))),
        )
        for (snapshot in invalidVariables) assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(waitingProgram, snapshot), "${snapshot.variables}")
    }

    // ---- 予算と公平性（FAST 128 命令 / tick・全体 4096 命令 / tick） ----

    @Test
    fun nestedProgramsStayWithinBudgetAndFair() {
        val operations = compile("val m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 16) {\n  val row = mutableListOf<Int>()\n  for (j in 0 until 16) { row.add(i * j) }\n  m.add(row)\n}\n" +
            "var k = 0\nwhile (true) {\n  m[k % 16][(k + 3) % 16] = m[(k + 5) % 16][k % 16] + 1\n  m[k % 16].removeAt(0)\n  m[k % 16].add(k)\n  k = (k + 1) % 1000\n}")
        val churn = compile("var t = listOf(listOf(1))\nvar k = 0\nwhile (true) {\n  t = listOf(listOf(k), mutableListOf(k, k))\n  k = (k + 1) % 1000\n}")
        val scheduler = Scheduler<Int>(4096)
        repeat(300) { scheduler.register(it, CsVm(if (it % 2 == 0) operations else churn)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(300)
        repeat(200) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096 && result.gcBudgetUsed <= Scheduler.GC_BUDGET_PER_TICK)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            assertTrue(result.finished.isEmpty(), "${result.finished}")
            result.executed.forEach { (key, count) -> totals[key] += count }
        }
        val running = totals.filterIndexed { index, _ -> index % 2 == 0 }
        assertTrue(running.max() - running.min() <= 1, "unfair: ${running.min()}..${running.max()}")
        // 少数なら FAST の上限ちょうど
        val few = Scheduler<Int>(4096)
        repeat(3) { few.register(it, CsVm(operations)) }
        few.tick { fast }
        repeat(50) { few.tick { fast } }
        assertEquals(mapOf(0 to 128, 1 to 128, 2 to 128), few.tick { fast }.executed)
    }

    // ---- 互換性: 入れ子の形は指紋に含まれる。入れ子でないプログラムの指紋は第3.10a段階と同じ ----

    @Test
    fun nestedShapesAreFingerprinted() {
        val program = compile("val a = mutableListOf(mutableListOf(1))\nprintln(a)")
        val base = ProgramFingerprint.ofProgram(program)
        val changed = listOf(
            listOf(program.lists[0], ListShape(SlotType.LIST(SlotType.BOOLEAN), true, 1)),
            listOf(program.lists[0], ListShape(SlotType.LIST(SlotType.LIST(SlotType.INT)), true, 1)),
            listOf(program.lists[0], ListShape(SlotType.LIST(SlotType.INT), false, 1)),
        )
        for (lists in changed) assertFalse(base.contentEquals(ProgramFingerprint.ofProgram(program.copy(lists = lists))), "$lists")
    }

    // ---- Kotlin の MutableList<MutableList<Int>> で同じ操作をした結果と比べる（無作為の 300 本、15 本は全境界で保存・復元） ----

    private class Generated(val source: String, val output: List<String>)

    private fun generate(random: Random): Generated {
        val rows = listOf(mutableListOf(1), mutableListOf(), mutableListOf(2, 3))
        val m = mutableListOf<MutableList<Int>>()
        val output = ArrayList<String>()
        val code = StringBuilder("val m = mutableListOf<MutableList<Int>>()\nval r0 = mutableListOf(1)\nval r1 = mutableListOf<Int>()\nval r2 = mutableListOf(2, 3)\nval v: List<MutableList<Int>> = m\n")
        // 回収を起こすための捨てるリスト（3 個 × 130 回）
        code.append("for (q in 0 until 130) { val g = listOf(listOf(q), mutableListOf(q)) }\n")
        fun emit(line: String) = code.append(line).append('\n')
        fun print(value: String, text: String) {
            emit("println($value)")
            output.add(Console.fit(text))
        }
        repeat(random.nextInt(10, 60)) {
            val n = random.nextInt(-50, 100)
            val k = random.nextInt(3)
            val i = if (m.isEmpty()) -1 else random.nextInt(m.size)
            when (random.nextInt(12)) {
                0 -> if (m.size < 200) { emit("m.add(r$k)"); m.add(rows[k]) }
                1 -> if (rows[k].size < 200) { emit("r$k.add($n)"); rows[k].add(n) }
                2 -> if (i >= 0 && m[i].size < 200) { emit("m[$i].add($n)"); m[i].add(n) }
                3 -> if (i >= 0 && m[i].isNotEmpty()) { val j = random.nextInt(m[i].size); emit("m[$i][$j] = $n"); m[i][j] = n }
                4 -> if (i >= 0 && m[i].isNotEmpty()) { val j = random.nextInt(m[i].size); print("m[$i][$j]", m[i][j].toString()) }
                5 -> if (rows[k].isNotEmpty()) { emit("println(r$k.removeAt(0))"); output.add(rows[k].removeAt(0).toString()) }
                6 -> if (i >= 0) { emit("m.removeAt($i)"); m.removeAt(i) }
                7 -> if (i >= 0) { emit("m[$i] = r$k"); m[i] = rows[k] }
                8 -> if (i >= 0) { emit("m[$i].clear()"); m[i].clear() }
                9 -> print("v", m.toString())
                10 -> if (i >= 0) print("v[$i] == r$k", (m[i] === rows[k]).toString()) else print("v.size", m.size.toString())
                // 最上位の可変性だけが違う要素は List<List<Int>>（第3.10b段階の推論）
                else -> print("listOf(listOf($n), r$k)", listOf(listOf(n), rows[k]).toString())
            }
        }
        print("v", m.toString())
        print("r0.size + r1.size + r2.size", rows.sumOf { it.size }.toString())
        return Generated(code.toString(), output)
    }

    @Test
    fun randomNestedProgramsMatchReference() {
        val random = Random(3102)
        var compared = 0
        var restored = 0
        var collected = 0
        repeat(300) { index ->
            val generated = generate(random)
            if (generated.source.length > 4096) return@repeat
            val program = compile(generated.source)
            val vm = CsVm(program)
            var collecting = false
            while (vm.state == VmState.RUNNING) {
                vm.advance(1000)
                collecting = collecting || vm.isCollecting
                assertCounted(vm)
            }
            if (collecting) collected++
            assertEquals(VmState.HALTED, vm.state, "${vm.error} in ${generated.source}")
            assertEquals(generated.output.takeLast(Console.MAX_LINES), vm.console.history, generated.source)
            compared++
            if (index % 20 == 0) {
                restored++
                val original = CsVm(program)
                while (original.state == VmState.RUNNING) {
                    val copy = CsVm.restore(program, original.snapshot()!!)!!
                    while (copy.state == VmState.RUNNING) step(copy)
                    assertEquals(vm.console.history, copy.console.history, generated.source)
                    step(original)
                }
            }
        }
        assertTrue(compared >= 280, "compared $compared")
        assertTrue(collected >= compared / 2, "collected $collected")
        println("[3.10b random] $compared programs compared ($collected with collection), $restored restored at every boundary")
    }

    // ---- 性能（実測） ----

    private fun perTick(scheduler: Scheduler<Int>, ticks: Int = 1000): String {
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(300) { scheduler.tick { fast } }
        val times = DoubleArray(ticks)
        var gc = 0L
        for (index in 0 until ticks) {
            val start = System.nanoTime()
            gc += scheduler.tick { fast }.gcBudgetUsed
            times[index] = (System.nanoTime() - start) / 1_000_000.0
        }
        val sorted = times.sorted()
        return "avg %.3f / p50 %.3f / p99 %.3f / max %.3f ms, gc units/tick %.0f".format(times.average(), sorted[ticks / 2], sorted[ticks * 99 / 100], sorted.last(), gc.toDouble() / ticks)
    }

    private fun nanosPerInstruction(vms: List<CsVm>): Double {
        vms.forEach { it.run(200_000) }
        val start = System.nanoTime()
        var executed = 0L
        vms.forEach { executed += it.run(1_000_000) }
        return (System.nanoTime() - start).toDouble() / executed
    }

    @Test
    fun measureNestedLists() {
        // メモリ: 15 行 × 256 要素の行列を持つ VM（新しく実行した 50 台の差分。ほかの測定の前に測る）
        val matrix = compile("val m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 15) {\n  val row = mutableListOf<Int>()\n  for (j in 0 until 256) { row.add(j) }\n  m.add(row)\n}\nwaitTicks(100)\nprintln(m.size)")
        val runtime = Runtime.getRuntime()
        fun used(): Long {
            repeat(5) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }
        val baseline = used()
        val holders = List(50) { CsVm(matrix).also { vm -> while (vm.state != VmState.WAITING) vm.advance(1000) } }
        val perVm = (used() - baseline) / holders.size / 1024
        println("[3.10b measure] memory per VM with a 15 x 256 matrix: ~$perVm KB (${holders.count { it.state == VmState.WAITING }} VMs)")
        val max = maxTypeDepth()
        // 字句解析からコンパイル（検証を含む）までの時間: 最大の深さの型
        val compileTimes = (0 until 30).map { val start = System.nanoTime(); compile(deepTypeSource(max)); (System.nanoTime() - start) / 1e6 }.drop(10)
        println("[3.10b measure] compile type depth $max: avg %.2f / max %.2f ms".format(compileTimes.average(), compileTimes.max()))
        // 深い型のリストへの add / removeAt の 1 命令（同じ型は同じインスタンスのため同一性で比べる）。浅い型・保存データから読んだ型（別のインスタンス）で復元した VM と比べる
        val elementDepth = max - 12
        val deepElement = "List<".repeat(elementDepth) + "Int" + ">".repeat(elementDepth)
        val deep = compile("val e = listOf<$deepElement>()\nval a = mutableListOf(e)\nwhile (true) {\n  a.add(e)\n  a.removeAt(0)\n}")
        val shallow = compile("val e = listOf<Int>()\nval a = mutableListOf(e)\nwhile (true) {\n  a.add(e)\n  a.removeAt(0)\n}")
        val deepRestored = List(20) {
            val vm = CsVm(deep).also { it.run(50) }
            val snapshot = vm.snapshot()!!
            CsVm.restore(deep, snapshot.copy(objects = snapshot.objects.map { entry -> entry.copy(list = entry.list?.let { it.copy(element = rebuilt(it.element)) }) }))!!
        }
        println("[3.10b measure] add/removeAt per instruction: type depth ${elementDepth + 1} %.1f ns, restored from copied types %.1f ns, depth 1 %.1f ns".format(
            nanosPerInstruction(List(20) { CsVm(deep) }), nanosPerInstruction(deepRestored), nanosPerInstruction(List(20) { CsVm(shallow) })))
        // 検証 + 復元: 15 行 × 256 要素の行列（要素の合計 3855）・最大の深さの型・151 段の値（保存データから読んだ型と同じく、型は別のインスタンス）
        for ((label, program) in listOf("matrix 15 x 256" to matrix, "type depth ${max - 3}" to compile(deepTypeSource(max - 3).replace("println", "waitTicks(9)\nprintln")), "value depth ${DEEP_VALUE_LEVELS + 1}" to compile(deepValueSource().replace("l0.add(8)", "waitTicks(9)")))) {
            val vm = CsVm(program)
            while (vm.state != VmState.WAITING) vm.advance(1000)
            val snapshot = vm.snapshot()!!.let { s -> s.copy(objects = s.objects.map { entry -> entry.copy(list = entry.list?.let { it.copy(element = rebuilt(it.element)) }) }) }
            repeat(200) { CsVm.restore(program, snapshot) }
            val samples = (0 until 500).map { val start = System.nanoTime(); CsVm.restore(program, snapshot)!!; (System.nanoTime() - start) / 1e6 }.sorted()
            println("[3.10b measure] verify + restore $label: median %.3f / p99 %.3f ms".format(samples[250], samples[495]))
        }
        // Scheduler の tick（1000 台）: 16 × 16 の行列の読み書き・入れ子のリストの作り直し
        val operations = compile("val m = mutableListOf<MutableList<Int>>()\nfor (i in 0 until 16) {\n  val row = mutableListOf<Int>()\n  for (j in 0 until 16) { row.add(i * j) }\n  m.add(row)\n}\n" +
            "var k = 0\nwhile (true) {\n  m[k % 16][(k + 3) % 16] = m[(k + 5) % 16][k % 16] + 1\n  k = (k + 1) % 1000\n}")
        val churn = compile("var t = listOf(listOf(1))\nvar k = 0\nwhile (true) {\n  t = listOf(listOf(k), mutableListOf(k, k))\n  k = (k + 1) % 1000\n}")
        for ((label, program) in listOf("1000 VMs reading and writing m[i][j]" to operations, "1000 VMs recreating nested lists" to churn)) {
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program).also { vm -> vm.run(3000) }) }
            println("[3.10b measure] $label: ${perTick(scheduler)}")
        }
        // GC の最悪: 15 行 × 256 要素を外側から参照したまま、満杯のヒープで内側を作り直す（256 台）
        val worst = compile("class Leaf\nval pool = mutableListOf<Leaf>()\nfor (i in 0 until 225) { pool.add(Leaf()) }\nval m = mutableListOf<MutableList<Leaf>>()\nfor (i in 0 until 15) {\n" +
            "  val row = mutableListOf<Leaf>()\n  for (j in 0 until 256) { row.add(pool[j % 225]) }\n  m.add(row)\n}\nvar t = listOf(listOf(Leaf()))\nwhile (true) { t = listOf(listOf(pool[0])) }")
        val probe = CsVm(worst)
        while (!probe.isCollecting) probe.advance(1)
        val steady = probe.snapshot()!!
        val units = CsVm.restore(worst, steady)!!.also { it.run(1) }.collectGarbage(Int.MAX_VALUE)
        val crowded = Scheduler<Int>(4096)
        repeat(256) { crowded.register(it, CsVm.restore(worst, steady)!!) }
        println("[3.10b measure] worst nested GC ($units units, ${steady.objects.size} objects, ${steady.objects.sumOf { it.list?.elements?.size ?: 0 }} elements), 256 VMs: ${perTick(crowded)}")
    }
}
