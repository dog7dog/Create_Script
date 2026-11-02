package com.creatrbypc.createanalytical.script.parser

import com.creatrbypc.createanalytical.script.ast.Assignment
import com.creatrbypc.createanalytical.script.ast.BinaryExpression
import com.creatrbypc.createanalytical.script.ast.BinaryOperator
import com.creatrbypc.createanalytical.script.ast.Block
import com.creatrbypc.createanalytical.script.ast.BreakStatement
import com.creatrbypc.createanalytical.script.ast.BooleanLiteral
import com.creatrbypc.createanalytical.script.ast.CallExpression
import com.creatrbypc.createanalytical.script.ast.ContinueStatement
import com.creatrbypc.createanalytical.script.ast.DoubleLiteral
import com.creatrbypc.createanalytical.script.ast.Expression
import com.creatrbypc.createanalytical.script.ast.ExpressionStatement
import com.creatrbypc.createanalytical.script.ast.FloatLiteral
import com.creatrbypc.createanalytical.script.ast.ForStatement
import com.creatrbypc.createanalytical.script.ast.IfStatement
import com.creatrbypc.createanalytical.script.ast.IndexExpression
import com.creatrbypc.createanalytical.script.ast.IntLiteral
import com.creatrbypc.createanalytical.script.ast.LogicalExpression
import com.creatrbypc.createanalytical.script.ast.LogicalOperator
import com.creatrbypc.createanalytical.script.ast.MemberAccessExpression
import com.creatrbypc.createanalytical.script.ast.NameReference
import com.creatrbypc.createanalytical.script.ast.Program
import com.creatrbypc.createanalytical.script.ast.RangeKind
import com.creatrbypc.createanalytical.script.ast.ReturnStatement
import com.creatrbypc.createanalytical.script.ast.Statement
import com.creatrbypc.createanalytical.script.ast.StringLiteral
import com.creatrbypc.createanalytical.script.ast.ThisExpression
import com.creatrbypc.createanalytical.script.ast.UnaryExpression
import com.creatrbypc.createanalytical.script.ast.UnaryOperator
import com.creatrbypc.createanalytical.script.ast.VariableDeclaration
import com.creatrbypc.createanalytical.script.ast.WhileStatement
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.Lexer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParserTest {
    private fun parse(source: String) = Parser.parse(Lexer.tokenize(source))

    private fun program(source: String): Program {
        val result = parse(source)
        assertEquals(emptyList<DiagnosticCode>(), result.diagnostics.map { it.code }, "unexpected diagnostics for: $source")
        return result.program!!
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = parse(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    private fun firstCode(source: String) = codes(source).first()

    // 位置を除いた S 式で AST を表す
    private fun render(expression: Expression): String = when (expression) {
        is IntLiteral -> expression.value.toString()
        is FloatLiteral -> expression.value.toString() + "f"
        is DoubleLiteral -> expression.value.toString()
        is StringLiteral -> "\"${expression.value}\""
        is BooleanLiteral -> expression.value.toString()
        is NameReference -> expression.name
        is UnaryExpression -> "(${unarySymbol(expression.operator)} ${render(expression.operand)})"
        is BinaryExpression -> "(${symbol(expression.operator)} ${render(expression.left)} ${render(expression.right)})"
        is LogicalExpression -> "(${if (expression.operator == LogicalOperator.AND) "&&" else "||"} ${render(expression.left)} ${render(expression.right)})"
        is CallExpression -> "(call ${render(expression.callee)}${expression.arguments.joinToString("") { " " + render(it) }})"
        is MemberAccessExpression -> "(. ${render(expression.receiver)} ${expression.name})"
        is ThisExpression -> "this"
        is IndexExpression -> "([] ${render(expression.receiver)} ${render(expression.index)})"
    }

    private fun unarySymbol(operator: UnaryOperator) = when (operator) {
        UnaryOperator.MINUS -> "neg"
        UnaryOperator.PLUS -> "pos"
        UnaryOperator.NOT -> "not"
    }

    private fun symbol(operator: BinaryOperator) = when (operator) {
        BinaryOperator.ADD -> "+"
        BinaryOperator.SUBTRACT -> "-"
        BinaryOperator.MULTIPLY -> "*"
        BinaryOperator.DIVIDE -> "/"
        BinaryOperator.REMAINDER -> "%"
        BinaryOperator.EQUAL -> "=="
        BinaryOperator.NOT_EQUAL -> "!="
        BinaryOperator.LESS -> "<"
        BinaryOperator.LESS_EQUAL -> "<="
        BinaryOperator.GREATER -> ">"
        BinaryOperator.GREATER_EQUAL -> ">="
    }

    private fun render(statement: Statement): String = when (statement) {
        is VariableDeclaration ->
            "(${if (statement.isMutable) "var" else "val"} ${statement.name}${statement.type?.let { ":" + it.name } ?: ""} ${render(statement.initializer)})"
        is Assignment -> "(= ${render(statement.target)} ${render(statement.value)})"
        is ExpressionStatement -> render(statement.expression)
        is IfStatement -> "(if" + statement.branches.joinToString("") { " ${render(it.condition)} ${render(it.body)}" } +
            (statement.elseBlock?.let { " else ${render(it)}" } ?: "") + ")"
        is WhileStatement -> "(while ${render(statement.condition)} ${render(statement.body)})"
        is ForStatement -> "(for ${statement.variable} ${render(statement.start)} ${rangeSymbol(statement.kind)} ${render(statement.end)} ${render(statement.body)})"
        is BreakStatement -> "break"
        is ContinueStatement -> "continue"
        is ReturnStatement -> statement.value?.let { "(return ${render(it)})" } ?: "return"
    }

    private fun rangeSymbol(kind: RangeKind) = when (kind) {
        RangeKind.RANGE_TO -> ".."
        RangeKind.UNTIL -> "until"
        RangeKind.DOWN_TO -> "downTo"
    }

    private fun render(block: Block) = "{" + block.statements.joinToString(" ") { render(it) } + "}"

    private fun statements(source: String) = program(source).statements.map(::render)

    private fun expression(source: String) = statements(source).single()

    // ---- 正常系 ----

    @Test
    fun declarations() {
        assertEquals(listOf("(val speed 5)"), statements("val speed = 5"))
        assertEquals(listOf("(var n:Int 3)"), statements("var n: Int = 3"))
        assertEquals(listOf("(val s:String \"Hello\")"), statements("val s: String = \"Hello\""))
        // 型名の妥当性は Semantic 段階で判定する
        assertEquals(listOf("(val x:Foo 1)"), statements("val x: Foo = 1"))
    }

    @Test
    fun assignmentAndExpressionStatements() {
        assertEquals(listOf("(var x 1)", "(= x (+ x 1))"), statements("var x = 1\nx = x + 1"))
        assertEquals(listOf("(+ speed 5)"), statements("speed + 5"))
    }

    @Test
    fun positions() {
        val declaration = program("  val x = 1").statements.single() as VariableDeclaration
        assertEquals(SourcePosition(2, 1, 3), declaration.position)
        assertEquals(SourcePosition(6, 1, 7), declaration.namePosition)
        val binary = (program("a + b").statements.single() as ExpressionStatement).expression as BinaryExpression
        assertEquals(SourcePosition(2, 1, 3), binary.position)
    }

    // ---- 優先順位・括弧・単項 ----

    @Test
    fun precedence() {
        assertEquals("(+ 1 (* 2 3))", expression("1 + 2 * 3"))
        assertEquals("(* (+ 1 2) 3)", expression("(1 + 2) * 3"))
        assertEquals("(- (- 10 3) 2)", expression("10 - 3 - 2"))
        assertEquals("(% (/ 8 2) 3)", expression("8 / 2 % 3"))
        assertEquals("(- 1 (* 2 (neg 3)))", expression("1 - 2 * -3"))
        assertEquals("(* (neg 2) 3)", expression("-2 * 3"))
        assertEquals("(neg (. a b))", expression("-a.b"))
        assertEquals("(pos 1)", expression("+1"))
    }

    @Test
    fun negativeLiterals() {
        assertEquals("(neg 2147483648)", expression("-2147483648"))
        assertEquals("(neg 2147483648)", expression("-(2147483648)"))
        assertEquals("(neg (neg 5))", expression("- -5"))
        // "--" はデクリメント演算子であり、-(-5) ではない
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("--5"))
    }

    // ---- Boolean・比較・論理演算子（第3.7b段階） ----

    @Test
    fun booleanLiterals() {
        assertEquals(listOf("(val b true)", "(var c:Boolean false)", "(= c b)"), statements("val b = true\nvar c: Boolean = false\nc = b"))
    }

    @Test
    fun comparisonAndLogicalPrecedence() {
        // 後置 > 前置 - + ! > * / % > + - > < <= > >= > == != > && > ||。すべて左結合
        assertEquals("(|| (|| a b) c)", expression("a || b || c"))
        assertEquals("(&& (&& a b) c)", expression("a && b && c"))
        assertEquals("(|| a (&& b c))", expression("a || b && c"))
        assertEquals("(|| (&& a b) c)", expression("a && b || c"))
        assertEquals("(&& (== a b) (!= c d))", expression("a == b && c != d"))
        assertEquals("(== (< a b) (>= c d))", expression("a < b == c >= d"))
        assertEquals("(< (+ 1 2) (* 3 4))", expression("1 + 2 < 3 * 4"))
        assertEquals("(<= (> (< a b) c) d)", expression("a < b > c <= d"))
        assertEquals("(== (== a b) c)", expression("a == b == c"))
        assertEquals("(&& (not a) b)", expression("!a && b"))
        assertEquals("(not (&& a b))", expression("!(a && b)"))
        assertEquals("(== (not a) b)", expression("!a == b"))
        assertEquals("(not (. a b))", expression("!a.b"))
        assertEquals("(< (neg a) b)", expression("-a < b"))
        assertEquals("(&& (|| a b) c)", expression("(a || b) && c"))
        assertEquals("(call println (|| (&& a b) (not c)))", expression("println(a && b || !c)"))
    }

    @Test
    fun prefixNotOperators() {
        // 前置の "!!" は !(!x)（11.9-8）。2つ目の "!" の位置は1つ後ろ
        assertEquals("(not (not a))", expression("!!a"))
        assertEquals("(not (not (not a)))", expression("!!!a"))
        assertEquals("(not (not (not (not a))))", expression("! ! !!a"))
        val outer = (program("  !!a").statements.single() as ExpressionStatement).expression as UnaryExpression
        assertEquals(SourcePosition(2, 1, 3), outer.position)
        assertEquals(SourcePosition(3, 1, 4), outer.operand.position)
        // 後置の "!!" は未対応のまま
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("a!!"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("val b = a!! && c"))
    }

    @Test
    fun newlinesAroundComparisonAndLogicalOperators() {
        // 演算子の直後の改行は継続する
        assertEquals("(&& a b)", expression("a &&\nb"))
        assertEquals("(|| a b)", expression("a ||\n\nb"))
        assertEquals("(== a b)", expression("a ==\nb"))
        assertEquals("(< a b)", expression("a <\nb"))
        assertEquals("(not a)", expression("!\na"))
        // 次の行の先頭の && || は継続する（Kotlin 2.0 と同じ）
        assertEquals("(&& a b)", expression("a\n&& b"))
        assertEquals("(|| (&& a b) c)", expression("a\n  && b\n\n  || c"))
        assertEquals(listOf("(val x (|| a (&& b c)))", "(call println x)"), statements("val x = a\n|| b\n&& c\nprintln(x)"))
        // 比較演算子は次の行の先頭では継続しない（Kotlin も構文エラー）
        for (source in listOf("a\n== b", "a\n!= b", "a\n< b", "a\n<= b", "a\n> b", "a\n>= b")) {
            assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes(source), source)
        }
        // 括弧の中では改行の位置によらない（D18）
        assertEquals("(== a b)", expression("(a\n== b)"))
        assertEquals("(call println (< a b))", expression("println(a\n<\nb)"))
    }

    @Test
    fun comparisonAndLogicalErrors() {
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("a &&"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("a == "))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("!"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("&& a"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("a < < b"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("a !"))
        // true / false は識別子に使えない
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("val true = 1"))
        assertEquals(listOf(DiagnosticCode.PARSE_INVALID_ASSIGNMENT_TARGET), codes("true = false"))
        assertEquals(listOf(DiagnosticCode.PARSE_INVALID_ASSIGNMENT_TARGET), codes("a == b = c"))
        // 論理演算子・比較の連鎖も木の深さとして数える
        program("a" + " && a".repeat(31))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes("a" + " && a".repeat(32)))
        program("!".repeat(31) + "a")
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes("!".repeat(32) + "a"))
    }

    // ---- if 文とブロック（第3.7c段階） ----

    @Test
    fun ifStatements() {
        assertEquals(listOf("(if true {(call println \"OK\")})"), statements("if (true) {\n    println(\"OK\")\n}"))
        assertEquals(listOf("(if a {} else {})"), statements("if (a) { } else { }"))
        assertEquals(listOf("(if a {x y})"), statements("if (a) { x; y }"))
        assertEquals(listOf("(if a {x y})"), statements("if (a) {\n\n x\n\n y;\n}"))
        // else if は平らに並べる
        assertEquals(listOf("(if a {1} b {2} c {3} else {4})"), statements("if (a) { 1 } else if (b) { 2 } else if (c) { 3 } else { 4 }"))
        assertEquals(listOf("(if a {1} b {2})"), statements("if (a) { 1 } else if (b) { 2 }"))
        // 入れ子・ブロックの中の宣言と代入
        assertEquals(
            listOf("(var n 0)", "(if (< n 1) {(val m 2) (if (== m 2) {(= n m)} else {(= n 0)})})", "(call println n)"),
            statements("var n = 0\nif (n < 1) {\n  val m = 2\n  if (m == 2) { n = m } else { n = 0 }\n}\nprintln(n)"),
        )
        // 条件は括弧の中なので改行できる
        assertEquals(listOf("(if (&& a b) {})"), statements("if (a &&\n b\n) { }"))
    }

    @Test
    fun newlinesAroundIfAndElse() {
        // ")" と "{"、"}" と else、else と "{" / if の間の改行は可
        assertEquals(listOf("(if a {x} else {y})"), statements("if (a)\n{\n x\n}\nelse\n{\n y\n}"))
        assertEquals(listOf("(if a {x} b {y})"), statements("if (a) { x }\n\nelse\nif (b) { y }"))
        // if 文の後にも文の区切りが必要（Kotlin 2.0 でも1行ではエラー）
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("if (a) { } println(1)"))
        assertEquals(listOf("(if a {})", "(call println 1)"), statements("if (a) { }; println(1)"))
        // "} ; else" は拒否（11.9-8）
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_ELSE), codes("if (a) { } ; else { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_ELSE), codes("if (a) { }\n;else { }").filter { it == DiagnosticCode.PARSE_UNEXPECTED_ELSE })
        // if と "(" の間の改行は不可（次の行は別の文として解析を続ける）
        assertEquals(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN, firstCode("if\n(a) { }"))
    }

    @Test
    fun ifAndBlockErrors() {
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN), codes("if a { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("if () { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), codes("if (a { }"))
        // {} は必須（D4）
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("if (a) println(1)"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("if (a) { } else println(1)"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE), codes("if (a) {\n println(1)"))
        assertEquals(List(2) { DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE }, codes("if (a) {\n if (b) {\n"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_RIGHT_BRACE), codes("println(1)\n}"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_ELSE), codes("else { println(1) }"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_ELSE), codes("if (a) { } else { } else { }"))
        // ブロックの中の空文は D18 と同じくエラー
        assertEquals(listOf(DiagnosticCode.PARSE_EMPTY_STATEMENT), codes("if (a) { ; }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EMPTY_STATEMENT), codes("if (a) { x;; }"))
        // ブロックの中の単独の { } は従来どおりラムダ（A21）
        assertEquals(listOf(DiagnosticCode.PARSE_LAMBDA_UNSUPPORTED), codes("if (a) { { } }"))
        // if 式は対象外
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes("val x = if (a) { 1 } else { 2 }"))
        // 同じ行で "}" の直前に文の区切りは不要だが、文の途中で "}" は閉じられない
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("if (a) { val x = }"))
    }

    @Test
    fun recoveryInsideBlocks() {
        // ブロックの中のエラーは次の文から再開し、ブロックを閉じる "}" を越えない
        val result = parse("if (a) {\n  val = 1\n  println(2)\n  val y\n}\nval = 3\nprintln(4)")
        assertEquals(
            listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, DiagnosticCode.PARSE_EXPECTED_INITIALIZER, DiagnosticCode.PARSE_EXPECTED_IDENTIFIER),
            result.diagnostics.map { it.code },
        )
        assertEquals(listOf(2, 4, 6), result.diagnostics.map { it.position.line })
        // 同じ行の "}" の手前で止まる
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("if (a) { val = 1 }\nval = 2"))
        // if の見出しのエラーでは、その if 全体（ブロックの中身を含む）を読み飛ばす
        val header = parse("if (1 +) {\n  val = 1\n}\nval = 2")
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION, DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), header.diagnostics.map { it.code })
        assertEquals(listOf(1, 4), header.diagnostics.map { it.position.line })
    }

    @Test
    fun blockNestingCountsWithExpressions() {
        fun nested(blocks: Int, expression: String) = "if (a) {\n".repeat(blocks) + expression + "\n}".repeat(blocks)
        // ブロック 31 段の中の深さ 1 の式で合計 32
        program(nested(31, "x"))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes(nested(32, "x")))
        // ブロック 10 段なら式は深さ 22 まで
        program(nested(10, "x" + " + x".repeat(21)))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes(nested(10, "x" + " + x".repeat(22))))
        program(nested(10, "(".repeat(21) + "x" + ")".repeat(21)))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes(nested(10, "(".repeat(22) + "x" + ")".repeat(22))))
        // else if の連鎖は深さを使わない
        program("if (a) { x }" + " else if (a) { x }".repeat(100))
    }

    // ---- while 文（第3.7d段階） ----

    @Test
    fun whileStatements() {
        assertEquals(listOf("(while true {})"), statements("while (true) { }"))
        assertEquals(listOf("(var i 0)", "(while (< i 3) {(call println i) (= i (+ i 1))})"), statements("var i = 0\nwhile (i < 3) {\n  println(i)\n  i = i + 1\n}"))
        // ")" と "{" の間の改行は可。条件は括弧の中なので改行できる
        assertEquals(listOf("(while (&& a b) {x})"), statements("while (a\n&&\nb)\n{\n x\n}"))
        // 入れ子・if との組み合わせ
        assertEquals(
            listOf("(while a {(while b {(if c {x} else {y})})})"),
            statements("while (a) {\n  while (b) {\n    if (c) { x } else { y }\n  }\n}"),
        )
        assertEquals(listOf("(if a {(while b {})} else {(while c {})})"), statements("if (a) { while (b) { } } else { while (c) { } }"))
    }

    @Test
    fun whileErrors() {
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN), codes("while a { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("while () { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), codes("while (a { }"))
        // {} は必須
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("while (a) println(1)"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("while (true)"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE), codes("while (a) {\n println(1)"))
        // while に else は無い
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_ELSE), codes("while (a) { } else { }"))
        // while の後にも文の区切りが必要。while は式ではない
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("while (a) { } println(1)"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes("val x = while (a) { }"))
        // ループの中のエラーからも回復する
        val result = parse("while (a) {\n  val = 1\n  println(2)\n}\nval = 3")
        assertEquals(listOf(2, 5), result.diagnostics.map { it.position.line })
        // 入れ子の上限は if と同じ（ブロックと式の合計 32）
        fun nested(blocks: Int) = "while (a) {\n".repeat(blocks) + "x" + "\n}".repeat(blocks)
        program(nested(31))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes(nested(32)))
    }

    // ---- break / continue（第3.8a段階） ----

    @Test
    fun breakAndContinue() {
        assertEquals(listOf("(while a {break})"), statements("while (a) { break }"))
        assertEquals(listOf("(while a {continue x})"), statements("while (a) {\n  continue\n  x\n}"))
        assertEquals(listOf("(while a {(if b {break} else {continue})})"), statements("while (a) { if (b) { break } else { continue } }"))
        assertEquals(listOf("(while a {break continue})"), statements("while (a) { break; continue }"))
        // ループの外かどうかは Semantic 段階で判定する（構文としては文）
        assertEquals(listOf("break", "continue"), statements("break\ncontinue"))
        val position = (program("while (a) {\n  break\n}").statements.single() as WhileStatement).body.statements.single().position
        assertEquals(SourcePosition(14, 2, 3), position)
        // ラベル・値は未対応。式としても使えない
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("while (a) { break@outer }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("while (a) { break 1 }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("while (a) { continue x }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("break = 1"))
    }

    // ---- 整数範囲の for（第3.8b段階） ----

    @Test
    fun forStatements() {
        assertEquals(listOf("(for i 0 .. 5 {(call println i)})"), statements("for (i in 0..5) {\n    println(i)\n}"))
        assertEquals(listOf("(for i 0 until 5 {})"), statements("for (i in 0 until 5) { }"))
        assertEquals(listOf("(for i 5 downTo 1 {})"), statements("for (i in 5 downTo 1) { }"))
        // 始点・終点は加減算の式（".." until downTo は + - より弱い）。括弧の中・")" と "{" の間は改行できる
        assertEquals(listOf("(for k (+ a 1) .. (* b 2) {})"), statements("for (k in a + 1..b * 2) { }"))
        assertEquals(listOf("(for k (neg 2147483648) until (- n 1) {x})"), statements("for (\n  k\n  in\n  -2147483648\n  until\n  n - 1\n)\n{\n  x\n}"))
        assertEquals(listOf("(for i (< a b) .. c {})"), statements("for (i in (a < b)..c) { }"))
        // 入れ子・break / continue・他の構文との組み合わせ
        assertEquals(
            listOf("(for i 0 .. 2 {(for j i downTo 0 {(if (== j 1) {continue} else {break})}) (while a {break})})"),
            statements("for (i in 0..2) {\n  for (j in i downTo 0) {\n    if (j == 1) { continue } else { break }\n  }\n  while (a) { break }\n}"),
        )
        // until / downTo は for の外では普通の識別子
        assertEquals(listOf("(val until 1)", "(val downTo until)"), statements("val until = 1\nval downTo = until"))
        assertEquals(listOf("(for until until until downTo {})"), statements("for (until in until until downTo) { }"))
        val loop = program("for (abc in 1 downTo 0) { }").statements.single() as ForStatement
        assertEquals(SourcePosition(0, 1, 1), loop.position)
        assertEquals(SourcePosition(5, 1, 6), loop.variablePosition)
        assertEquals(SourcePosition(14, 1, 15), loop.rangePosition)
    }

    @Test
    fun forErrors() {
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN), codes("for i in 0..5 { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LOOP_VARIABLE), codes("for (1 in 0..5) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LOOP_VARIABLE), codes("for (val i in 0..5) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IN), codes("for (i: Int in 0..5) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IN), codes("for (i of 0..5) { }"))
        // 範囲の演算子が無い・未対応（コレクション・to・比較）
        for (source in listOf("for (i in xs) { }", "for (i in 0 to 5) { }", "for (i in 0 < 5) { }", "for (i in 0) { }")) {
            assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RANGE), codes(source), source)
        }
        val range = parse("for (i in xs) { }").diagnostics.single()
        assertEquals(SourcePosition(12, 1, 13), range.position)
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("for (i in 0..<5) { }"))
        // step は未対応
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), codes("for (i in 0..10 step 2) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("for (i in 0..) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("for (i in ..5) { }"))
        // {} は必須
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("for (i in 0..5) println(i)"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE), codes("for (i in 0..5) {\n println(i)"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNEXPECTED_ELSE), codes("for (i in 0..5) { } else { }"))
        // for の外の範囲は従来どおり未対応の演算子
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("val r = 0..5"))
        // 入れ子の上限は if / while と同じ
        fun nested(blocks: Int) = "for (i in 0..1) {\n".repeat(blocks) + "x" + "\n}".repeat(blocks)
        program(nested(31))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes(nested(32)))
        // エラーからの回復（ブロックの中身ごと読み飛ばす）
        val recovered = parse("for (i in xs) {\n  val = 1\n}\nval = 2")
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RANGE, DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), recovered.diagnostics.map { it.code })
        assertEquals(listOf(1, 4), recovered.diagnostics.map { it.position.line })
    }

    @Test
    fun parenthesesErrors() {
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), codes("(1 + 2"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("()"))
    }

    // ---- 呼び出し・メンバーアクセス ----

    @Test
    fun calls() {
        assertEquals("(call println)", expression("println()"))
        assertEquals("(call println 1)", expression("println(1)"))
        assertEquals("(call println \"Hello\")", expression("println(\"Hello\")"))
        assertEquals("(call waitTicks (* 2 10))", expression("waitTicks(2 * 10)"))
        assertEquals("(call f 1 (+ 2 3))", expression("f(1, 2 + 3)"))
        val call = (program("println(1)").statements.single() as ExpressionStatement).expression as CallExpression
        assertEquals(SourcePosition(0, 1, 1), call.position)
    }

    @Test
    fun memberAccess() {
        assertEquals("(call (. gear start))", expression("gear.start()"))
        assertEquals("(call (. gear setSpeed) 16)", expression("gear.setSpeed(16)"))
        assertEquals("(. (. a b) c)", expression("a.b.c"))
        assertEquals("(. 1 foo)", expression("1.foo"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_MEMBER_NAME), codes("gear.when"))
    }

    // ---- 改行・セミコロン（D18） ----

    @Test
    fun newlineContinuation() {
        assertEquals(listOf("(val x (+ 1 2))"), statements("val x = 1 +\n2"))
        assertEquals(listOf("(val x 5)"), statements("val x =\n5"))
        assertEquals(listOf("(= x 5)"), statements("x =\n5"))
        assertEquals(listOf("(call println 1 2)"), statements("println(\n1,\n2\n)"))
        assertEquals(listOf("(val x (+ 1 2))"), statements("val x = (1\n+ 2)"))
        assertEquals(listOf("(call (. gear start))"), statements("gear\n.start()"))
        assertEquals(listOf("(call (. gear start))"), statements("gear.\nstart()"))
        assertEquals(listOf("(neg 5)"), statements("-\n5"))
        assertEquals(listOf("(* 2 3)"), statements("2 *\r\n3"))
    }

    @Test
    fun newlineTerminatesStatement() {
        // 次の行の先頭の + は継続しない（単項 + の式文になる）
        assertEquals(listOf("(val x 1)", "(pos 2)"), statements("val x = 1\n+ 2"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("val x = 2\n* 3"))
        assertEquals(DiagnosticCode.PARSE_EXPECTED_INITIALIZER, firstCode("val x\n= 5"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("x\n= 5"))
        assertEquals(listOf("a", "b"), statements("a\n\n\nb"))
    }

    @Test
    fun semicolons() {
        assertEquals(listOf("a", "b"), statements("a; b"))
        assertEquals(listOf("a"), statements("a;"))
        assertEquals(listOf("a", "b"), statements("a;\nb"))
        assertEquals(listOf(DiagnosticCode.PARSE_EMPTY_STATEMENT), codes("a;;"))
        assertEquals(listOf(DiagnosticCode.PARSE_EMPTY_STATEMENT), codes(";"))
        assertEquals(listOf(DiagnosticCode.PARSE_EMPTY_STATEMENT), codes("a;\n;"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("val x = 1 2"))
    }

    // ---- 未対応の構文 ----

    @Test
    fun standaloneBlockIsLambda() {
        assertEquals(listOf(DiagnosticCode.PARSE_LAMBDA_UNSUPPORTED), codes("{ println(1) }"))
        assertEquals(listOf(DiagnosticCode.PARSE_LAMBDA_UNSUPPORTED), codes("val f = { 1 }"))
        assertEquals(listOf(DiagnosticCode.PARSE_LAMBDA_UNSUPPORTED), codes("println(1) { }"))
    }

    @Test
    fun laterStageSyntaxIsRejected() {
        // do-while・when は対象外。break / continue（第3.8a段階）と for（第3.8b段階）は文としてだけ使える
        val doResult = parse("do { } while (a)")
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), doResult.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("do")), doResult.diagnostics[0].arguments)
        for (source in listOf("when (a) { }", "val x = break", "println(continue)", "while (a) { x = break }", "val x = for (i in 0..1) { }")) {
            assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes(source), source)
        }
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes("val b = null"))
        // if 式（3.7c でも対象外）・when も未対応のキーワード
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes("val b = if (a) 1 else 2"))
        // 添字 a[0] は第3.10a段階で対応（a[0] += 1 の += は未対応のまま）
        for (source in listOf("a === b", "a !== b", "x += 1", "x++", "a?.b", "a!!", "a[0] += 1", "1..5", "a ?: b")) {
            assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes(source), source)
        }
    }

    @Test
    fun declarationAndAssignmentErrors() {
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("val = 3"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("val when = 1"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_INITIALIZER), codes("val x"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_INITIALIZER), codes("var x: Int"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_TYPE), codes("val x: = 1"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR), codes("val x: Int? = 1"))
        // 代入先は変数名とプロパティ（a.b: 第3.9b段階）だけ
        assertEquals(listOf(DiagnosticCode.PARSE_INVALID_ASSIGNMENT_TARGET), codes("f() = 1"))
        assertEquals(listOf(DiagnosticCode.PARSE_INVALID_ASSIGNMENT_TARGET), codes("this = 1"))
        assertEquals(listOf(DiagnosticCode.PARSE_INVALID_ASSIGNMENT_TARGET), codes("1 = 2"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("a = b = 1"))
    }

    // ---- 診断・回復 ----

    @Test
    fun recoversAtNextStatement() {
        val result = parse("val = 1\nprintln(2)\nval y\nz")
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, DiagnosticCode.PARSE_EXPECTED_INITIALIZER), result.diagnostics.map { it.code })
        assertEquals(3, result.diagnostics[1].position.line)
        assertTrue(result.diagnostics.all { it.phase == DiagnosticPhase.PARSER })
    }

    @Test
    fun lexErrorsSkipParsing() {
        val result = parse("val x = 012\n(")
        assertEquals(listOf(DiagnosticCode.LEX_LEADING_ZERO), result.diagnostics.map { it.code })
        assertNull(result.program)
        assertTrue(result.hasErrors)
    }

    @Test
    fun diagnosticLimitAcrossStatements() {
        val result = parse("()\n".repeat(20))
        assertEquals(16, result.diagnostics.size)
        assertTrue(result.diagnosticsTruncated)
    }

    // ---- 上限 ----

    @Test
    fun nestingLimit() {
        program("(".repeat(31) + "1" + ")".repeat(31))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes("(".repeat(32) + "1" + ")".repeat(32)))
        program("- ".repeat(31) + "1")
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes("- ".repeat(32) + "1"))
        // 左結合の二項演算の連鎖も木の深さとして数える
        program("1" + "+1".repeat(31))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), codes("1" + "+1".repeat(32)))
    }

    @Test
    fun nodeLimit() {
        assertEquals(2048, program("a;".repeat(1024)).statements.size * 2)
        assertEquals(listOf(DiagnosticCode.PARSE_TOO_MANY_NODES), codes("a;".repeat(1023) + "-a"))
    }

    // ---- ユーザー定義関数（第3.8c段階） ----

    @Test
    fun functionDefinitions() {
        val parsed = program("fun greet() {\n  println(\"Hello\")\n}\ngreet()\nfun work()\n{\n  return\n}")
        assertEquals(listOf("greet", "work"), parsed.functions.map { it.name })
        // 定義は文の並びに含めない
        assertEquals(listOf("(call greet)"), parsed.statements.map(::render))
        assertEquals(listOf("(call println \"Hello\")"), parsed.functions[0].body.statements.map(::render))
        assertEquals(listOf("return"), parsed.functions[1].body.statements.map(::render))
        assertEquals(SourcePosition(0, 1, 1), parsed.functions[0].position)
        assertEquals(SourcePosition(4, 1, 5), parsed.functions[0].namePosition)
        // return は文の終わり（改行・";"・"}"・EOF）の直前に置ける
        program("fun f() { return }\nfun g() { if (true) { return }; return\n}")
        program("fun f() { return; println(1) }")
        program("fun f() {\n  while (true) {\n    return\n  }\n}")
        // 引数・戻り値の型・値を返す return（第3.8d段階）。値は同じ行の式で、次の行の式は別の文
        val typed = program("fun add(a: Int,\n    b: Int): Int {\n  return a + b\n}\nfun none(): Unit { return }\nfun next() {\n  return\n  1\n}").functions
        assertEquals(listOf("a", "b"), typed[0].parameters.map { it.name })
        assertEquals(listOf("Int", "Int"), typed[0].parameters.map { it.type.name })
        assertEquals(SourcePosition(8, 1, 9), typed[0].parameters[0].position)
        assertEquals("Int", typed[0].returnType?.name)
        assertEquals(listOf("(return (+ a b))"), typed[0].body.statements.map(::render))
        assertEquals("Unit", typed[1].returnType?.name)
        assertEquals(null, typed[2].returnType)
        assertEquals(listOf("return", "1"), typed[2].body.statements.map(::render))
        assertEquals(listOf("(var r (call add 1 (call add 2 3)))"), program("var r = add(1, add(2, 3))").statements.map(::render))
        assertEquals(1, program("fun f() { }; f()").functions.size)
    }

    @Test
    fun functionErrors() {
        // ブロック・関数の中の定義は未対応（中身はまとめて読み飛ばす）
        for (source in listOf("fun outer() {\n  fun inner() { println(1) }\n}", "while (true) { fun f() { } }", "if (true) {\n fun f() { }\n}")) {
            assertEquals(listOf(DiagnosticCode.PARSE_NESTED_FUNCTION_UNSUPPORTED), codes(source), source)
        }
        // 引数の型の注釈は必須。型の名前が無い・既定値・末尾の ","・式の本体は未対応
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_TYPE), codes("fun f(a) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_TYPE), codes("fun f(a: ) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_TYPE), codes("fun f(): { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), codes("fun f(a: Int = 1) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("fun f(a: Int,) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("fun f(1) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("fun f() = println(1)"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("fun f(): Int = 1"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE), codes("fun f()"))
        // 名前・括弧が無い（ジェネリクス・拡張関数を含む）、"}" が無い
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("fun () { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("fun <T> f() { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN), codes("fun f { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN), codes("fun Int.f() { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN), codes("fun f(a: Int { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE), codes("fun f() {\n println(1)"))
        // return の値は式
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_EXPRESSION), codes("fun f(): Int { return * }"))
        // 式の中の fun・return は未対応のキーワード
        for (source in listOf("val f = fun() { }", "println(return)", "val x = return")) {
            assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes(source), source)
        }
    }

    // ---- クラス（第3.9a段階） ----

    @Test
    fun classDeclarations() {
        val parsed = program("class Robot {\n}\nval robot = Robot()\nclass Gear\nclass Empty\n{ }\nprintln(robot)")
        assertEquals(listOf("Robot", "Gear", "Empty"), parsed.classes.map { it.name })
        // 宣言は文の並びに含めない。生成は通常の呼び出しの構文（Semantic でクラスかどうかを決める）
        assertEquals(listOf("(val robot (call Robot))", "(call println robot)"), parsed.statements.map(::render))
        assertEquals(SourcePosition(0, 1, 1), parsed.classes[0].position)
        assertEquals(SourcePosition(6, 1, 7), parsed.classes[0].namePosition)
        assertEquals(2, program("class A {}; class B {}").classes.size)
    }

    // プロパティ・メソッド・this・プロパティへの代入（第3.9b段階）
    @Test
    fun classMembers() {
        val parsed = program("class Counter {\n  var count = 0\n  val name: String = \"c\"; fun increment() {\n    count = count + 1\n  }\n\n  fun get(): Int { return this.count }\n}\n" +
            "val c = Counter()\nc.increment()\nc.count = c.get() + 1\nprintln(c.name)")
        val counter = parsed.classes.single()
        assertEquals(listOf("(var count 0)", "(val name:String \"c\")"), counter.properties.map(::render))
        assertEquals(listOf("increment", "get"), counter.methods.map { it.name })
        assertEquals(listOf("(= count (+ count 1))"), counter.methods[0].body.statements.map(::render))
        assertEquals("(return (. this count))", render(counter.methods[1].body.statements.single()))
        assertEquals(
            listOf("(val c (call Counter))", "(call (. c increment))", "(= (. c count) (+ (call (. c get)) 1))", "(call println (. c name))"),
            parsed.statements.map(::render),
        )
        // 代入の位置は代入先の名前、this の位置はキーワード
        assertEquals(SourcePosition(178, 11, 3), parsed.statements[2].position)
        assertEquals(SourcePosition(129, 7, 27), ((counter.methods[1].body.statements.single() as ReturnStatement).value as MemberAccessExpression).receiver.position)
        // 本体の無いクラス・空の本体・メンバーの間の空行・本体の "{" が次の行
        assertEquals(listOf(0, 0, 2), program("class A\nclass B {\n\n}\nclass C\n{\n  val x = 1\n\n  var y = this\n}").classes.map { it.properties.size })
        assertEquals("(. (. (call make) next) next)", render((program("make().next.next = 1").statements.single() as Assignment).target))
    }

    @Test
    fun classErrors() {
        // 主コンストラクタ・継承・型引数は未対応（位置はその記号）
        assertEquals(listOf(DiagnosticCode.PARSE_CLASS_HEADER_UNSUPPORTED), codes("class Robot(val x: Int) { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_CLASS_HEADER_UNSUPPORTED), codes("class Robot : Machine { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_CLASS_HEADER_UNSUPPORTED), codes("class Box<T> { }"))
        assertEquals(SourcePosition(11, 1, 12), parse("class Robot(x: Int)").diagnostics.single().position)
        // プロパティ・メソッド以外のメンバー（init ブロック・修飾子・コンストラクタ・文: 第3.9b段階）は未対応。そのメンバーを読み飛ばし、次のメンバーから続ける
        for (source in listOf("class Robot {\n  init { }\n}\nprintln(1)", "class Robot { private val x = 1 }", "class Robot {\n  constructor() { }\n}", "class Robot { println(1) }")) {
            assertEquals(listOf(DiagnosticCode.PARSE_CLASS_MEMBERS_UNSUPPORTED), codes(source), source)
        }
        assertEquals(SourcePosition(16, 2, 3), parse("class Robot {\n  init { }\n}").diagnostics.single().position)
        assertEquals(
            listOf(DiagnosticCode.PARSE_CLASS_MEMBERS_UNSUPPORTED, DiagnosticCode.PARSE_EXPECTED_INITIALIZER, DiagnosticCode.PARSE_CLASS_MEMBERS_UNSUPPORTED),
            codes("class Robot {\n  init { val a = 1 }\n  var x: Int\n  val y = 1\n  lateinit var z: Int\n  fun f() { }\n}\nprintln(1)"),
        )
        // メンバーの区切り・メソッドの中の fun・class、本体の閉じていない "}"
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_STATEMENT_END), codes("class Robot { val x = 1 y }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EMPTY_STATEMENT), codes("class Robot { ; val x = 1 }"))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTED_FUNCTION_UNSUPPORTED), codes("class Robot {\n  fun f() {\n    fun g() { }\n  }\n}"))
        assertEquals(listOf(DiagnosticCode.PARSE_NESTED_CLASS_UNSUPPORTED), codes("class Robot {\n  class Part\n}"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE), codes("class Robot {\n  val x = 1\n"))
        // ブロック・関数の中のクラスは未対応
        for (source in listOf("fun f() {\n  class A { }\n}", "if (true) {\n  class A\n}", "while (true) { class A { } }")) {
            assertEquals(listOf(DiagnosticCode.PARSE_NESTED_CLASS_UNSUPPORTED), codes(source), source)
        }
        // 名前が無い・"}" が無い・式の中の class
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER), codes("class { }"))
        assertEquals(listOf(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE), codes("class Robot {"))
        assertEquals(listOf(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD), codes("val c = class"))
    }
}
