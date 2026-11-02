package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.lexer.LexerLimits
import com.creatrbypc.createanalytical.script.lexer.TokenType
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.semantic.ScriptType
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.FieldInfo
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.NumericKind
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotProblem
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.SlotType
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// 第3.11a段階の Float・Double を、字句解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。期待値は同じ式を Kotlin で計算した値
class FloatingPointTest {
    // Kotlin 側の期待値で 0 除算（Infinity・NaN）を計算するための 0（定数の 0 除算の警告を避ける）
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

    private fun lexCodes(source: String) = Lexer.tokenize(source).diagnostics.map { it.code }

    private fun tokens(source: String) = Lexer.tokenize(source).tokens.dropLast(1)

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

    // ---- リテラル（F1・F2） ----

    @Test
    fun literalsAreLexed() {
        val doubles = listOf("1.5" to 1.5, "0.25" to 0.25, ".5" to 0.5, "1e3" to 1e3, "1.2e-4" to 1.2e-4, "1E+3" to 1E+3, "0.0" to 0.0, "0e5" to 0.0, "0.000" to 0.0,
            "4.9e-324" to 4.9e-324, "1.7976931348623157e308" to Double.MAX_VALUE, "2.2250738585072014E-308" to java.lang.Double.MIN_NORMAL)
        for ((source, value) in doubles) {
            val token = tokens(source).single()
            assertEquals(TokenType.DOUBLE_LITERAL, token.type, source)
            assertEquals(value.toRawBits(), token.doubleValue!!.toRawBits(), source)
        }
        val floats = listOf("1.0f" to 1.0f, "1.0F" to 1.0f, "1f" to 1f, "1e3f" to 1e3f, "0f" to 0f, "1.4e-45f" to 1.4e-45f, "3.4028235e38f" to Float.MAX_VALUE,
            // Float は Double を経由せずに丸める（1 + 2^-24 よりわずかに大きい値。Double を経由すると 1 + 2^-24 ちょうどになり、偶数への丸めで 1.0f になる）
            "1.00000005960464477539062500001f" to 1.0000001f, "0.1f" to 0.1f)
        for ((source, value) in floats) {
            val token = tokens(source).single()
            assertEquals(TokenType.FLOAT_LITERAL, token.type, source)
            assertEquals(value.toRawBits(), token.floatValue!!.toRawBits(), source)
        }
        assertEquals(1.0f, "1.00000005960464477539062500001".toDouble().toFloat())
        val rejected = mapOf(
            "1d" to DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT, "1.0L" to DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT, "1_0.5" to DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT,
            "0x1.8p3" to DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT, "1.5.5" to DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT, "1.5ff" to DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT,
            "1e" to DiagnosticCode.LEX_MALFORMED_EXPONENT, "1e+" to DiagnosticCode.LEX_MALFORMED_EXPONENT, "1.5e-x" to DiagnosticCode.LEX_MALFORMED_EXPONENT,
            "1ex" to DiagnosticCode.LEX_MALFORMED_EXPONENT, ".5e" to DiagnosticCode.LEX_MALFORMED_EXPONENT,
            "01.5" to DiagnosticCode.LEX_LEADING_ZERO, "00.5" to DiagnosticCode.LEX_LEADING_ZERO, "01e3" to DiagnosticCode.LEX_LEADING_ZERO, "01f" to DiagnosticCode.LEX_LEADING_ZERO,
            "1e400" to DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE, "1e-400" to DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE, "2e-324" to DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE,
            "3.5e38f" to DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE, "1e-50f" to DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE,
            "1." + "0".repeat(LexerLimits.MAX_FLOAT_LITERAL_LENGTH - 1) to DiagnosticCode.LEX_FLOAT_LITERAL_TOO_LONG,
        )
        for ((source, code) in rejected) assertEquals(listOf(code), lexCodes(source), source)
        // 上限ちょうどの長さは読める。長すぎる綴りの引数は長さと上限
        assertEquals(TokenType.DOUBLE_LITERAL, tokens("1." + "0".repeat(LexerLimits.MAX_FLOAT_LITERAL_LENGTH - 2)).single().type)
        // "1." は整数と "."（1.toDouble() に使える）。範囲演算子・メンバーアクセスの前の数値は整数のまま
        assertEquals(listOf(TokenType.INT_LITERAL, TokenType.DOT), tokens("1.").map { it.type })
        assertEquals(listOf(TokenType.INT_LITERAL, TokenType.RANGE, TokenType.INT_LITERAL), tokens("1..5").map { it.type })
        assertEquals(listOf(TokenType.DOUBLE_LITERAL, TokenType.RANGE, TokenType.DOUBLE_LITERAL), tokens("1.0..2.0").map { it.type })
        assertEquals(listOf(TokenType.INT_LITERAL, TokenType.DOT, TokenType.IDENTIFIER), tokens("1.toDouble").map { it.type })
        assertEquals(listOf(TokenType.DOUBLE_LITERAL, TokenType.DOT, TokenType.IDENTIFIER), tokens("1.5.toInt").map { it.type })
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_MEMBER_NAME), codes("val a = 1."))
        // 整数は今までどおり
        assertEquals(listOf(DiagnosticCode.LEX_INTEGER_TOO_MANY_DIGITS), lexCodes("12345678901"))
        assertEquals(listOf(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE), codes("val a = 2147483648"))
        assertEquals(listOf("-2147483648"), output("println(-2147483648)"))
    }

    // ---- 型（F3・F4・F8・F10） ----

    @Test
    fun typesArePromotedOnlyInArithmetic() {
        val source = "val a = 1.5\nval b = 1f\nval c = 1 + 0.5\nval d = 1 + 0.5f\nval e = 0.5f + 1.0\nval f = 1 < 1.5\nval g = -1.5f\nval h = 3 % 2.0\nval i = 1.5.toInt()\n" +
            "val j = 1.toFloat()\nval k = 2f.toDouble()\nval l = 1.0.toDouble()\nval m = listOf(1.0, 2.0)\nval n = mutableListOf(1f)\nval o = 7 / 2"
        val expected = mapOf("a" to ScriptType.DOUBLE, "b" to ScriptType.FLOAT, "c" to ScriptType.DOUBLE, "d" to ScriptType.FLOAT, "e" to ScriptType.DOUBLE, "f" to ScriptType.BOOLEAN,
            "g" to ScriptType.FLOAT, "h" to ScriptType.DOUBLE, "i" to ScriptType.INT, "j" to ScriptType.FLOAT, "k" to ScriptType.DOUBLE, "l" to ScriptType.DOUBLE,
            "m" to ScriptType.ListType(ScriptType.DOUBLE, false), "n" to ScriptType.ListType(ScriptType.FLOAT, true), "o" to ScriptType.INT)
        for ((name, type) in expected) assertEquals(type, typeOf(source, name), name)
        // 代入・引数・戻り値・プロパティ・リストの要素では暗黙に変換しない
        val mismatches = mapOf(
            "val d: Double = 1" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val f: Float = 1.0" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "var d = 1.0\nd = 1" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "fun g(d: Double) { }\ng(1)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "fun g(): Float { return 1.0 }" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "class P { var x: Double = 0f }" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val l = listOf(1, 1.0)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val l = mutableListOf<Double>(1)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val l = mutableListOf(1.0)\nl.add(1f)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val l: List<Double> = listOf(1f)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val l = listOf(1)\nprintln(l[1.0])" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "waitTicks(1.0)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "for (i in 0..1.0) { }" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "if (1.0) { }" to DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN,
            "println(!1.0)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(true + 1.5)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(1.5 < \"a\")" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(-true)" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            // 変換は () 付きの 3 つだけ。それ以外の数値のメンバーは他のクラス以外の値と同じく未対応
            "val x = 1.5\nprintln(x.toInt)" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(1.5.toLong())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(1.5.toInt(1))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(\"a\".toInt())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            // Float・Double は組み込みの型の名前（F10）
            "class Float" to DiagnosticCode.SEM_CLASS_NAME_CONFLICT,
            "class Double { }" to DiagnosticCode.SEM_CLASS_NAME_CONFLICT,
            "val x: Long = 1" to DiagnosticCode.SEM_UNKNOWN_TYPE,
        )
        for ((program, code) in mismatches) assertEquals(listOf(code), codes(program), program)
        // 変数名としては使える（Int と同じ）
        assertEquals(listOf("1.5"), output("val Double = 1.5\nprintln(Double)"))
        // 関数・プロパティ・戻り値・入れ子のリストで使える
        assertEquals(
            listOf("2.25", "[[0.5], [1.5, 2.5]]", "0.75", "3.0"),
            output("class P { var x = 0.5f\n  fun twice(): Float { return x * 2f } }\nfun sq(d: Double): Double { return d * d }\nprintln(sq(1.5))\n" +
                "val m = mutableListOf(mutableListOf(0.5), mutableListOf(1.5))\nm[1].add(2.5)\nprintln(m)\nval p = P()\np.x = p.x + 0.25f\nprintln(p.x)\nprintln(m[1][0] + p.twice())"),
        )
    }

    // ---- 比較（修正設計 2.1 の表）・IEEE 754 の等価とビットの一致の分離 ----

    @Test
    fun comparisonsFollowTheTable() {
        // 型の違う数値の == != はコンパイル時にエラー（Kotlin と同じ）
        for (source in listOf("1 == 1.0f", "1 == 1.0", "1.0f == 1.0", "1 != 1.0", "1.0 != 1f", "1.0 == true", "1.5 == \"a\"")) {
            assertEquals(listOf(DiagnosticCode.SEM_INCOMPARABLE_TYPES), codes("println($source)"), source)
        }
        val nan = 0.0 / zeroD
        val nanF = 0f / zeroF
        val zero = 0.0
        val minusZero = -0.0
        assertPrints(
            listOf(
                "1.5f == 1.5f" to (1.5f == 1.5f), "0.1f == 0.1f" to true, "1.5 == 1.5" to true, "0.1 + 0.2 == 0.3" to (0.1 + 0.2 == 0.3),
                "0.0 / 0.0 == 0.0 / 0.0" to (nan == nan), "0.0 / 0.0 != 0.0 / 0.0" to (nan != nan), "0f / 0f == 0f / 0f" to (nanF == nanF),
                "0.0 == -0.0" to (zero == minusZero), "0f == -0f" to true, "0.0 != -0.0" to (zero != minusZero),
                "-0.0 < 0.0" to (minusZero < zero), "-0.0 <= 0.0" to (minusZero <= zero), "-0.0 >= 0.0" to (minusZero >= zero),
                "0.0 / 0.0 < 1.0" to (nan < 1.0), "0.0 / 0.0 > 1.0" to (nan > 1.0), "0.0 / 0.0 <= 0.0 / 0.0" to (nan <= nan), "0.0 / 0.0 >= 1.0" to (nan >= 1.0),
                "1.0 / 0.0 > 1e308" to true, "-1.0 / 0.0 < -1e308" to true, "1.0 / 0.0 == 2.0 / 0.0" to true,
                // 型の違う数値の大小比較は広い方の型で（Int と Float は Float: 2^24 を超える Int は丸められる）
                "1 < 1.5" to (1 < 1.5), "2 >= 2.0f" to (2 >= 2.0f), "16777217 > 16777216.0f" to (16777217 > 16777216.0f), "16777217 > 16777216.0" to (16777217 > 16777216.0),
                "0.1f > 0.1" to (0.1f > 0.1), "0.1f == 0.1f.toDouble().toFloat()" to true, "0.5f <= 0.5" to (0.5f <= 0.5), "2147483647 < 2147483647.0f" to (2147483647 < 2147483647.0f),
            ),
        )
        // VM の値の equals はビットの一致（保存・復元の確認用）で、スクリプトの == とは別
        assertEquals(Value.DoubleValue.of(nan), Value.DoubleValue.of(nan))
        assertNotEquals(Value.DoubleValue.of(zero), Value.DoubleValue.of(minusZero))
        assertNotEquals(Value.DoubleValue(0x7ff8000000000000L), Value.DoubleValue(0x7ff8000000000001L))
        assertNotEquals(Value.FloatValue.of(0f), Value.FloatValue.of(-0f))
        // == は Float・Double を operand 1・2 の IEEE 754 の比較で、それ以外は operand 0
        val program = compile("val a = 1.5\nval b = 1f\nval c = 1\nprintln(a == a)\nprintln(b != b)\nprintln(c == c)\nprintln(listOf(a) == listOf(a))")
        assertEquals(listOf(NumericKind.DOUBLE, NumericKind.FLOAT, NumericKind.INT, NumericKind.INT), program.instructions.filter { it.opcode == Opcode.EQUAL || it.opcode == Opcode.NOT_EQUAL }.map { it.operand })
    }

    // ---- 演算・変換・表示（Kotlin/JVM と同じ） ----

    @Test
    fun arithmeticConversionAndDisplayMatchKotlin() {
        assertPrints(
            listOf(
                "1.5 * 2.0" to 1.5 * 2.0, "0.1 + 0.2" to 0.1 + 0.2, "0.1f + 0.2f" to 0.1f + 0.2f, "0.1f + 0.2" to 0.1f + 0.2, "1.0f / 3.0" to 1.0f / 3.0,
                "1 / 3.0" to 1 / 3.0, "1 / 3f" to 1 / 3f, "10 - 0.1f" to 10 - 0.1f, "7 % 2.5" to 7 % 2.5, "-7.5 % 2.0" to -7.5 % 2.0, "7.5 % -2.0" to 7.5 % -2.0,
                "5.5f % 2" to 5.5f % 2, "5.0 % 0.0" to 5.0 % 0.0, "1.0 / 0.0" to 1.0 / zeroD, "-1.0 / 0.0" to -1.0 / zeroD, "0.0 / 0.0" to 0.0 / zeroD, "1f / 0f" to 1f / zeroF,
                "1e308 * 10.0" to 1e308 * 10.0, "3.4e38f * 10f" to 3.4e38f * 10f, "-0.0" to -0.0, "-0f" to -0f, "0.0 * -1.0" to 0.0 * -1.0, "4.9e-324 / 2.0" to 4.9e-324 / 2.0,
                "+1.5" to +1.5, "-(-2.5f)" to -(-2.5f), "1e7f" to 1e7f, "1.0E-5" to 1.0E-5, "100.0" to 100.0, "1e21" to 1e21, "123456789f" to 123456789f, "0.001" to 0.001,
                "1e-3f" to 1e-3f, "1e16" to 1e16, "1e-7" to 1e-7,
                // 変換（F8）: Int.MAX_VALUE から Float は丸められ、Int に戻すと上限で止まる
                "2147483647.toFloat()" to 2147483647.toFloat(), "2147483647.toFloat().toInt()" to 2147483647.toFloat().toInt(), "16777217.toFloat()" to 16777217.toFloat(),
                "16777217.toFloat().toInt()" to 16777217.toFloat().toInt(), "2147483647.toDouble()" to 2147483647.toDouble(), "2147483647.toDouble().toInt()" to 2147483647.toDouble().toInt(),
                "(-2147483648).toFloat().toInt()" to (-2147483648).toFloat().toInt(), "(0.0 / 0.0).toInt()" to (0.0 / zeroD).toInt(), "(0f / 0f).toInt()" to (0f / zeroF).toInt(),
                "1e10.toInt()" to 1e10.toInt(), "(-1e10).toInt()" to (-1e10).toInt(), "(1.0 / 0.0).toInt()" to (1.0 / zeroD).toInt(), "-1.9.toInt()" to -1.9.toInt(),
                "(-1.9).toInt()" to (-1.9).toInt(), "1.9f.toInt()" to 1.9f.toInt(), "1e39.toFloat()" to 1e39.toFloat(), "0.1.toFloat()" to 0.1.toFloat(), "0.1f.toDouble()" to 0.1f.toDouble(),
                "5.toFloat() / 2" to 5.toFloat() / 2, "1.5.toDouble()" to 1.5, "3.toInt()" to 3, "1.4e-45f.toDouble()" to 1.4e-45f.toDouble(), "(-0.0).toFloat()" to (-0.0).toFloat(),
                "1e-46.toFloat()" to 1e-46.toFloat(), "(2147483647 + 0.0).toInt()" to (2147483647 + 0.0).toInt(),
                // Float と Double の混合
                "0.1f * 3.0" to 0.1f * 3.0, "1.0f / 3.0f + 1.0 / 3.0" to 1.0f / 3.0f + 1.0 / 3.0, "0.5f - 0.1" to 0.5f - 0.1, "1e30f * 1e30" to 1e30f * 1e30,
                "3.4e38f + 3.4e38f" to 3.4e38f + 3.4e38f, "(3.4e38f + 3.4e38f).toDouble() == 3.4e38f + 3.4e38" to false, "16777216f + 1" to 16777216f + 1, "16777216.0 + 1" to 16777216.0 + 1,
            ),
        )
        // Int の演算は今までどおり（オーバーフロー・0 除算は実行時エラー）。浮動小数点数の 0 除算はエラーにならない
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, finish(CsVm(compile("println(1 / 0)"))).error?.code)
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, finish(CsVm(compile("println(2147483647 + 1)"))).error?.code)
        assertEquals(listOf("Infinity", "NaN"), output("println(1.0 / 0)\nprintln(0 % 0.0)"))
        // リストの表示
        assertEquals(listOf("[1.5, NaN, -0.0, Infinity, 1.0E10]", "[0.1, 3.0]"), output("println(listOf(1.5, 0.0 / 0.0, -0.0, 1.0 / 0.0, 1e10))\nprintln(mutableListOf(0.1f, 3f))"))
    }

    // ---- 命令: 既存の命令の operand で型を選び、追加は CONVERT だけ。Int だけのプログラムの命令は変わらない ----

    @Test
    fun instructionsUseNumericKinds() {
        val ints = compile("var a = 1\nval b = -a\nprintln(a + b * 2 - 3 / 1 % 2)\nprintln(a < b)\nfor (i in 0..3) { a = a + i }")
        assertTrue(ints.instructions.none { it.opcode == Opcode.CONVERT })
        assertTrue(ints.instructions.all { it.operand == 0 || it.opcode in setOf(Opcode.PUSH_CONST, Opcode.LOAD, Opcode.STORE, Opcode.JUMP, Opcode.JUMP_IF_FALSE, Opcode.CALL_HOST) })
        // 1 + 2.5f: Int を Float にしてから ADD 1。1.5 < 2: 右辺を Double にしてから LESS 2。x.toInt(): CONVERT（Double → Int）。同じ型への変換は命令を出さない
        val mixed = compile("println(1 + 2.5f)\nprintln(1.5 < 2)\nval x = 1.5\nprintln(x.toInt())\nprintln(x.toDouble())\nprintln(-x)")
        val numeric = mixed.instructions.filter { it.opcode in setOf(Opcode.CONVERT, Opcode.ADD, Opcode.LESS, Opcode.NEGATE) }.map { it.opcode to it.operand }
        assertEquals(
            listOf(
                Opcode.CONVERT to NumericKind.conversion(NumericKind.INT, NumericKind.FLOAT), Opcode.ADD to NumericKind.FLOAT,
                Opcode.CONVERT to NumericKind.conversion(NumericKind.INT, NumericKind.DOUBLE), Opcode.LESS to NumericKind.DOUBLE,
                Opcode.CONVERT to NumericKind.conversion(NumericKind.DOUBLE, NumericKind.INT), Opcode.NEGATE to NumericKind.DOUBLE,
            ),
            numeric,
        )
        // 定数はビット列。同じビットだけをまとめる（0.0 と -0.0 の定数は別。-0.0 は 0.0 の NEGATE）
        val constants = compile("val a = 0.0\nval b = 0.0\nval c = 1.5f\nval d = 1.5\nval e = -0.0\nprintln(e)").constants
        assertEquals(listOf(Constant.DoubleConstant(0.0.toRawBits()), Constant.FloatConstant(1.5f.toRawBits()), Constant.DoubleConstant(1.5.toRawBits())), constants)
        assertNotEquals(Constant.DoubleConstant(0.0.toRawBits()), Constant.DoubleConstant((-0.0).toRawBits()))
    }

    // ---- Verifier: operand の型と合わない命令・未知の operand・不正な変換を拒否する。VM も実行時に拒否する ----

    private fun replaced(program: CompiledProgram, opcode: Opcode, replacement: (Instruction) -> Instruction): CompiledProgram {
        val index = program.instructions.indexOfFirst { it.opcode == opcode }
        check(index >= 0)
        return program.copy(instructions = program.instructions.mapIndexed { i, instruction -> if (i == index) replacement(instruction) else instruction })
    }

    @Test
    fun invalidNumericBytecodeIsRejected() {
        val doubles = compile("val a = 1.5\nval b = 2.5\nprintln(a + b)")
        assertNotNull(SnapshotVerifier.framesOf(doubles))
        val invalid = listOf(
            replaced(doubles, Opcode.ADD) { it.copy(operand = NumericKind.INT) },
            replaced(doubles, Opcode.ADD) { it.copy(operand = NumericKind.FLOAT) },
            replaced(doubles, Opcode.ADD) { it.copy(operand = 3) },
            replaced(doubles, Opcode.ADD) { it.copy(operand = -1) },
            replaced(doubles, Opcode.ADD) { it.copy(opcode = Opcode.EQUAL, operand = NumericKind.INT) },
            replaced(doubles, Opcode.ADD) { it.copy(opcode = Opcode.EQUAL, operand = NumericKind.FLOAT) },
            replaced(doubles, Opcode.ADD) { it.copy(opcode = Opcode.LESS, operand = NumericKind.INT) },
            replaced(doubles, Opcode.ADD) { it.copy(opcode = Opcode.CONVERT, operand = NumericKind.conversion(NumericKind.INT, NumericKind.DOUBLE)) },
        )
        for (program in invalid) assertNull(SnapshotVerifier.framesOf(program), "${program.instructions}")
        assertNotNull(SnapshotVerifier.framesOf(replaced(doubles, Opcode.ADD) { it.copy(opcode = Opcode.EQUAL, operand = NumericKind.DOUBLE) }))
        val conversion = compile("val a = 1.5\nprintln(a.toInt())")
        for (operand in listOf(-1, 0, 4, 8, 9, NumericKind.conversion(NumericKind.FLOAT, NumericKind.INT), NumericKind.conversion(NumericKind.INT, NumericKind.FLOAT))) {
            assertNull(SnapshotVerifier.framesOf(replaced(conversion, Opcode.CONVERT) { it.copy(operand = operand) }), "CONVERT $operand")
        }
        assertNotNull(SnapshotVerifier.framesOf(replaced(conversion, Opcode.CONVERT) { it.copy(operand = NumericKind.conversion(NumericKind.DOUBLE, NumericKind.FLOAT)) }))
        // Int の == に Double を渡す（operand 0）・Int の比較に Int 以外は、検証を通さずに実行しても VM が止める
        val ints = compile("val a = 1\nval b = 2\nprintln(a == b)\nprintln(a < b)\nprintln(-a)")
        for (vm in listOf(
            CsVm(replaced(doubles, Opcode.ADD) { it.copy(opcode = Opcode.EQUAL, operand = NumericKind.INT) }),
            CsVm(replaced(doubles, Opcode.ADD) { it.copy(operand = 7) }),
            CsVm(replaced(ints, Opcode.LESS) { it.copy(operand = NumericKind.DOUBLE) }),
            CsVm(replaced(ints, Opcode.NEGATE) { it.copy(operand = NumericKind.FLOAT) }),
            CsVm(replaced(ints, Opcode.EQUAL) { it.copy(opcode = Opcode.CONVERT, operand = NumericKind.conversion(NumericKind.INT, NumericKind.INT)) }),
        )) {
            finish(vm)
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code)
        }
        // 値の型と変数の型の照合: Float の変数に Double・Int の値は入れられない
        val waiting = compile("var f = 1.5f\nvar d = 2.5\nwaitTicks(5)\nprintln(f + d)")
        val vm = CsVm(waiting)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        for (variables in listOf(listOf(Value.DoubleValue.of(1.5), snapshot.variables[1]), listOf(snapshot.variables[0], Value.FloatValue.of(2.5f)), listOf(Value.IntValue(1), snapshot.variables[1]))) {
            assertEquals(SnapshotProblem.INVALID_VARIABLES, SnapshotVerifier.verify(waiting, snapshot.copy(variables = variables)), "$variables")
        }
    }

    // ---- -0.0・NaN のビット・Infinity は実行・保存・復元を通して変わらない ----

    @Test
    fun signAndNanBitsSurviveSnapshots() {
        val program = compile("var z = -0.0\nvar f = -0f\nvar n = 0.0\nvar g = 0f\nwaitTicks(3)\nprintln(z)\nprintln(f)\nprintln(1.0 / z)\nprintln(1f / f)\nprintln(z == z)\nprintln(n)\nprintln(g != g)")
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.run(1)
        val snapshot = vm.snapshot()!!
        assertEquals(listOf(Value.DoubleValue(Long.MIN_VALUE), Value.FloatValue(Int.MIN_VALUE), Value.DoubleValue(0), Value.FloatValue(0)), snapshot.variables)
        assertEquals(listOf("-0.0", "-0.0", "-Infinity", "-Infinity", "true", "0.0", "false"), finish(CsVm.restore(program, snapshot)!!).console.history)
        // 標準でない NaN のビット（Double 0x7ff8000000000001・Float 0x7fc00001）と Infinity を入れても、復元した VM のビットは同じ。== は IEEE 754 で false
        val payloads = listOf(Value.DoubleValue(0x7ff8000000000001L), Value.FloatValue(0x7fc00001), Value.DoubleValue.of(Double.POSITIVE_INFINITY), Value.FloatValue(0xffc00000.toInt()))
        val restored = CsVm.restore(program, snapshot.copy(variables = payloads))!!
        assertEquals(payloads, restored.snapshot()!!.variables)
        assertEquals(0x7ff8000000000001L, (restored.snapshot()!!.variables[0] as Value.DoubleValue).bits)
        assertEquals(listOf("NaN", "NaN", "NaN", "NaN", "false", "Infinity", "true"), finish(restored).console.history)
        assertEquals(listOf(Value.DoubleValue(Long.MIN_VALUE), Value.FloatValue(Int.MIN_VALUE)), CsVm.restore(program, snapshot)!!.snapshot()!!.variables.take(2))
    }

    // ---- すべての境界（回収の各単位を含む）で保存・復元しても、結果は中断しない実行と同じ ----

    @Test
    fun everyBoundaryRestores() {
        val source = "class Probe {\n  var t: Float = 0.5f\n  var d = -0.0\n}\nfun half(x: Double): Double { return x / 2.0 }\nval p = Probe()\nval xs = mutableListOf(1.5, 0.0 / 0.0, -0.0)\n" +
            "var acc = 0.0\nfor (i in 0 until 20) {\n  acc = acc + half(i.toDouble())\n  p.t = p.t * 1.5f\n}\nfor (q in 0 until 300) { val junk = listOf(q * 0.5, q.toDouble()) }\nwaitTicks(2)\n" +
            "xs.add(acc)\nprintln(xs)\nprintln(p.t)\nprintln(p.d)\nprintln(xs[1] == xs[1])\nprintln(acc.toInt() + 1)\nprintln(p.t.toDouble() > acc)"
        var t = 0.5f
        var acc = 0.0
        for (i in 0 until 20) {
            acc += i.toDouble() / 2.0
            t *= 1.5f
        }
        val expected = listOf(listOf(1.5, Double.NaN, -0.0, acc).toString(), t.toString(), "-0.0", "false", (acc.toInt() + 1).toString(), (t.toDouble() > acc).toString())
        val program = compile(source)
        val original = CsVm(program)
        var collectingCuts = 0
        while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
            val snapshot = original.snapshot()!!
            if (original.isCollecting) collectingCuts++
            val restored = CsVm.restore(program, snapshot)
            assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, snapshot)}")
            assertEquals(snapshot, restored!!.snapshot())
            while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
            assertEquals(expected, restored.console.history)
            step(original)
        }
        assertEquals(expected, original.console.history)
        assertTrue(collectingCuts > 0)
    }

    // ---- 指紋: 浮動小数点数の定数（ビット）・型はハッシュに含まれる ----

    @Test
    fun floatingProgramsAreFingerprinted() {
        val base = compile("val a = 0.0\nclass P { var x = 1.5f }\nprintln(a)")
        val hash = ProgramFingerprint.ofProgram(base)
        val changed = listOf(
            base.copy(constants = listOf(Constant.DoubleConstant((-0.0).toRawBits()), base.constants[1])),
            base.copy(constants = listOf(Constant.DoubleConstant(0x7ff8000000000001L), base.constants[1])),
            base.copy(constants = listOf(Constant.FloatConstant(0), base.constants[1])),
            base.copy(classes = listOf(base.classes[0].copy(fields = listOf(FieldInfo("x", SlotType.DOUBLE))))),
            base.copy(classes = listOf(base.classes[0].copy(fields = listOf(FieldInfo("x", SlotType.INT))))),
        )
        for (program in changed) assertFalse(hash.contentEquals(ProgramFingerprint.ofProgram(program)), "${program.constants} ${program.classes}")
        assertTrue(hash.contentEquals(ProgramFingerprint.ofProgram(compile("val a = 0.0\nclass P { var x = 1.5f }\nprintln(a)"))))
    }

    // ---- 無作為の式（Int・Float・Double の混合・変換・比較）を Kotlin で計算した結果と比べる ----

    // Kotlin で計算した値（Int・Float・Double のどれか）と、同じ値を作る CreateScript の式
    private class Term(val source: String, val value: Any)

    private class IntOverflow : RuntimeException(null, null, false, false)

    private val floatLiterals = listOf("0.5f" to 0.5f, "1.25f" to 1.25f, "3f" to 3f, "0.1f" to 0.1f, "1e-3f" to 1e-3f, "2.5e10f" to 2.5e10f, "16777216f" to 16777216f, "1e-40f" to 1e-40f)
    private val doubleLiterals = listOf("0.1" to 0.1, "2.5" to 2.5, "1e300" to 1e300, "1e-300" to 1e-300, "3.0" to 3.0, "0.0" to 0.0, "7.75" to 7.75, "4.9e-324" to 4.9e-324)

    private fun leaf(random: Random): Term = when (random.nextInt(3)) {
        0 -> random.nextInt(-20, 21).let { Term(if (it < 0) "($it)" else "$it", it) }
        1 -> floatLiterals.random(random).let { Term(it.first, it.second) }
        else -> doubleLiterals.random(random).let { Term(it.first, it.second) }
    }

    private fun Any.asFloat() = when (this) {
        is Int -> toFloat()
        is Float -> this
        else -> error("not promoted to Float: $this")
    }

    private fun Any.asDouble() = when (this) {
        is Int -> toDouble()
        is Float -> toDouble()
        is Double -> this
        else -> error("not numeric: $this")
    }

    // Kotlin と同じ昇格（Int・Float は Float、Double を含めば Double）。Int 同士はオーバーフロー・0 除算の式を作らない
    private fun arithmetic(operator: Char, left: Any, right: Any): Any = when {
        left is Double || right is Double -> left.asDouble().let { a ->
            val b = right.asDouble()
            when (operator) { '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b; else -> a % b }
        }
        left is Float || right is Float -> left.asFloat().let { a ->
            val b = right.asFloat()
            when (operator) { '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b; else -> a % b }
        }
        else -> {
            val a = left as Int
            val b = right as Int
            try {
                when (operator) {
                    '+' -> Math.addExact(a, b)
                    '-' -> Math.subtractExact(a, b)
                    '*' -> Math.multiplyExact(a, b)
                    else -> if (b == 0 || (a == Int.MIN_VALUE && b == -1)) throw IntOverflow() else if (operator == '/') a / b else a % b
                }
            } catch (overflow: ArithmeticException) {
                throw IntOverflow()
            }
        }
    }

    private fun comparison(operator: String, left: Any, right: Any): Boolean = when {
        left is Double || right is Double -> left.asDouble().let { a -> val b = right.asDouble(); when (operator) { "<" -> a < b; "<=" -> a <= b; ">" -> a > b; else -> a >= b } }
        left is Float || right is Float -> left.asFloat().let { a -> val b = right.asFloat(); when (operator) { "<" -> a < b; "<=" -> a <= b; ">" -> a > b; else -> a >= b } }
        else -> (left as Int).let { a -> val b = right as Int; when (operator) { "<" -> a < b; "<=" -> a <= b; ">" -> a > b; else -> a >= b } }
    }

    private fun expression(random: Random, depth: Int): Term {
        if (depth == 0 || random.nextInt(4) == 0) return leaf(random)
        return when (random.nextInt(6)) {
            0 -> expression(random, depth - 1).let { inner ->
                when (val v = inner.value) {
                    is Int -> if (v == Int.MIN_VALUE) throw IntOverflow() else Term("(-${inner.source})", -v)
                    is Float -> Term("(-${inner.source})", -v)
                    else -> Term("(-${inner.source})", -(v as Double))
                }
            }
            1 -> expression(random, depth - 1).let { inner ->
                when (random.nextInt(3)) {
                    0 -> Term("${inner.source}.toInt()", when (val v = inner.value) { is Int -> v; is Float -> v.toInt(); else -> (v as Double).toInt() })
                    1 -> Term("${inner.source}.toFloat()", when (val v = inner.value) { is Int -> v.toFloat(); is Float -> v; else -> (v as Double).toFloat() })
                    else -> Term("${inner.source}.toDouble()", inner.value.asDouble())
                }
            }
            else -> {
                val operator = "+-*/%"[random.nextInt(5)]
                val left = expression(random, depth - 1)
                val right = expression(random, depth - 1)
                Term("(${left.source} $operator ${right.source})", arithmetic(operator, left.value, right.value))
            }
        }
    }

    private class Generated(val source: String, val output: List<String>)

    // 引数の型が Float・Double と決まっているため IEEE 754 の比較（Any のままの比較は equals になる）
    private fun ieeeEquals(left: Float, right: Float) = left == right

    private fun ieeeEquals(left: Double, right: Double) = left == right

    private fun generate(random: Random): Generated {
        val lines = ArrayList<String>()
        val output = ArrayList<String>()
        while (lines.size < 12) {
            try {
                val left = expression(random, 3)
                if (random.nextInt(3) == 0) {
                    val right = expression(random, 2)
                    val sameType = left.value::class == right.value::class
                    if (sameType && random.nextBoolean()) {
                        val equal = random.nextBoolean()
                        val result = when (left.value) {
                            is Int -> left.value as Int == right.value as Int
                            is Float -> ieeeEquals(left.value as Float, right.value as Float)
                            else -> ieeeEquals(left.value as Double, right.value as Double)
                        }
                        lines.add("println(${left.source} ${if (equal) "==" else "!="} ${right.source})")
                        output.add((result == equal).toString())
                    } else {
                        val operator = listOf("<", "<=", ">", ">=").random(random)
                        lines.add("println(${left.source} $operator ${right.source})")
                        output.add(comparison(operator, left.value, right.value).toString())
                    }
                } else {
                    lines.add("println(${left.source})")
                    output.add(left.value.toString())
                }
            } catch (overflow: IntOverflow) {
                // Int のオーバーフロー・0 除算になる式は使わない
            }
        }
        return Generated(lines.joinToString("\n"), output)
    }

    @Test
    fun randomExpressionsMatchKotlin() {
        val random = Random(3111)
        var compared = 0
        var restored = 0
        var instructions = 0
        repeat(300) { index ->
            val generated = generate(random)
            val compiled = Compiler.compileSource(generated.source)
            // ソース・命令数の上限を超えたものは使わない
            val program = compiled.program ?: return@repeat
            instructions += program.instructions.size
            val vm = finish(CsVm(program))
            assertEquals(VmState.HALTED, vm.state, "${vm.error} in ${generated.source}")
            assertEquals(generated.output, vm.console.history, generated.source)
            compared++
            if (index % 30 == 0) {
                restored++
                val original = CsVm(program)
                while (original.state == VmState.RUNNING) {
                    val copy = CsVm.restore(program, original.snapshot()!!)!!
                    while (copy.state == VmState.RUNNING) step(copy)
                    assertEquals(generated.output, copy.console.history)
                    step(original)
                }
            }
        }
        assertTrue(compared >= 280, "compared $compared")
        println("[3.11a random] $compared programs (${instructions / compared} instructions each) compared with Kotlin, $restored restored at every boundary")
    }

    // ---- 予算と公平性（FAST 128 命令 / tick・全体 4096 命令 / tick）。浮動小数点数の演算も 1 命令 = 1 単位 ----

    private val loops = listOf(
        "var d = 0.1\nwhile (true) { d = d * 1.0000001 + 0.5 }",
        "var f = 1f\nvar n = 0\nwhile (true) {\n  f = (f + n) % 7.5f\n  n = (n + 1) % 100\n}",
        "var d = 0.1\nvar k = 0\nwhile (true) {\n  d = d * 1.37 % 1e6 + 0.1\n  println(d)\n  k = (d / 3.0).toInt()\n}",
        "val xs = mutableListOf(0.5, 1.5)\nwhile (true) {\n  xs.add(xs[0] / 3.0)\n  xs.removeAt(0)\n  if (xs[0] != xs[0]) { xs[0] = 0.0 }\n}",
    )

    @Test
    fun floatingProgramsStayWithinBudgetAndFair() {
        val programs = loops.map(::compile)
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
    fun measureFloatingPoint() {
        // メモリ: 15 行 × 256 要素の Double の行列を持つ VM（新しく実行した 50 台の差分）
        val matrix = compile("val m = mutableListOf<MutableList<Double>>()\nfor (i in 0 until 15) {\n  val row = mutableListOf<Double>()\n  for (j in 0 until 256) { row.add(j * 0.5) }\n  m.add(row)\n}\nwaitTicks(100)\nprintln(m.size)")
        val runtime = Runtime.getRuntime()
        fun used(): Long {
            repeat(5) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }
        val baseline = used()
        val holders = List(50) { CsVm(matrix).also { vm -> while (vm.state != VmState.WAITING) vm.advance(1000) } }
        val perVm = (used() - baseline) / holders.size / 1024
        println("[3.11a measure] memory per VM with a 15 x 256 Double matrix: ~$perVm KB (${holders.count { it.state == VmState.WAITING }} VMs)")
        // 1 命令あたりの時間: Int の演算・Double の演算・% と変換・表示・リスト
        val int = compile("var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }")
        for ((label, program) in listOf("Int arithmetic" to int) + listOf("Double arithmetic", "Float rem and Int mixing", "Double rem, println, toInt", "List<Double>").zip(loops.map(::compile))) {
            println("[3.11a measure] per instruction, $label: %.1f ns".format(nanosPerInstruction(List(20) { CsVm(program) })))
        }
        // 字句解析からコンパイルまで: 上限の長さ（64）のリテラルでソースの上限（4096）を埋める
        val literal = "1." + "3".repeat(LexerLimits.MAX_FLOAT_LITERAL_LENGTH - 7) + "e-300"
        val line = "println($literal)\n"
        val dense = line.repeat(4096 / line.length)
        repeat(20) { compile(dense) }
        val compileTimes = (0 until 50).map { val start = System.nanoTime(); compile(dense); (System.nanoTime() - start) / 1e6 }
        println("[3.11a measure] compile ${dense.length} chars of ${4096 / line.length} literals of ${literal.length} chars: avg %.3f / max %.3f ms".format(compileTimes.average(), compileTimes.max()))
        // 検証 + 復元: 16 個の List<Double>（要素の合計 4096）
        val full = compile("val all = mutableListOf<MutableList<Double>>()\nfor (i in 0 until 15) {\n  val row = mutableListOf<Double>()\n  for (j in 0 until 256) { row.add(j / 3.0) }\n  all.add(row)\n}\nwaitTicks(9)\nprintln(all.size)")
        val vm = CsVm(full)
        while (vm.state != VmState.WAITING) vm.advance(1000)
        val snapshot = vm.snapshot()!!
        repeat(200) { CsVm.restore(full, snapshot) }
        val samples = (0 until 500).map { val start = System.nanoTime(); CsVm.restore(full, snapshot)!!; (System.nanoTime() - start) / 1e6 }.sorted()
        println("[3.11a measure] verify + restore 15 x 256 Double matrix: median %.3f / p99 %.3f ms".format(samples[250], samples[495]))
        // Scheduler の tick（1000 台）
        for ((label, source) in listOf("1000 VMs Double arithmetic" to loops[0], "1000 VMs Double rem, println, toInt" to loops[2], "1000 VMs Int arithmetic" to "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }")) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program).also { machine -> machine.run(3000) }) }
            println("[3.11a measure] $label: ${perTick(scheduler)}")
        }
    }
}
