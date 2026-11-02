package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
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
import com.creatrbypc.createanalytical.script.vm.Instruction
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
import kotlin.math.hypot
import kotlin.random.Random

// 第3.11e段階の Vector（vector・+ - * /・dot・length・normalize・v[i]・v.size）を、意味解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。
// 期待値は同じ計算を Kotlin で書いた参照の実装（和は左から順、FMA なし、length は hypot を左から順）。LINALG は計算の単位 W と確定の 1 単位を 1 回に 1 つずつ払う
class VectorTest {
    private val zero = 0.0
    private val nan = zero / zero
    private val inf = 1.0 / zero

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

    // ---- Kotlin の参照の実装 ----

    private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }
    private fun subtract(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] - b[it] }
    private fun negate(a: DoubleArray) = DoubleArray(a.size) { -a[it] }
    private fun times(a: DoubleArray, s: Double) = DoubleArray(a.size) { a[it] * s }
    private fun timesLeft(s: Double, a: DoubleArray) = DoubleArray(a.size) { s * a[it] }
    private fun divide(a: DoubleArray, s: Double) = DoubleArray(a.size) { a[it] / s }
    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
    private fun length(a: DoubleArray): Double {
        var result = 0.0
        for (x in a) result = hypot(result, x)
        return result
    }
    private fun normalize(a: DoubleArray): DoubleArray = length(a).let { l -> DoubleArray(a.size) { a[it] / l } }

    // Vector を各成分ずつ表示する（コンソールの 1 行は 64 文字のため）
    private fun components(expression: String) = "val r = $expression\nfor (i in 0 until r.size) { println(r[i]) }"

    // ---- 各演算の結果（NaN・±Infinity・±0.0・オーバーフロー・アンダーフロー・非正規化数を含む）は参照の実装と同じ ----

    @Test
    fun vectorsMatchReference() {
        val a = doubleArrayOf(1.5, -2.0, 0.0, -0.0, 1e300, 4.9e-324)
        val b = doubleArrayOf(0.25, 3.0, -0.0, -0.0, 1e300, -4.9e-324)
        val av = "vector(1.5, -2.0, 0.0, -0.0, 1e300, 4.9E-324)"
        val bv = "vector(0.25, 3.0, -0.0, -0.0, 1e300, -4.9E-324)"
        val cases = listOf(
            "$av + $bv" to add(a, b), "$av - $bv" to subtract(a, b), "-$av" to negate(a), "$av * 2.0" to times(a, 2.0), "-0.5 * $av" to timesLeft(-0.5, a),
            "$av / 3.0" to divide(a, 3.0), "$av / 0.0" to divide(a, 0.0), "$av * (0.0 / 0.0)" to times(a, nan), "$av * 1e10" to times(a, 1e10),
            "normalize(vector(3.0, 4.0))" to normalize(doubleArrayOf(3.0, 4.0)), "normalize(vector(4.9E-324, 0.0))" to normalize(doubleArrayOf(4.9e-324, 0.0)),
            "normalize(vector(1e300, -1e300, 1e300))" to normalize(doubleArrayOf(1e300, -1e300, 1e300)), "normalize(vector(-2.5))" to doubleArrayOf(-1.0),
            "normalize(vector(1.0 / 0.0, 1.0))" to normalize(doubleArrayOf(inf, 1.0)), "normalize(vector(0.0 / 0.0, 1.0))" to normalize(doubleArrayOf(nan, 1.0)),
            "vector(listOf(1.0, -0.0, 0.0 / 0.0))" to doubleArrayOf(1.0, -0.0, nan), "vector(7.0)" to doubleArrayOf(7.0),
        )
        for ((expression, expected) in cases) assertEquals(expected.map { it.toString() }, output(components(expression)), expression)
        val scalars = listOf(
            "dot($av, $bv)" to dot(a, b), "length($av)" to length(a), "dot(vector(1e200, 1e200), vector(1e200, 1e200))" to dot(doubleArrayOf(1e200, 1e200), doubleArrayOf(1e200, 1e200)),
            "length(vector(1e200, 1e200))" to length(doubleArrayOf(1e200, 1e200)), "length(vector(1e-200, 1e-200))" to length(doubleArrayOf(1e-200, 1e-200)),
            "length(vector(3.0, 4.0))" to 5.0, "length(vector(0.0 / 0.0, 1.0 / 0.0))" to length(doubleArrayOf(nan, inf)), "length(vector(-0.0))" to 0.0,
            "dot(vector(-0.0), vector(1.0))" to dot(doubleArrayOf(-0.0), doubleArrayOf(1.0)), "dot(vector(0.0 / 0.0), vector(0.0))" to nan,
            "dot(vector(0.1, 0.2, 0.3), vector(0.3, 0.2, 0.1))" to dot(doubleArrayOf(0.1, 0.2, 0.3), doubleArrayOf(0.3, 0.2, 0.1)),
            "length(vector(1.7976931348623157E308, 1.7976931348623157E308))" to length(doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE)),
        )
        assertEquals(scalars.map { it.second.toString() }, output(scalars.joinToString("\n") { "println(${it.first})" }))
        // -0.0 の符号は値として残る
        assertEquals(listOf("-Infinity", "Infinity"), output("val v = -vector(0.0, -0.0)\nprintln(1.0 / v[0])\nprintln(1.0 / v[1])"))
        // 16 次元（上限）と表示（リストと同じ形）
        val sixteen = DoubleArray(16) { it * 0.5 - 3.0 }
        assertEquals(add(sixteen, sixteen).map { it.toString() }, output(components("vector(${sixteen.joinToString()}) + vector(${sixteen.joinToString()})")))
        assertEquals(listOf("[1.0, -2.5]", "[[1.0, -2.5], [0.0]]", "2"), output("val v = vector(1.0, -2.5)\nprintln(v)\nprintln(listOf(v, vector(0.0)))\nprintln(v.size)"))
    }

    // ---- 無作為の式（入れ子の演算・境界値）を参照の実装と比べる ----

    private class Term(val source: String, val vector: DoubleArray?, val scalar: Double)

    private val leaves = listOf(
        "0.0" to 0.0, "(-0.0)" to -0.0, "1.5" to 1.5, "(-2.5)" to -2.5, "0.1" to 0.1, "1e300" to 1e300, "(-1e300)" to -1e300, "4.9E-324" to 4.9e-324,
        "(0.0 / 0.0)" to Double.NaN, "(1.0 / 0.0)" to Double.POSITIVE_INFINITY, "(-1.0 / 0.0)" to Double.NEGATIVE_INFINITY, "1e-200" to 1e-200, "3.0" to 3.0,
    )

    private fun scalar(random: Random): Pair<String, Double> = leaves.random(random)

    private fun vectorTerm(random: Random, size: Int, depth: Int): Term {
        if (depth == 0 || random.nextInt(4) == 0) {
            val parts = List(size) { scalar(random) }
            return Term("vector(${parts.joinToString { it.first }})", DoubleArray(size) { parts[it].second }, 0.0)
        }
        fun sub() = vectorTerm(random, size, depth - 1)
        return when (random.nextInt(7)) {
            0 -> Pair(sub(), sub()).let { (a, b) -> Term("(${a.source} + ${b.source})", add(a.vector!!, b.vector!!), 0.0) }
            1 -> Pair(sub(), sub()).let { (a, b) -> Term("(${a.source} - ${b.source})", subtract(a.vector!!, b.vector!!), 0.0) }
            2 -> sub().let { a -> Term("(-${a.source})", negate(a.vector!!), 0.0) }
            3 -> Pair(sub(), scalar(random)).let { (a, s) -> Term("(${a.source} * ${s.first})", times(a.vector!!, s.second), 0.0) }
            4 -> Pair(scalar(random), sub()).let { (s, a) -> Term("(${s.first} * ${a.source})", timesLeft(s.second, a.vector!!), 0.0) }
            5 -> Pair(sub(), scalar(random)).let { (a, s) -> Term("(${a.source} / ${s.first})", divide(a.vector!!, s.second), 0.0) }
            // 長さ 0 の normalize は実行時エラーになるため、長さが 0 でない Vector だけを正規化する
            else -> sub().let { a -> if (length(a.vector!!) == 0.0) a else Term("normalize(${a.source})", normalize(a.vector), 0.0) }
        }
    }

    @Test
    fun randomExpressionsMatchReference() {
        val random = Random(3_11_6)
        var scalars = 0
        repeat(300) {
            val size = random.nextInt(1, 6)
            val a = vectorTerm(random, size, 3)
            when (random.nextInt(4)) {
                0 -> vectorTerm(random, size, 2).let { b ->
                    scalars++
                    assertEquals(listOf(dot(a.vector!!, b.vector!!).toString()), output("println(dot(${a.source}, ${b.source}))"), a.source)
                }
                1 -> {
                    scalars++
                    assertEquals(listOf(length(a.vector!!).toString()), output("println(length(${a.source}))"), a.source)
                }
                else -> assertEquals(a.vector!!.map { it.toString() }, output(components(a.source)), a.source)
            }
        }
        assertTrue(scalars > 100, "scalars $scalars")
    }

    // ---- 型の規則と診断。既存の診断コードを使い、次元の誤り・長さ 0 は実行時エラー ----

    @Test
    fun typesAndDiagnostics() {
        assertEquals(VmLimits.MAX_VECTOR_SIZE, LinalgOperation.MAX_VECTOR_SIZE)
        val v = "val v = vector(1.0, 2.0)\n"
        val types = listOf(
            "vector(1.0)" to ScriptType.VECTOR, "vector(listOf(1.0))" to ScriptType.VECTOR, "vector(mutableListOf(1.0))" to ScriptType.VECTOR,
            "dot(vector(1.0), vector(2.0))" to ScriptType.DOUBLE, "length(vector(1.0))" to ScriptType.DOUBLE, "normalize(vector(1.0))" to ScriptType.VECTOR,
            "vector(1.0)[0]" to ScriptType.DOUBLE, "vector(1.0).size" to ScriptType.INT, "-vector(1.0)" to ScriptType.VECTOR, "vector(1.0) * 2.0" to ScriptType.VECTOR,
            "listOf(vector(1.0))" to ScriptType.ListType(ScriptType.VECTOR, false),
        )
        for ((expression, type) in types) assertEquals(type, typeOf("val x = $expression", "x"), expression)
        val errors = listOf(
            "println(vector())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(vector(${List(17) { "1.0" }.joinToString()}))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(vector(1))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(vector(1.0, 2f))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(vector(listOf(1)))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(vector(listOf(1.0), listOf(2.0)))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${v}println(dot(v))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "${v}println(dot(v, 1.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(length(listOf(1.0)))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${v}println(normalize(v, v))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "${v}println(v + 1.0)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(v * 2)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(2 * v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(v * 2f)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(v * v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(2.0 / v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(v % 2.0)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(v < v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(+v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(!v)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "${v}println(v == v)" to DiagnosticCode.SEM_INCOMPARABLE_TYPES,
            "${v}println(v != v)" to DiagnosticCode.SEM_INCOMPARABLE_TYPES,
            "${v}v[0] = 1.0" to DiagnosticCode.SEM_READ_ONLY_LIST,
            "${v}v.add(1.0)" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "${v}v.clear()" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "${v}println(v.size())" to DiagnosticCode.SEM_NOT_CALLABLE,
            "${v}println(v.x)" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "${v}v.size = 3" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "${v}println(v[1.0])" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "${v}val l: List<Double> = v" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val w: Vector = listOf(1.0)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val w: MutableList<Double> = vector(1.0)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val w: Vector<Double> = vector(1.0)" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "println(vector<Double>(1.0))" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "println(1.0.vector())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
        // 実行時エラー（位置は演算子・関数の名前・添字）
        val runtime = listOf(
            "println(vector(1.0, 2.0) + vector(1.0))" to (DiagnosticCode.RUN_DIMENSION_MISMATCH to 26),
            "println(vector(1.0) - vector(1.0, 2.0))" to (DiagnosticCode.RUN_DIMENSION_MISMATCH to 21),
            "println(dot(vector(1.0), vector(1.0, 2.0)))" to (DiagnosticCode.RUN_DIMENSION_MISMATCH to 9),
            "println(vector(mutableListOf<Double>()))" to (DiagnosticCode.RUN_INVALID_DIMENSION to 9),
            "val l = mutableListOf<Double>()\nfor (i in 0 until 17) { l.add(1.0) }\nprintln(vector(l))" to (DiagnosticCode.RUN_INVALID_DIMENSION to 9),
            "println(normalize(vector(0.0, -0.0)))" to (DiagnosticCode.RUN_ZERO_VECTOR to 9),
            "println(vector(1.0)[1])" to (DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS to 20),
        )
        for ((source, expected) in runtime) assertEquals(expected, runtimeError(source), source)
        val vm = finish(CsVm(compile("println(vector(1.0, 2.0, 3.0) + vector(1.0))")))
        assertEquals(listOf(3, 1), vm.error!!.arguments.map { (it as DiagnosticArgument.IntArgument).value })
    }

    // ---- 値は変更できない: vector(list) はコピーで、元のリストを変えても変わらない。引数・戻り値・プロパティ・リストの要素に使える ----

    @Test
    fun vectorsAreValues() {
        assertEquals(
            listOf("[1.0, 2.0]", "[9.0, 2.0, 3.0]", "[1.0, 2.0]"),
            output("val l = mutableListOf(1.0, 2.0)\nval v = vector(l)\nprintln(v)\nl[0] = 9.0\nl.add(3.0)\nprintln(l)\nprintln(v)"),
        )
        val source = "class Body {\n  var position = vector(0.0, 0.0)\n  var velocity = vector(1.0, 2.0)\n  fun step(dt: Double) { position = position + velocity * dt }\n}\n" +
            "fun mid(a: Vector, b: Vector): Vector { return (a + b) / 2.0 }\n" +
            "val body = Body()\nbody.step(0.5)\nbody.step(0.5)\nval before = body.position\nbody.step(1.0)\nprintln(before)\nprintln(body.position)\n" +
            "val path = mutableListOf<Vector>()\npath.add(before)\npath.add(mid(before, body.position))\nprintln(path)\nprintln(path[1][0])\nval frozen: List<Vector> = path\nprintln(frozen.size)"
        assertEquals(listOf("[1.0, 2.0]", "[2.0, 4.0]", "[[1.0, 2.0], [1.5, 3.0]]", "1.5", "2"), output(source))
        // 同じ名前のクラスがあれば、型名 Vector はそのクラスを指す（vector(…) は組み込みのまま）
        assertEquals(listOf("Vector@1", "[1.0]"), output("class Vector\nval c: Vector = Vector()\nprintln(c)\nprintln(vector(1.0))"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class Vector\nval c: Vector = vector(1.0)"))
        // 名前は予約しない: 同じ名前の変数・関数があってもよく、ユーザー定義の関数が優先する
        assertEquals(listOf("3", "5.0"), output("val length = 3\nprintln(length)\nprintln(length(vector(3.0, 4.0)))"))
        assertEquals(listOf("12"), output("fun dot(a: Int, b: Int): Int { return a * b }\nprintln(dot(3, 4))"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("fun normalize(x: Int): Int { return x }\nprintln(normalize(vector(1.0)))"))
    }

    // ---- 命令と払う単位: どの演算も LINALG 1 つで、計算の単位 W と確定の 1 単位（W + 1）を 1 回に 1 つずつ払う ----

    // 先頭から 1 単位ずつ実行し、LINALG に留まった回数を命令ごとに数える
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
                    if (current >= 0) units.add(count)
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
        val operands = listOf(LinalgFunction.VECTOR_OF to 3, LinalgFunction.ADD to 0, LinalgFunction.DOT to 0)
        assertEquals(operands.map { (function, count) -> Opcode.LINALG to function.operand(count) }, compile("val v = vector(1.0, 2.0, 3.0)\nval w = v + v\nval d = dot(v, w)").instructions.filter { it.opcode == Opcode.LINALG }.map { it.opcode to it.operand })
        assertEquals((0..10).toList(), LinalgFunction.entries.take(11).map { it.id })
        val sixteen = List(16) { "${it + 1}.0" }.joinToString()
        val cases = listOf(
            "val v = vector($sixteen)" to listOf(17),
            "val v = vector(1.0, 2.0, 3.0)\nval a = v + v\nval b = v - v\nval c = -v\nval d = v * 2.0\nval e = 2.0 * v\nval f = v / 2.0" to listOf(4, 4, 4, 4, 4, 4, 4),
            "val v = vector(1.0, 2.0, 3.0)\nval d = dot(v, v)\nval l = length(v)\nval n = normalize(v)" to listOf(4, 4, 4, 7),
            "val v = vector(listOf(1.0, 2.0, 3.0, 4.0, 5.0))" to listOf(6),
            "val v = normalize(vector($sixteen))" to listOf(17, 33),
        )
        for ((source, expected) in cases) assertEquals(expected, unitsOf(compile(source)), source)
        // 長さ 0 の normalize は、長さを計算した次の単位で止まる
        val zero = CsVm(compile("val v = vector(0.0, 0.0, 0.0)\nprintln(normalize(v))"))
        var used = 0
        while (zero.state == VmState.RUNNING) used += zero.run(1)
        assertEquals(DiagnosticCode.RUN_ZERO_VECTOR, zero.error?.code)
        // PUSH_CONST 3・vector の 4・STORE・LOAD・normalize の長さの 3 単位と、止まった 1 単位
        assertEquals(3 + 4 + 1 + 1 + 3 + 1, used)
    }

    // ---- 分割実行: SLOW（1 単位 / tick）では W + 1 tick かかる。動力が無い間は止まり、払った分は残る。tick をまたいでも結果は同じ ----

    @Test
    fun splitExecutionAcrossTicks() {
        val sixteen = List(16) { "${it + 1}.0" }.joinToString()
        val program = compile("val v = vector($sixteen)\nval n = normalize(v)\nprintln(n[15])")
        val scheduler = Scheduler<Int>(4096)
        scheduler.register(0, CsVm(program))
        val slow = ExecutionCondition(PowerLevel.SLOW, true)
        val none = ExecutionCondition(PowerLevel.NONE, true)
        scheduler.tick { slow }
        var ticks = 0
        var executed = 0
        var paused = 0
        while (scheduler.registeredKeys.isNotEmpty()) {
            ticks++
            // 途中で 20 tick だけ動力を止める（その間は 1 単位も進まない）
            val condition = if (ticks in 10 until 30) none else slow
            val result = scheduler.tick { condition }
            executed += result.executed.values.sum()
            if (condition == none) {
                assertEquals(0, result.executed.values.sum())
                paused++
            }
            check(ticks < 1000)
        }
        val vector = (1..16).map { it.toDouble() }.toDoubleArray()
        // LINALG 以外の命令は 1 回ずつ（分岐の無いプログラム）＋ vector の 17 ＋ normalize の 33。動力のある tick は 1 単位ずつ
        assertEquals(program.instructions.size - 2 + 17 + 33, executed)
        assertEquals(20, paused)
        assertEquals(executed + paused, ticks)
        val vm = finish(CsVm(program))
        assertEquals(listOf(normalize(vector)[15].toString()), vm.console.history)
    }

    // ---- 保存・復元: すべての単位の境界（LINALG の途中を含む）で保存・復元しても結果は同じ。途中で保存した状態は命令を実行する前の状態で、
    // 復元すると W + 1 を最初から払い直す（払う量は増えるだけで、予算を回避できない） ----

    @Test
    fun snapshotsDuringLinalgRestart() {
        val source = "val a = vector(1.0, -2.0, 3.0)\nval b = vector(0.5, 0.25, -0.0)\nval c = normalize(a + b * 4.0)\nwaitTicks(2)\nprintln(c)\nprintln(dot(a, c))\nprintln(length(-c))"
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
        assertTrue(midLinalg > 20, "cuts at LINALG: $midLinalg")
        // normalize の途中（5 単位払った後）で保存すると、保存した状態は払う前と同じで、復元した VM はもう一度 7 単位払う
        val normalizing = compile("val v = vector(1.0, 2.0, 3.0)\nval n = normalize(v)\nprintln(n)")
        val at = normalizing.instructions.indexOfLast { it.opcode == Opcode.LINALG }
        val vm = CsVm(normalizing)
        while (vm.snapshot()!!.pc != at) vm.run(1)
        val before = vm.snapshot()!!
        repeat(5) { assertEquals(1, vm.run(1)) }
        val during = vm.snapshot()!!
        assertEquals(before, during)
        val restored = CsVm.restore(normalizing, during)!!
        var units = 0
        while (restored.snapshot()!!.pc == during.pc) units += restored.run(1)
        assertEquals(7, units)
        var rest = 0
        while (vm.snapshot()!!.pc == during.pc) rest += vm.run(1)
        assertEquals(2, rest)
    }

    // ---- GC: 確定のときにヒープが満杯なら回収を待ち（その単位は数えない）、回収の後に確定する。生きている Vector が多すぎれば実行時エラー ----

    @Test
    fun linalgWaitsForCollection() {
        val vm = CsVm(compile("var v = vector(1.0, 2.0)\nfor (i in 0 until 1000) { v = v + vector(1.0, 1.0) }\nprintln(v)"))
        var collections = 0
        while (vm.state == VmState.RUNNING) {
            if (vm.isCollecting) {
                vm.collectGarbage(100)
            } else {
                vm.run(1)
                if (vm.isCollecting) collections++
            }
        }
        assertEquals(VmState.HALTED, vm.state, "${vm.error}")
        assertEquals(listOf("[1001.0, 1002.0]"), vm.console.history)
        assertTrue(collections >= 5, "collections $collections")
        assertEquals(DiagnosticCode.RUN_TOO_MANY_OBJECTS to 25, runtimeError("val keep = mutableListOf<Vector>()\nwhile (true) { keep.add(vector(1.0)) }"))
        // 要素の合計の上限（4096）: 16 次元の Vector を 256 個は置けない
        val elements = runtimeError("val keep = mutableListOf<Vector>()\nfor (i in 0 until 300) { keep.add(vector(${List(16) { "1.0" }.joinToString()})) }")
        assertEquals(DiagnosticCode.RUN_TOO_MANY_LIST_ELEMENTS, elements.first)
    }

    // ---- Verifier と VM: 不正な LINALG・Vector を変更する命令・型の合わない値・書き換えた保存データを拒否する ----

    private fun replaced(program: CompiledProgram, opcode: Opcode, replacement: (Instruction) -> Instruction): CompiledProgram {
        val index = program.instructions.indexOfFirst { it.opcode == opcode }
        check(index >= 0)
        return program.copy(instructions = program.instructions.mapIndexed { i, instruction -> if (i == index) replacement(instruction) else instruction })
    }

    @Test
    fun invalidBytecodeIsRejected() {
        val sum = compile("val a = vector(1.0, 2.0)\nprintln(a + a)")
        assertNotNull(SnapshotVerifier.framesOf(sum))
        val add = LinalgFunction.ADD.operand()
        val invalid = listOf(
            -1, LinalgFunction.entries.size * LinalgFunction.COUNT_RANGE, Int.MAX_VALUE, LinalgFunction.VECTOR_OF.operand(0), LinalgFunction.VECTOR_OF.operand(17), add + 1,
            LinalgFunction.DOT.operand(), LinalgFunction.TIMES.operand(), LinalgFunction.VECTOR_OF_LIST.operand(),
        )
        // 最初の LINALG（vector(1.0, 2.0)）を書き換える
        for (operand in invalid) assertNull(SnapshotVerifier.framesOf(replaced(sum, Opcode.LINALG) { it.copy(operand = operand) }), "LINALG $operand")
        // 2 つ目の LINALG（a + a）を、結果の型が違う DOT に
        val second = sum.instructions.indexOfLast { it.opcode == Opcode.LINALG }
        val dotted = sum.copy(instructions = sum.instructions.mapIndexed { i, it -> if (i == second) it.copy(operand = LinalgFunction.DOT.operand()) else it })
        assertNotNull(SnapshotVerifier.framesOf(dotted))
        val fromList = sum.copy(instructions = sum.instructions.mapIndexed { i, it -> if (i == second) it.copy(operand = LinalgFunction.VECTOR_OF_LIST.operand()) else it })
        assertNull(SnapshotVerifier.framesOf(fromList))
        // Vector を変更する命令（LIST_SET・LIST_ADD の受け手を Vector に）
        val mutation = compile("val v = vector(1.0)\nval l = mutableListOf(1.0)\nl[0] = 2.0\nl.add(3.0)")
        val loads = mutation.instructions.withIndex().filter { it.value.opcode == Opcode.LOAD && it.value.operand == 1 }.map { it.index }
        for (load in loads) {
            val tampered = mutation.copy(instructions = mutation.instructions.mapIndexed { i, it -> if (i == load) it.copy(operand = 0) else it })
            assertNull(SnapshotVerifier.framesOf(tampered), "load $load")
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, finish(CsVm(tampered)).error?.code)
        }
        // Verifier を通さずに実行しても、VM が止める
        for (operand in listOf(-1, LinalgFunction.VECTOR_OF.operand(0), LinalgFunction.DOT.operand(), LinalgFunction.VECTOR_OF_LIST.operand())) {
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, finish(CsVm(replaced(sum, Opcode.LINALG) { it.copy(operand = operand) })).error?.code, "LINALG $operand")
        }
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, finish(CsVm(fromList)).error?.code)
    }

    @Test
    fun tamperedSnapshotsAreRejected() {
        val program = compile("val v = vector(1.0, 2.0)\nval l = listOf(3.0)\nwaitTicks(5)\nprintln(v)\nprintln(l)")
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertNotNull(CsVm.restore(program, snapshot))
        val vector = snapshot.objects.single { it.classId == ObjectSnapshot.VECTOR_CLASS }
        val list = snapshot.objects.single { it.classId == ObjectSnapshot.LIST_CLASS }
        fun with(edit: (ObjectSnapshot) -> ObjectSnapshot): VmSnapshot = snapshot.copy(objects = snapshot.objects.map { if (it.id == vector.id) edit(it) else it })
        val tampered = listOf(
            "mutable vector" to with { it.copy(list = it.list!!.copy(mutable = true)) },
            "int vector" to with { it.copy(list = ListSnapshot(SlotType.INT, false, listOf(Value.IntValue(1), Value.IntValue(2)))) },
            "empty vector" to with { it.copy(list = it.list!!.copy(elements = emptyList())) },
            "17 components" to with { it.copy(list = it.list!!.copy(elements = List(17) { Value.DoubleValue.of(1.0) })) },
            "vector as list" to with { it.copy(classId = ObjectSnapshot.LIST_CLASS) },
            "vector without list" to with { it.copy(list = null) },
            "list as vector" to snapshot.copy(objects = snapshot.objects.map { if (it.id == list.id) it.copy(classId = ObjectSnapshot.VECTOR_CLASS) else it }),
            "unknown kind" to with { it.copy(classId = -4) },
            "variable points to list" to snapshot.copy(variables = listOf(Value.ObjectRef(list.id), snapshot.variables[1])),
            "list variable points to vector" to snapshot.copy(variables = listOf(snapshot.variables[0], Value.ObjectRef(vector.id))),
        )
        for ((label, state) in tampered) assertNull(CsVm.restore(program, state), label)
        assertEquals(listOf("[1.0, 2.0]", "[3.0]"), finish(CsVm.restore(program, snapshot)!!).console.history)
    }

    // ---- 予算と公平性: LINALG の単位も 1 命令と同じく数え、FAST 128 命令 / tick・全体 4096 命令 / tick を守る ----

    private val loops = listOf(
        "var v = vector(1.0, 2.0, 3.0)\nwhile (true) { v = normalize(v + vector(0.5, -0.25, 1.0)) }",
        "var v = vector(${List(16) { "${it}.5" }.joinToString()})\nwhile (true) { v = -v * 1.5 / 1.5 }",
        "var d = 0.0\nval a = vector(1.0, 2.0)\nwhile (true) { d = dot(a, a) + length(a) + d * 0.0 }",
        "var v = vector(1.0)\nwhile (true) {\n  v = vector(v[0] + 1.0)\n  println(v.size)\n}",
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
        // 回収を待つ VM はその間の命令を実行しないため、回収のある VM どうしの差は回収の回数の分だけ広がる。回収の無いループ（3 番目）どうしは差 1 以内
        val steady = totals.filterIndexed { index, _ -> index % programs.size == 2 }
        assertTrue(steady.max() - steady.min() <= 1, "unfair: ${steady.min()}..${steady.max()}")
        val few = Scheduler<Int>(4096)
        programs.forEachIndexed { index, program -> few.register(index, CsVm(program)) }
        few.tick { fast }
        repeat(50) { few.tick { fast } }
        assertEquals(PowerLevel.FAST.instructionsPerTick, few.tick { fast }.executed[2])
    }

    // ---- 性能（実測）: 1 単位の時間と、1000 台の 1 tick の時間（2 次元と 16 次元で比べる） ----

    private fun nanosPerUnit(vms: List<CsVm>): Double {
        fun run(vm: CsVm, units: Int): Long {
            var done = 0L
            while (done < units) {
                if (vm.isCollecting) vm.collectGarbage(10_000) else done += vm.run(units - done.toInt())
            }
            return done
        }
        vms.forEach { run(it, 200_000) }
        val start = System.nanoTime()
        var executed = 0L
        vms.forEach { executed += run(it, 1_000_000) }
        return (System.nanoTime() - start).toDouble() / executed
    }

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
        return "avg %.3f / p50 %.3f / p99 %.3f / max %.3f ms".format(times.average(), sorted[ticks / 2], sorted[ticks * 99 / 100], sorted.last())
    }

    @Test
    fun measureVectors() {
        val int = "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }"
        val two = "var v = vector(1.0, 2.0)\nwhile (true) { v = normalize(v + vector(0.5, -0.25)) }"
        val sixteen = "var v = vector(${List(16) { "${it}.5" }.joinToString()})\nwhile (true) { v = normalize(v + v) }"
        for ((label, source) in listOf("Int arithmetic" to int, "2-dim normalize" to two, "16-dim normalize" to sixteen, "dot/length" to loops[2])) {
            println("[3.11e measure] per unit, $label: %.1f ns".format(nanosPerUnit(List(20) { CsVm(compile(source)) })))
        }
        for ((label, source) in listOf("1000 VMs Int arithmetic" to int, "1000 VMs 2-dim normalize" to two, "1000 VMs 16-dim normalize" to sixteen)) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program)) }
            println("[3.11e measure] $label: ${perTick(scheduler)}")
        }
    }
}
