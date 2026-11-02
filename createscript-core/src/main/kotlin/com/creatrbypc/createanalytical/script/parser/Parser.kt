package com.creatrbypc.createanalytical.script.parser

import com.creatrbypc.createanalytical.script.ast.Assignment
import com.creatrbypc.createanalytical.script.ast.BinaryExpression
import com.creatrbypc.createanalytical.script.ast.BinaryOperator
import com.creatrbypc.createanalytical.script.ast.Block
import com.creatrbypc.createanalytical.script.ast.BooleanLiteral
import com.creatrbypc.createanalytical.script.ast.BreakStatement
import com.creatrbypc.createanalytical.script.ast.CallExpression
import com.creatrbypc.createanalytical.script.ast.ClassDeclaration
import com.creatrbypc.createanalytical.script.ast.ContinueStatement
import com.creatrbypc.createanalytical.script.ast.DoubleLiteral
import com.creatrbypc.createanalytical.script.ast.Expression
import com.creatrbypc.createanalytical.script.ast.ExpressionStatement
import com.creatrbypc.createanalytical.script.ast.FloatLiteral
import com.creatrbypc.createanalytical.script.ast.ForStatement
import com.creatrbypc.createanalytical.script.ast.FunctionDeclaration
import com.creatrbypc.createanalytical.script.ast.IfBranch
import com.creatrbypc.createanalytical.script.ast.IfStatement
import com.creatrbypc.createanalytical.script.ast.IndexExpression
import com.creatrbypc.createanalytical.script.ast.IntLiteral
import com.creatrbypc.createanalytical.script.ast.LogicalExpression
import com.creatrbypc.createanalytical.script.ast.LogicalOperator
import com.creatrbypc.createanalytical.script.ast.MemberAccessExpression
import com.creatrbypc.createanalytical.script.ast.NameReference
import com.creatrbypc.createanalytical.script.ast.Parameter
import com.creatrbypc.createanalytical.script.ast.Program
import com.creatrbypc.createanalytical.script.ast.RangeKind
import com.creatrbypc.createanalytical.script.ast.ReturnStatement
import com.creatrbypc.createanalytical.script.ast.Statement
import com.creatrbypc.createanalytical.script.ast.StringLiteral
import com.creatrbypc.createanalytical.script.ast.ThisExpression
import com.creatrbypc.createanalytical.script.ast.TypeReference
import com.creatrbypc.createanalytical.script.ast.UnaryExpression
import com.creatrbypc.createanalytical.script.ast.UnaryOperator
import com.creatrbypc.createanalytical.script.ast.VariableDeclaration
import com.creatrbypc.createanalytical.script.ast.WhileStatement
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCollector
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.LexResult
import com.creatrbypc.createanalytical.script.lexer.Token
import com.creatrbypc.createanalytical.script.lexer.TokenType

// 入れ子の深さと AST ノード数の上限（暫定値。第3.3段階の検証で確定する）。入れ子の深さはブロックの深さと式の深さの合計（11.9-9）
object ParserLimits {
    const val MAX_NESTING_DEPTH = 32
    const val MAX_NODES = 2048
}

// program は構文エラー・字句エラーが無いときだけ返す。診断は Lexer の分を含めて最大16件
data class ParseResult(
    val program: Program?,
    val diagnostics: List<Diagnostic>,
    val diagnosticsTruncated: Boolean,
    val hasErrors: Boolean,
)

// CreateScript の構文解析器（再帰下降）。Minecraft に依存しない純粋な Kotlin で、1回の parse ごとに新しいインスタンスを使う
class Parser private constructor(private val tokens: List<Token>, private val diagnostics: DiagnosticCollector) {
    private var index = 0

    // 括弧の中にいる間は改行トークンを読み飛ばす（D18）
    private var parenDepth = 0

    // 再帰の深さ。上限を超えたら解析を打ち切り、サーバーのスタックを使い切らないようにする
    private var nestingDepth = 0

    // いま解析している文を囲むブロックの数（トップレベルは 0）。式の深さと合わせて MAX_NESTING_DEPTH まで
    private var blockDepth = 0
    private var nodeCount = 0

    // トップレベルの関数定義（第3.8c段階）・クラス宣言（第3.9a段階）
    private val functions = ArrayList<FunctionDeclaration>()
    private val classes = ArrayList<ClassDeclaration>()

    // 構文エラー。その文を読み飛ばして次の文から解析を続ける
    private class ParseFailure : RuntimeException(null, null, false, false)

    // 上限超過。安全に続けられないため解析全体を打ち切る
    private class ParseAbort : RuntimeException(null, null, false, false)

    // 式と、その式を根とする部分木の深さ
    private class Parsed(val expression: Expression, val depth: Int)

