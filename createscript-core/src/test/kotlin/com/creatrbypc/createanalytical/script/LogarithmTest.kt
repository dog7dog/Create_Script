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
import com.creatrbypc.createanalytical.script.vm.MathResult
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
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// 第3.11d段階の ln・log10・exp・hypot・sign と x.isNaN()・x.isInfinite() を、意味解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。
// 期待値は同じ式を Kotlin で計算した値（どちらも java.lang.Math などを使うため、同じ JVM の上では一致する）
class LogarithmTest {
    // Kotlin 側の期待値で NaN・Infinity を作るための 0（定数の 0 除算の警告を避ける）
    private val zeroD = 0.0
    private val zeroF = 0f
    private val nan = zeroD / zeroD
    private val inf = 1.0 / zeroD
    private val nanF = zeroF / zeroF
    private val infF = 1f / zeroF

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

    // 式ごとに println し、Kotlin で計算した値の表示と比べる（コンソールは 32 行のため分けて実行する）。
    // Float・Double の表示は値を一意に表す最短の桁（JDK 19 以降）のため、ビットの違い（-0.0・非正規化数を含む）も表示の違いになる
    private fun assertPrints(cases: List<Pair<String, Any>>) {
        for (chunk in cases.chunked(30)) {
            assertEquals(chunk.map { it.second.toString() }, output(chunk.joinToString("\n") { "println(${it.first})" }), chunk.joinToString { it.first })
        }
    }

    // ---- 各関数の結果（NaN・±Infinity・±0.0・負数の対数を含む）は Kotlin と同じ ----

    @Test
    fun functionsMatchKotlin() {
        assertPrints(
            listOf(
                "ln(1.0)" to ln(1.0), "ln(E)" to ln(E), "ln(10.0)" to ln(10.0), "ln(0.5)" to ln(0.5), "ln(0.0)" to ln(0.0), "ln(-0.0)" to ln(-0.0),
                "ln(-1.0)" to ln(-1.0), "ln(1.0 / 0.0)" to ln(inf), "ln(-1.0 / 0.0)" to ln(-inf), "ln(0.0 / 0.0)" to ln(nan),
                "log10(1000.0)" to log10(1000.0), "log10(1e-5)" to log10(1e-5), "log10(2.0)" to log10(2.0), "log10(0.0)" to log10(0.0), "log10(-0.0)" to log10(-0.0),
                "log10(-10.0)" to log10(-10.0), "log10(1.0 / 0.0)" to log10(inf), "log10(0.0 / 0.0)" to log10(nan), "log10(1.0)" to log10(1.0),
                "exp(0.0)" to exp(0.0), "exp(-0.0)" to exp(-0.0), "exp(1.0)" to exp(1.0), "exp(-1.0)" to exp(-1.0), "exp(10.0)" to exp(10.0),
                "exp(1.0 / 0.0)" to exp(inf), "exp(-1.0 / 0.0)" to exp(-inf), "exp(0.0 / 0.0)" to exp(nan), "exp(ln(5.0))" to exp(ln(5.0)),
                "sign(2.5)" to sign(2.5), "sign(-2.5)" to sign(-2.5), "sign(0.0)" to sign(0.0), "sign(-0.0)" to sign(-0.0), "sign(0.0 / 0.0)" to sign(nan),
                "sign(1.0 / 0.0)" to sign(inf), "sign(-1.0 / 0.0)" to sign(-inf), "sign(4.9e-324)" to sign(4.9e-324), "sign(-4.9e-324)" to sign(-4.9e-324),
                "hypot(3.0, 4.0)" to hypot(3.0, 4.0), "hypot(-3.0, -4.0)" to hypot(-3.0, -4.0), "hypot(1.0, 1.0)" to hypot(1.0, 1.0), "hypot(5.0, 12.0)" to hypot(5.0, 12.0),
                "ln(2f)" to ln(2f), "ln(0f)" to ln(0f), "ln(-0f)" to ln(-0f), "ln(-1f)" to ln(-1f), "ln(1f / 0f)" to ln(infF), "ln(0f / 0f)" to ln(nanF),
                "log10(0.1f)" to log10(0.1f), "log10(10f)" to log10(10f), "log10(2f)" to log10(2f), "log10(-0f)" to log10(-0f),
                "exp(1f)" to exp(1f), "exp(-1f / 0f)" to exp(-infF), "exp(2f)" to exp(2f), "exp(0f / 0f)" to exp(nanF),
                "sign(-0f)" to sign(-0f), "sign(3f)" to sign(3f), "sign(-3f)" to sign(-3f), "sign(0f / 0f)" to sign(nanF), "sign(-1f / 0f)" to sign(-infF),
                "hypot(3f, 4f)" to hypot(3f, 4f), "hypot(0.1f, 0.2f)" to hypot(0.1f, 0.2f), "hypot(1f / 0f, 0f / 0f)" to hypot(infF, nanF),
            ),
        )
        // -0.0 の符号のビットは値として残る（表示だけでなく 1.0 / x で確かめる）
        assertEquals(
            listOf("-Infinity", "-Infinity", "Infinity", "Infinity"),
            output("println(1.0 / sign(-0.0))\nprintln(1f / sign(-0f))\nprintln(1.0 / hypot(-0.0, -0.0))\nprintln(1f / hypot(-0f, 0f))"),
        )
        // 最下位の桁は CPU・JVM で変わることがあるため、固定の文字列ではなく、同じ JVM で Kotlin が計算した値と比べる
        assertPrints(listOf("E" to E, "ln(1000.0) / ln(10.0)" to ln(1000.0) / ln(10.0)))
    }

