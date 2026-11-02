package com.creatrbypc.createanalytical.script.semantic

import com.creatrbypc.createanalytical.script.ast.ExpressionStatement
import com.creatrbypc.createanalytical.script.ast.FunctionDeclaration
import com.creatrbypc.createanalytical.script.ast.VariableDeclaration
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticAnalyzerTest {
    private fun analyze(source: String) = SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize(source)))

    private fun ok(source: String): AnalyzedProgram {
        val result = analyze(source)
        assertEquals(emptyList<DiagnosticCode>(), result.diagnostics.map { it.code }, "unexpected diagnostics for: $source")
        return result.program!!
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = analyze(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    private fun declaredType(source: String, name: String) =
        ok(source).declarations.values.single { it.name == name }.type

    // ---- 宣言・型推論・型注釈 ----

    @Test
    fun declarationsAndInference() {
        assertEquals(ScriptType.INT, declaredType("val n = 1 + 2 * 3", "n"))
        assertEquals(ScriptType.STRING, declaredType("val s = \"Hello\"", "s"))
        assertEquals(ScriptType.INT, declaredType("var n: Int = 3", "n"))
        assertEquals(ScriptType.STRING, declaredType("val s: String = \"\"", "s"))
        assertEquals(ScriptType.INT, declaredType("val a = 1\nval b = a", "b"))
    }

    @Test
    fun typeAnnotationErrors() {
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val n: Int = \"a\""))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val s: String = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_TYPE), codes("val x: Long = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val u: Unit = 1"))
        val mismatch = analyze("val n: Int = \"a\"").diagnostics.single()
        assertEquals(listOf(DiagnosticArgument.StringArgument("Int"), DiagnosticArgument.StringArgument("String")), mismatch.arguments)
        assertEquals(SourcePosition(13, 1, 14), mismatch.position)
    }

    // ---- 代入・再代入 ----

    @Test
    fun assignments() {
        ok("var x = 1\nx = x + 1")
        ok("var s = \"a\"\ns = \"b\"")
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("val x = 1\nx = 2"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var x = 1\nx = \"a\""))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("y = 1"))
    }

    // ---- 名前の解決 ----

    @Test
    fun nameResolution() {
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("println(x)"))
        // 初期化式の中では、宣言中の変数はまだ見えない
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("val x = x"))
        // 宣言より前の参照
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("println(x)\nval x = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_DECLARATION), codes("val x = 1\nvar x = 2"))
        val program = ok("val x = 1\nprintln(x)")
        assertSame(program.declarations.values.single(), program.references.values.single())
    }

    @Test
    fun builtinNames() {
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("val println = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("var waitTicks = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("val f = println"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("waitTicks = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_FUNCTION), codes("foo()"))
        assertEquals(listOf(DiagnosticCode.SEM_NOT_CALLABLE), codes("val x = 1\nx()"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_CALLEE), codes("(1)(2)"))
    }

    // ---- 演算 ----

    @Test
    fun arithmeticTypes() {
        ok("val a = 7 / 2 % 3 - -1 + +4")
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("val s = \"a\" + \"b\""))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("val s = \"a\" + 1"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("val s = 1 * \"a\""))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("val s = -\"a\""))
        val error = analyze("val s = \"a\" % 2").diagnostics.single()
        assertEquals(listOf(DiagnosticArgument.StringArgument("%"), DiagnosticArgument.StringArgument("String")), error.arguments)
    }

    // ---- Boolean・比較・論理演算子（第3.7b段階） ----

    @Test
    fun booleanDeclarationsAndAssignments() {
        assertEquals(ScriptType.BOOLEAN, declaredType("val b = true", "b"))
        assertEquals(ScriptType.BOOLEAN, declaredType("var b: Boolean = false", "b"))
        assertEquals(ScriptType.BOOLEAN, declaredType("val b = 1 < 2", "b"))
        assertEquals(ScriptType.BOOLEAN, declaredType("val b = \"a\" == \"b\"", "b"))
        assertEquals(ScriptType.BOOLEAN, declaredType("val p = true\nval b = !p && p || p", "b"))
        ok("var b = true\nb = false\nb = 1 >= 2\nprintln(b)")
        // Boolean と Int・String の暗黙の変換は無い
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val b: Boolean = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val n: Int = true"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val s: String = false"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var b = true\nb = 0"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var n = 0\nn = 1 == 1"))
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("val b = true\nb = false"))
        // 型名は大文字小文字を区別する
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_TYPE), codes("val b: boolean = true"))
    }

    @Test
    fun equalityTypes() {
        ok("val a = 1 == 2\nval b = true != false\nval c = \"x\" == \"y\"\nval d = a == b")
        for (source in listOf("1 == true", "true != 0", "\"1\" == 1", "1 != \"1\"", "\"true\" == true", "false == \"\"")) {
            assertEquals(listOf(DiagnosticCode.SEM_INCOMPARABLE_TYPES), codes("val x = $source"), source)
        }
        val error = analyze("val x = 1 != true").diagnostics.single()
        assertEquals(
            listOf(DiagnosticArgument.StringArgument("!="), DiagnosticArgument.StringArgument("Int"), DiagnosticArgument.StringArgument("Boolean")),
            error.arguments,
        )
        assertEquals(SourcePosition(10, 1, 11), error.position)
        // Unit は比べられない（値として使えない: A22）
        assertEquals(List(2) { DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED }, codes("val x = println() == println()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val x = 1 != waitTicks(1)"))
    }

    @Test
    fun comparisonAndLogicalTypes() {
        ok("val a = 1 < 2\nval b = 1 <= 2\nval c = 1 > 2\nval d = 1 >= 2\nval e = a && b || !c")
        val cases = mapOf(
            "true < false" to ("<" to "Boolean"),
            "\"a\" <= \"b\"" to ("<=" to "String"),
            "1 > true" to (">" to "Boolean"),
            "\"a\" >= 1" to (">=" to "String"),
            // a < b < c は (a < b) < c で、Boolean の大小比較になる
            "1 < 2 < 3" to ("<" to "Boolean"),
            "1 && true" to ("&&" to "Int"),
            "true || \"a\"" to ("||" to "String"),
            "!1" to ("!" to "Int"),
            "!\"a\"" to ("!" to "String"),
            "-true" to ("-" to "Boolean"),
            "+false" to ("+" to "Boolean"),
            "true + 1" to ("+" to "Boolean"),
            "1 * false" to ("*" to "Boolean"),
        )
        for ((source, expected) in cases) {
            val error = analyze("val x = $source").diagnostics.single()
            assertEquals(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, error.code, source)
            assertEquals(listOf(DiagnosticArgument.StringArgument(expected.first), DiagnosticArgument.StringArgument(expected.second)), error.arguments, source)
        }
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val x = !println()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val x = true && println()"))
        // 内側のエラーを外側の演算に重ねない
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("val x = !!1"))
        assertEquals(listOf(DiagnosticCode.SEM_INCOMPARABLE_TYPES), codes("val x = (1 == true) && false"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("val x = y < 1 || y > 2").distinct())
    }

    @Test
    fun booleanPrintlnAndUnusedExpressions() {
        ok("println(true)\nprintln(1 == 1)\nprintln(!false || false)")
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("waitTicks(true)"))
        // 比較・論理演算の結果を使わない文は警告する（A4）
        val result = analyze("val a = 1\na == 2\na < 3 && true\n!true")
        assertFalse(result.hasErrors)
        assertEquals(3, result.diagnostics.count { it.code == DiagnosticCode.SEM_UNUSED_EXPRESSION && it.severity == DiagnosticSeverity.WARNING })
    }

    // ---- if 文とブロックスコープ（第3.7c段階） ----

    @Test
    fun ifConditionsMustBeBoolean() {
        ok("val p = true\nif (p) { }\nif (1 < 2) { } else if (!p) { } else { }")
        val cases = mapOf("1" to "Int", "\"a\"" to "String", "1 + 2" to "Int")
        for ((condition, type) in cases) {
            val error = analyze("if ($condition) { }").diagnostics.single()
            assertEquals(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN, error.code, condition)
            assertEquals(listOf(DiagnosticArgument.StringArgument(type)), error.arguments, condition)
        }
        assertEquals(SourcePosition(4, 1, 5), analyze("if (1) { }").diagnostics.single().position)
        // else if の条件も同じ。Unit は値として使えない
        assertEquals(listOf(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN), codes("if (true) { } else if (0) { }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("if (println()) { }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("if (q) { }"))
        // 条件のエラーがあってもブロックの中は解析する
        assertEquals(listOf(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN, DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("if (1) { println(z) }"))
    }

    @Test
    fun blockScopes() {
        // 外側の変数はブロックの中から読み書きできる
        ok("var n = 1\nif (true) { n = n + 1\nprintln(n) }\nprintln(n)")
        // ブロックの中の宣言はブロックの外では見えない（else や後続のブロックからも）
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("if (true) { val x = 1 }\nprintln(x)"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("if (true) { val x = 1 } else { println(x) }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("if (true) { val x = 1 } else if (x == 1) { }"))
        // 同じブロック内の重複宣言はエラー、内側での同名の宣言（シャドーイング）は可（D4）。警告もしない（11.9-7）
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_DECLARATION), codes("if (true) { val x = 1\nval x = 2 }"))
        val shadowing = ok("val x = 1\nif (true) { val x = \"s\"\nprintln(x)\nif (true) { val x = true\nprintln(x) } }\nprintln(x)")
        val symbols = shadowing.declarations.values.sortedBy { it.declarationPosition.offset }
        assertEquals(listOf(ScriptType.INT, ScriptType.STRING, ScriptType.BOOLEAN), symbols.map { it.type })
        // 各参照は一番内側の宣言を指す
        assertEquals(listOf(symbols[1], symbols[2], symbols[0]), shadowing.references.entries.sortedBy { it.key.position.offset }.map { it.value })
        // 別々のブロックで同じ名前を宣言できる
        ok("if (true) { val y = 1 } else { val y = \"a\" }\nif (false) { val y = false }\nval y = 2")
        // 内側の宣言は、宣言より前の位置からは見えない（外側の同名変数を指す）
        val before = ok("val x = 1\nif (true) { println(x)\nval x = 2 }")
        assertEquals(before.declarations.values.minBy { it.declarationPosition.offset }, before.references.values.single())
        // val の再代入・型の不一致・組み込み関数名の再宣言はブロックの中でも同じ
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("val x = 1\nif (true) { x = 2 }"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var x = 1\nif (true) { x = true }"))
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("if (true) { val println = 1 }"))
        // シャドーイングした変数は、代入の型も内側の宣言の型で検査する
        ok("var x = 1\nif (true) { var x = \"a\"\nx = \"b\" }\nx = 2")
        assertEquals(listOf(DiagnosticCode.SEM_NOT_CALLABLE), codes("val f = 1\nif (true) { f() }"))
    }

    // ---- while 文（第3.7d段階） ----

    @Test
    fun whileConditionsAndScopes() {
        ok("var i = 0\nwhile (i < 3) { val sq = i * i\nprintln(sq)\ni = i + 1 }")
        assertEquals(listOf(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN), codes("while (1) { }"))
        assertEquals(listOf(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN), codes("val s = \"a\"\nwhile (s) { }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("while (waitTicks(1)) { }"))
        // 本体の変数は本体の外（条件・ループの後）から見えない
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("while (true) { val x = 1 }\nprintln(x)"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("while (done) { val done = true }"))
        // 本体の中の重複宣言はエラー、シャドーイングは可
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_DECLARATION), codes("while (true) { val x = 1\nval x = 2 }"))
        ok("val x = 1\nwhile (x > 2) { val x = \"s\"\nwhile (true) { val x = false } }")
        // 外側の val には代入できない
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("val i = 0\nwhile (i < 3) { i = i + 1 }"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var i = 0\nwhile (i < 3) { i = true }"))
    }

    // ---- break / continue（第3.8a段階） ----

    @Test
    fun breakAndContinueOnlyInsideLoops() {
        ok("while (true) { break }\nwhile (false) { continue }")
        // if / else の中でも、外側に while があれば使える。入れ子の while でも同じ
        ok("var i = 0\nwhile (i < 3) { i = i + 1\nif (i == 1) { continue } else if (i == 2) { break } else { while (true) { break } } }")
        val outside = analyze("break").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP, outside.code)
        assertEquals(SourcePosition(0, 1, 1), outside.position)
        assertEquals(DiagnosticPhase.SEMANTIC, outside.phase)
        val inIf = analyze("if (true) {\n  continue\n}").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_CONTINUE_OUTSIDE_LOOP, inIf.code)
        assertEquals(SourcePosition(14, 2, 3), inIf.position)
        // ループを抜けた後は外
        assertEquals(listOf(DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP), codes("while (true) { }\nbreak"))
        assertEquals(
            listOf(DiagnosticCode.SEM_CONTINUE_OUTSIDE_LOOP, DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP),
            codes("if (true) { continue } else { break }\nwhile (false) { break }"),
        )
        // 到達しない文は警告しない（11.9-7）。ブロックのスコープは break の後も通常どおり
        ok("while (true) { val x = 1\nbreak\nprintln(x) }")
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("while (true) { val x = 1\nbreak }\nprintln(x)"))
    }

    // ---- 整数範囲の for（第3.8b段階） ----

    @Test
    fun forBoundsAndLoopVariable() {
        ok("var n = 3\nfor (i in 0..n) { println(i) }\nfor (i in n - 1 until n * 2) { }\nfor (i in 5 downTo -2147483648) { }")
        // 始点・終点は Int のみ（暗黙の変換なし）
        for ((source, type) in listOf("for (i in true..5) { }" to "Boolean", "for (i in 0 until \"a\") { }" to "String", "for (i in \"a\"..\"b\") { }" to "String")) {
            val errors = analyze(source).diagnostics
            assertTrue(errors.isNotEmpty() && errors.all { it.code == DiagnosticCode.SEM_TYPE_MISMATCH }, source)
            assertEquals(listOf(DiagnosticArgument.StringArgument("Int"), DiagnosticArgument.StringArgument(type)), errors.first().arguments, source)
        }
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("for (i in 0..println()) { }"))
        // ループ変数は Int の val（代入できない）。外側の var と同じ名前でも同じ
        val loop = ok("for (i in 0..1) { println(i) }")
        assertEquals(ScriptType.INT, loop.loopVariables.values.single().type)
        assertFalse(loop.loopVariables.values.single().isMutable)
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("for (i in 0..5) { i = i + 1 }"))
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("var i = 0\nfor (i in 0..5) { i = 2 }"))
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("for (println in 0..1) { }"))
    }

    @Test
    fun forScopes() {
        // ループ変数は for の外（後・始点・終点）から見えない
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("for (i in 0..1) { }\nprintln(i)"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("for (i in 0..i) { }"))
        // 外側の同名の変数は、始点・終点では外側、本体ではループ変数を指す。本体の中での同名の宣言はシャドーイング
        val shadow = ok("val i = 5\nfor (i in 0..i) { val i = \"s\"\nprintln(i) }\nprintln(i)")
        val outer = shadow.declarations.values.minBy { it.declarationPosition.offset }
        val refs = shadow.references.entries.sortedBy { it.key.position.offset }.map { it.value }
        assertEquals(outer, refs[0])
        assertEquals(ScriptType.STRING, refs[1].type)
        assertEquals(outer, refs[2])
        // 同じ名前の for を並べる・入れ子にする（内側が外側を隠す）
        ok("for (i in 0..1) { }\nfor (i in 0..1) { for (i in i..2) { println(i) } }")
        // break / continue は for の中でも使える
        ok("for (i in 0..9) { if (i == 2) { continue }\nif (i == 5) { break } }")
        assertEquals(listOf(DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP), codes("for (i in 0..1) { }\nbreak"))
    }

    // ---- 整数の範囲（D16） ----

    @Test
    fun integerRange() {
        val max = ok("val a = 2147483647")
        assertEquals(Int.MAX_VALUE, max.intConstants.values.single())
        val min = ok("val a = -2147483648")
        val initializer = (min.program.statements.single() as VariableDeclaration).initializer
        assertEquals(Int.MIN_VALUE, min.intConstants[initializer])
        ok("val a = -(2147483648)")
        assertEquals(listOf(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE), codes("val a = 2147483648"))
        assertEquals(listOf(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE), codes("val a = -2147483649"))
        assertEquals(listOf(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE), codes("val a = +2147483648"))
        assertEquals(listOf(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE), codes("val a = 9999999999"))
        // 内側は定数 -2147483648、外側の符号反転は実行時に計算する（オーバーフローは実行時エラー）
        ok("val a = - -2147483648")
    }

    // ---- 組み込み関数 ----

    @Test
    fun builtinCalls() {
        ok("println()")
        ok("println(1 + 2)")
        ok("println(\"Hello\")")
        ok("val s = \"x\"\nprintln(s)")
        ok("waitTicks(20)")
        ok("val t = 5\nwaitTicks(t * 4)")
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), codes("println(1, 2)"))
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), codes("waitTicks()"))
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), codes("waitTicks(1, 2)"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("waitTicks(\"a\")"))
        val program = ok("println(1)")
        assertEquals(ScriptType.UNIT, program.expressionTypes[(program.program.statements.single() as ExpressionStatement).expression])
    }

    // ---- Unit（A2・A22） ----

    @Test
    fun unitIsNotAValue() {
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val u = println()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("var x = 1\nx = waitTicks(1)"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val x = println() + 1"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("println(println())"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("waitTicks(waitTicks(1))"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("val x = -println()"))
        // 式だけの文としては使える
        ok("println()\nwaitTicks(0)")
    }

    // ---- メンバーアクセス（D11。クラスのプロパティ・メソッドは第3.9b段階） ----

    @Test
    fun memberAccessIsRejected() {
        // クラス以外の値（Int・String・Boolean）のメンバーは未対応（位置はメンバー名）
        assertEquals(listOf(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED), codes("val x = 1\nval y = x.size"))
        assertEquals(listOf(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED), codes("println(1.foo)"))
        assertEquals(listOf(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED), codes("val s = \"a\"\ns.length()"))
        assertEquals(listOf(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED), codes("var b = true\nb.x = 1"))
        assertEquals(SourcePosition(12, 2, 3), analyze("val x = 1\nx.size").diagnostics.single().position)
        // メンバーを読む前に受け手を解析するため、宣言されていない受け手（Host API の gear など）は未宣言の変数になる
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("gear.start()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("gear.setSpeed(16)"))
    }

    // ---- 未使用の式（A4） ----

    @Test
    fun unusedExpressionWarning() {
        val result = analyze("val speed = 1\nspeed + 5\n\"text\"\nspeed")
        assertFalse(result.hasErrors)
        assertNotNull(result.program)
        assertEquals(3, result.diagnostics.size)
        assertTrue(result.diagnostics.all { it.code == DiagnosticCode.SEM_UNUSED_EXPRESSION && it.severity == DiagnosticSeverity.WARNING })
        // 呼び出しは副作用があるため警告しない
        ok("println(1)\nwaitTicks(1)")
        // エラーのある式には警告を重ねない
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("x + 1"))
    }

    // ---- 診断の扱い ----

    @Test
    fun errorsDoNotCascade() {
        // 初期化式にエラーがある変数は宣言だけ行い、後続の参照で未宣言エラーを重ねない
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("val a = b\nprintln(a)\nval c = a + 1"))
        assertTrue(analyze("val a = b").diagnostics.all { it.phase == DiagnosticPhase.SEMANTIC })
    }

    @Test
    fun earlierStageErrorsSkipAnalysis() {
        val result = analyze("val x = (1\nprintln(y)")
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), result.diagnostics.map { it.code })
        assertNull(result.program)
    }

    @Test
    fun diagnosticLimit() {
        val result = analyze((1..20).joinToString("\n") { "println(x$it)" })
        assertEquals(16, result.diagnostics.size)
        assertTrue(result.diagnosticsTruncated)
        assertTrue(result.hasErrors)
        // 警告で埋まっていてもエラーは残る
        val mixed = analyze("val a = 1\n" + "a\n".repeat(16) + "println(z)")
        assertEquals(16, mixed.diagnostics.size)
        assertTrue(mixed.diagnostics.any { it.code == DiagnosticCode.SEM_UNDECLARED_VARIABLE })
        assertNull(mixed.program)
    }

    // ---- ユーザー定義関数（第3.8c段階） ----

    @Test
    fun functionCallsAndScopes() {
        val analyzed = ok("fun greet() { println(1) }\ngreet()\ngreet()")
        assertEquals(2, analyzed.calls.size)
        assertTrue(analyzed.calls.values.all { it === analyzed.program.functions.single() })
        // 定義の前の呼び出し・関数から関数の呼び出し（呼び出しの順に記録する）
        val callees = ok("a()\nfun a() { b()\nc()\nb() }\nfun b() { c() }\nfun c() { }").callees
        assertEquals(listOf(listOf("b", "c", "b"), listOf("c"), emptyList()), callees.entries.map { it.key as FunctionDeclaration to it.value }.sortedBy { it.first.position.offset }.map { entry -> entry.second.map { (it as FunctionDeclaration).name } })
        // 呼び出しの結果は Unit（値として使えない）
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("fun f() { }\nval x = f()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("fun f() { }\nprintln(f())"))
        // 関数の変数は関数の中だけで見える。関数から見えるのはトップレベルのスコープの変数だけ（トップレベルのブロックの変数は見えない）
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("fun f() { val x = 1 }\nf()\nprintln(x)"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("fun f() { println(y) }\nif (true) {\n  val y = 1\n  f()\n}"))
        // 関数の中の for・while・if・break・continue・return
        ok("var count = 0\nfun f() {\n  for (i in 0..3) {\n    if (i == 1) { continue }\n    if (i == 2) { break }\n    count = count + i\n  }\n  while (true) { return }\n}\nf()")
        // break / continue は関数の境界を越えない（呼び出し元のループは関係ない）
        assertEquals(listOf(DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP), codes("fun f() { break }\nwhile (true) { f() }"))
        assertEquals(listOf(DiagnosticCode.SEM_CONTINUE_OUTSIDE_LOOP), codes("fun f() { continue }\nfor (i in 0..1) { f() }"))
        // 関数の変数はトップレベルの同名の変数を隠す。トップレベルの val には関数の中からも代入できず、型も同じ規則
        ok("val n = 1\nfun f() {\n  val n = \"s\"\n  println(n)\n}\nf()")
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("val n = 1\nfun f() { n = 2 }\nf()"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("var n = 1\nfun f() { n = \"s\" }\nf()"))
    }

    @Test
    fun functionDeclarationErrors() {
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_FUNCTION), codes("greet()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_FUNCTION), codes("fun f() { g() }"))
        // 同名の関数の重複定義（位置は 2 つ目の名前）
        val duplicate = analyze("fun f() { }\nfun f() { }").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_DUPLICATE_FUNCTION, duplicate.code)
        assertEquals(SourcePosition(16, 2, 5), duplicate.position)
        assertEquals(listOf(DiagnosticArgument.StringArgument("f")), duplicate.arguments)
        // 組み込み関数と同じ名前の関数
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("fun println() { }"))
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("fun waitTicks() { }"))
        // 変数名と関数名の衝突（定義の前後・トップレベル・関数の中・ブロックの中・ループ変数。位置は変数名）
        val conflicts = listOf(
            "val f = 1\nfun f() { }", "fun f() { }\nvar f = 1", "fun f() { }\nfun g() { val f = 2 }",
            "fun f() { }\nfor (f in 0..1) { }", "fun f() { }\nif (true) { val f = 1 }",
        )
        for (source in conflicts) assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT), codes(source), source)
        assertEquals(SourcePosition(4, 1, 5), analyze("val f = 1\nfun f() { }").diagnostics.single().position)
        // 関数を値として使う・代入する、変数を呼び出す
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("fun f() { }\nval x = f"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("fun f() { }\nf = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_NOT_CALLABLE), codes("val x = 1\nx()"))
        // 引数付きの呼び出し（引数: 関数名、引数の数）
        val arguments = analyze("fun f() { }\nf(1, 2)").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, arguments.code)
        assertEquals(listOf(DiagnosticArgument.StringArgument("f"), DiagnosticArgument.IntArgument(2)), arguments.arguments)
        // 関数の外の return（ループの中を含む）
        assertEquals(listOf(DiagnosticCode.SEM_RETURN_OUTSIDE_FUNCTION), codes("return"))
        assertEquals(listOf(DiagnosticCode.SEM_RETURN_OUTSIDE_FUNCTION), codes("while (true) { return }"))
    }

    // 再帰（第3.8e段階）: 直接・相互の再帰を許可する。引数・戻り値・return の規則は再帰でも同じ
    @Test
    fun recursionIsAllowed() {
        val factorial = ok("fun factorial(n: Int): Int {\n  if (n <= 1) {\n    return 1\n  }\n  return n * factorial(n - 1)\n}\nprintln(factorial(5))")
        val function = factorial.program.functions.single()
        assertEquals(listOf(function), factorial.callees.getValue(function))
        ok("fun isEven(n: Int): Boolean {\n  if (n == 0) { return true }\n  return isOdd(n - 1)\n}\nfun isOdd(n: Int): Boolean {\n  if (n == 0) { return false }\n  return isEven(n - 1)\n}\nprintln(isEven(4))")
        ok("fun countdown(n: Int) {\n  if (n == 0) { return }\n  println(n)\n  countdown(n - 1)\n}\ncountdown(3)")
        ok("fun loop() { loop() }")
        // 再帰でも引数・戻り値の型と return の不足を確かめる
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("fun f(n: Int): Int { return f(true) }"))
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), codes("fun f(n: Int): Int { return f() }"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("fun f(n: Int): Int { return f(n - 1) == 1 }"))
        assertEquals(listOf(DiagnosticCode.SEM_MISSING_RETURN), codes("fun f(n: Int): Int { if (n > 0) { return f(n - 1) } }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("fun f(n: Int) { println(f(n)) }"))
        // 循環を通して使う変数も、トップレベルの呼び出しより前に宣言されていなければならない（循環でも解析は止まる）
        assertEquals(listOf(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION), codes("fun a() { b() }\nfun b() {\n  println(n)\n  a()\n}\na()\nvar n = 0"))
        ok("fun a() { b() }\nfun b() {\n  println(n)\n  a()\n}\nvar n = 0\na()")
    }

    // 関数が（呼び出す関数を通して）使うトップレベルの変数は、トップレベルの呼び出しを含む文より前の文で宣言されていなければならない
    @Test
    fun globalsUsedByFunctionsMustBeDeclaredBeforeTheCall() {
        ok("fun show() { println(total) }\nval total = 5\nshow()")
        val early = analyze("fun show() { println(total) }\nshow()\nval total = 5").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION, early.code)
        assertEquals(SourcePosition(30, 2, 1), early.position)
        assertEquals(listOf(DiagnosticArgument.StringArgument("show"), DiagnosticArgument.StringArgument("total")), early.arguments)
        // 呼び出す関数を通して使う変数・代入だけの変数、後で宣言する変数をループの中で使う呼び出しも同じ
        assertEquals(listOf(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION), codes("fun a() { b() }\nfun b() { n = 1 }\na()\nvar n = 0"))
        assertEquals(listOf(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION), codes("fun f() { println(n) }\nwhile (true) { f() }\nvar n = 0"))
        // 関数の中からの呼び出しは、トップレベルの呼び出しの位置で確かめる
        ok("fun a() { b() }\nfun b() { println(n) }\nvar n = 0\na()")
        // 関数の中の同名のローカル変数は、トップレベルの変数を使わない
        ok("fun f() {\n  val n = 1\n  println(n)\n}\nf()\nval n = 2")
    }

    // ---- 引数・戻り値（第3.8d段階） ----

    @Test
    fun parametersAndReturnTypes() {
        val source = "fun add(a: Int, b: Int): Int {\n  return a + b\n}\nval r = add(1, 2)\nprintln(add(r, add(3, 4)))"
        val analyzed = ok(source)
        val add = analyzed.program.functions.single()
        assertEquals(ScriptType.INT, analyzed.returnTypes[add])
        assertEquals(listOf("a", "b"), analyzed.parameters.getValue(add).map { it.name })
        assertTrue(analyzed.parameters.getValue(add).all { !it.isMutable && it.type == ScriptType.INT })
        assertEquals(ScriptType.INT, declaredType(source, "r"))
        // Boolean・String の引数と戻り値、Unit の明示、定義の前の呼び出し
        ok(
            "fun pick(c: Boolean, s: String): String {\n  if (c) { return s } else { return \"no\" }\n}\nfun show(s: String): Unit { println(s) }\n" +
                "show(pick(true, \"yes\"))\nval b: Boolean = isBig(5)\nfun isBig(n: Int): Boolean { return n > 3 }",
        )
        assertEquals(ScriptType.UNIT, ok("fun f(): Unit { }").returnTypes.values.single())
        // 戻り値を使わない呼び出し（結果は捨てる。警告なし）
        ok("fun one(): Int { return 1 }\none()")
        // 引数のスコープ: 本体の中の同名の宣言はシャドーイング、関数の外からは見えない、トップレベルの同名の変数を隠す
        ok("fun f(a: Int) {\n  val a = \"s\"\n  println(a)\n}\nf(1)")
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("fun f(a: Int) { }\nf(1)\nprintln(a)"))
        ok("val a = \"global\"\nfun f(a: Int): Int { return a + 1 }\nprintln(f(1))")
        // 引数は読み取り専用
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("fun f(a: Int) { a = 2 }"))
    }

    @Test
    fun parameterAndReturnErrors() {
        val add = "fun add(a: Int, b: Int): Int { return a + b }\n"
        // 引数の個数（引数: 関数名、渡した数）・型（引数: 関数名、期待する型、実際の型。位置は引数）
        val count = analyze(add + "println(add(1))").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, count.code)
        assertEquals(listOf(DiagnosticArgument.StringArgument("add"), DiagnosticArgument.IntArgument(1)), count.arguments)
        val type = analyze(add + "println(add(1, true))").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, type.code)
        assertEquals(SourcePosition(61, 2, 16), type.position)
        assertEquals(listOf("add", "Int", "Boolean").map { DiagnosticArgument.StringArgument(it) }, type.arguments)
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes(add + "println(add(\"1\", 2))"))
        // Unit を引数に渡す・Unit 型の引数、使えない型（Long・Set など）
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("fun u() { }\nfun f(a: Int) { }\nf(u())"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("fun f(a: Unit) { }"))
        for (source in listOf("fun f(a: Long) { }", "fun f(): Char { return 1 }", "fun f(a: Set) { }", "fun f(): Any { return 1 }")) {
            assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_TYPE), codes(source), source)
        }
        // Float・Double は第3.11a段階で組み込みの型になった（戻り値への Int の暗黙の変換は無い）
        ok("fun f(a: Float) { }")
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("fun f(): Double { return 1 }"))
        // List は第3.10a段階で組み込みの型になり、型引数が 1 つ要る
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT), codes("fun f(a: List) { }"))
        // 引数名の重複・組み込み関数名・関数名
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_DECLARATION), codes("fun f(a: Int, a: Int) { }"))
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("fun f(println: Int) { }"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT), codes("fun g() { }\nfun f(g: Int) { }"))
        // 戻り値の型の不一致（引数: 期待する型、実際の型）・Unit の関数から値を返す
        val mismatch = analyze("fun f(): Int { return \"s\" }").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_TYPE_MISMATCH, mismatch.code)
        assertEquals(listOf("Int", "String").map { DiagnosticArgument.StringArgument(it) }, mismatch.arguments)
        val unitValue = analyze("fun f() { return 1 }").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_TYPE_MISMATCH, unitValue.code)
        assertEquals(listOf("Unit", "Int").map { DiagnosticArgument.StringArgument(it) }, unitValue.arguments)
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("fun f(): Unit { return true }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("fun u() { }\nfun f() { return u() }"))
        // 値を返す関数の値の無い return（引数: 関数名、戻り値の型。位置は return）
        val bare = analyze("fun f(): Int {\n  return\n}").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_RETURN_WITHOUT_VALUE, bare.code)
        assertEquals(SourcePosition(17, 2, 3), bare.position)
        assertEquals(listOf("f", "Int").map { DiagnosticArgument.StringArgument(it) }, bare.arguments)
        // 結果の型の使い方
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("fun one(): Int { return 1 }\nval s: String = one()"))
        assertEquals(listOf(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN), codes("fun one(): Int { return 1 }\nif (one()) { }"))
        // 関数を値として使う
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("fun one(): Int { return 1 }\nval x = one"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("fun one(): Int { return 1 }\nprintln(one + 1)"))
    }

    // 値を返す関数は、本体のすべての経路が return で終わらなければならない
    @Test
    fun everyPathMustReturnAValue() {
        val missing = analyze("fun f(): Int {\n  println(1)\n}").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_MISSING_RETURN, missing.code)
        assertEquals(SourcePosition(4, 1, 5), missing.position)
        assertEquals(listOf("f", "Int").map { DiagnosticArgument.StringArgument(it) }, missing.arguments)
        val incomplete = listOf(
            "fun f(c: Boolean): Int { if (c) { return 1 } }",
            "fun f(c: Boolean): Int { if (c) { return 1 } else if (!c) { return 2 } }",
            "fun f(c: Boolean): Int { if (c) { return 1 } else { println(2) } }",
            // while・for の中の return だけでは足りない（while (true) も同じ）
            "fun f(): Int { while (true) { return 1 } }",
            "fun f(): Int { for (i in 0..1) { return i } }",
            "fun f(): Boolean { }",
        )
        for (source in incomplete) assertEquals(listOf(DiagnosticCode.SEM_MISSING_RETURN), codes(source), source)
        // すべての経路で返す（else if の連鎖・入れ子の if・ループの後の return）
        ok("fun f(c: Boolean): Int {\n  if (c) { return 1 } else if (!c) { return 2 } else { return 3 }\n}")
        ok("fun f(c: Boolean): Int {\n  if (c) {\n    if (!c) { return 1 } else { return 2 }\n  } else {\n    return 3\n  }\n}")
        ok("fun f(): Int {\n  for (i in 0..3) {\n    if (i == 2) { return i }\n  }\n  while (true) { return 9 }\n  return 0\n}")
        // return の後の文（到達しない）は警告しない。Unit の関数は末尾に return が無くてよい
        ok("fun f(): Int {\n  return 1\n  println(2)\n}")
        ok("fun f(c: Boolean) { if (c) { return } }")
    }

    // ---- クラス（第3.9a段階） ----

    @Test
    fun classTypes() {
        val analyzed = ok("class Robot {}\nval robot = Robot()\nval same: Robot = robot\nprintln(robot == same)")
        val robot = analyzed.program.classes.single()
        val type = analyzed.declarations.values.first { it.name == "robot" }.type
        assertEquals(ScriptType.ClassType(robot), type)
        assertEquals("Robot", type?.displayName)
        assertEquals(robot, analyzed.constructions.values.single())
        // 宣言の前の使用・関数の引数と戻り値・var への代入・再帰
        ok("val r = Robot()\nclass Robot")
        ok("fun make(): Robot { return Robot() }\nfun same(a: Robot, b: Robot): Boolean { return a == b }\nclass Robot\nvar r = make()\nr = Robot()\nprintln(same(r, make()))")
        ok("class Robot\nfun keep(n: Int, r: Robot): Robot {\n  if (n == 0) { return r }\n  return keep(n - 1, r)\n}\nprintln(keep(3, Robot()))")
        // 同じクラスどうしの == / !=
        ok("class A\nprintln(A() != A())")
    }

    @Test
    fun classErrors() {
        // 未定義のクラス（生成は未定義の関数、型は未知の型）
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_FUNCTION), codes("val r = Robot()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_TYPE), codes("fun f(r: Robot) { }"))
        // 同名のクラス（位置は 2 つ目）・関数・組み込みの型名・組み込み関数名・変数名との衝突
        val duplicate = analyze("class A\nclass A").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_DUPLICATE_CLASS, duplicate.code)
        assertEquals(SourcePosition(14, 2, 7), duplicate.position)
        for (source in listOf("class A\nfun A() { }", "fun A() { }\nclass A", "class Int", "class A\nval A = 1", "class A\nfun f(A: Int) { }", "class A\nfor (A in 0..1) { }")) {
            assertEquals(listOf(DiagnosticCode.SEM_CLASS_NAME_CONFLICT), codes(source), source)
        }
        assertEquals(SourcePosition(4, 1, 5), analyze("fun A() { }\nclass A").diagnostics.single().position)
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("class println"))
        // クラス名を値として使う・コンストラクタの引数
        assertEquals(listOf(DiagnosticCode.SEM_CLASS_AS_VALUE), codes("class A\nval x = A"))
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), codes("class A\nval a = A(1)"))
        // 型の不一致（代入・宣言・引数・戻り値）
        val mismatch = analyze("class A\nclass B\nval a: A = B()").diagnostics.single()
        assertEquals(DiagnosticCode.SEM_TYPE_MISMATCH, mismatch.code)
        assertEquals(listOf("A", "B").map { DiagnosticArgument.StringArgument(it) }, mismatch.arguments)
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class A\nvar a = A()\na = 1"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("class A\nclass B\nfun f(a: A) { }\nf(B())"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class A\nfun f(): A { return 1 }"))
        // 比較: 別のクラス・別の型とは比べられない。大小比較・条件・算術には使えない
        assertEquals(listOf(DiagnosticCode.SEM_INCOMPARABLE_TYPES), codes("class A\nclass B\nprintln(A() == B())"))
        assertEquals(listOf(DiagnosticCode.SEM_INCOMPARABLE_TYPES), codes("class A\nprintln(A() == 1)"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("class A\nprintln(A() < A())"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("class A\nprintln(A() + 1)"))
        assertEquals(listOf(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN), codes("class A\nif (A()) { }"))
    }
}