    companion object {
        // 使える構文の記号（Stage 3.5 と、第3.7b段階の比較・論理演算子）。これ以外の演算子が現れたら未対応の演算子として報告する。
        // 前置の "!!"（!(!x)）は使えるが、後置の "x!!" は未対応のため、"!!" はここに含めない
        private val SUPPORTED_SYMBOLS = setOf(
            TokenType.PLUS, TokenType.MINUS, TokenType.TIMES, TokenType.DIVIDE, TokenType.REMAINDER,
            TokenType.EQUALS, TokenType.NOT_EQUALS, TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL,
            TokenType.AND_AND, TokenType.OR_OR, TokenType.NOT,
            TokenType.ASSIGN, TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN, TokenType.DOT, TokenType.COMMA,
            TokenType.COLON, TokenType.SEMICOLON, TokenType.LEFT_BRACE, TokenType.RIGHT_BRACE,
            // リストの要素（第3.10a段階）
            TokenType.LEFT_BRACKET, TokenType.RIGHT_BRACKET,
        )

        private val STATEMENT_BOUNDARIES = setOf(TokenType.NEWLINE, TokenType.SEMICOLON, TokenType.EOF)

        fun parse(lexResult: LexResult): ParseResult {
            val diagnostics = DiagnosticCollector()
            diagnostics.merge(lexResult.diagnostics, lexResult.diagnosticsTruncated, lexResult.hasErrors)
            // 字句エラーがあるとトークン列が欠けており、誤った構文エラーを出しうるため構文解析しない（A8）
            val program = if (lexResult.hasErrors) null else Parser(lexResult.tokens, diagnostics).parseProgram()
            return ParseResult(
                program.takeUnless { diagnostics.hasErrors },
                diagnostics.diagnostics,
                diagnostics.isTruncated,
                diagnostics.hasErrors,
            )
        }
    }

    private fun parseProgram(): Program {
        val statements = ArrayList<Statement>()
        try {
            parseStatements(statements)
        } catch (abort: ParseAbort) {
            // 上限超過は報告済み。これ以上は解析しない
        }
        return Program(statements, functions.toList(), classes.toList())
    }

    // 文の並び。ブロックの中（blockDepth > 0）では、ブロックを閉じる "}" の手前で止まる。EOF でも止まる（閉じていないブロックは parseBlock が報告する）
    private fun parseStatements(statements: MutableList<Statement>) {
        while (true) {
            skipNewlines()
            val token = peek()
            if (token.type == TokenType.EOF) return
            if (token.type == TokenType.RIGHT_BRACE) {
                if (blockDepth > 0) return
                // 対応する "{" の無い "}"
                report(DiagnosticCode.PARSE_UNEXPECTED_RIGHT_BRACE, token.position)
                advance()
                continue
            }
            // 文の無い ";"（";;"、先頭の ";"、改行直後の ";"）は構文エラー（D18。ブロックの中でも同じ）
            if (token.type == TokenType.SEMICOLON) {
                report(DiagnosticCode.PARSE_EMPTY_STATEMENT, token.position)
                advance()
                continue
            }
            try {
                when {
                    token.type == TokenType.KEYWORD && token.lexeme == "fun" -> functions.add(parseFunction())
                    token.type == TokenType.KEYWORD && token.lexeme == "class" -> classes.add(parseClass())
                    else -> statements.add(parseStatement())
                }
                parseStatementEnd()
            } catch (failure: ParseFailure) {
                synchronize()
            }
        }
    }

    // 文の終わりは改行・";"・EOF のいずれか。ブロックの中では、ブロックを閉じる "}" の直前でもよい（"}" は読み進めない）
    private fun parseStatementEnd() {
        val token = peek()
        when {
            token.type == TokenType.NEWLINE || token.type == TokenType.SEMICOLON -> advance()
            token.type == TokenType.EOF -> Unit
            token.type == TokenType.RIGHT_BRACE && blockDepth > 0 -> Unit
            // if 文が終わった後の else（"if (a) { } else { } else { }" など）
            isElse(token) -> fail(DiagnosticCode.PARSE_UNEXPECTED_ELSE, token.position)
            else -> failUnexpected(token, DiagnosticCode.PARSE_EXPECTED_STATEMENT_END)
        }
    }

    // エラーの後は、次の改行・";" まで読み飛ばして次の文から再開する。途中の { } の中身はまとめて読み飛ばし、
    // ブロックの中では、そのブロックを閉じる "}" を越えない（11.8）
    private fun synchronize() {
        parenDepth = 0
        nestingDepth = 0
        var braces = 0
        while (true) {
            val type = tokens[index].type
            when {
                type == TokenType.EOF -> return
                type == TokenType.LEFT_BRACE -> braces++
                type == TokenType.RIGHT_BRACE && braces > 0 -> braces--
                type == TokenType.RIGHT_BRACE && blockDepth > 0 -> return
                braces == 0 && type in STATEMENT_BOUNDARIES -> {
                    index++
                    return
                }
            }
            index++
        }
    }