    // ---- 境界: exp のオーバーフロー・アンダーフロー（非正規化数）、非正規化数・最大値の対数、hypot の途中のオーバーフロー・アンダーフロー ----

    @Test
    fun boundariesMatchKotlin() {
        assertPrints(
            listOf(
                "exp(709.78)" to exp(709.78), "exp(709.79)" to exp(709.79), "exp(710.0)" to exp(710.0), "exp(-708.0)" to exp(-708.0),
                "exp(-740.0)" to exp(-740.0), "exp(-745.0)" to exp(-745.0), "exp(-746.0)" to exp(-746.0), "exp(1e308)" to exp(1e308), "exp(-1e308)" to exp(-1e308),
                "ln(4.9e-324)" to ln(4.9e-324), "ln(1e-310)" to ln(1e-310), "ln(2.2250738585072014E-308)" to ln(2.2250738585072014E-308), "ln(1.7976931348623157E308)" to ln(Double.MAX_VALUE),
                "log10(4.9e-324)" to log10(4.9e-324), "log10(1e-320)" to log10(1e-320), "log10(1e308)" to log10(1e308), "log10(1.7976931348623157E308)" to log10(Double.MAX_VALUE),
                "hypot(1e308, 1e308)" to hypot(1e308, 1e308), "hypot(1.7976931348623157E308, 1.0)" to hypot(Double.MAX_VALUE, 1.0),
                "hypot(1.7976931348623157E308, 1.7976931348623157E308)" to hypot(Double.MAX_VALUE, Double.MAX_VALUE),
                "hypot(1e-320, 1e-320)" to hypot(1e-320, 1e-320), "hypot(4.9e-324, 4.9e-324)" to hypot(4.9e-324, 4.9e-324), "hypot(1e-200, 1e-200)" to hypot(1e-200, 1e-200),
                "exp(88f)" to exp(88f), "exp(88.7f)" to exp(88.7f), "exp(89f)" to exp(89f), "exp(-87f)" to exp(-87f), "exp(-100f)" to exp(-100f),
                "exp(-103f)" to exp(-103f), "exp(-104f)" to exp(-104f), "ln(1.4e-45f)" to ln(1.4e-45f), "ln(1e-40f)" to ln(1e-40f), "ln(3.4028235e38f)" to ln(Float.MAX_VALUE),
                "log10(1.4e-45f)" to log10(1.4e-45f), "log10(3e38f)" to log10(3e38f), "hypot(3e38f, 3e38f)" to hypot(3e38f, 3e38f),
                "hypot(1.4e-45f, 1.4e-45f)" to hypot(1.4e-45f, 1.4e-45f), "hypot(1e-30f, 1e-30f)" to hypot(1e-30f, 1e-30f),
                "sign(1e-40f)" to sign(1e-40f), "sign(-1.4e-45f)" to sign(-1.4e-45f),
            ),
        )
        // 単純な sqrt(x * x + y * y) は途中でオーバーフロー・アンダーフローするが、hypot はしない
        assertEquals(listOf("Infinity", hypot(1e308, 1e308).toString(), "0.0", hypot(1e-200, 1e-200).toString()), output("println(sqrt(1e308 * 1e308 + 1e308 * 1e308))\nprintln(hypot(1e308, 1e308))\nprintln(sqrt(1e-200 * 1e-200 + 1e-200 * 1e-200))\nprintln(hypot(1e-200, 1e-200))"))
    }

    // ---- hypot(x, y): 符号・軸・±0・±Infinity と NaN の組み合わせ（どちらかが無限大なら NaN があっても Infinity） ----

