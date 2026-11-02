package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.semantic.ScriptType
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.MathFunction
import com.creatrbypc.createanalytical.script.vm.NumericKind
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

// 第3.11c段階の三角関数・双曲線関数と数学の定数 PI・E を、意味解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。
// 期待値は同じ式を Kotlin で計算した値（どちらも java.lang.Math を使うため、同じ JVM の上では一致する）
class TrigonometryTest {
    // Kotlin 側の期待値で NaN・Infinity を作るための 0（定数の 0 除算の警告を避ける）
    private val zeroD = 0.0
    private val zeroF = 0f

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
            vm.advance(1000).also { if (vm.state == VmState.WAITING) vm.countWaitTick() }
            check(++steps < 2_000_000) { "did not finish" }
        }
        return vm
    }

    private fun output(source: String): List<String> {
        val vm = finish(CsVm(compile(source)))
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private fun typeOf(source: String, name: String): ScriptType? =
        SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize(source))).program!!.declarations.values.single { it.name == name }.type

    // 式ごとに println し、Kotlin で計算した値の表示と比べる（コンソールは 32 行のため分けて実行する）
    private fun assertPrints(cases: List<Pair<String, Any>>) {
        for (chunk in cases.chunked(30)) {
            assertEquals(chunk.map { it.second.toString() }, output(chunk.joinToString("\n") { "println(${it.first})" }), chunk.joinToString { it.first })
        }
    }

    // ---- 各関数の結果（NaN・Infinity・-0.0・定義域外を含む）は Kotlin と同じ ----

    @Test
    fun functionsMatchKotlin() {
        val nan = zeroD / zeroD
        val inf = 1.0 / zeroD
        val nanF = zeroF / zeroF
        val infF = 1f / zeroF
        assertPrints(
            listOf(
                "PI" to PI, "E" to E, "PI.toFloat()" to PI.toFloat(),
                "sin(PI)" to sin(PI), "cos(PI)" to cos(PI), "tan(PI / 2)" to tan(PI / 2), "sin(PI / 6)" to sin(PI / 6), "cos(PI / 3)" to cos(PI / 3),
                "tan(PI / 4)" to tan(PI / 4), "sin(0.5)" to sin(0.5), "cos(2.0)" to cos(2.0), "tan(-1.0)" to tan(-1.0),
                "sin(-0.0)" to sin(-0.0), "cos(-0.0)" to cos(-0.0), "tan(-0.0)" to tan(-0.0), "sin(1.0 / 0.0)" to sin(inf), "cos(-1.0 / 0.0)" to cos(-inf),
                "tan(0.0 / 0.0)" to tan(nan), "sin(1e300)" to sin(1e300), "cos(1e22)" to cos(1e22), "tan(1e300)" to tan(1e300), "sin(4.9e-324)" to sin(4.9e-324),
                "asin(1.0)" to asin(1.0), "asin(-0.0)" to asin(-0.0), "asin(2.0)" to asin(2.0), "asin(-1.0000001)" to asin(-1.0000001), "asin(0.5)" to asin(0.5),
                "acos(-1.0)" to acos(-1.0), "acos(1.0)" to acos(1.0), "acos(-1.0001)" to acos(-1.0001), "acos(0.0)" to acos(0.0), "acos(0.0 / 0.0)" to acos(nan),
                "atan(1.0 / 0.0)" to atan(inf), "atan(-1.0 / 0.0)" to atan(-inf), "atan(-0.0)" to atan(-0.0), "atan(1.0)" to atan(1.0), "atan(0.0 / 0.0)" to atan(nan),
                "sinh(-0.0)" to sinh(-0.0), "sinh(1.0)" to sinh(1.0), "sinh(710.0)" to sinh(710.0), "sinh(711.0)" to sinh(711.0), "sinh(-1.0 / 0.0)" to sinh(-inf),
                "cosh(0.0)" to cosh(0.0), "cosh(-710.0)" to cosh(-710.0), "cosh(-1.0 / 0.0)" to cosh(-inf), "cosh(0.0 / 0.0)" to cosh(nan),
                "tanh(1.0 / 0.0)" to tanh(inf), "tanh(-1.0 / 0.0)" to tanh(-inf), "tanh(-0.0)" to tanh(-0.0), "tanh(20.0)" to tanh(20.0), "tanh(0.5)" to tanh(0.5),
                "sin(0.5f)" to sin(0.5f), "cos(0.5f)" to cos(0.5f), "tan(0.5f)" to tan(0.5f), "sin(PI.toFloat())" to sin(PI.toFloat()), "sin(-0f)" to sin(-0f),
                "asin(2f)" to asin(2f), "acos(-1f)" to acos(-1f), "atan(1f / 0f)" to atan(infF), "sinh(1f)" to sinh(1f), "cosh(100f)" to cosh(100f),
                "tanh(0.5f)" to tanh(0.5f), "tanh(0f / 0f)" to tanh(nanF), "cosh(-0f)" to cosh(-0f), "sinh(-0f)" to sinh(-0f),
                "sin(30.0 * PI / 180.0)" to sin(30.0 * PI / 180.0), "atan(1.0) * 4.0" to atan(1.0) * 4.0, "E.pow(2)" to E.pow(2),
            ),
        )
        // -0.0 の符号のビットは値として残る（表示だけでなく 1.0 / x で確かめる）
        assertEquals(
            listOf("-Infinity", "-Infinity", "-Infinity", "-Infinity", "-Infinity", "-Infinity"),
            output("println(1.0 / sin(-0.0))\nprintln(1.0 / tan(-0.0))\nprintln(1.0 / asin(-0.0))\nprintln(1.0 / atan(-0.0))\nprintln(1.0 / tanh(-0.0))\nprintln(1f / sinh(-0f))"),
        )
    }

    // ---- atan2(y, x) の 4 象限・軸・符号付きゼロ・Infinity ----

    @Test
    fun atan2CoversEveryQuadrant() {
        val nan = zeroD / zeroD
        val inf = 1.0 / zeroD
        val doubles = listOf(
            "1.0" to 1.0, "(-1.0)" to -1.0, "0.0" to 0.0, "(-0.0)" to -0.0, "(1.0 / 0.0)" to inf, "(-1.0 / 0.0)" to -inf, "(0.0 / 0.0)" to nan, "2.5" to 2.5,
        )
        val cases = ArrayList<Pair<String, Any>>()
        for ((y, yv) in doubles) for ((x, xv) in doubles) cases.add("atan2($y, $x)" to atan2(yv, xv))
        val floats = listOf("1f" to 1f, "(-1f)" to -1f, "0f" to 0f, "(-0f)" to -0f, "(1f / 0f)" to 1f / zeroF)
        for ((y, yv) in floats) for ((x, xv) in floats) cases.add("atan2($y, $x)" to atan2(yv, xv))
        assertPrints(cases)
        // 象限と符号: 第 1〜4 象限・x 軸の負の側（+0.0 は π、-0.0 は -π）・原点の 4 通り
        assertEquals(
            listOf(PI / 4, 3 * PI / 4, -3 * PI / 4, -PI / 4, PI, -PI, 0.0, -0.0, PI, -PI).map { it.toString() },
            output("println(atan2(1.0, 1.0))\nprintln(atan2(1.0, -1.0))\nprintln(atan2(-1.0, -1.0))\nprintln(atan2(-1.0, 1.0))\nprintln(atan2(0.0, -1.0))\nprintln(atan2(-0.0, -1.0))\n" +
                "println(atan2(0.0, 0.0))\nprintln(atan2(-0.0, 0.0))\nprintln(atan2(0.0, -0.0))\nprintln(atan2(-0.0, -0.0))"),
        )
    }

    // ---- 型の規則と診断。Int からの暗黙の変換はしない（Kotlin と同じ） ----

    @Test
    fun typesAndDiagnostics() {
        val types = listOf(
            "sin(1.5)" to ScriptType.DOUBLE, "sin(1.5f)" to ScriptType.FLOAT, "atan2(1f, 2f)" to ScriptType.FLOAT, "atan2(1.0, 2.0)" to ScriptType.DOUBLE,
            "tanh(0.5f)" to ScriptType.FLOAT, "acos(0.5)" to ScriptType.DOUBLE, "PI" to ScriptType.DOUBLE, "E" to ScriptType.DOUBLE, "PI.toFloat()" to ScriptType.FLOAT,
        )
        for ((expression, type) in types) assertEquals(type, typeOf("val v = $expression", "v"), expression)
        val errors = listOf(
            "println(sin(1))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(atan2(1, 1))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(cosh(\"a\"))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(atan2(1.0, 1f))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(atan2(1f, 1.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(atan2(1.0, 1))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(sin(1.0, 2.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(atan2(1.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(tan())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.5.sin())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(30.0.toRadians())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(PI.toDegrees())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(toRadians(30.0))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "PI = 1.0" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "E = 2.0" to DiagnosticCode.SEM_VAL_REASSIGNMENT,
            "println(PI())" to DiagnosticCode.SEM_NOT_CALLABLE,
            "println(E(1))" to DiagnosticCode.SEM_NOT_CALLABLE,
            "val f: Float = PI" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "println(PI == 3)" to DiagnosticCode.SEM_INCOMPARABLE_TYPES,
            "println(Pi)" to DiagnosticCode.SEM_UNDECLARED_VARIABLE,
            "println(sin<Double>(1.0))" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
        // 位置: 型の誤りは引数、定数への代入・呼び出しは名前
        val positions = listOf("println(sin(1))" to 13, "println(atan2(1.0, 1f))" to 20, "PI = 1.0" to 1, "println(PI())" to 9)
        for ((source, column) in positions) assertEquals(column, Compiler.compileSource(source).diagnostics.single().position.column, source)
    }

    // ---- 名前は予約しない。同じ名前の変数・引数・プロパティ・関数・メソッド・クラスが優先する ----

    // 第3.11b段階のビルド（三角関数・定数の無い jar）でコンパイルしたときの指紋。三角関数・定数を追加しても、使わないプログラムの命令は変わらない
    private val legacyFingerprints = listOf(
        "val PI = 3\nvar E = PI * 2\nE = 4\nprintln(E + PI)" to "ddd2438ec17986936d6c77d14c9060983cd4d0460fb905683b6b03a1c9897938",
        "fun sin(x: Double): Double { return x * 2.0 }\nfun atan2(y: Int, x: Int): Int { return y - x }\nprintln(sin(1.5))\nprintln(atan2(3, 1))" to
            "b1512584cc7c66edfeb77ed51f33b91151c5502f299372e6f6d75331f6773b58",
        "class Wave {\n  var PI = 1.5\n  var cos = 2\n  fun tan(a: Double): Double { return a + PI }\n  fun f(): Double { return tan(PI) }\n}\nval w = Wave()\nprintln(w.f())\nprintln(w.cos)" to
            "184c43820e531bed9ea04318cf0f0e96d1d127f25b5d56ce967023d99cb582de",
        "class sinh\nval s = sinh()\nprintln(s)" to "82233317b6a9d40960386a3def3d83a772bd91c68f87a48b79be9b66e4278022",
        "fun g(asin: Double, acos: Float): Double { return asin * acos }\nfor (tanh in 0..3) { println(g(tanh.toDouble(), 1.5f)) }" to
            "ee21fcaf4fdcbc34aeb831bf3f7832dc70dbaca9056fe23d78b9d10923d32306",
        "val x = 3.141592653589793\nval y = 2.718281828459045\nprintln(sqrt(x) + abs(-y))\nprintln(2.0.pow(3))\nprintln(x.roundToInt().coerceIn(0, 2))" to
            "1515c3c8e48cb1b691600cee68a8654fea6dd6730b9891d976f99ff8ab142171",
        "var cosh = 0.0\nfor (i in 0 until 3) { cosh = cosh + i * 0.5 }\nprintln(max(cosh, 1.0))\nwaitTicks(5)\nprintln(floor(cosh))" to
            "0f017776cba5138596b0fe479de624b5d4fa98e4c22b200e787f35fefa8fe64b",
    )

    @Test
    fun namesAreNotReserved() {
        for ((source, hash) in legacyFingerprints) {
            val program = compile(source)
            assertTrue(program.instructions.none { it.opcode == Opcode.MATH && it.operand >= MathFunction.SIN.operand(NumericKind.INT) }, source)
            assertEquals(hash, ProgramFingerprint.ofProgram(program).joinToString("") { "%02x".format(it) }, source)
        }
        assertEquals(listOf(PI.toString(), E.toString()), output("println(PI)\nprintln(E)"))
        // 変数（val・var・ブロックの中・引数・for の変数）とプロパティは定数より優先し、スコープの外では定数に戻る
        assertEquals(listOf("3"), output("val PI = 3\nprintln(PI)"))
        assertEquals(listOf("4", "8"), output("var PI = 3\nPI = 4\nprintln(PI)\nPI = PI * 2\nprintln(PI)"))
        assertEquals(listOf("1", PI.toString()), output("if (true) {\n  val PI = 1\n  println(PI)\n}\nprintln(PI)"))
        assertEquals(listOf("2.5", "0", "1", E.toString()), output("fun f(PI: Double): Double { return PI }\nprintln(f(2.5))\nfor (E in 0..1) { println(E) }\nprintln(E)"))
        assertEquals(
            listOf("1.5", (PI * 2.0 * 2.0).toString()),
            output("class Wave {\n  var PI = 1.5\n  fun get(): Double { return PI }\n}\nclass Circle {\n  fun area(r: Double): Double { return PI * r * r }\n}\nprintln(Wave().get())\nprintln(Circle().area(2.0))"),
        )
        // 関数・メソッド・クラスは三角関数より優先する
        assertEquals(listOf("3.0", cos(1.5).toString()), output("fun sin(x: Double): Double { return x * 2.0 }\nprintln(sin(1.5))\nprintln(cos(1.5))"))
        assertEquals(listOf("7", tan(1.0).toString()), output("class M {\n  fun tan(a: Int): Int { return a + 6 }\n  fun f(): Int { return tan(1) }\n}\nprintln(M().f())\nprintln(tan(1.0))"))
        assertEquals(listOf("sinh@1"), output("class sinh\nprintln(sinh())"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("fun sin(x: Int): Int { return x }\nprintln(sin(1.5))"))
        // PI は同じビットのリテラルと 1 つの定数にまとまる
        assertEquals(listOf(Constant.DoubleConstant(PI.toRawBits())), compile("println(PI)\nprintln(3.141592653589793)").constants)
    }

    // ---- 命令: 三角関数は MATH 1 つ（番号 10〜19。Float は Float のまま）。PI・E は PUSH_CONST ----

    @Test
    fun instructionsUseMath() {
        fun numeric(source: String) = compile(source).instructions.filter { it.opcode == Opcode.MATH || it.opcode == Opcode.CONVERT }.map { it.opcode to it.operand }
        assertEquals((10..19).toList(), listOf(MathFunction.SIN, MathFunction.COS, MathFunction.TAN, MathFunction.ASIN, MathFunction.ACOS, MathFunction.ATAN, MathFunction.ATAN2, MathFunction.SINH, MathFunction.COSH, MathFunction.TANH).map { it.id })
        assertEquals((0..9).toList(), MathFunction.entries.take(10).map { it.id })
        assertEquals(
            listOf(Opcode.MATH to MathFunction.SIN.operand(NumericKind.DOUBLE), Opcode.MATH to MathFunction.ATAN2.operand(NumericKind.FLOAT), Opcode.MATH to MathFunction.TANH.operand(NumericKind.FLOAT)),
            numeric("val a = sin(1.5)\nval b = atan2(1f, 2f)\nval c = tanh(0.5f)"),
        )
        val pi = compile("val r = 2.0\nprintln(PI * r)").instructions.map { it.opcode }
        assertEquals(listOf(Opcode.PUSH_CONST, Opcode.STORE, Opcode.PUSH_CONST, Opcode.LOAD, Opcode.MULTIPLY, Opcode.CALL_HOST, Opcode.POP, Opcode.HALT), pi)
    }

    // ---- Verifier と VM: 不正な MATH operand・型の合わない値を拒否する ----

    private fun replaced(program: CompiledProgram, opcode: Opcode, replacement: (Instruction) -> Instruction): CompiledProgram {
        val index = program.instructions.indexOfFirst { it.opcode == opcode }
        check(index >= 0)
        return program.copy(instructions = program.instructions.mapIndexed { i, instruction -> if (i == index) replacement(instruction) else instruction })
    }

    @Test
    fun invalidTrigBytecodeIsRejected() {
        val doubles = compile("var d = 1.5\nd = sin(d)\nprintln(d)")
        assertNotNull(SnapshotVerifier.framesOf(doubles))
        val invalidOperands = listOf(
            // 未知の番号の Double は、番号 20 が第3.11d段階の ln になったため、最初の未知の番号から求める
            -1, 60, 61, MathFunction.entries.size * NumericKind.COUNT + NumericKind.DOUBLE, Int.MAX_VALUE,
            MathFunction.SIN.operand(NumericKind.INT), MathFunction.ATAN2.operand(NumericKind.INT), MathFunction.TANH.operand(NumericKind.INT),
            // 型が違う（Double の値に Float の関数）・引数が足りない（スタックに 1 つしかない）
            MathFunction.COS.operand(NumericKind.FLOAT), MathFunction.ATAN2.operand(NumericKind.DOUBLE),
        )
        for (operand in invalidOperands) assertNull(SnapshotVerifier.framesOf(replaced(doubles, Opcode.MATH) { it.copy(operand = operand) }), "MATH $operand")
        for (valid in listOf(MathFunction.COS, MathFunction.ASIN, MathFunction.ATAN, MathFunction.COSH, MathFunction.SQRT)) {
            assertNotNull(SnapshotVerifier.framesOf(replaced(doubles, Opcode.MATH) { it.copy(operand = valid.operand(NumericKind.DOUBLE)) }), "$valid")
        }
        for (operand in listOf(-1, 60, MathFunction.SIN.operand(NumericKind.INT), MathFunction.COS.operand(NumericKind.FLOAT), MathFunction.ATAN2.operand(NumericKind.DOUBLE))) {
            val vm = finish(CsVm(replaced(doubles, Opcode.MATH) { it.copy(operand = operand) }))
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code, "MATH $operand")
        }
    }

    // ---- 三角関数の結果（-0.0・NaN・Infinity）は保存・復元を通して変わらない。すべての境界で復元しても結果は同じ ----

    @Test
    fun trigResultsSurviveSnapshots() {
        val source = "var a = sin(-0.0)\nvar b = asin(2.0)\nvar c = atan2(-0.0, -1.0)\nvar f = cos(0.5f)\nvar g = tanh(-0f)\nval xs = mutableListOf(PI, E, sinh(711.0))\nwaitTicks(3)\n" +
            "println(1.0 / a)\nprintln(b == b)\nprintln(c)\nprintln(f)\nprintln(1f / g)\nprintln(xs)\nprintln(atan2(f, 1f))"
        val expected = listOf("-Infinity", "false", (-PI).toString(), cos(0.5f).toString(), "-Infinity", listOf(PI, E, Double.POSITIVE_INFINITY).toString(), atan2(cos(0.5f), 1f).toString())
        val program = compile(source)
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertEquals(Value.DoubleValue(Long.MIN_VALUE), snapshot.variables[0])
        assertTrue((snapshot.variables[1] as Value.DoubleValue).value.isNaN())
        assertEquals(Value.DoubleValue.of(-PI), snapshot.variables[2])
        assertEquals(Value.FloatValue(Int.MIN_VALUE), snapshot.variables[4])
        assertEquals(expected, finish(CsVm.restore(program, snapshot)!!).console.history)
        val original = CsVm(program)
        while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
            val cut = original.snapshot()!!
            val restored = CsVm.restore(program, cut)
            assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, cut)}")
            assertEquals(cut, restored!!.snapshot())
            while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
            assertEquals(expected, restored.console.history)
            step(original)
        }
        assertEquals(expected, original.console.history)
    }

    // ---- 無作為の式（三角関数と第3.11b段階の関数の入れ子・境界値・定数）を Kotlin で計算した結果と比べる ----

    // Kotlin で計算した値（Float・Double のどちらか）と、同じ値を作る CreateScript の式
    private class Term(val source: String, val value: Any)

    private val floatLeaves = listOf(
        "0f" to 0f, "(-0f)" to -0f, "1.5f" to 1.5f, "(-2.5f)" to -2.5f, "0.1f" to 0.1f, "3e38f" to 3e38f, "(0f / 0f)" to zeroF / zeroF,
        "(1f / 0f)" to 1f / zeroF, "(-1f / 0f)" to -1f / zeroF, "1e-40f" to 1e-40f, "0.5f" to 0.5f, "PI.toFloat()" to PI.toFloat(),
    )
    private val doubleLeaves = listOf(
        "0.0" to 0.0, "(-0.0)" to -0.0, "2.5" to 2.5, "(-2.5)" to -2.5, "0.1" to 0.1, "1e300" to 1e300, "(0.0 / 0.0)" to zeroD / zeroD,
        "(1.0 / 0.0)" to 1.0 / zeroD, "(-1.0 / 0.0)" to -1.0 / zeroD, "4.9e-324" to 4.9e-324, "0.5" to 0.5, "(-0.5)" to -0.5, "PI" to PI, "E" to E, "(-PI)" to -PI,
    )

    private fun unary(value: Any, float: (Float) -> Float, double: (Double) -> Double): Any = if (value is Float) float(value) else double(value as Double)

    private fun binary(left: Any, right: Any, float: (Float, Float) -> Float, double: (Double, Double) -> Double): Any =
        if (left is Float) float(left, right as Float) else double(left as Double, right as Double)

    private fun term(random: Random, float: Boolean, depth: Int): Term {
        if (depth == 0 || random.nextInt(5) < 2) {
            return (if (float) floatLeaves else doubleLeaves).random(random).let { Term(it.first, it.second) }
        }
        fun sub() = term(random, float, depth - 1)
        val unaries = listOf<Triple<String, (Float) -> Float, (Double) -> Double>>(
            Triple("sin", { sin(it) }, { sin(it) }), Triple("cos", { cos(it) }, { cos(it) }), Triple("tan", { tan(it) }, { tan(it) }),
            Triple("asin", { asin(it) }, { asin(it) }), Triple("acos", { acos(it) }, { acos(it) }), Triple("atan", { atan(it) }, { atan(it) }),
            Triple("sinh", { sinh(it) }, { sinh(it) }), Triple("cosh", { cosh(it) }, { cosh(it) }), Triple("tanh", { tanh(it) }, { tanh(it) }),
            Triple("abs", { abs(it) }, { abs(it) }), Triple("sqrt", { sqrt(it) }, { sqrt(it) }),
        )
        return when (val choice = random.nextInt(unaries.size + 4)) {
            in unaries.indices -> unaries[choice].let { (name, f, d) -> sub().let { x -> Term("$name(${x.source})", unary(x.value, f, d)) } }
            unaries.size -> Pair(sub(), sub()).let { (y, x) -> Term("atan2(${y.source}, ${x.source})", binary(y.value, x.value, { a, b -> atan2(a, b) }, { a, b -> atan2(a, b) })) }
            unaries.size + 1 -> Pair(sub(), sub()).let { (a, b) -> Term("min(${a.source}, ${b.source})", binary(a.value, b.value, { p, q -> min(p, q) }, { p, q -> min(p, q) })) }
            unaries.size + 2 -> Pair(sub(), sub()).let { (a, b) -> Term("max(${a.source}, ${b.source})", binary(a.value, b.value, { p, q -> max(p, q) }, { p, q -> max(p, q) })) }
            else -> Pair(sub(), sub()).let { (a, b) -> Term("(${a.source}).pow(${b.source})", binary(a.value, b.value, { p, q -> p.pow(q) }, { p, q -> p.pow(q) })) }
        }
    }

    @Test
    fun randomExpressionsMatchKotlin() {
        val random = Random(3_11_3)
        val cases = List(600) { index -> term(random, index % 2 == 0, 3).let { it.source to it.value } }
        cases.count { it.first.contains("atan2") }.let { assertTrue(it > 30, "atan2 in $it cases") }
        assertPrints(cases)
    }

    // ---- 予算と公平性: 三角関数も 1 命令 = 1 単位で、FAST 128 命令 / tick・全体 4096 命令 / tick を守る ----

    private val loops = listOf(
        "var d = 0.5\nwhile (true) { d = sin(d) + cos(d) }",
        "var f = 0.5f\nwhile (true) { f = atan2(sinh(f), cosh(f)) + tanh(f) }",
        "var d = 0.25\nwhile (true) {\n  d = asin(d) + acos(d) - atan(d) - 1.0\n  println(tan(d))\n  d = d - floor(d)\n}",
        "var d = 1e300\nwhile (true) { d = tan(d) * 1e300 + PI }",
    )

    @Test
    fun trigProgramsStayWithinBudgetAndFair() {
        val programs = loops.map(::compile)
        // どのループも実行時エラーにならず回り続ける
        for (program in programs) assertEquals(VmState.RUNNING, CsVm(program).also { it.run(500_000) }.state)
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
        assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        val few = Scheduler<Int>(4096)
        programs.forEachIndexed { index, program -> few.register(index, CsVm(program)) }
        few.tick { fast }
        repeat(50) { few.tick { fast } }
        assertEquals(programs.indices.associateWith { PowerLevel.FAST.instructionsPerTick }, few.tick { fast }.executed)
        // 1 回の三角関数は 1 命令: 引数を積む命令 + MATH + 変数への代入
        val vm = CsVm(compile("val a = sin(0.5)\nprintln(a)"))
        assertEquals(3, vm.run(3))
        assertEquals(listOf(Value.DoubleValue.of(sin(0.5))), vm.snapshot()!!.variables)
    }

    // ---- 性能（実測） ----

    private fun nanosPerInstruction(vms: List<CsVm>): Double {
        vms.forEach { it.run(200_000) }
        val start = System.nanoTime()
        var executed = 0L
        vms.forEach { executed += it.run(1_000_000) }
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
    fun measureTrigonometry() {
        val int = "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }"
        val labels = listOf("sin + cos", "Float atan2/sinh/cosh/tanh", "asin/acos/atan/tan + println", "tan of huge arguments")
        for ((label, source) in listOf("Int arithmetic" to int) + labels.zip(loops)) {
            println("[3.11c measure] per instruction, $label: %.1f ns".format(nanosPerInstruction(List(20) { CsVm(compile(source)) })))
        }
        for ((label, source) in listOf("1000 VMs sin + cos" to loops[0], "1000 VMs tan of huge arguments" to loops[3], "1000 VMs Int arithmetic" to int)) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program).also { machine -> machine.run(3000) }) }
            println("[3.11c measure] $label: ${perTick(scheduler)}")
        }
    }
}