    private fun parseStatement(): Statement {
        val token = peek()
        if (token.type == TokenType.KEYWORD && (token.lexeme == "val" || token.lexeme == "var")) return parseVariableDeclaration()
        if (token.type == TokenType.KEYWORD && token.lexeme == "if") return parseIf()
        if (token.type == TokenType.KEYWORD && token.lexeme == "while") return parseWhile()
        if (token.type == TokenType.KEYWORD && token.lexeme == "for") return parseFor()
        // break / continue は文の先頭でだけ使える。ループの外かどうかは Semantic 段階で判定する。ラベル（break@a）は未対応の演算子になる
        if (token.type == TokenType.KEYWORD && token.lexeme == "break") return countNode(BreakStatement(advance().position))
        if (token.type == TokenType.KEYWORD && token.lexeme == "continue") return countNode(ContinueStatement(advance().position))
        // return [値] は文の先頭でだけ使える（第3.8c段階）。値は同じ行に書く（Kotlin と同じく、改行・";"・"}" の前で終われば値なし）。
        // 関数の外かどうか・値の有無と型は Semantic 段階で判定する
        if (token.type == TokenType.KEYWORD && token.lexeme == "return") {
            advance()
            val next = peek()
            val value = if (next.type in STATEMENT_BOUNDARIES || next.type == TokenType.RIGHT_BRACE) null else parseExpression().expression
            return countNode(ReturnStatement(value, token.position))
        }
        // 対応する if の無い else（"} ; else" を含む: 11.9-8）
        if (token.type == TokenType.KEYWORD && token.lexeme == "else") fail(DiagnosticCode.PARSE_UNEXPECTED_ELSE, token.position)
        val target = parseExpression()
        if (peek().type != TokenType.ASSIGN) return countNode(ExpressionStatement(target.expression, target.expression.position))
        advance()
        skipNewlines()
        // 代入先は変数名か、プロパティ（a.b・this.b: 第3.9b段階）か、リストの要素（a[i]: 第3.10a段階）
        val assigned = target.expression.takeIf { it is NameReference || it is MemberAccessExpression || it is IndexExpression }
            ?: fail(DiagnosticCode.PARSE_INVALID_ASSIGNMENT_TARGET, target.expression.position)
        val value = parseExpression()
        return countNode(Assignment(assigned, value.expression, assigned.position))
    }

    // 変数の宣言と、クラスのプロパティ（第3.9b段階）
    private fun parseVariableDeclaration(): VariableDeclaration {
        val keyword = advance()
        val name = advance()
        if (name.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, name.position)
        val type = if (peek().type == TokenType.COLON) parseTypeAnnotation() else null
        // 宣言時の初期化は必須（D19）
        val assign = peek()
        if (assign.type != TokenType.ASSIGN) failUnexpected(assign, DiagnosticCode.PARSE_EXPECTED_INITIALIZER)
        advance()
        skipNewlines()
        val initializer = parseExpression()
        return countNode(
            VariableDeclaration(keyword.lexeme == "var", name.lexeme, name.position, type, initializer.expression, keyword.position)
        )
    }

    // ---- if 文とブロック（第3.7c段階） ----

    // if "(" 条件 ")" ブロック { else if "(" 条件 ")" ブロック } [ else ブロック ]。")" と "{"、"}" と else、else と "{" / if の間の改行は可。
    // ブロックは必須（D4）。else if の連鎖は IfStatement の branches に平らに並べる
    private fun parseIf(): Statement {
        val keyword = advance()
        val branches = arrayListOf(parseIfBranch(keyword))
        var elseBlock: Block? = null
        while (elseBlock == null && isElse(nextNonNewline())) {
            skipNewlines()
            advance()
            skipNewlines()
            val next = peek()
            if (next.type == TokenType.KEYWORD && next.lexeme == "if") branches.add(parseIfBranch(advance())) else elseBlock = parseBlock()
        }
        return countNode(IfStatement(branches, elseBlock, keyword.position))
    }

    private fun isElse(token: Token) = token.type == TokenType.KEYWORD && token.lexeme == "else"

    private fun parseIfBranch(keyword: Token): IfBranch {
        val condition = parseCondition()
        val body = parseBlock()
        return countNode(IfBranch(condition, body, keyword.position))
    }

    // while "(" 条件 ")" ブロック（第3.7d段階）。改行の扱いと {} 必須は if と同じ。do-while・for は未対応のキーワードのまま
    private fun parseWhile(): Statement {
        val keyword = advance()
        val condition = parseCondition()
        val body = parseBlock()
        return countNode(WhileStatement(condition, body, keyword.position))
    }