    @Test
    fun hypotCoversSpecialValues() {
        val doubles = listOf(
            "1.0" to 1.0, "(-3.0)" to -3.0, "0.0" to 0.0, "(-0.0)" to -0.0, "(1.0 / 0.0)" to inf, "(-1.0 / 0.0)" to -inf, "(0.0 / 0.0)" to nan, "4.9e-324" to 4.9e-324,
        )
        val cases = ArrayList<Pair<String, Any>>()
        for ((x, xv) in doubles) for ((y, yv) in doubles) cases.add("hypot($x, $y)" to hypot(xv, yv))
        val floats = listOf("1f" to 1f, "(-4f)" to -4f, "(-0f)" to -0f, "(-1f / 0f)" to -infF, "(0f / 0f)" to nanF)
        for ((x, xv) in floats) for ((y, yv) in floats) cases.add("hypot($x, $y)" to hypot(xv, yv))
        assertPrints(cases)
        assertEquals(listOf("Infinity", "Infinity", "NaN", "NaN"), output("println(hypot(1.0 / 0.0, 0.0 / 0.0))\nprintln(hypot(0.0 / 0.0, -1.0 / 0.0))\nprintln(hypot(0.0 / 0.0, 1.0))\nprintln(hypot(0f / 0f, 0f))"))
    }

    // ---- isNaN()・isInfinite() の結果は Boolean で、println・if・while・&&・||・!・==・変数・引数・戻り値・プロパティ・リストに使える ----

    @Test
    fun booleanResultsWorkEverywhere() {
        val values = listOf(
            "0.0" to 0.0, "(-0.0)" to -0.0, "1.5" to 1.5, "(1.0 / 0.0)" to inf, "(-1.0 / 0.0)" to -inf, "(0.0 / 0.0)" to nan, "4.9e-324" to 4.9e-324,
            "1.7976931348623157E308" to Double.MAX_VALUE, "PI" to PI, "ln(-1.0)" to ln(-1.0), "exp(710.0)" to exp(710.0), "log10(0.0)" to log10(0.0),
        )
        val floats = listOf(
            "0f" to 0f, "(-0f)" to -0f, "(1f / 0f)" to infF, "(-1f / 0f)" to -infF, "(0f / 0f)" to nanF, "1.4e-45f" to 1.4e-45f, "3.4028235e38f" to Float.MAX_VALUE,
            "exp(89f)" to exp(89f), "ln(-1f)" to ln(-1f), "(3e38f * 10f)" to 3e38f * 10f,
        )
        val cases = ArrayList<Pair<String, Any>>()
        for ((source, value) in values) cases += listOf("$source.isNaN()" to value.isNaN(), "$source.isInfinite()" to value.isInfinite())
        for ((source, value) in floats) cases += listOf("$source.isNaN()" to value.isNaN(), "$source.isInfinite()" to value.isInfinite())
        assertPrints(cases)
        assertEquals(ScriptType.BOOLEAN, typeOf("val b = 1.5.isNaN()", "b"))
        assertEquals(ScriptType.BOOLEAN, typeOf("val b = 1f.isInfinite()", "b"))
        val source = "val n = 0.0 / 0.0\nval i = -1.0 / 0.0\nval f = 2.5f\n" +
            "if (n.isNaN()) { println(\"nan\") } else { println(\"number\") }\n" +
            "if (!f.isNaN() && !f.isInfinite()) { println(\"finite\") }\n" +
            "println(n.isNaN() && i.isInfinite())\nprintln(f.isNaN() || f.isInfinite())\nprintln(!i.isInfinite())\n" +
            "println(n.isNaN() == i.isInfinite())\nprintln(n.isNaN() != f.isNaN())\nprintln(n == n)\n" +
            "var b: Boolean = ln(-1.0).isNaN()\nprintln(b)\nb = exp(1000.0).isInfinite() && !exp(1000.0).isNaN()\nprintln(b)\n" +
            "fun bad(x: Double): Boolean { return x.isNaN() || x.isInfinite() }\nprintln(bad(1.0 / 0.0))\nprintln(bad(-0.0))\n" +
            "class Probe {\n  var hit = (0f / 0f).isNaN()\n  fun check(x: Float): Boolean { return x.isInfinite() == hit }\n}\nval p = Probe()\nprintln(p.hit)\nprintln(p.check(1f / 0f))\n" +
            "val flags = mutableListOf(n.isNaN(), f.isNaN(), i.isInfinite())\nprintln(flags)\n" +
            "var x = 1.0\nvar count = 0\nwhile (!x.isInfinite()) {\n  x = x * 1e100\n  count = count + 1\n}\nprintln(count)\n" +
            "println(sign(i).isNaN())\nprintln(hypot(i, n).isInfinite())"
        assertEquals(
            listOf("nan", "finite", "true", "false", "false", "true", "true", "false", "true", "true", "true", "false", "true", "true", "[true, false, true]", "4", "false", "true"),
            output(source),
        )
    }

    // ---- 型の規則と診断。Int からの暗黙の変換はしない（Kotlin と同じ）。既存の診断コードを使う ----

