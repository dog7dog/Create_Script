package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.semantic.LinalgOperation
import com.creatrbypc.createanalytical.script.semantic.ScriptType
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.LinalgFunction
import com.creatrbypc.createanalytical.script.vm.ListSnapshot
import com.creatrbypc.createanalytical.script.vm.ObjectSnapshot
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.SlotType
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

// 第3.11e段階の Matrix（matrix・+ - *・transpose・matmul・m[i]・rowCount・columnCount）を、意味解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。
// 期待値は同じ計算を Kotlin で書いた参照の実装（積の和は k を 0 から順に足し、FMA なし）。結果の Matrix は保存した状態のヒープから読み、ビットまで比べる
class MatrixTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = Compiler.compileSource(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    private fun step(vm: CsVm) {
        when {
            vm.state == VmState.WAITING -> vm.countWaitTick()
            vm.isCollecting -> vm.collectGarbage(1)
            else -> vm.run(1)
        }
    }

    private fun finish(vm: CsVm): CsVm {
        var steps = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.isCollecting) vm.collectGarbage(1000) else vm.advance(1000).also { if (vm.state == VmState.WAITING) vm.countWaitTick() }
            check(++steps < 2_000_000) { "did not finish" }
        }
        return vm
    }

    private fun output(source: String): List<String> {
        val vm = finish(CsVm(compile(source)))
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private fun runtimeError(source: String): Pair<DiagnosticCode?, Int?> {
        val vm = finish(CsVm(compile(source)))
        return vm.error?.code to vm.error?.position?.column
    }

    private fun typeOf(source: String, name: String): ScriptType? =
        SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize(source))).program!!.declarations.values.single { it.name == name }.type

    // 式の値を変数に入れて待機し、保存した状態のヒープから Matrix（行の並び）か Vector（1 行）を読む
    private fun valueOf(expression: String): List<List<Double>> {
        val vm = CsVm(compile("val r = $expression\nwaitTicks(1)"))
        while (vm.state == VmState.RUNNING) if (vm.isCollecting) vm.collectGarbage(1000) else vm.run(1000)
        assertEquals(VmState.WAITING, vm.state, "${vm.error} in $expression")
        val snapshot = vm.snapshot()!!
        val objects = snapshot.objects.associateBy { it.id }
        fun row(value: Value) = objects.getValue((value as Value.ObjectRef).id).also { assertEquals(ObjectSnapshot.VECTOR_CLASS, it.classId) }.list!!.elements.map { (it as Value.DoubleValue).value }
        val result = objects.getValue((snapshot.variables.last() as Value.ObjectRef).id)
        return if (result.classId == ObjectSnapshot.MATRIX_CLASS) result.list!!.elements.map(::row) else listOf(row(snapshot.variables.last()!!))
    }

    private fun bits(matrix: List<List<Double>>) = matrix.map { row -> row.map { it.toString() } }

    // ---- Kotlin の参照の実装 ----

    private fun add(a: List<List<Double>>, b: List<List<Double>>) = a.indices.map { i -> a[i].indices.map { j -> a[i][j] + b[i][j] } }
    private fun subtract(a: List<List<Double>>, b: List<List<Double>>) = a.indices.map { i -> a[i].indices.map { j -> a[i][j] - b[i][j] } }
    private fun times(a: List<List<Double>>, s: Double) = a.map { row -> row.map { it * s } }
    private fun timesLeft(s: Double, a: List<List<Double>>) = a.map { row -> row.map { s * it } }
    private fun transpose(a: List<List<Double>>) = a[0].indices.map { j -> a.indices.map { i -> a[i][j] } }
    private fun matmul(a: List<List<Double>>, b: List<List<Double>>) = a.indices.map { i ->
        b[0].indices.map { j ->
            var sum = 0.0
            for (k in b.indices) sum += a[i][k] * b[k][j]
            sum
        }
    }
    private fun matmulVector(a: List<List<Double>>, v: List<Double>) = a.map { row ->
        var sum = 0.0
        for (k in v.indices) sum += row[k] * v[k]
        sum
    }

    private fun source(matrix: List<List<Double>>) = "matrix(${matrix.joinToString { row -> "vector(${row.joinToString { literal(it) }})" }})"

    private fun literal(value: Double) = when {
        value.isNaN() -> "(0.0 / 0.0)"
        value == Double.POSITIVE_INFINITY -> "(1.0 / 0.0)"
        value == Double.NEGATIVE_INFINITY -> "(-1.0 / 0.0)"
        value < 0.0 || (value == 0.0 && 1.0 / value < 0.0) -> "($value)"
        else -> value.toString()
    }

    private val a23 = listOf(listOf(1.5, -2.0, 0.0), listOf(-0.0, 1e300, 4.9e-324))
    private val b23 = listOf(listOf(0.25, 3.0, -0.0), listOf(-0.0, 1e300, -4.9e-324))
    private val c32 = listOf(listOf(1.0, 2.0), listOf(-1.0, 0.5), listOf(1e-200, 1e200))
    private val eight = List(8) { i -> List(8) { j -> (i * 8 + j) * 0.25 - 7.5 } }

    // ---- 各演算の結果（NaN・±Infinity・±0.0・オーバーフロー・非正規化数を含む）は参照の実装と同じ ----

    @Test
    fun matricesMatchReference() {
        val cases = listOf(
            "${source(a23)} + ${source(b23)}" to add(a23, b23), "${source(a23)} - ${source(b23)}" to subtract(a23, b23),
            "${source(a23)} * 2.0" to times(a23, 2.0), "(-0.5) * ${source(a23)}" to timesLeft(-0.5, a23), "${source(a23)} * (0.0 / 0.0)" to times(a23, Double.NaN),
            "transpose(${source(a23)})" to transpose(a23), "transpose(transpose(${source(a23)}))" to a23, "matmul(${source(a23)}, ${source(c32)})" to matmul(a23, c32),
            "matmul(${source(c32)}, ${source(a23)})" to matmul(c32, a23), "matmul(${source(eight)}, ${source(eight)})" to matmul(eight, eight),
            "transpose(${source(eight)})" to transpose(eight), "${source(eight)} - ${source(eight)} * 2.0" to subtract(eight, times(eight, 2.0)),
            "matrix(vector(7.0))" to listOf(listOf(7.0)), "matmul(matrix(vector(1e200, 1e200)), matrix(vector(1e200), vector(1e200)))" to listOf(listOf(1e200 * 1e200 + 1e200 * 1e200)),
            "matmul(matrix(vector(1.0 / 0.0, 1.0)), matrix(vector(0.0), vector(1.0)))" to listOf(listOf(Double.POSITIVE_INFINITY * 0.0 + 1.0)),
        )
        for ((expression, expected) in cases) assertEquals(bits(expected), bits(valueOf(expression)), expression)
        assertEquals(bits(listOf(matmulVector(a23, listOf(1.0, -1.0, 0.5)))), bits(valueOf("matmul(${source(a23)}, vector(1.0, -1.0, 0.5))")))
        assertEquals(bits(listOf(matmulVector(eight, eight[3]))), bits(valueOf("matmul(${source(eight)}, vector(${eight[3].joinToString { literal(it) }}))")))
        // m[i] は行の Vector、rowCount・columnCount、表示はリストと同じ形
        assertEquals(
            listOf("[[1.0, 2.0], [3.0, 4.0]]", "[3.0, 4.0]", "2.0", "2", "2", "3", "2"),
            output("val m = matrix(vector(1.0, 2.0), vector(3.0, 4.0))\nprintln(m)\nprintln(m[1])\nprintln(m[0][1])\nprintln(m.rowCount)\nprintln(m.columnCount)\nval t = matrix(vector(1.0, 2.0), vector(3.0, 4.0), vector(5.0, 6.0))\nprintln(t.rowCount)\nprintln(t.columnCount)"),
        )
    }

    // ---- 無作為の式（入れ子の演算・境界値）を参照の実装と比べる ----

    private val leaves = listOf(0.0, -0.0, 1.5, -2.5, 0.1, 1e300, -1e300, 4.9e-324, Double.NaN, Double.POSITIVE_INFINITY, 1e-200, 3.0)

    private class Term(val source: String, val value: List<List<Double>>)

    private fun matrixTerm(random: Random, rows: Int, columns: Int, depth: Int): Term {
        if (depth == 0 || random.nextInt(4) == 0) {
            val value = List(rows) { List(columns) { leaves.random(random) } }
            return Term(source(value), value)
        }
        fun sub() = matrixTerm(random, rows, columns, depth - 1)
        return when (random.nextInt(6)) {
            0 -> Pair(sub(), sub()).let { (a, b) -> Term("(${a.source} + ${b.source})", add(a.value, b.value)) }
            1 -> Pair(sub(), sub()).let { (a, b) -> Term("(${a.source} - ${b.source})", subtract(a.value, b.value)) }
            2 -> Pair(sub(), leaves.random(random)).let { (a, s) -> Term("(${a.source} * ${literal(s)})", times(a.value, s)) }
            3 -> Pair(leaves.random(random), sub()).let { (s, a) -> Term("(${literal(s)} * ${a.source})", timesLeft(s, a.value)) }
            4 -> matrixTerm(random, columns, rows, depth - 1).let { a -> Term("transpose(${a.source})", transpose(a.value)) }
            else -> {
                val inner = random.nextInt(1, 4)
                val a = matrixTerm(random, rows, inner, depth - 1)
                val b = matrixTerm(random, inner, columns, depth - 1)
                Term("matmul(${a.source}, ${b.source})", matmul(a.value, b.value))
            }
        }
    }

    @Test
    fun randomExpressionsMatchReference() {
        val random = Random(3_11_7)
        repeat(150) {
            val term = matrixTerm(random, random.nextInt(1, 4), random.nextInt(1, 4), 2)
            assertEquals(bits(term.value), bits(valueOf(term.source)), term.source)
        }
    }

    // ---- 型の規則と診断 ----

    @Test
    fun typesAndDiagnostics() {
        assertEquals(VmLimits.MAX_MATRIX_SIZE, LinalgOperation.MAX_MATRIX_SIZE)
        val m = "val m = matrix(vector(1.0, 2.0), vector(3.0, 4.0))\nval v = vector(1.0, 2.0)\n"
        val types = listOf(
            "matrix(vector(1.0))" to ScriptType.MATRIX, "transpose(matrix(vector(1.0)))" to ScriptType.MATRIX, "matmul(matrix(vector(1.0)), matrix(vector(1.0)))" to ScriptType.MATRIX,
            "matmul(matrix(vector(1.0)), vector(1.0))" to ScriptType.VECTOR, "matrix(vector(1.0))[0]" to ScriptType.VECTOR, "matrix(vector(1.0))[0][0]" to ScriptType.DOUBLE,
            "matrix(vector(1.0)).rowCount" to ScriptType.INT, "matrix(vector(1.0)).columnCount" to ScriptType.INT, "matrix(vector(1.0)) * 2.0" to ScriptType.MATRIX,
            "listOf(matrix(vector(1.0)))" to ScriptType.ListType(ScriptType.MATRIX, false),
        )
        for ((expression, type) in types) assertEquals(type, typeOf("val x = $expression", "x"), expression)
        val errors = listOf(
            "println(matrix())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(matrix(${List(9) { "vector(1.0)" }.joinToString()}))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(matrix(1.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(matrix(listOf(1.0)))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${m}println(matrix(v, m))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${m}println(transpose(v))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${m}println(transpose(m, m))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "${m}println(matmul(m))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "${m}println(matmul(m, 1.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${m}println(matmul(v, m))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${m}println(m * m)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${m}println(m * v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${m}println(m + v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${m}println(m * 2)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${m}println(m / 2.0)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${m}println(-m)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${m}println(m == m)" to DiagnosticCode.SEM_INCOMPARABLE_TYPES,
            "${m}m[0] = v" to DiagnosticCode.SEM_READ_ONLY_LIST,
            "${m}m[0][0] = 1.0" to DiagnosticCode.SEM_READ_ONLY_LIST,
            "${m}m.rowCount = 2" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "${m}m.columnCount = 2" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "${m}println(m.size)" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "${m}println(v.rowCount)" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "${m}println(m.rowCount())" to DiagnosticCode.SEM_NOT_CALLABLE,
            "${m}println(m.columnCount())" to DiagnosticCode.SEM_NOT_CALLABLE,
            "${m}m.add(v)" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "${m}val l: List<Vector> = m" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val w: Matrix = listOf(vector(1.0))" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val w: Matrix = vector(1.0)" to DiagnosticCode.SEM_TYPE_MISMATCH,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
        // 実行時エラー（位置は関数の名前・演算子・添字）
        val runtime = listOf(
            "println(matrix(vector(1.0), vector(1.0, 2.0)))" to (DiagnosticCode.RUN_RAGGED_MATRIX to 9),
            "println(matrix(vector(${List(9) { "1.0" }.joinToString()})))" to (DiagnosticCode.RUN_INVALID_DIMENSION to 9),
            "println(matrix(vector(1.0)) + matrix(vector(1.0, 2.0)))" to (DiagnosticCode.RUN_DIMENSION_MISMATCH to 29),
            "println(matmul(matrix(vector(1.0, 2.0)), matrix(vector(1.0, 2.0))))" to (DiagnosticCode.RUN_DIMENSION_MISMATCH to 9),
            "println(matmul(matrix(vector(1.0, 2.0)), vector(1.0)))" to (DiagnosticCode.RUN_DIMENSION_MISMATCH to 9),
            "println(matrix(vector(1.0))[1])" to (DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS to 28),
        )
        for ((source, expected) in runtime) assertEquals(expected, runtimeError(source), source)
    }

    // ---- 値は変更できない。行は共有しても安全。引数・戻り値・プロパティ・リストの要素に使える ----

    @Test
    fun matricesAreValues() {
        val source = "val r = vector(1.0, 2.0)\nval m = matrix(r, r)\nval row = m[0]\nprintln(row)\n" +
            "class Camera {\n  var view = matrix(vector(1.0, 0.0), vector(0.0, 1.0))\n  fun turn(by: Matrix) { view = matmul(by, view) }\n}\n" +
            "fun rotate90(): Matrix { return matrix(vector(0.0, -1.0), vector(1.0, 0.0)) }\n" +
            "val camera = Camera()\ncamera.turn(rotate90())\ncamera.turn(rotate90())\nprintln(camera.view)\nprintln(matmul(camera.view, vector(1.0, 2.0)))\n" +
            "val history = mutableListOf<Matrix>()\nhistory.add(m)\nhistory.add(transpose(m))\nprintln(history[1][1][0])\nprintln(m[0][0] + r[1])"
        assertEquals(listOf("[1.0, 2.0]", "[[-1.0, 0.0], [0.0, -1.0]]", "[-1.0, -2.0]", "2.0", "3.0"), output(source))
        assertEquals(listOf("Matrix@1", "[[1.0]]"), output("class Matrix\nprintln(Matrix())\nprintln(matrix(vector(1.0)))"))
        assertEquals(listOf("6"), output("fun transpose(n: Int): Int { return n * 2 }\nprintln(transpose(3))"))
        assertEquals(listOf("4", "[[1.0]]"), output("val matmul = 4\nprintln(matmul)\nprintln(matmul(matrix(vector(1.0)), matrix(vector(1.0))))"))
    }

    // ---- 命令と払う単位: matrix は r + 1、+ - * と transpose は r × c + 1、matmul は r × k × c + 1、matmul(m, v) は r × c + 1、columnCount は 1 ----

    private fun unitsOf(program: CompiledProgram): List<Int> {
        val vm = CsVm(program)
        val units = ArrayList<Int>()
        var current = -1
        var count = 0
        while (vm.state == VmState.RUNNING) {
            if (vm.isCollecting) {
                vm.collectGarbage(1000)
                continue
            }
            val pc = vm.snapshot()!!.pc
            if (program.instructions[pc].opcode == Opcode.LINALG) {
                if (pc != current) {
                    current = pc
                    count = 0
                }
                count += vm.run(1)
                if (vm.snapshot()?.pc != pc) {
                    units.add(count)
                    current = -1
                }
            } else {
                vm.run(1)
            }
        }
        assertEquals(VmState.HALTED, vm.state, "${vm.error}")
        return units
    }

    @Test
    fun linalgPaysWorkPlusOne() {
        assertEquals((11..19).toList(), LinalgFunction.entries.drop(11).map { it.id })
        val m23 = source(a23)
        val m32 = source(c32)
        val m88 = source(eight)
        val cases = listOf(
            // vector 3 つ（各 4）と vector 2 つ（各 3）の後
            "val a = $m23" to listOf(4, 4, 3),
            "val a = $m23\nval b = a + a\nval c = a - a\nval d = a * 2.0\nval e = 2.0 * a\nval t = transpose(a)\nval n = a.columnCount\nval r = a.rowCount" to listOf(4, 4, 3, 7, 7, 7, 7, 7, 1),
            "val a = $m23\nval b = $m32\nval p = matmul(a, b)\nval q = matmul(b, a)\nval v = matmul(a, vector(1.0, 2.0, 3.0))" to listOf(4, 4, 3, 3, 3, 3, 4, 13, 19, 4, 7),
            "val a = $m88\nval p = matmul(a, a)" to List(8) { 9 } + listOf(9, 513),
        )
        for ((source, expected) in cases) assertEquals(expected, unitsOf(compile(source)), source)
    }

    // ---- 分割実行: SLOW では 8 × 8 の matmul が 513 tick かかり、動力の無い間は止まる。1 tick の実際の計算は払った単位数に比例する ----

    @Test
    fun matmulSplitsAcrossTicks() {
        val program = compile("val a = ${source(eight)}\nval p = matmul(a, a)\nprintln(p[7][7])")
        val scheduler = Scheduler<Int>(4096)
        val vm = CsVm(program)
        scheduler.register(0, vm)
        val slow = ExecutionCondition(PowerLevel.SLOW, true)
        scheduler.tick { slow }
        val matmul = program.instructions.indexOfLast { it.opcode == Opcode.LINALG }
        var ticksAtMatmul = 0
        var ticks = 0
        while (scheduler.registeredKeys.isNotEmpty()) {
            ticks++
            val condition = if (ticks in 300 until 320) ExecutionCondition(PowerLevel.NONE, true) else slow
            val atMatmul = vm.state == VmState.RUNNING && vm.snapshot()!!.pc == matmul
            val result = scheduler.tick { condition }
            if (atMatmul && condition == slow) {
                ticksAtMatmul++
                assertEquals(1, result.executed.values.sum())
            }
            check(ticks < 5000)
        }
        assertEquals(513, ticksAtMatmul)
        assertEquals(listOf(matmul(eight, eight)[7][7].toString()), vm.console.history)
    }

    // ---- 保存・復元: すべての単位の境界（matmul の途中を含む）で保存・復元しても結果は同じ。途中で保存すると払い直す ----

    @Test
    fun snapshotsDuringMatmulRestart() {
        val source = "val a = ${source(c32)}\nval b = transpose(a)\nval p = matmul(a, b)\nwaitTicks(2)\nprintln(p)\nprintln(matmul(b, vector(1.0, -1.0, 2.0)))\nprintln(p.columnCount)"
        val program = compile(source)
        val expected = output(source)
        val original = CsVm(program)
        var midLinalg = 0
        while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
            val cut = original.snapshot()!!
            val restored = CsVm.restore(program, cut)
            assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, cut)}")
            assertEquals(cut, restored!!.snapshot())
            while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
            assertEquals(expected, restored.console.history)
            if (program.instructions[cut.pc].opcode == Opcode.LINALG) midLinalg++
            step(original)
        }
        assertEquals(expected, original.console.history)
        assertTrue(midLinalg > 40, "cuts at LINALG: $midLinalg")
        // 3 × 2 と 2 × 3 の matmul（3 × 2 × 3 + 1 = 19 単位）の途中、10 単位払った後で保存して復元すると、19 単位を払い直す
        val at = program.instructions.indexOfFirst { it.opcode == Opcode.LINALG && it.operand == LinalgFunction.MATMUL.operand() }
        val vm = CsVm(program)
        while (vm.snapshot()!!.pc != at) vm.run(1)
        repeat(10) { vm.run(1) }
        val during = vm.snapshot()!!
        val again = CsVm.restore(program, during)!!
        var units = 0
        while (again.snapshot()!!.pc == at) units += again.run(1)
        assertEquals(19, units)
    }

    // ---- GC: 結果の Matrix（行の数 + 1 個の実体）を置く空きが無ければ回収を待ち、回収の後に確定する。生きている Matrix が多すぎれば実行時エラー ----

    @Test
    fun matmulWaitsForCollection() {
        val vm = CsVm(compile("val a = matrix(vector(1.0, 1.0), vector(0.0, 1.0))\nvar m = a\nfor (i in 0 until 500) { m = matmul(m, a) }\nprintln(m)"))
        var collections = 0
        while (vm.state == VmState.RUNNING) {
            if (vm.isCollecting) {
                vm.collectGarbage(100)
            } else {
                vm.run(1)
                if (vm.isCollecting) collections++
            }
        }
        assertEquals(listOf("[[1.0, 501.0], [0.0, 1.0]]"), vm.console.history, "${vm.error}")
        assertTrue(collections >= 4, "collections $collections")
        val full = runtimeError("val keep = mutableListOf<Matrix>()\nval a = ${source(eight)}\nwhile (true) { keep.add(a * 1.0) }")
        assertEquals(DiagnosticCode.RUN_TOO_MANY_OBJECTS, full.first)
    }

    // ---- Verifier と VM: 不正な LINALG・Matrix を変更する命令・書き換えた保存データ（行の長さの不一致など）を拒否する ----

    @Test
    fun invalidBytecodeIsRejected() {
        val program = compile("val v = vector(1.0, 2.0)\nval m = matrix(v, v)\nprintln(matmul(m, m))")
        assertNotNull(SnapshotVerifier.framesOf(program))
        val linalgs = program.instructions.withIndex().filter { it.value.opcode == Opcode.LINALG }.map { it.index }
        fun with(index: Int, operand: Int) = program.copy(instructions = program.instructions.mapIndexed { i, it -> if (i == index) it.copy(operand = operand) else it })
        val matrixOf = linalgs[1]
        val matmul = linalgs[2]
        val invalid = listOf(
            matrixOf to LinalgFunction.MATRIX_OF.operand(0), matrixOf to LinalgFunction.MATRIX_OF.operand(9), matrixOf to LinalgFunction.MATRIX_OF.operand(3),
            matrixOf to LinalgFunction.ADD.operand(), matmul to LinalgFunction.MATMUL_VECTOR.operand(), matmul to LinalgFunction.DOT.operand(),
            matmul to LinalgFunction.MATRIX_TIMES.operand(),
        )
        for ((index, operand) in invalid) assertNull(SnapshotVerifier.framesOf(with(index, operand)), "LINALG $operand at $index")
        for ((index, operand) in listOf(matrixOf to LinalgFunction.MATRIX_OF.operand(3), matmul to LinalgFunction.MATMUL_VECTOR.operand(), matmul to LinalgFunction.DOT.operand(), matrixOf to LinalgFunction.TRANSPOSE.operand())) {
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, finish(CsVm(with(index, operand))).error?.code, "LINALG $operand at $index")
        }
        // Matrix を変更する命令（LIST_SET の受け手を Matrix に）
        val mutation = compile("val m = matrix(vector(1.0))\nval l = mutableListOf(vector(1.0))\nl[0] = vector(2.0)")
        val load = mutation.instructions.indexOfLast { it.opcode == Opcode.LOAD && it.operand == 1 }
        val tampered = mutation.copy(instructions = mutation.instructions.mapIndexed { i, it -> if (i == load) it.copy(operand = 0) else it })
        assertNull(SnapshotVerifier.framesOf(tampered))
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, finish(CsVm(tampered)).error?.code)
    }

    @Test
    fun tamperedSnapshotsAreRejected() {
        val program = compile("val a = vector(1.0, 2.0)\nval b = vector(3.0, 4.0)\nval c = vector(5.0)\nval m = matrix(a, b)\nwaitTicks(5)\nprintln(m)\nprintln(c)")
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertNotNull(CsVm.restore(program, snapshot))
        val matrix = snapshot.objects.single { it.classId == ObjectSnapshot.MATRIX_CLASS }
        val c = snapshot.variables[2] as Value.ObjectRef
        val b = snapshot.variables[1] as Value.ObjectRef
        fun with(edit: (ObjectSnapshot) -> ObjectSnapshot): VmSnapshot = snapshot.copy(objects = snapshot.objects.map { if (it.id == matrix.id) edit(it) else it })
        val nine = snapshot.copy(objects = snapshot.objects.map { if (it.id == b.id) it.copy(list = it.list!!.copy(elements = List(9) { Value.DoubleValue.of(1.0) })) else it })
        val tampered = listOf(
            "ragged" to with { it.copy(list = it.list!!.copy(elements = listOf(snapshot.variables[0]!!, c))) },
            "mutable" to with { it.copy(list = it.list!!.copy(mutable = true)) },
            "double elements" to with { it.copy(list = ListSnapshot(SlotType.DOUBLE, false, listOf(Value.DoubleValue.of(1.0)))) },
            "nine rows" to with { it.copy(list = it.list!!.copy(elements = List(9) { snapshot.variables[0]!! })) },
            "no rows" to with { it.copy(list = it.list!!.copy(elements = emptyList())) },
            "as list" to with { it.copy(classId = ObjectSnapshot.LIST_CLASS) },
            "row is a list" to snapshot.copy(objects = snapshot.objects.map { if (it.id == b.id) it.copy(classId = ObjectSnapshot.LIST_CLASS) else it }),
            "nine columns" to nine.copy(objects = nine.objects.map { if (it.id == snapshot.variables[0].let { v -> (v as Value.ObjectRef).id }) it.copy(list = it.list!!.copy(elements = List(9) { Value.DoubleValue.of(1.0) })) else it }),
            "matrix in a vector variable" to snapshot.copy(variables = listOf(Value.ObjectRef(matrix.id)) + snapshot.variables.drop(1)),
        )
        for ((label, state) in tampered) assertNull(CsVm.restore(program, state), label)
    }

    // ---- 予算と公平性: Matrix の単位も 1 命令と同じく数え、FAST 128 命令 / tick・全体 4096 命令 / tick を守る ----

    private val loops = listOf(
        "val a = matrix(vector(0.0, 1.0), vector(-1.0, 0.0))\nvar m = a\nwhile (true) { m = matmul(m, a) }",
        "val a = ${source(eight)}\nvar m = a\nwhile (true) { m = transpose(m) * 1.0 - m }",
        "val a = ${source(eight)}\nvar n = 0\nwhile (true) { n = a.columnCount + a.rowCount + n % 3 }",
        "val a = ${source(eight)}\nvar v = vector(${eight[0].joinToString()})\nwhile (true) { v = normalize(matmul(a, v)) }",
    )

    @Test
    fun programsStayWithinBudgetAndFair() {
        val programs = loops.map(::compile)
        for (program in programs) assertEquals(VmState.RUNNING, CsVm(program).also { vm -> repeat(5000) { if (vm.isCollecting) vm.collectGarbage(1000) else vm.run(100) } }.state)
        val scheduler = Scheduler<Int>(4096)
        repeat(300) { scheduler.register(it, CsVm(programs[it % programs.size])) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(300)
        repeat(200) {
            val result = scheduler.tick { fast }
            assertEquals(4096, result.budgetUsed)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            assertTrue(result.finished.isEmpty(), "${result.finished}")
            result.executed.forEach { (key, count) -> totals[key] += count }
        }
        val steady = totals.filterIndexed { index, _ -> index % programs.size == 2 }
        assertTrue(steady.max() - steady.min() <= 1, "unfair: ${steady.min()}..${steady.max()}")
    }

    // ---- 性能（実測。R3 の暫定基準: 1000 台が FAST で 8 × 8 の matmul を続けるとき、1 tick の p99 が Int だけの 2 倍以内、かつ 2 × 2 との差が 20% 以内） ----

    private fun perTick(scheduler: Scheduler<Int>, ticks: Int = 1000): DoubleArray {
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(300) { scheduler.tick { fast } }
        val times = DoubleArray(ticks)
        for (index in 0 until ticks) {
            val start = System.nanoTime()
            scheduler.tick { fast }
            times[index] = (System.nanoTime() - start) / 1_000_000.0
        }
        return times
    }

    private fun summary(times: DoubleArray): String {
        val sorted = times.sorted()
        return "avg %.3f / p50 %.3f / p99 %.3f / max %.3f ms".format(times.average(), sorted[times.size / 2], sorted[times.size * 99 / 100], sorted.last())
    }

    @Test
    fun measureMatrices() {
        val int = "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }"
        val two = "val a = matrix(vector(0.0, 1.0), vector(-1.0, 0.0))\nvar m = a\nwhile (true) { m = matmul(m, a) }"
        val eightLoop = "val a = ${source(eight)}\nvar m = a\nwhile (true) { m = matmul(m, a) * 0.001 }"
        for (round in 1..2) {
            for ((label, source) in listOf("Int arithmetic" to int, "2x2 matmul" to two, "8x8 matmul" to eightLoop)) {
                val program = compile(source)
                val scheduler = Scheduler<Int>(4096)
                repeat(1000) { scheduler.register(it, CsVm(program)) }
                println("[3.11e measure] round $round 1000 VMs $label: ${summary(perTick(scheduler))}")
            }
        }
        // 同時に確定がそろう場合: 同じプログラムを同じ tick に始めた 4096 台（全体の予算と同じ数。1 台 1 単位 / tick で同じ単位を進め、同じ tick に確定する）
        for ((label, source) in listOf("Int arithmetic" to int, "synchronized 8x8 matmul" to "val a = ${source(eight)}\nvar m = a\nwhile (true) { m = matmul(a, a) }")) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(4096) { scheduler.register(it, CsVm(program)) }
            println("[3.11e measure] 4096 VMs $label: ${summary(perTick(scheduler, 2000))}")
        }
    }
}