    // for "(" 変数名 in 始点 ( ".." | until | downTo ) 終点 ")" ブロック（第3.8b段階）。始点・終点は加減算の式（Kotlin と同じく
    // ".." と until / downTo は + - より弱く結びつく）。括弧の中なので改行できる。step・コレクション・"..<"・変数の型注釈は未対応
    private fun parseFor(): Statement {
        val keyword = advance()
        val open = peek()
        if (open.type != TokenType.LEFT_PAREN) fail(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN, open.position)
        advance()
        parenDepth++
        val variable = advance()
        if (variable.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_LOOP_VARIABLE, variable.position)
        val inKeyword = peek()
        if (inKeyword.type != TokenType.KEYWORD || inKeyword.lexeme != "in") fail(DiagnosticCode.PARSE_EXPECTED_IN, inKeyword.position)
        advance()
        val start = parseAdditive()
        val range = peek()
        val kind = when {
            range.type == TokenType.RANGE -> RangeKind.RANGE_TO
            range.type == TokenType.IDENTIFIER && range.lexeme == "until" -> RangeKind.UNTIL
            range.type == TokenType.IDENTIFIER && range.lexeme == "downTo" -> RangeKind.DOWN_TO
            // "..<" などの未対応の演算子はそのまま報告する
            range.type.symbol != null && range.type !in SUPPORTED_SYMBOLS -> failUnexpected(range, DiagnosticCode.PARSE_EXPECTED_RANGE)
            else -> fail(DiagnosticCode.PARSE_EXPECTED_RANGE, range.position)
        }
        advance()
        val end = parseAdditive()
        if (peek().type != TokenType.RIGHT_PAREN) fail(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN, peek().position)
        expectRightParen()
        skipNewlines()
        val body = parseBlock()
        return countNode(ForStatement(variable.lexeme, variable.position, start.expression, kind, range.position, end.expression, body, keyword.position))
    }

    // fun 名前 "(" [ 引数 { "," 引数 } ] ")" [ ":" 型 ] ブロック（第3.8c段階。引数・戻り値の型は第3.8d段階）。引数は 名前 ":" 型（型は必須）。
    // トップレベルでのみ定義できる（ブロック・関数の中は未対応）。括弧の中と ")" / 型と "{" の間の改行は可。
    // 式の本体（fun f() = 1）は "{" の不足、ジェネリクス・拡張関数は名前・"(" の不足、既定値は ")" の不足として報告する。
    // 型の名前が使えるかどうか（Int・Boolean・String・Unit）は Semantic 段階で判定する。
    // クラスの本体の直下（method = true。本体は1段深いブロックとして数える）ではメソッドになる（第3.9b段階）
    private fun parseFunction(method: Boolean = false): FunctionDeclaration {
        val keyword = advance()
        if (blockDepth > (if (method) 1 else 0)) fail(DiagnosticCode.PARSE_NESTED_FUNCTION_UNSUPPORTED, keyword.position)
        val name = advance()
        if (name.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, name.position)
        val open = peek()
        if (open.type != TokenType.LEFT_PAREN) fail(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN, open.position)
        advance()
        parenDepth++
        val parameters = ArrayList<Parameter>()
        if (peek().type != TokenType.RIGHT_PAREN) {
            while (true) {
                val parameter = advance()
                if (parameter.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, parameter.position)
                parameters.add(countNode(Parameter(parameter.lexeme, parseTypeAnnotation(), parameter.position)))
                if (peek().type != TokenType.COMMA) break
                advance()
            }
        }
        // "fun f(a: Int {" はラムダではなく ")" の不足として報告する
        if (peek().type != TokenType.RIGHT_PAREN) fail(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN, peek().position)
        expectRightParen()
        val returnType = if (peek().type == TokenType.COLON) parseTypeAnnotation() else null
        skipNewlines()
        val body = parseBlock()
        return countNode(FunctionDeclaration(name.lexeme, name.position, parameters, returnType, body, keyword.position))
    }

    // class 名前 [ "{" { メンバー } "}" ]（第3.9a段階。メンバーは第3.9b段階）。トップレベルでのみ宣言できる。本体は省略でき（Kotlin と同じ）、
    // "{" は次の行でもよい。主コンストラクタ・継承・型引数は未対応
    private fun parseClass(): ClassDeclaration {
        val keyword = advance()
        if (blockDepth > 0) fail(DiagnosticCode.PARSE_NESTED_CLASS_UNSUPPORTED, keyword.position)
        val name = advance()
        if (name.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_IDENTIFIER, name.position)
        val header = peek()
        if (header.type == TokenType.LEFT_PAREN || header.type == TokenType.COLON || header.type == TokenType.LESS) fail(DiagnosticCode.PARSE_CLASS_HEADER_UNSUPPORTED, header.position)
        val properties = ArrayList<VariableDeclaration>()
        val methods = ArrayList<FunctionDeclaration>()
        if (nextNonNewline().type == TokenType.LEFT_BRACE) {
            skipNewlines()
            parseClassBody(advance(), properties, methods)
        }
        return countNode(ClassDeclaration(name.lexeme, name.position, keyword.position, properties, methods))
    }