    @Test
    fun typesAndDiagnostics() {
        val types = listOf(
            "ln(1.5)" to ScriptType.DOUBLE, "ln(1.5f)" to ScriptType.FLOAT, "log10(2.0)" to ScriptType.DOUBLE, "log10(2f)" to ScriptType.FLOAT,
            "exp(1.0)" to ScriptType.DOUBLE, "exp(1f)" to ScriptType.FLOAT, "hypot(3.0, 4.0)" to ScriptType.DOUBLE, "hypot(3f, 4f)" to ScriptType.FLOAT,
            "sign(-2.0)" to ScriptType.DOUBLE, "sign(-2f)" to ScriptType.FLOAT, "PI.isNaN()" to ScriptType.BOOLEAN, "2f.isInfinite()" to ScriptType.BOOLEAN,
        )
        for ((expression, type) in types) assertEquals(type, typeOf("val v = $expression", "v"), expression)
        val errors = listOf(
            "println(ln(1))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(log10(10))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(exp(1))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(sign(-1))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(hypot(3, 4))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(ln(true))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(exp(\"a\"))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(hypot(3.0, 4f))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(hypot(3f, 4.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(hypot(3.0, 4))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(ln())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(exp(1.0, 2.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(hypot(3.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(sign())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.5.isNaN(1))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.5.isInfinite(2.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.isNaN())" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "println(1.isInfinite())" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "println(1.5.isNaN)" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(1.5.sign)" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(1.5.ln())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(1.5.isFinite())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(true.isNaN())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(\"a\".isNaN())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(isNaN(1.5))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "println(isInfinite(1.5))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "println(log2(8.0))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "println(log(8.0, 2.0))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "println(ln1p(1.0))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "println(expm1(1.0))" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "val d: Double = 1.5.isNaN()" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val b: Boolean = ln(2.0)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "if (ln(2.0)) { println(1) }" to DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN,
            "println(ln(1.5.isNaN()))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(1.5.isNaN().isNaN())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(ln<Double>(1.0))" to DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
        // 位置: 型の誤りは引数、メソッドの誤りはメソッドの名前
        val positions = listOf("println(ln(1))" to 12, "println(hypot(1.0, 1f))" to 20, "println(1.isNaN())" to 11, "println(1.5.isNaN(1))" to 13)
        for ((source, column) in positions) assertEquals(column, Compiler.compileSource(source).diagnostics.single().position.column, source)
    }

    // ---- 名前は予約しない。同じ名前の変数・引数・プロパティ・関数・メソッド・クラスが優先する ----

    // 第3.11c段階のビルド（対数などの無い jar）でコンパイルしたときの指紋。関数を追加しても、使わないプログラムの命令は変わらない
    private val legacyFingerprints = listOf(
        "val ln = 3\nvar exp = ln * 2\nexp = 4\nprintln(exp + ln)" to "f9378fbbff01ccdf0d400c939f55b7cd30e7959cf63d0dd7b124ca6dbe80d866",
        "fun sign(n: Int): String { if (n < 0) { return \"neg\" }\n  return \"pos\" }\nfun hypot(x: Int, y: Int): Int { return x + y }\nprintln(sign(-5))\nprintln(hypot(3, 4))" to
            "9ec506d7aca8a3b6c6e689b3b07fe6cd40c630405e4af0e5c7f2273f0a6aeb33",
        "class Num {\n  var log10 = 1.5\n  fun isNaN(): Boolean { return log10 != log10 }\n  fun isInfinite(): Boolean { return false }\n}\nval n = Num()\nprintln(n.isNaN())\nprintln(n.isInfinite())\nprintln(n.log10)" to
            "2e12a310a9542a22f615ee07f3c5d56afc00d2fc599d1d4f74b92cdf94e784a8",
        "class exp\nval e = exp()\nprintln(e)" to "1874bf10a9bbac6f6531fb6fff7251becf83a488daae2bde9f365974dbb9b2c6",
        "fun g(ln: Double, log10: Float): Double { return ln * log10 }\nfor (sign in 0..3) { println(g(sign.toDouble(), 1.5f)) }" to
            "67b9bb436062118f5496e29a3f9d996d0a5a24728fd38c13ab5ec1372a1ea5f2",
        "val x = 0.0 / 0.0\nprintln(x != x)\nprintln(sqrt(2.0) + abs(-1.5))\nprintln(atan2(1.0, -1.0) + sin(PI))\nprintln(2.0.pow(3).roundToInt().coerceIn(0, 5))" to
            "fc4306e5b64a350bb88d84914ebbe40e02855d57c28040239e7644a6cf596eb4",
        "var hypot = 0.0\nfor (i in 0 until 3) { hypot = hypot + i * 0.5 }\nprintln(max(hypot, 1.0) > E)\nwaitTicks(5)\nprintln(cosh(floor(hypot)))" to
            "5e8e6fc93dad3ad72ffa1080630f087ebac20d22f2796b169ca62e0e6acdc84b",
    )

    @Test
    fun namesAreNotReserved() {
        for ((source, hash) in legacyFingerprints) {
            val program = compile(source)
            assertTrue(program.instructions.none { it.opcode == Opcode.MATH && it.operand >= MathFunction.LN.operand(NumericKind.INT) }, source)
            assertEquals(hash, ProgramFingerprint.ofProgram(program).joinToString("") { "%02x".format(it) }, source)
        }
        assertEquals(listOf("7", exp(ln(8.0)).toString()), output(legacyFingerprints[0].first + "\nprintln(exp(ln(8.0)))"))
        // 変数（val・var・引数・for の変数）とプロパティは名前の衝突にならず、関数の呼び出しは数学関数のまま
        assertEquals(listOf("3", ln(2.0).toString()), output("val ln = 3\nprintln(ln)\nprintln(ln(2.0))"))
        assertEquals(listOf("4", hypot(3.0, 4.0).toString()), output("var hypot = 3\nhypot = 4\nprintln(hypot)\nprintln(hypot(3.0, 4.0))"))
        assertEquals(listOf("2.5", "0", "1", exp(1.0).toString()), output("fun f(exp: Double): Double { return exp }\nprintln(f(2.5))\nfor (exp in 0..1) { println(exp) }\nprintln(exp(1.0))"))
        // 関数・メソッド・クラスは数学関数より優先する（同じ名前の関数があると、数学関数は呼べない）
        assertEquals(listOf("neg", "pos", log10(100.0).toString()), output(legacyFingerprints[1].first.replace("println(hypot(3, 4))", "println(sign(1))\nprintln(log10(100.0))")))
        assertEquals(listOf("7", ln(1.0).toString()), output("class M {\n  fun ln(a: Int): Int { return a + 6 }\n  fun f(): Int { return ln(1) }\n}\nprintln(M().f())\nprintln(ln(1.0))"))
        assertEquals(listOf("exp@1", "1.0"), output("class exp\nprintln(exp())\nprintln(sign(2.0))"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("fun sign(x: Int): Int { return x }\nprintln(sign(1.5))"))
        // ユーザー定義のクラスの isNaN・isInfinite は、そのクラスのメソッドとして呼ばれる（数値のメソッドとは関係が無い）
        assertEquals(listOf("false", "false", "1.5", "true"), output(legacyFingerprints[2].first + "\nprintln(n.log10.isNaN() == n.isNaN())"))
    }

    // ---- 命令: どの関数も MATH 1 つ（番号 20〜26。Float は Float のまま）。isNaN・isInfinite の結果は Boolean ----

    @Test
    fun instructionsUseMath() {
        fun numeric(source: String) = compile(source).instructions.filter { it.opcode == Opcode.MATH || it.opcode == Opcode.CONVERT }.map { it.opcode to it.operand }
        val added = listOf(MathFunction.LN, MathFunction.LOG10, MathFunction.EXP, MathFunction.HYPOT, MathFunction.SIGN, MathFunction.IS_NAN, MathFunction.IS_INFINITE)
        assertEquals((20..26).toList(), added.map { it.id })
        assertEquals((0..19).toList(), MathFunction.entries.take(20).map { it.id })
        // 第3.11e段階で番号 27（isClose）が加わったため、番号 20〜26 に限って確かめる
        assertEquals(added, MathFunction.entries.drop(20).take(7))
        assertEquals(listOf(MathFunction.ROUND_TO_INT), MathFunction.entries.filter { it.result == MathResult.INT })
        assertEquals(listOf(MathFunction.IS_NAN, MathFunction.IS_INFINITE), MathFunction.entries.filter { it.result == MathResult.BOOLEAN && it.id in 20..26 })
        assertEquals(listOf(2, 2, 1, 1), listOf(MathFunction.HYPOT, MathFunction.ATAN2, MathFunction.IS_NAN, MathFunction.SIGN).map { it.arity })
        assertEquals(
            listOf(
                Opcode.MATH to MathFunction.LN.operand(NumericKind.DOUBLE), Opcode.MATH to MathFunction.LOG10.operand(NumericKind.FLOAT), Opcode.MATH to MathFunction.EXP.operand(NumericKind.DOUBLE),
                Opcode.MATH to MathFunction.HYPOT.operand(NumericKind.FLOAT), Opcode.MATH to MathFunction.SIGN.operand(NumericKind.DOUBLE),
                Opcode.MATH to MathFunction.IS_NAN.operand(NumericKind.FLOAT), Opcode.MATH to MathFunction.IS_INFINITE.operand(NumericKind.DOUBLE),
            ),
            numeric("val a = ln(1.5)\nval b = log10(2f)\nval c = exp(1.0)\nval d = hypot(3f, 4f)\nval e = sign(-1.0)\nval f = 1f.isNaN()\nval g = 2.0.isInfinite()"),
        )
        assertEquals((60..80).filter { it % NumericKind.COUNT != NumericKind.INT }, (0..90).filter { MathFunction.byOperand(it)?.id in 20..26 })
        // isNaN の後は Boolean として扱われる（JUMP_IF_FALSE・NOT・EQUAL の operand 0）
        val program = compile("val x = 1.5\nif (!x.isNaN()) { println(x.isInfinite() == false) }")
        assertEquals(
            listOf(Opcode.PUSH_CONST, Opcode.STORE, Opcode.LOAD, Opcode.MATH, Opcode.NOT, Opcode.JUMP_IF_FALSE, Opcode.LOAD, Opcode.MATH, Opcode.PUSH_BOOL, Opcode.EQUAL, Opcode.CALL_HOST, Opcode.POP),
            program.instructions.take(12).map { it.opcode },
        )
        val frames = SnapshotVerifier.framesOf(program)!!
        assertEquals(listOf(SnapshotVerifier.SlotType.BOOLEAN), frames[4]!!.stack)
        assertEquals(listOf(SnapshotVerifier.SlotType.BOOLEAN), frames[8]!!.stack)
    }

    // ---- Verifier と VM: 不正な MATH operand・型の合わない値（Boolean の結果を数値として使うものを含む）を拒否する ----

    private fun replaced(program: CompiledProgram, opcode: Opcode, replacement: (Instruction) -> Instruction): CompiledProgram {
        val index = program.instructions.indexOfFirst { it.opcode == opcode }
        check(index >= 0)
        return program.copy(instructions = program.instructions.mapIndexed { i, instruction -> if (i == index) replacement(instruction) else instruction })
    }

    @Test
    fun invalidBytecodeIsRejected() {
        val doubles = compile("var d = 1.5\nd = ln(d)\nprintln(d)")
        assertNotNull(SnapshotVerifier.framesOf(doubles))
        val unknown = MathFunction.entries.size * NumericKind.COUNT
        val invalidOperands = listOf(
            -1, unknown, unknown + 1, unknown + 2, Int.MAX_VALUE, Int.MIN_VALUE,
            // Int 版は無い（番号 20〜26 の Int の operand 60・63・…・78）
            MathFunction.LN.operand(NumericKind.INT), MathFunction.LOG10.operand(NumericKind.INT), MathFunction.EXP.operand(NumericKind.INT), MathFunction.HYPOT.operand(NumericKind.INT),
            MathFunction.SIGN.operand(NumericKind.INT), MathFunction.IS_NAN.operand(NumericKind.INT), MathFunction.IS_INFINITE.operand(NumericKind.INT),
            // 型が違う（Double の値に Float の関数）・引数が足りない（スタックに 1 つしかない）
            MathFunction.EXP.operand(NumericKind.FLOAT), MathFunction.IS_NAN.operand(NumericKind.FLOAT), MathFunction.HYPOT.operand(NumericKind.DOUBLE),
        )
        assertEquals(listOf(60, 63, 66, 69, 72, 75, 78), invalidOperands.subList(6, 13))
        for (operand in invalidOperands) assertNull(SnapshotVerifier.framesOf(replaced(doubles, Opcode.MATH) { it.copy(operand = operand) }), "MATH $operand")
        for (valid in listOf(MathFunction.LOG10, MathFunction.EXP, MathFunction.SIGN, MathFunction.SIN, MathFunction.SQRT)) {
            assertNotNull(SnapshotVerifier.framesOf(replaced(doubles, Opcode.MATH) { it.copy(operand = valid.operand(NumericKind.DOUBLE)) }), "$valid")
        }
        // Boolean の結果を数値の計算・数値の結果を条件に使うバイトコードは拒否する
        val arithmetic = compile("val d = 1.5\nprintln(ln(d) + 1.0)")
        val condition = compile("val d = 1.5\nif (d.isNaN()) { println(1) }")
        val isNaN = MathFunction.IS_NAN.operand(NumericKind.DOUBLE)
        val ln = MathFunction.LN.operand(NumericKind.DOUBLE)
        assertNotNull(SnapshotVerifier.framesOf(arithmetic))
        assertNotNull(SnapshotVerifier.framesOf(condition))
        assertNull(SnapshotVerifier.framesOf(replaced(arithmetic, Opcode.MATH) { it.copy(operand = isNaN) }))
        assertNull(SnapshotVerifier.framesOf(replaced(condition, Opcode.MATH) { it.copy(operand = ln) }))
        assertNull(SnapshotVerifier.framesOf(replaced(condition, Opcode.MATH) { it.copy(operand = MathFunction.ROUND_TO_INT.operand(NumericKind.DOUBLE)) }))
        // Verifier を通さずに実行しても、VM が実行時エラーで止める（数値を取り出す・Boolean を取り出すところで型を確かめる）
        val rejectedAtRuntime = listOf(
            doubles to -1, doubles to unknown + 2, doubles to MathFunction.LN.operand(NumericKind.INT), doubles to MathFunction.IS_INFINITE.operand(NumericKind.INT),
            doubles to MathFunction.EXP.operand(NumericKind.FLOAT), doubles to MathFunction.IS_NAN.operand(NumericKind.FLOAT), doubles to MathFunction.HYPOT.operand(NumericKind.DOUBLE),
            arithmetic to isNaN, condition to ln,
        )
        for ((program, operand) in rejectedAtRuntime) {
            val vm = finish(CsVm(replaced(program, Opcode.MATH) { it.copy(operand = operand) }))
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code, "MATH $operand")
        }
    }

    // ---- 結果（-0.0・NaN・Infinity・非正規化数・Boolean）は保存・復元を通して変わらない。すべての境界（スタックに Boolean がある間を含む）で復元しても結果は同じ ----

    @Test
    fun resultsSurviveSnapshots() {
        val source = "var a = sign(-0.0)\nvar b = ln(-1.0)\nvar c = exp(-745.0)\nvar f = ln(2f)\nvar g = sign(-0f)\nvar n = b.isNaN()\nvar i = exp(710.0).isInfinite()\n" +
            "val xs = mutableListOf(hypot(1e308, 1e308), log10(0.0), exp(1.0))\nwaitTicks(3)\n" +
            "println(1.0 / a)\nprintln(b == b)\nprintln(c)\nprintln(f)\nprintln(1f / g)\nprintln(n && i)\nprintln(xs)\nprintln(!xs[1].isInfinite() || xs[0].isNaN())\nprintln(hypot(f, 1f))"
        val expected = listOf(
            "-Infinity", "false", "4.9E-324", ln(2f).toString(), "-Infinity", "true", listOf(hypot(1e308, 1e308), Double.NEGATIVE_INFINITY, exp(1.0)).toString(), "false", hypot(ln(2f), 1f).toString(),
        )
        val program = compile(source)
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertEquals(Value.DoubleValue(Long.MIN_VALUE), snapshot.variables[0])
        assertTrue((snapshot.variables[1] as Value.DoubleValue).value.isNaN())
        assertEquals(Value.DoubleValue(1L), snapshot.variables[2])
        assertEquals(Value.FloatValue.of(ln(2f)), snapshot.variables[3])
        assertEquals(Value.FloatValue(Int.MIN_VALUE), snapshot.variables[4])
        assertEquals(listOf(Value.BoolValue(true), Value.BoolValue(true)), snapshot.variables.subList(5, 7))
        assertEquals(expected, finish(CsVm.restore(program, snapshot)!!).console.history)
        val original = CsVm(program)
        var booleanOnStack = 0
        while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
            val cut = original.snapshot()!!
            if (cut.stack.any { it is Value.BoolValue }) booleanOnStack++
            val restored = CsVm.restore(program, cut)
            assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, cut)}")
            assertEquals(cut, restored!!.snapshot())
            while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
            assertEquals(expected, restored.console.history)
            step(original)
        }
        assertEquals(expected, original.console.history)
        assertTrue(booleanOnStack >= 4, "boolean on stack at $booleanOnStack cuts")
    }

    // ---- 無作為の式（追加した関数と第3.11b・3.11c段階の関数の入れ子・境界値・定数）を Kotlin で計算した結果と比べる ----

    // Kotlin で計算した値（Float・Double・Boolean のどれか）と、同じ値を作る CreateScript の式
    private class Term(val source: String, val value: Any)

    private val floatLeaves = listOf(
        "0f" to 0f, "(-0f)" to -0f, "1.5f" to 1.5f, "(-2.5f)" to -2.5f, "0.1f" to 0.1f, "3e38f" to 3e38f, "(0f / 0f)" to nanF,
        "(1f / 0f)" to infF, "(-1f / 0f)" to -infF, "1e-40f" to 1e-40f, "88.5f" to 88.5f, "(-104f)" to -104f, "PI.toFloat()" to PI.toFloat(),
    )
    private val doubleLeaves = listOf(
        "0.0" to 0.0, "(-0.0)" to -0.0, "2.5" to 2.5, "(-2.5)" to -2.5, "0.1" to 0.1, "1e300" to 1e300, "(0.0 / 0.0)" to nan,
        "(1.0 / 0.0)" to inf, "(-1.0 / 0.0)" to -inf, "4.9e-324" to 4.9e-324, "709.5" to 709.5, "(-745.0)" to -745.0, "PI" to PI, "E" to E, "(-PI)" to -PI,
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
            Triple("ln", { ln(it) }, { ln(it) }), Triple("log10", { log10(it) }, { log10(it) }), Triple("exp", { exp(it) }, { exp(it) }),
            Triple("sign", { sign(it) }, { sign(it) }), Triple("sin", { sin(it) }, { sin(it) }), Triple("abs", { abs(it) }, { abs(it) }),
            Triple("sqrt", { sqrt(it) }, { sqrt(it) }),
        )
        return when (val choice = random.nextInt(unaries.size + 3)) {
            in unaries.indices -> unaries[choice].let { (name, f, d) -> sub().let { x -> Term("$name(${x.source})", unary(x.value, f, d)) } }
            unaries.size -> Pair(sub(), sub()).let { (x, y) -> Term("hypot(${x.source}, ${y.source})", binary(x.value, y.value, { a, b -> hypot(a, b) }, { a, b -> hypot(a, b) })) }
            unaries.size + 1 -> Pair(sub(), sub()).let { (y, x) -> Term("atan2(${y.source}, ${x.source})", binary(y.value, x.value, { a, b -> atan2(a, b) }, { a, b -> atan2(a, b) })) }
            else -> Pair(sub(), sub()).let { (a, b) -> Term("max(${a.source}, ${b.source})", binary(a.value, b.value, { p, q -> max(p, q) }, { p, q -> max(p, q) })) }
        }
    }

    // 3 つに 1 つは、式の結果を isNaN()・isInfinite() で Boolean にし、&& ・ || ・ ! でつなぐ
    private fun case(random: Random, index: Int): Pair<String, Any> {
        val float = index % 2 == 0
        if (index % 3 != 2) return term(random, float, 3).let { it.source to it.value }
        fun test(): Term {
            val x = term(random, float, 2)
            val nan = random.nextBoolean()
            val value = if (x.value is Float) (if (nan) x.value.isNaN() else x.value.isInfinite()) else (x.value as Double).let { if (nan) it.isNaN() else it.isInfinite() }
            return Term("(${x.source}).${if (nan) "isNaN" else "isInfinite"}()", value)
        }
        val (a, b) = test() to test()
        return when (random.nextInt(4)) {
            0 -> a.source to a.value
            1 -> "${a.source} && ${b.source}" to (a.value as Boolean && b.value as Boolean)
            2 -> "${a.source} || ${b.source}" to (a.value as Boolean || b.value as Boolean)
            else -> "!${a.source}" to !(a.value as Boolean)
        }
    }

    @Test
    fun randomExpressionsMatchKotlin() {
        val random = Random(3_11_4)
        val cases = List(600) { index -> case(random, index) }
        cases.count { it.first.contains("hypot") }.let { assertTrue(it > 30, "hypot in $it cases") }
        cases.count { it.second is Boolean }.let { assertTrue(it == 200, "boolean in $it cases") }
        cases.count { it.second == true }.let { assertTrue(it > 20, "true in $it cases") }
        assertPrints(cases)
    }

    // ---- 予算と公平性: 追加した関数も 1 命令 = 1 単位で、FAST 128 命令 / tick・全体 4096 命令 / tick を守る ----

    private val loops = listOf(
        "var d = 0.5\nwhile (true) { d = ln(exp(d) + 1.0) - log10(d + 1.0) }",
        "var f = 0.5f\nwhile (true) { f = hypot(f, sign(f)) - 1f }",
        "var d = 1.0\nwhile (true) {\n  d = exp(d * 100.0)\n  if (d.isInfinite() || d.isNaN()) { d = 1.0 }\n  println(d.isInfinite())\n}",
        "var d = 1e300\nwhile (true) { d = hypot(d, d) * 1e10 + ln(4.9e-324) }",
    )

    @Test
    fun programsStayWithinBudgetAndFair() {
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
        // 1 回の関数は 1 命令: 引数を積む命令 + MATH + 変数への代入（hypot は引数 2 つ、isNaN は受け手 1 つ）
        val vm = CsVm(compile("val a = ln(0.5)\nval b = hypot(3.0, 4.0)\nval c = a.isNaN()\nprintln(c)"))
        assertEquals(3, vm.run(3))
        assertEquals(Value.DoubleValue.of(ln(0.5)), vm.snapshot()!!.variables[0])
        assertEquals(4, vm.run(4))
        assertEquals(Value.DoubleValue.of(5.0), vm.snapshot()!!.variables[1])
        assertEquals(3, vm.run(3))
        assertEquals(Value.BoolValue(false), vm.snapshot()!!.variables[2])
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
    fun measureLogarithms() {
        val int = "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }"
        val labels = listOf("ln/exp/log10", "Float hypot/sign", "exp + isInfinite/isNaN + println", "hypot of huge arguments + ln of subnormal")
        for ((label, source) in listOf("Int arithmetic" to int) + labels.zip(loops)) {
            println("[3.11d measure] per instruction, $label: %.1f ns".format(nanosPerInstruction(List(20) { CsVm(compile(source)) })))
        }
        for ((label, source) in listOf("1000 VMs ln/exp/log10" to loops[0], "1000 VMs hypot of huge arguments" to loops[3], "1000 VMs Int arithmetic" to int)) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program).also { machine -> machine.run(3000) }) }
            println("[3.11d measure] $label: ${perTick(scheduler)}")
        }
    }
}
