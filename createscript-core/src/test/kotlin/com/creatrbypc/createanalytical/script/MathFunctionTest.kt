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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// 第3.11b段階の数学関数を、意味解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。期待値は同じ式を Kotlin で計算した値
class MathFunctionTest {
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

    // ---- 各関数の結果（NaN・Infinity・-0.0 を含む）は Kotlin と同じ ----

    @Test
    fun functionsMatchKotlin() {
        val nan = zeroD / zeroD
        val inf = 1.0 / zeroD
        val nanF = zeroF / zeroF
        val infF = 1f / zeroF
        assertPrints(
            listOf(
                "abs(-5)" to abs(-5), "abs(-2147483647 - 1)" to abs(Int.MIN_VALUE), "abs(-0.0)" to abs(-0.0), "abs(-1.5f)" to abs(-1.5f),
                "abs(0.0 / 0.0)" to abs(nan), "abs(-1.0 / 0.0)" to abs(-inf), "abs(-0f)" to abs(-0f),
                "min(3, -2)" to min(3, -2), "max(3, -2)" to max(3, -2), "min(0.0, -0.0)" to min(0.0, -0.0), "max(0.0, -0.0)" to max(0.0, -0.0),
                "min(-0.0, 0.0)" to min(-0.0, 0.0), "max(-0f, 0f)" to max(-0f, 0f), "min(0f, -0f)" to min(0f, -0f), "min(0.0 / 0.0, 1.0)" to min(nan, 1.0),
                "max(1.0, 0.0 / 0.0)" to max(1.0, nan), "min(1.5f, 2.5f)" to min(1.5f, 2.5f), "max(0f / 0f, 1f)" to max(nanF, 1f),
                "max(-2147483647 - 1, 3)" to max(Int.MIN_VALUE, 3),
                "sqrt(2.0)" to sqrt(2.0), "sqrt(-1.0)" to sqrt(-1.0), "sqrt(-0.0)" to sqrt(-0.0), "sqrt(2f)" to sqrt(2f), "sqrt(1.0 / 0.0)" to sqrt(inf),
                "sqrt(1e-320)" to sqrt(1e-320), "sqrt(-0f)" to sqrt(-0f), "sqrt(0f / 0f)" to sqrt(nanF),
                "2.0.pow(10)" to 2.0.pow(10), "2.0.pow(0.5)" to 2.0.pow(0.5), "(0.0 / 0.0).pow(0.0)" to nan.pow(0.0), "0.0.pow(-1.0)" to 0.0.pow(-1.0),
                "(-0.0).pow(-1.0)" to (-0.0).pow(-1.0), "(-8.0).pow(1.0 / 3)" to (-8.0).pow(1.0 / 3), "1e308.pow(2)" to 1e308.pow(2),
                "(-2.0).pow(3)" to (-2.0).pow(3), "1.5f.pow(2)" to 1.5f.pow(2), "1.5f.pow(2.5f)" to 1.5f.pow(2.5f), "2f.pow(-1)" to 2f.pow(-1),
                "(-1f).pow(16777217)" to (-1f).pow(16777217), "10f.pow(39)" to 10f.pow(39), "0.1f.pow(0.1f)" to 0.1f.pow(0.1f),
                "(1.0 / 0.0).pow(-2)" to inf.pow(-2), "1.0.pow(0.0 / 0.0)" to 1.0.pow(nan),
                "floor(-1.5)" to floor(-1.5), "ceil(-0.5)" to ceil(-0.5), "floor(2.7f)" to floor(2.7f), "ceil(-0.5f)" to ceil(-0.5f),
                "floor(-1.0 / 0.0)" to floor(-inf), "ceil(0.0 / 0.0)" to ceil(nan), "floor(-0.0)" to floor(-0.0), "ceil(1e300)" to ceil(1e300),
                "round(2.5)" to round(2.5), "round(-2.5)" to round(-2.5), "round(0.5)" to round(0.5), "round(1.5)" to round(1.5), "round(-0.4)" to round(-0.4),
                "round(2.5f)" to round(2.5f), "round(3.5f)" to round(3.5f), "round(0.0 / 0.0)" to round(nan), "round(1f / 0f)" to round(infF),
                "2.5.roundToInt()" to 2.5.roundToInt(), "(-2.5).roundToInt()" to (-2.5).roundToInt(), "(-0.5).roundToInt()" to (-0.5).roundToInt(),
                "1e20.roundToInt()" to 1e20.roundToInt(), "(-1e20).roundToInt()" to (-1e20).roundToInt(), "2.5f.roundToInt()" to 2.5f.roundToInt(),
                "(1.0 / 0.0).roundToInt()" to inf.roundToInt(), "(-1f / 0f).roundToInt()" to (-infF).roundToInt(), "0.49999999999999994.roundToInt()" to 0.49999999999999994.roundToInt(),
                "5.coerceIn(1, 3)" to 5.coerceIn(1, 3), "(-5).coerceIn(1, 3)" to (-5).coerceIn(1, 3), "2.coerceIn(1, 3)" to 2.coerceIn(1, 3), "3.coerceIn(3, 3)" to 3.coerceIn(3, 3),
                "1.5.coerceIn(0.0 / 0.0, 2.0)" to 1.5.coerceIn(nan, 2.0), "(-0.0).coerceIn(0.0, 1.0)" to (-0.0).coerceIn(0.0, 1.0),
                "(0.0 / 0.0).coerceIn(0.0, 1.0)" to nan.coerceIn(0.0, 1.0), "2.5f.coerceIn(0f, 1f)" to 2.5f.coerceIn(0f, 1f),
                "(-1.0 / 0.0).coerceIn(-1.0, 1.0)" to (-inf).coerceIn(-1.0, 1.0), "0.5.coerceIn(1.0, 0.0 / 0.0)" to 0.5.coerceIn(1.0, nan),
            ),
        )
        // -0.0 の符号のビットは値として残る（表示だけでなく 1.0 / x で確かめる）
        assertEquals(listOf("-Infinity", "Infinity", "-Infinity", "-Infinity"), output("println(1.0 / min(0.0, -0.0))\nprintln(1.0 / abs(-0.0))\nprintln(1.0 / ceil(-0.5))\nprintln(1f / sqrt(-0f))"))
    }