    // クラスの本体の "{" の直後から、対応する "}" の次まで（第3.9b段階）。メンバーはプロパティ（val / var。初期化式は必須）とメソッド（fun）で、
    // 改行か ";" で区切る。それ以外のメンバー（init ブロック・修飾子・コンストラクタ・文など）は未対応で、エラーの後は次のメンバーから続ける。
    // 本体はブロックと同じく1段深く数える（入れ子のクラス・関数の判定と、"}" を越えない読み飛ばしに使う）
    private fun parseClassBody(open: Token, properties: MutableList<VariableDeclaration>, methods: MutableList<FunctionDeclaration>) {
        blockDepth++
        try {
            if (blockDepth >= ParserLimits.MAX_NESTING_DEPTH) abort(DiagnosticCode.PARSE_NESTING_TOO_DEEP, open.position, ParserLimits.MAX_NESTING_DEPTH)
            while (true) {
                skipNewlines()
                val token = peek()
                if (token.type == TokenType.RIGHT_BRACE) {
                    advance()
                    return
                }
                if (token.type == TokenType.EOF) fail(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE, token.position)
                if (token.type == TokenType.SEMICOLON) {
                    report(DiagnosticCode.PARSE_EMPTY_STATEMENT, token.position)
                    advance()
                    continue
                }
                try {
                    val keyword = if (token.type == TokenType.KEYWORD) token.lexeme else null
                    when (keyword) {
                        "val", "var" -> properties.add(parseVariableDeclaration())
                        "fun" -> methods.add(parseFunction(method = true))
                        // 入れ子のクラスは CS_PARSE_NESTED_CLASS_UNSUPPORTED になる
                        "class" -> parseClass()
                        else -> fail(DiagnosticCode.PARSE_CLASS_MEMBERS_UNSUPPORTED, token.position)
                    }
                    parseStatementEnd()
                } catch (failure: ParseFailure) {
                    synchronize()
                }
            }
        } finally {
            blockDepth--
        }
    }

    // ":" 型
    private fun parseTypeAnnotation(): TypeReference {
        val colon = peek()
        if (colon.type != TokenType.COLON) fail(DiagnosticCode.PARSE_EXPECTED_TYPE, colon.position)
        advance()
        return parseType()
    }

    // 型の名前 [ "<" 型 { "," 型 } ">" ]（型引数は第3.10a段階）。使える型・型引数の数は Semantic 段階で判定する。
    // 型引数の入れ子（第3.10b段階の入れ子のリスト）は式・ブロックの深さの上限に数えない。深さはトークン数・ノード数の上限までに収まる。
    // 深い入れ子でもスタックを使い切らないよう、再帰せずに読みかけの型（名前と読み終えた型引数）を積んで読む
    private fun parseType(): TypeReference {
        val open = ArrayList<Pair<Token, ArrayList<TypeReference>>>()
        while (true) {
            val typeName = advance()
            if (typeName.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_TYPE, typeName.position)
            if (peek().type == TokenType.LESS) {
                advance()
                open.add(typeName to ArrayList())
                continue
            }
            var finished = countNode(TypeReference(typeName.lexeme, typeName.position))
            // 読み終えた型を、それを囲む型の型引数にする。"," なら次の型引数を読み、">" なら囲む型も読み終える
            while (true) {
                val (name, arguments) = open.lastOrNull() ?: return finished
                arguments.add(finished)
                if (peek().type == TokenType.COMMA) {
                    advance()
                    break
                }
                val close = peek()
                if (close.type != TokenType.GREATER) failUnexpected(close, DiagnosticCode.PARSE_EXPECTED_TYPE)
                advance()
                open.removeAt(open.lastIndex)
                finished = countNode(TypeReference(name.lexeme, name.position, arguments))
            }
        }
    }

    // 名前の直後の "<" が型引数の始まり（"<" 型の名前 … ">" "("）か。トークンを読み進めずに確かめる（a < b のような比較と区別する）
    private fun typeArgumentsAhead(): Boolean {
        var i = index
        if (tokens[i].type != TokenType.LESS) return false
        i++
        var depth = 1
        var expectName = true
        while (true) {
            val token = tokens[i]
            if (expectName) {
                if (token.type != TokenType.IDENTIFIER) return false
                expectName = false
                if (tokens[i + 1].type == TokenType.LESS) {
                    depth++
                    expectName = true
                    i++
                }
            } else {
                when (token.type) {
                    TokenType.COMMA -> expectName = true
                    TokenType.GREATER -> if (--depth == 0) return tokens[i + 1].type == TokenType.LEFT_PAREN
                    else -> return false
                }
            }
            i++
        }
    }

    // if / while の "(" 条件 ")"。")" の後の改行は読み飛ばす（ブロックの "{" は次の行でもよい）
    private fun parseCondition(): Expression {
        val open = peek()
        if (open.type != TokenType.LEFT_PAREN) fail(DiagnosticCode.PARSE_EXPECTED_LEFT_PAREN, open.position)
        advance()
        parenDepth++
        val condition = parseExpression()
        // "if (a {" はラムダではなく ")" の不足として報告する
        if (peek().type != TokenType.RIGHT_PAREN) fail(DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN, peek().position)
        expectRightParen()
        skipNewlines()
        return condition.expression
    }

    // "{" 文の並び "}"。1つのスコープになる。ブロックの深さは式の深さと合わせて上限まで（中の文の式は少なくとも深さ 1 のため、ブロックは 31 段まで）
    private fun parseBlock(): Block {
        val open = peek()
        if (open.type != TokenType.LEFT_BRACE) fail(DiagnosticCode.PARSE_EXPECTED_LEFT_BRACE, open.position)
        advance()
        blockDepth++
        try {
            if (blockDepth >= ParserLimits.MAX_NESTING_DEPTH) abort(DiagnosticCode.PARSE_NESTING_TOO_DEEP, open.position, ParserLimits.MAX_NESTING_DEPTH)
            val statements = ArrayList<Statement>()
            parseStatements(statements)
            val close = peek()
            if (close.type != TokenType.RIGHT_BRACE) fail(DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACE, close.position)
            advance()
            return countNode(Block(statements, open.position))
        } finally {
            blockDepth--
        }
    }

    // ---- 式（優先順位の低い順: || < && < == != < < <= > >= < 加減算 < 乗除算・剰余 < 単項 < 後置（呼び出し・メンバーアクセス）） ----

    private fun parseExpression(): Parsed = parseDisjunction()

    private fun parseDisjunction(): Parsed {
        var left = parseConjunction()
        while (continuesWith(TokenType.OR_OR)) left = parseLogicalRight(LogicalOperator.OR, left, ::parseConjunction)
        return left
    }

    private fun parseConjunction(): Parsed {
        var left = parseEquality()
        while (continuesWith(TokenType.AND_AND)) left = parseLogicalRight(LogicalOperator.AND, left, ::parseEquality)
        return left
    }

    // 次の行の先頭の && || は前の式を継続する（Kotlin 2.0 と同じ）。比較演算子は次の行の先頭では継続しない
    private fun continuesWith(type: TokenType): Boolean {
        if (peek().type == TokenType.NEWLINE && nextNonNewline().type == type) skipNewlines()
        return peek().type == type
    }

    private fun parseLogicalRight(operator: LogicalOperator, left: Parsed, parseOperand: () -> Parsed): Parsed {
        val operatorToken = advance()
        skipNewlines()
        val right = parseOperand()
        return build(LogicalExpression(operator, left.expression, right.expression, operatorToken.position), maxOf(left.depth, right.depth) + 1)
    }

    private fun parseEquality(): Parsed {
        var left = parseComparison()
        while (true) {
            val operator = when (peek().type) {
                TokenType.EQUALS -> BinaryOperator.EQUAL
                TokenType.NOT_EQUALS -> BinaryOperator.NOT_EQUAL
                else -> return left
            }
            left = parseBinaryRight(operator, left, ::parseComparison)
        }
    }

    // a < b < c は構文上 (a < b) < c になり、Semantic 段階の型エラーになる
    private fun parseComparison(): Parsed {
        var left = parseAdditive()
        while (true) {
            val operator = when (peek().type) {
                TokenType.LESS -> BinaryOperator.LESS
                TokenType.LESS_EQUAL -> BinaryOperator.LESS_EQUAL
                TokenType.GREATER -> BinaryOperator.GREATER
                TokenType.GREATER_EQUAL -> BinaryOperator.GREATER_EQUAL
                else -> return left
            }
            left = parseBinaryRight(operator, left, ::parseAdditive)
        }
    }

    private fun parseAdditive(): Parsed {
        var left = parseMultiplicative()
        while (true) {
            val operator = when (peek().type) {
                TokenType.PLUS -> BinaryOperator.ADD
                TokenType.MINUS -> BinaryOperator.SUBTRACT
                else -> return left
            }
            left = parseBinaryRight(operator, left, ::parseMultiplicative)
        }
    }

    private fun parseMultiplicative(): Parsed {
        var left = parseUnary()
        while (true) {
            val operator = when (peek().type) {
                TokenType.TIMES -> BinaryOperator.MULTIPLY
                TokenType.DIVIDE -> BinaryOperator.DIVIDE
                TokenType.REMAINDER -> BinaryOperator.REMAINDER
                else -> return left
            }
            left = parseBinaryRight(operator, left, ::parseUnary)
        }
    }