    // ---- 型の規則と診断。引数の数値の型は暗黙に変換しない（Kotlin と同じ） ----

    @Test
    fun typesAndDiagnostics() {
        val types = listOf(
            "abs(1)" to ScriptType.INT, "abs(1.5f)" to ScriptType.FLOAT, "max(1.5, 2.5)" to ScriptType.DOUBLE, "sqrt(2f)" to ScriptType.FLOAT,
            "2.0.pow(3)" to ScriptType.DOUBLE, "2f.pow(3)" to ScriptType.FLOAT, "2f.pow(0.5f)" to ScriptType.FLOAT, "round(2.5)" to ScriptType.DOUBLE,
            "2.5.roundToInt()" to ScriptType.INT, "2.5f.roundToInt()" to ScriptType.INT, "1.coerceIn(0, 2)" to ScriptType.INT, "1f.coerceIn(0f, 2f)" to ScriptType.FLOAT,
        )
        for ((expression, type) in types) assertEquals(type, typeOf("val v = $expression", "v"), expression)
        val errors = listOf(
            "println(sqrt(2))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(floor(3))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(round(1))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(abs(\"a\"))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(min(true, false))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(max(1, 2.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(min(1.5f, 2.5))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(min(1.5, \"a\"))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(2.0.pow(1.5f))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(2f.pow(1.5))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(1.coerceIn(0, 2.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(1.5.coerceIn(0, 2.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(abs(1, 2))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(max(1))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(sqrt())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.coerceIn(0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.5.roundToInt(1))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.5.pow())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(2.pow(3))" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "println(2.roundToInt())" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "println(1.5.abs())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(1.5.sqrt())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(abs<Int>(1))" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
            "val s: Int = round(1.5)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val s: Double = 2f.pow(2)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "println(clamp(1, 0, 2))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "println(pow(2.0, 3.0))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
        // 位置: 型の誤りは引数、存在しないメソッドはメソッド名
        val positions = listOf("println(sqrt(2))" to 14, "println(max(1, 2.0))" to 16, "println(2.pow(3))" to 11)
        for ((source, column) in positions) assertEquals(column, Compiler.compileSource(source).diagnostics.single().position.column, source)
    }

    // ---- 数学関数の名前は予約しない。ユーザー定義の関数・メソッド・クラスがあればそちらを呼び、変数とは共存する ----

    // 第3.11a段階のビルド（数学関数の無い jar）でコンパイルしたときの指紋。数学関数を追加しても、使わないプログラムの命令は変わらない
    private val legacyFingerprints = listOf(
        "val max = 3\nvar min = max * 2\nprintln(min)" to "b7443499a75cc9622dd2e8b9ec47fadfb7458a1d7e1aafb444c53e90ca45638a",
        "fun abs(x: Int): Int { return 99 }\nprintln(abs(-5))" to "e4d4f29d02836075e6b634b40a5386266d4ce5e886dd8dfa0ddbb34963637d81",
        "class Box {\n  var round = 1.5\n  fun max(a: Int, b: Int): Int { return a }\n  fun f(): Int { return max(1, 2) }\n}\nval b = Box()\nprintln(b.f())\nprintln(b.round)" to
            "ed2ccefc7f02eb632e9652bf4c05cd82fa70a52b4d6b6ef69463350666cb47f7",
        "class sqrt\nval s = sqrt()\nprintln(s)" to "32c7303a17d4283ac36387111dacac369d6b0102a29a1f89de1d4f8ab6b2d224",
        "fun g(floor: Double, ceil: Float): Double { return floor * ceil }\nfor (pow in 0..3) { println(g(pow.toDouble(), 1.5f)) }" to
            "63c5523c467124a253e2544cefc3f8f4bdc7d3fda7d0fbd39836b1d04344f031",
        "val xs = mutableListOf(1.5, -0.0)\nvar d = 0.0\nfor (i in 0 until xs.size) { d = d + xs[i] }\nprintln(d.toInt())\nwaitTicks(5)\nprintln(d / 0.0)" to
            "1037c65267011fdb274d71696132324ffdc975db0a70a05d20355965b39e1871",
    )

    @Test
    fun namesAreNotReserved() {
        for ((source, hash) in legacyFingerprints) {
            val program = compile(source)
            assertTrue(program.instructions.none { it.opcode == Opcode.MATH }, source)
            assertEquals(hash, ProgramFingerprint.ofProgram(program).joinToString("") { "%02x".format(it) }, source)
        }
        // 変数と同じ名前の数学関数を呼べる。ユーザー定義の関数・メソッドは数学関数より優先する（Kotlin と同じ）
        assertEquals(listOf("2", "3"), output("val max = 3\nprintln(max(1, 2))\nprintln(max)"))
        assertEquals(listOf("99", "5"), output("fun abs(x: Int): Int { return 99 }\nprintln(abs(-5))\nprintln(5.coerceIn(0, 9))"))
        assertEquals(listOf("1", "2"), output("class Box {\n  fun max(a: Int, b: Int): Int { return a }\n  fun f(): Int { return max(1, 2) }\n}\nprintln(Box().f())\nprintln(max(1, 2))"))
        assertEquals(listOf("2.0", "1", "0.0"), output("fun g(floor: Double): Double { return floor(floor) }\nprintln(g(2.5))\nfor (round in 1..1) { println(round) }\nvar sqrt = 0.0\nsqrt = sqrt(sqrt)\nprintln(sqrt)"))
        assertEquals(listOf("sqrt@1"), output("class sqrt\nprintln(sqrt())"))
        assertEquals(listOf("4.0"), output("class P { var pow = 2.0\n  fun twice(): Double { return pow.pow(2) } }\nprintln(P().twice())"))
        // 既存の組み込み関数名の再宣言の禁止は変わらない。ユーザー定義の関数があれば、その引数の型で検査する
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("val println = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("fun abs(x: Int): Int { return x }\nprintln(abs(1.5))"))
    }

    // ---- 命令: MATH 1 つ（operand = 関数 × 3 + 数値の型）。pow は Double で計算し、前後に CONVERT を挟む ----

    @Test
    fun instructionsUseMath() {
        fun numeric(source: String) = compile(source).instructions.filter { it.opcode == Opcode.MATH || it.opcode == Opcode.CONVERT }.map { it.opcode to it.operand }
        assertEquals(
            listOf(Opcode.MATH to MathFunction.ABS.operand(NumericKind.INT), Opcode.MATH to MathFunction.MAX.operand(NumericKind.DOUBLE), Opcode.MATH to MathFunction.ROUND_TO_INT.operand(NumericKind.FLOAT)),
            numeric("val a = abs(-3)\nval b = max(1.5, 2.5)\nval c = 2.5f.roundToInt()"),
        )
        // Float.pow(Int): 受け手を Double に、指数を Double にしてから MATH、結果を Float に戻す
        assertEquals(
            listOf(
                Opcode.CONVERT to NumericKind.conversion(NumericKind.FLOAT, NumericKind.DOUBLE), Opcode.CONVERT to NumericKind.conversion(NumericKind.INT, NumericKind.DOUBLE),
                Opcode.MATH to MathFunction.POW.operand(NumericKind.DOUBLE), Opcode.CONVERT to NumericKind.conversion(NumericKind.DOUBLE, NumericKind.FLOAT),
            ),
            numeric("println(2f.pow(3))"),
        )
        assertEquals(listOf(Opcode.MATH to MathFunction.POW.operand(NumericKind.DOUBLE)), numeric("println(2.0.pow(3.0))"))
        assertEquals(
            listOf(Opcode.CONVERT to NumericKind.conversion(NumericKind.INT, NumericKind.DOUBLE), Opcode.MATH to MathFunction.POW.operand(NumericKind.DOUBLE)),
            numeric("println(2.0.pow(3))"),
        )
        // 受け手・引数は左から評価する（関数の呼び出しの順で確かめる）
        assertEquals(
            listOf("a", "b", "c", "2.0"),
            output("fun t(s: String, v: Double): Double { println(s)\n  return v }\nprintln(t(\"a\", 5.0).coerceIn(t(\"b\", 1.0), t(\"c\", 2.0)))"),
        )
    }

    // ---- Verifier と VM: 不正な MATH operand・型の合わない値を拒否する ----

    private fun replaced(program: CompiledProgram, opcode: Opcode, replacement: (Instruction) -> Instruction): CompiledProgram {
        val index = program.instructions.indexOfFirst { it.opcode == opcode }
        check(index >= 0)
        return program.copy(instructions = program.instructions.mapIndexed { i, instruction -> if (i == index) replacement(instruction) else instruction })
    }

    @Test
    fun invalidMathBytecodeIsRejected() {
        val doubles = compile("var d = 1.5\nd = floor(d)\nprintln(d)")
        assertNotNull(SnapshotVerifier.framesOf(doubles))
        val invalidOperands = listOf(
            -1, Int.MIN_VALUE, Int.MAX_VALUE, MathFunction.entries.size * NumericKind.COUNT, 30,
            MathFunction.FLOOR.operand(NumericKind.INT), MathFunction.SQRT.operand(NumericKind.INT), MathFunction.ROUND.operand(NumericKind.INT),
            MathFunction.POW.operand(NumericKind.INT), MathFunction.POW.operand(NumericKind.FLOAT), MathFunction.ROUND_TO_INT.operand(NumericKind.INT),
            // 型が違う（Double の値に Float・Int の関数）・引数が足りない（スタックに 1 つしかない）
            MathFunction.FLOOR.operand(NumericKind.FLOAT), MathFunction.ABS.operand(NumericKind.INT), MathFunction.MIN.operand(NumericKind.DOUBLE),
            MathFunction.COERCE_IN.operand(NumericKind.DOUBLE), MathFunction.POW.operand(NumericKind.DOUBLE),
        )
        for (operand in invalidOperands) {
            val program = replaced(doubles, Opcode.MATH) { it.copy(operand = operand) }
            assertNull(SnapshotVerifier.framesOf(program), "MATH $operand")
        }
        // 結果の型: roundToInt の Int を Double の加算に渡せない
        val sum = compile("val d = 1.5\nprintln(floor(d) + 1.0)")
        assertNotNull(SnapshotVerifier.framesOf(sum))
        assertNull(SnapshotVerifier.framesOf(replaced(sum, Opcode.MATH) { it.copy(operand = MathFunction.ROUND_TO_INT.operand(NumericKind.DOUBLE)) }))
        for (valid in listOf(MathFunction.ABS, MathFunction.SQRT, MathFunction.CEIL, MathFunction.ROUND)) {
            assertNotNull(SnapshotVerifier.framesOf(replaced(doubles, Opcode.MATH) { it.copy(operand = valid.operand(NumericKind.DOUBLE)) }), "$valid")
        }
        // 検証を通さずに実行しても VM が止める（未知の operand・使えない型・型の違う値・足りない引数）
        for (operand in listOf(-1, 30, Int.MAX_VALUE, MathFunction.SQRT.operand(NumericKind.INT), MathFunction.FLOOR.operand(NumericKind.FLOAT), MathFunction.MIN.operand(NumericKind.DOUBLE))) {
            val vm = finish(CsVm(replaced(doubles, Opcode.MATH) { it.copy(operand = operand) }))
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code, "MATH $operand")
        }
    }

    // ---- Kotlin で例外になるもの（空の範囲の coerceIn・NaN の roundToInt）は実行時エラー ----

    @Test
    fun kotlinExceptionsBecomeRuntimeErrors() {
        val cases = listOf(
            "println(5.coerceIn(3, 1))" to DiagnosticCode.RUN_EMPTY_COERCE_RANGE,
            "println(1.5.coerceIn(2.0, 1.0))" to DiagnosticCode.RUN_EMPTY_COERCE_RANGE,
            "println(1f.coerceIn(2f, -0f))" to DiagnosticCode.RUN_EMPTY_COERCE_RANGE,
            "println((0.0 / 0.0).roundToInt())" to DiagnosticCode.RUN_ROUND_NAN,
            "println((0f / 0f).roundToInt())" to DiagnosticCode.RUN_ROUND_NAN,
        )
        for ((line, code) in cases) {
            val vm = finish(CsVm(compile("println(\"before\")\n$line\nprintln(\"after\")")))
            assertEquals(VmState.ERROR, vm.state, line)
            assertEquals(code, vm.error?.code, line)
            assertEquals(2, vm.error?.position?.line, line)
            assertEquals(listOf("before"), vm.console.history, line)
        }
        // 0.0 と -0.0 は等しいため空の範囲ではない
        assertEquals(
            listOf(1.0.coerceIn(0.0, -0.0).toString(), (-1.0).coerceIn(0.0, -0.0).toString()),
            output("println(1.0.coerceIn(0.0, -0.0))\nprintln((-1.0).coerceIn(0.0, -0.0))"),
        )
    }

    // ---- 数学関数の結果（-0.0・NaN・Infinity）は保存・復元を通して変わらない。すべての境界で復元しても結果は同じ ----

    @Test
    fun mathResultsSurviveSnapshots() {
        val source = "var a = min(0.0, -0.0)\nvar b = sqrt(-1.0)\nvar c = abs(-2147483647 - 1)\nvar f = 2f.pow(0.5f)\nvar g = ceil(-0.5f)\n" +
            "val xs = mutableListOf(floor(-1.0 / 0.0), round(2.5))\nwaitTicks(3)\n" +
            "println(1.0 / a)\nprintln(b == b)\nprintln(c)\nprintln(f)\nprintln(1f / g)\nprintln(xs)\nprintln(f.roundToInt() + c.coerceIn(-1, 1))"
        val expected = listOf("-Infinity", "false", Int.MIN_VALUE.toString(), 2f.pow(0.5f).toString(), "-Infinity", listOf(Double.NEGATIVE_INFINITY, 2.0).toString(), (2f.pow(0.5f).roundToInt() - 1).toString())
        val program = compile(source)
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertEquals(Value.DoubleValue(Long.MIN_VALUE), snapshot.variables[0])
        assertEquals(Value.FloatValue(Int.MIN_VALUE), snapshot.variables[4])
        assertTrue((snapshot.variables[1] as Value.DoubleValue).value.isNaN())
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

    // ---- 無作為の式（関数の入れ子・境界値・型の混在）を Kotlin で計算した結果と比べる ----

    // Kotlin で計算した値（Int・Float・Double のどれか）と、同じ値を作る CreateScript の式
    private class Term(val source: String, val value: Any)

    private val intLeaves = listOf("0" to 0, "1" to 1, "(-1)" to -1, "7" to 7, "(-7)" to -7, "2147483647" to Int.MAX_VALUE, "(-2147483647 - 1)" to Int.MIN_VALUE, "100" to 100)
    private val floatLeaves = listOf(
        "0f" to 0f, "(-0f)" to -0f, "1.5f" to 1.5f, "(-2.5f)" to -2.5f, "0.1f" to 0.1f, "3e38f" to 3e38f, "(0f / 0f)" to zeroF / zeroF,
        "(1f / 0f)" to 1f / zeroF, "(-1f / 0f)" to -1f / zeroF, "1e-40f" to 1e-40f, "0.5f" to 0.5f,
    )
    private val doubleLeaves = listOf(
        "0.0" to 0.0, "(-0.0)" to -0.0, "2.5" to 2.5, "(-2.5)" to -2.5, "0.1" to 0.1, "1e300" to 1e300, "(0.0 / 0.0)" to zeroD / zeroD,
        "(1.0 / 0.0)" to 1.0 / zeroD, "(-1.0 / 0.0)" to -1.0 / zeroD, "4.9e-324" to 4.9e-324, "0.5" to 0.5, "(-0.5)" to -0.5,
    )

    private fun leaf(random: Random, kind: Int): Term = when (kind) {
        NumericKind.INT -> intLeaves.random(random).let { Term(it.first, it.second) }
        NumericKind.FLOAT -> floatLeaves.random(random).let { Term(it.first, it.second) }
        else -> doubleLeaves.random(random).let { Term(it.first, it.second) }
    }

    private fun lessThan(left: Any, right: Any) = when (left) {
        is Int -> left < right as Int
        is Float -> left < right as Float
        else -> (left as Double) < right as Double
    }

    private fun term(random: Random, kind: Int, depth: Int): Term {
        if (depth == 0 || random.nextInt(5) < 2) return leaf(random, kind)
        fun sub(k: Int = kind) = term(random, k, depth - 1)
        val choices = if (kind == NumericKind.INT) 5 else 9
        return when (random.nextInt(choices)) {
            0 -> sub().let { x -> Term("abs(${x.source})", unary(x.value, { abs(it) }, { abs(it) }, { abs(it) })) }
            1 -> Pair(sub(), sub()).let { (a, b) -> Term("min(${a.source}, ${b.source})", binary(a.value, b.value, { x, y -> min(x, y) }, { x, y -> min(x, y) }, { x, y -> min(x, y) })) }
            2 -> Pair(sub(), sub()).let { (a, b) -> Term("max(${a.source}, ${b.source})", binary(a.value, b.value, { x, y -> max(x, y) }, { x, y -> max(x, y) }, { x, y -> max(x, y) })) }
            3 -> {
                // 範囲が空にならないよう、小さいほうを最小値にする（NaN を含む範囲はそのまま）
                val x = sub()
                var low = sub()
                var high = sub()
                if (lessThan(high.value, low.value)) low = high.also { high = low }
                Term("(${x.source}).coerceIn(${low.source}, ${high.source})", coerce(x.value, low.value, high.value))
            }
            4 -> {
                if (kind != NumericKind.INT) return sub().let { x -> Term("sqrt(${x.source})", unary(x.value, { it }, { sqrt(it) }, { sqrt(it) })) }
                val x = sub(if (random.nextBoolean()) NumericKind.FLOAT else NumericKind.DOUBLE)
                val value = x.value
                // NaN の roundToInt は実行時エラーのため除く
                if ((value is Float && value.isNaN()) || (value is Double && value.isNaN())) return leaf(random, kind)
                Term("(${x.source}).roundToInt()", if (value is Float) value.roundToInt() else (value as Double).roundToInt())
            }
            5 -> sub().let { x -> Term("floor(${x.source})", unary(x.value, { it }, { floor(it) }, { floor(it) })) }
            6 -> sub().let { x -> Term("ceil(${x.source})", unary(x.value, { it }, { ceil(it) }, { ceil(it) })) }
            7 -> sub().let { x -> Term("round(${x.source})", unary(x.value, { it }, { round(it) }, { round(it) })) }
            else -> {
                val base = sub()
                val exponent = sub(if (random.nextBoolean()) NumericKind.INT else kind)
                val value = when (val e = exponent.value) {
                    is Int -> if (base.value is Float) (base.value as Float).pow(e) else (base.value as Double).pow(e)
                    is Float -> (base.value as Float).pow(e)
                    else -> (base.value as Double).pow(e as Double)
                }
                Term("(${base.source}).pow(${exponent.source})", value)
            }
        }
    }

    private fun unary(value: Any, int: (Int) -> Int, float: (Float) -> Float, double: (Double) -> Double): Any = when (value) {
        is Int -> int(value)
        is Float -> float(value)
        else -> double(value as Double)
    }

    private fun binary(left: Any, right: Any, int: (Int, Int) -> Int, float: (Float, Float) -> Float, double: (Double, Double) -> Double): Any = when (left) {
        is Int -> int(left, right as Int)
        is Float -> float(left, right as Float)
        else -> double(left as Double, right as Double)
    }

    private fun coerce(value: Any, low: Any, high: Any): Any = when (value) {
        is Int -> value.coerceIn(low as Int, high as Int)
        is Float -> value.coerceIn(low as Float, high as Float)
        else -> (value as Double).coerceIn(low as Double, high as Double)
    }

    @Test
    fun randomExpressionsMatchKotlin() {
        val random = Random(3_11_2)
        val cases = List(600) { index -> term(random, index % NumericKind.COUNT, 3).let { it.source to it.value } }
        assertTrue(cases.count { it.first.contains("pow") } > 50)
        assertPrints(cases)
    }

    // ---- 予算と公平性: MATH も 1 命令 = 1 単位で、FAST 128 命令 / tick・全体 4096 命令 / tick を守る ----

    private val loops = listOf(
        "var d = 2.0\nwhile (true) { d = sqrt(d) + 1.0 }",
        "var f = 1.5f\nvar n = 0\nwhile (true) {\n  f = f.pow(n % 5).coerceIn(-1e30f, 1e30f) + 0.5f\n  n = (n + 1) % 100\n}",
        "var d = 0.1\nwhile (true) {\n  d = max(min(d * 3.0, 1e6), floor(d) + round(d) - ceil(d) + abs(d)) % 1000.0 + 0.1\n  println(d.roundToInt())\n}",
        "var n = 1\nwhile (true) { n = abs(n * 7 - 3).coerceIn(1, 100000) }",
    )

    @Test
    fun mathProgramsStayWithinBudgetAndFair() {
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
        // 1 回の MATH は 1 命令: 引数を積む命令 + MATH + 変数への代入
        val vm = CsVm(compile("val a = 2.5\nval b = max(a, 1.0)\nprintln(b)"))
        assertEquals(2, vm.run(2))
        assertEquals(4, vm.run(4))
        assertEquals(listOf(Value.DoubleValue.of(2.5), Value.DoubleValue.of(2.5)), vm.snapshot()!!.variables)
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
    fun measureMathFunctions() {
        // 命令の大半が pow になるループ（Math.pow は最も重い関数）
        val powHeavy = "var d = 1.0001\nwhile (true) { d = d.pow(1.0001).pow(0.9999).pow(1.0001).pow(0.9999) % 10.0 + 1.0001 }"
        val int = "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }"
        val labels = listOf("sqrt", "Float pow + coerceIn", "min/max/floor/round/ceil/abs + println", "Int abs + coerceIn")
        for ((label, source) in listOf("Int arithmetic" to int, "pow heavy" to powHeavy) + labels.zip(loops)) {
            println("[3.11b measure] per instruction, $label: %.1f ns".format(nanosPerInstruction(List(20) { CsVm(compile(source)) })))
        }
        for ((label, source) in listOf("1000 VMs pow heavy" to powHeavy, "1000 VMs min/max/floor/round" to loops[2], "1000 VMs Int arithmetic" to int)) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program).also { machine -> machine.run(3000) }) }
            println("[3.11b measure] $label: ${perTick(scheduler)}")
        }
    }
}