    // 二項演算子の直後の改行は継続とみなす（D18）。左結合
    private fun parseBinaryRight(operator: BinaryOperator, left: Parsed, parseOperand: () -> Parsed): Parsed {
        val operatorToken = advance()
        skipNewlines()
        val right = parseOperand()
        return build(BinaryExpression(operator, left.expression, right.expression, operatorToken.position), maxOf(left.depth, right.depth) + 1)
    }

    private fun parseUnary(): Parsed {
        val token = peek()
        enterNesting(token.position)
        try {
            val operator = when (token.type) {
                TokenType.MINUS -> UnaryOperator.MINUS
                TokenType.PLUS -> UnaryOperator.PLUS
                TokenType.NOT, TokenType.NOT_NULL_ASSERTION -> UnaryOperator.NOT
                else -> return parsePostfix()
            }
            advance()
            skipNewlines()
            val operand = parseUnary()
            // 前置の "!!" は1つのトークンだが、2つ目の "!" を内側の NOT とする
            if (token.type == TokenType.NOT_NULL_ASSERTION) {
                val innerPosition = SourcePosition(token.position.offset + 1, token.position.line, token.position.column + 1)
                val inner = build(UnaryExpression(UnaryOperator.NOT, operand.expression, innerPosition), operand.depth + 1)
                return build(UnaryExpression(UnaryOperator.NOT, inner.expression, token.position), inner.depth + 1)
            }
            return build(UnaryExpression(operator, operand.expression, token.position), operand.depth + 1)
        } finally {
            nestingDepth--
        }
    }

    private fun parsePostfix(): Parsed {
        var current = parsePrimary()
        while (true) {
            val token = peek()
            current = when {
                token.type == TokenType.LEFT_PAREN -> parseCall(current, emptyList())
                // 名前の後の型引数（listOf<Int>(): 第3.10a段階）
                token.type == TokenType.LESS && current.expression is NameReference && typeArgumentsAhead() -> parseCall(current, parseTypeArguments())
                token.type == TokenType.DOT -> parseMemberAccess(current)
                token.type == TokenType.LEFT_BRACKET -> parseIndex(current)
                // 次の行の先頭が "." なら前の式を継続する（D18）
                token.type == TokenType.NEWLINE && nextNonNewline().type == TokenType.DOT -> {
                    skipNewlines()
                    current
                }
                else -> return current
            }
        }
    }

    // "<" 型 { "," 型 } ">"（typeArgumentsAhead で形を確かめてから読む）
    private fun parseTypeArguments(): List<TypeReference> {
        advance()
        val arguments = arrayListOf(parseType())
        while (peek().type == TokenType.COMMA) {
            advance()
            arguments.add(parseType())
        }
        advance()
        return arguments
    }

    // "[" 添字 "]"（第3.10a段階）。括弧と同じく中の改行は読み飛ばす
    private fun parseIndex(receiver: Parsed): Parsed {
        val open = advance()
        parenDepth++
        val index = parseExpression()
        val close = peek()
        if (close.type != TokenType.RIGHT_BRACKET) failUnexpected(close, DiagnosticCode.PARSE_EXPECTED_RIGHT_BRACKET)
        parenDepth--
        advance()
        return build(IndexExpression(receiver.expression, index.expression, open.position), maxOf(receiver.depth, index.depth) + 1)
    }

    private fun parseCall(callee: Parsed, typeArguments: List<TypeReference>): Parsed {
        advance()
        parenDepth++
        val arguments = ArrayList<Expression>()
        var depth = callee.depth
        if (peek().type != TokenType.RIGHT_PAREN) {
            while (true) {
                val argument = parseExpression()
                arguments.add(argument.expression)
                depth = maxOf(depth, argument.depth)
                if (peek().type != TokenType.COMMA) break
                advance()
            }
        }
        expectRightParen()
        return build(CallExpression(callee.expression, arguments, callee.expression.position, typeArguments), depth + 1)
    }

    private fun parseMemberAccess(receiver: Parsed): Parsed {
        advance()
        skipNewlines()
        val name = advance()
        if (name.type != TokenType.IDENTIFIER) fail(DiagnosticCode.PARSE_EXPECTED_MEMBER_NAME, name.position)
        return build(MemberAccessExpression(receiver.expression, name.lexeme, name.position), receiver.depth + 1)
    }

    private fun parsePrimary(): Parsed {
        val token = peek()
        if (token.type == TokenType.KEYWORD && (token.lexeme == "true" || token.lexeme == "false")) {
            advance()
            return build(BooleanLiteral(token.lexeme == "true", token.position), 1)
        }
        // this（第3.9b段階）。使える場所（メソッド・初期化式の中）かどうかは Semantic 段階で判定する
        if (token.type == TokenType.KEYWORD && token.lexeme == "this") {
            advance()
            return build(ThisExpression(token.position), 1)
        }
        return when (token.type) {
            TokenType.INT_LITERAL -> {
                advance()
                build(IntLiteral(token.intValue!!, token.position), 1)
            }
            TokenType.FLOAT_LITERAL -> {
                advance()
                build(FloatLiteral(token.floatValue!!, token.position), 1)
            }
            TokenType.DOUBLE_LITERAL -> {
                advance()
                build(DoubleLiteral(token.doubleValue!!, token.position), 1)
            }
            TokenType.STRING_LITERAL -> {
                advance()
                build(StringLiteral(token.stringValue!!, token.position), 1)
            }
            TokenType.IDENTIFIER -> {
                advance()
                build(NameReference(token.lexeme, token.position), 1)
            }
            // 括弧は優先順位を変えるだけで、AST ノードは作らない
            TokenType.LEFT_PAREN -> {
                advance()
                parenDepth++
                val inner = parseExpression()
                expectRightParen()
                inner
            }
            else -> failUnexpected(token, DiagnosticCode.PARSE_EXPECTED_EXPRESSION)
        }
    }

    private fun expectRightParen() {
        val token = peek()
        if (token.type != TokenType.RIGHT_PAREN) failUnexpected(token, DiagnosticCode.PARSE_EXPECTED_RIGHT_PAREN)
        // 閉じ括弧の後の改行は通常どおり扱うため、読み進める前に括弧の外へ戻る
        parenDepth--
        advance()
    }

    // ---- ノード数・深さの上限 ----

    // 式の深さは、その文を囲むブロックの数と合わせて数える
    private fun build(expression: Expression, depth: Int): Parsed {
        if (depth + blockDepth > ParserLimits.MAX_NESTING_DEPTH) abort(DiagnosticCode.PARSE_NESTING_TOO_DEEP, expression.position, ParserLimits.MAX_NESTING_DEPTH)
        countNode(expression)
        return Parsed(expression, depth)
    }

    private fun <T> countNode(node: T): T {
        nodeCount++
        if (nodeCount > ParserLimits.MAX_NODES) abort(DiagnosticCode.PARSE_TOO_MANY_NODES, peek().position, ParserLimits.MAX_NODES)
        return node
    }

    private fun enterNesting(position: SourcePosition) {
        nestingDepth++
        if (nestingDepth + blockDepth > ParserLimits.MAX_NESTING_DEPTH) abort(DiagnosticCode.PARSE_NESTING_TOO_DEEP, position, ParserLimits.MAX_NESTING_DEPTH)
    }

    // ---- トークン操作 ----

    private fun peek(): Token {
        if (parenDepth > 0) skipNewlines()
        return tokens[index]
    }

    // tokens は必ず EOF で終わるため、EOF より先には進まない
    private fun advance(): Token {
        val token = peek()
        if (token.type != TokenType.EOF) index++
        return token
    }

    private fun skipNewlines() {
        while (tokens[index].type == TokenType.NEWLINE) index++
    }

    private fun nextNonNewline(): Token {
        var i = index
        while (tokens[i].type == TokenType.NEWLINE) i++
        return tokens[i]
    }

    // ---- 診断 ----

    // 期待しないトークンの種類に応じて、未対応のラムダ・演算子・キーワードを区別して報告する
    private fun failUnexpected(token: Token, defaultCode: DiagnosticCode): Nothing = when {
        token.type == TokenType.LEFT_BRACE -> fail(DiagnosticCode.PARSE_LAMBDA_UNSUPPORTED, token.position)
        token.type.symbol != null && token.type !in SUPPORTED_SYMBOLS ->
            fail(DiagnosticCode.PARSE_UNSUPPORTED_OPERATOR, token.position, DiagnosticArgument.StringArgument.truncated(token.lexeme))
        token.type == TokenType.KEYWORD ->
            fail(DiagnosticCode.PARSE_UNSUPPORTED_KEYWORD, token.position, DiagnosticArgument.StringArgument.truncated(token.lexeme))
        else -> fail(defaultCode, token.position)
    }

    private fun fail(code: DiagnosticCode, position: SourcePosition, vararg arguments: DiagnosticArgument): Nothing {
        report(code, position, *arguments)
        throw ParseFailure()
    }

    private fun abort(code: DiagnosticCode, position: SourcePosition, limit: Int): Nothing {
        report(code, position, DiagnosticArgument.IntArgument(limit))
        throw ParseAbort()
    }

    private fun report(code: DiagnosticCode, position: SourcePosition, vararg arguments: DiagnosticArgument) {
        diagnostics.report(Diagnostic(code, DiagnosticSeverity.ERROR, DiagnosticPhase.PARSER, position, arguments.toList()))
    }
}
