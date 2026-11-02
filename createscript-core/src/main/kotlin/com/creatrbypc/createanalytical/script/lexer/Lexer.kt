package com.creatrbypc.createanalytical.script.lexer

import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCollector
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition

object LexerLimits {
    const val MAX_SOURCE_LENGTH = 4096
    const val MAX_TOKENS = 2048
    const val MAX_IDENTIFIER_LENGTH = 32
    const val MAX_INTEGER_DIGITS = 10

    // 浮動小数点数のリテラルの綴りの長さ（第3.11a段階。値を求める処理の量を抑える）
    const val MAX_FLOAT_LITERAL_LENGTH = 64
    const val MAX_STRING_LITERAL_LENGTH = 256
}

// CreateScript の字句解析器。Minecraft に依存しない純粋な Kotlin で、1回の tokenize ごとに新しいインスタンスを使う
class Lexer private constructor(private val source: String) {
    private val diagnostics = DiagnosticCollector()
    private val tokens = ArrayList<Token>()
    private var index = 0
    private var line = 1
    private var column = 1

    // 安全に読み進められないエラー（未対応のブロックコメントなど）やトークン数超過で true になり、以降は解析しない
    private var stopped = false

    companion object {
        // 最長一致のため、綴りの長い演算子から順に試す
        private val OPERATORS = TokenType.entries.filter { it.symbol != null }.sortedByDescending { it.symbol!!.length }

        fun tokenize(source: String): LexResult = Lexer(source).run()
    }

    private fun run(): LexResult {
        if (source.length > LexerLimits.MAX_SOURCE_LENGTH) {
            reportError(DiagnosticCode.LEX_SOURCE_TOO_LONG, position(), source.length, LexerLimits.MAX_SOURCE_LENGTH)
        } else if (validateCharacters()) {
            while (!stopped && index < source.length) scanToken()
        }
        tokens.add(Token(TokenType.EOF, "", position()))
        return LexResult(tokens.toList(), diagnostics.diagnostics, diagnostics.isTruncated, diagnostics.hasErrors)
    }

    // コメント・文字列の内部も含め、ソース全体の禁止文字を検査する。1件でもあれば字句解析を行わない
    private fun validateCharacters(): Boolean {
        var valid = true
        var i = 0
        var currentLine = 1
        var currentColumn = 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\n' || (c == '\r' && source.getOrNull(i + 1) == '\n')) {
                i += if (c == '\r') 2 else 1
                currentLine++
                currentColumn = 1
                continue
            }
            val width = if (c.isHighSurrogate() && source.getOrNull(i + 1)?.isLowSurrogate() == true) 2 else 1
            val code = forbiddenCharacterCode(c, width)
            if (code != null) {
                valid = false
                reportError(code, SourcePosition(i, currentLine, currentColumn), c.code)
            }
            i += width
            currentColumn += width
        }
        return valid
    }

    private fun forbiddenCharacterCode(c: Char, width: Int): DiagnosticCode? = when {
        c.isSurrogate() && width == 1 -> DiagnosticCode.LEX_INVALID_SURROGATE
        c == '\r' -> DiagnosticCode.LEX_LONE_CARRIAGE_RETURN
        c == '\t' -> null
        c < ' ' || c in '\u007F'..'\u009F' -> DiagnosticCode.LEX_FORBIDDEN_CONTROL_CHARACTER
        c == '﻿' -> DiagnosticCode.LEX_BYTE_ORDER_MARK
        c in '‪'..'‮' || c in '⁦'..'⁩' -> DiagnosticCode.LEX_BIDI_CONTROL_CHARACTER
        else -> null
    }

    private fun scanToken() {
        val c = source[index]
        when {
            c == ' ' || c == '\t' -> advance(1)
            // validateCharacters 済みなので、'\r' は必ず CRLF の一部
            c == '\n' || c == '\r' -> scanNewline()
            c == '/' && peek(1) == '/' -> skipLineComment()
            c == '/' && peek(1) == '*' -> stop(DiagnosticCode.LEX_BLOCK_COMMENT_UNSUPPORTED)
            c == '"' -> scanString()
            c == '\'' -> stop(DiagnosticCode.LEX_CHAR_LITERAL_UNSUPPORTED)
            c == '`' -> stop(DiagnosticCode.LEX_BACKTICK_IDENTIFIER_UNSUPPORTED)
            // ".5" は Double のリテラル（Kotlin と同じ: 第3.11a段階）
            c.isAsciiDigit() || (c == '.' && peek(1)?.isAsciiDigit() == true) -> scanNumber()
            c.isIdentifierStart() -> scanIdentifier()
            else -> scanOperator()
        }
    }

    private fun scanNewline() {
        val start = index
        val startPosition = position()
        val length = if (source[index] == '\r') 2 else 1
        index += length
        addToken(TokenType.NEWLINE, start, startPosition)
        line++
        column = 1
    }

    private fun skipLineComment() {
        while (index < source.length && source[index] != '\n' && source[index] != '\r') advance(1)
    }

    private fun scanIdentifier() {
        val start = index
        val startPosition = position()
        while (index < source.length && source[index].isIdentifierPart()) advance(1)
        val text = source.substring(start, index)
        when {
            text.length > LexerLimits.MAX_IDENTIFIER_LENGTH ->
                reportError(DiagnosticCode.LEX_IDENTIFIER_TOO_LONG, startPosition, text.length, LexerLimits.MAX_IDENTIFIER_LENGTH)
            text.all { it == '_' } -> reportError(DiagnosticCode.LEX_UNDERSCORE_ONLY_IDENTIFIER, startPosition)
            else -> addToken(if (text in HARD_KEYWORDS) TokenType.KEYWORD else TokenType.IDENTIFIER, start, startPosition)
        }
    }

    // 数値リテラル。整数（10進のみ）と、浮動小数点数（第3.11a段階: 1.5・.5・1e3・1.2e-4・1E+3・1.5f・1f・1e3f）。
    // f / F の付いたものは Float、小数点・指数だけのものは Double（Kotlin と同じ）。小数点の後には数字が必要で、"1." は整数 1 と "." として読む
    // （1.toDouble() に使える）。整数部の先頭の 0 は整数と同じく禁止（0.5 は可）。
    // 16進・2進（0x / 0b）、区切り（1_000）、f 以外のサフィックス（1L / 1u / 1d）は未対応
    private fun scanNumber() {
        val start = index
        val startPosition = position()
        skipDigits()
        val integerDigits = index - start
        var floating = false
        if (peek(0) == '.' && peek(1)?.isAsciiDigit() == true) {
            advance(1)
            skipDigits()
            floating = true
        }
        if (peek(0) == 'e' || peek(0) == 'E') {
            // 指数の数字が無い（1e・1e+・1ex）
            val signLength = if (peek(1) == '+' || peek(1) == '-') 1 else 0
            advance(1 + signLength)
            if (peek(0)?.isAsciiDigit() != true) {
                skipNumberTail()
                reportError(DiagnosticCode.LEX_MALFORMED_EXPONENT, startPosition)
                return
            }
            skipDigits()
            floating = true
        }
        val isFloat = peek(0) == 'f' || peek(0) == 'F'
        if (isFloat) advance(1)
        // 続く文字があれば未対応の表記（1L・1d・0x1F・1_000・1.5.5 など）
        val next = peek(0)
        if (next?.isIdentifierPart() == true || (next == '.' && peek(1)?.isAsciiDigit() == true)) {
            scanUnsupportedNumber(startPosition)
            return
        }
        if (integerDigits > 1 && source[start] == '0') {
            reportError(DiagnosticCode.LEX_LEADING_ZERO, startPosition)
            return
        }
        val text = source.substring(start, index)
        if (floating || isFloat) {
            scanFloatingValue(text, isFloat, start, startPosition)
            return
        }
        if (text.length > LexerLimits.MAX_INTEGER_DIGITS) {
            reportError(DiagnosticCode.LEX_INTEGER_TOO_MANY_DIGITS, startPosition, text.length, LexerLimits.MAX_INTEGER_DIGITS)
        } else {
            addToken(TokenType.INT_LITERAL, start, startPosition, intValue = text.toLong())
        }
    }

    // 浮動小数点数のリテラルの値（第3.11a段階）。綴りは数字・小数点・指数（e、符号、数字）と末尾の f だけで、JDK の parseFloat / parseDouble で
    // 最も近い値に丸める（Float は Double を経由せず直接丸め、二重の丸めを避ける）。長すぎる綴りは値を求める前に拒否する。
    // 無限大に丸められる値（1e400）と、0 でない数字が 0 に丸められる値（1e-400）は、書いた値と大きく違うため拒否する（非正規化数は可）
    private fun scanFloatingValue(text: String, isFloat: Boolean, start: Int, startPosition: SourcePosition) {
        if (text.length > LexerLimits.MAX_FLOAT_LITERAL_LENGTH) {
            reportError(DiagnosticCode.LEX_FLOAT_LITERAL_TOO_LONG, startPosition, text.length, LexerLimits.MAX_FLOAT_LITERAL_LENGTH)
            return
        }
        val digits = if (isFloat) text.dropLast(1) else text
        val nonZero = digits.takeWhile { it != 'e' && it != 'E' }.any { it in '1'..'9' }
        if (isFloat) {
            val value = digits.toFloat()
            if (value.isInfinite() || (value == 0f && nonZero)) {
                reportError(DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE, startPosition)
            } else {
                addToken(TokenType.FLOAT_LITERAL, start, startPosition, floatValue = value)
            }
        } else {
            val value = digits.toDouble()
            if (value.isInfinite() || (value == 0.0 && nonZero)) {
                reportError(DiagnosticCode.LEX_FLOAT_LITERAL_OUT_OF_RANGE, startPosition)
            } else {
                addToken(TokenType.DOUBLE_LITERAL, start, startPosition, doubleValue = value)
            }
        }
    }

    private fun skipDigits() {
        while (index < source.length && source[index].isAsciiDigit()) advance(1)
    }

    // 未対応の数値表記を、識別子に使える文字と小数点が続く限り読み飛ばしてエラーにする
    private fun scanUnsupportedNumber(startPosition: SourcePosition) {
        skipNumberTail()
        reportError(DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT, startPosition)
    }

    private fun skipNumberTail() {
        while (index < source.length) {
            val c = source[index]
            if (c.isIdentifierPart() || (c == '.' && peek(1)?.isAsciiDigit() == true)) advance(1) else break
        }
    }

    private fun scanString() {
        val start = index
        val startPosition = position()
        if (peek(1) == '"' && peek(2) == '"') {
            stop(DiagnosticCode.LEX_RAW_STRING_UNSUPPORTED)
            return
        }
        advance(1)
        val value = StringBuilder()
        var hasError = false
        while (true) {
            // 改行と EOF は文字列に含めず、文字列の外側として扱う（改行トークンは通常どおり生成される）
            val c = peek(0)
            if (c == null || c == '\n' || c == '\r') {
                reportError(DiagnosticCode.LEX_UNTERMINATED_STRING, startPosition)
                return
            }
            when (c) {
                '"' -> {
                    advance(1)
                    break
                }
                '\\' -> hasError = !scanEscape(value) || hasError
                '$' -> {
                    reportError(DiagnosticCode.LEX_STRING_TEMPLATE_UNSUPPORTED, position())
                    hasError = true
                    advance(1)
                }
                '\t' -> {
                    reportError(DiagnosticCode.LEX_STRING_CONTROL_CHARACTER, position(), c.code)
                    hasError = true
                    advance(1)
                }
                else -> {
                    val width = if (c.isHighSurrogate()) 2 else 1
                    if (value.length <= LexerLimits.MAX_STRING_LITERAL_LENGTH) value.append(source, index, index + width)
                    advance(width)
                }
            }
            if (value.length > LexerLimits.MAX_STRING_LITERAL_LENGTH && !hasError) {
                reportError(DiagnosticCode.LEX_STRING_TOO_LONG, startPosition, LexerLimits.MAX_STRING_LITERAL_LENGTH)
                hasError = true
            }
        }
        if (!hasError) addToken(TokenType.STRING_LITERAL, start, startPosition, stringValue = value.toString())
    }

    // 許可するエスケープは \" \\ \$ \' のみ。解釈できたら true を返す
    private fun scanEscape(value: StringBuilder): Boolean {
        val escaped = peek(1)
        if (escaped == null || escaped == '\n' || escaped == '\r') {
            // バックスラッシュだけ読み進め、未終了の文字列として scanString に報告させる
            advance(1)
            return true
        }
        if (escaped == '"' || escaped == '\\' || escaped == '$' || escaped == '\'') {
            if (value.length <= LexerLimits.MAX_STRING_LITERAL_LENGTH) value.append(escaped)
            advance(2)
            return true
        }
        val codePoint = source.codePointAt(index + 1)
        reportError(DiagnosticCode.LEX_UNSUPPORTED_ESCAPE, position(), codePoint)
        advance(1 + Character.charCount(codePoint))
        return false
    }

    private fun scanOperator() {
        val start = index
        val startPosition = position()
        val operator = OPERATORS.firstOrNull { source.startsWith(it.symbol!!, index) }
        if (operator == null) {
            val codePoint = source.codePointAt(index)
            reportError(DiagnosticCode.LEX_INVALID_CHARACTER, startPosition, codePoint)
            advance(Character.charCount(codePoint))
            return
        }
        advance(operator.symbol!!.length)
        addToken(operator, start, startPosition)
    }

    private fun addToken(
        type: TokenType,
        start: Int,
        startPosition: SourcePosition,
        intValue: Long? = null,
        stringValue: String? = null,
        floatValue: Float? = null,
        doubleValue: Double? = null,
    ) {
        if (tokens.size >= LexerLimits.MAX_TOKENS) {
            reportError(DiagnosticCode.LEX_TOO_MANY_TOKENS, startPosition, LexerLimits.MAX_TOKENS)
            stopped = true
            return
        }
        tokens.add(Token(type, source.substring(start, index), startPosition, intValue, stringValue, floatValue, doubleValue))
    }

    private fun stop(code: DiagnosticCode) {
        reportError(code, position())
        stopped = true
    }

    private fun reportError(code: DiagnosticCode, position: SourcePosition, vararg arguments: Int) {
        diagnostics.report(
            Diagnostic(code, DiagnosticSeverity.ERROR, DiagnosticPhase.LEXER, position, arguments.map { DiagnosticArgument.IntArgument(it) })
        )
    }

    // 改行以外の文字を読み進める（改行は scanNewline で行・列を更新する）
    private fun advance(length: Int) {
        index += length
        column += length
    }

    private fun peek(offset: Int): Char? = source.getOrNull(index + offset)

    private fun position() = SourcePosition(index, line, column)

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun Char.isIdentifierStart() = this in 'a'..'z' || this in 'A'..'Z' || this == '_'

    private fun Char.isIdentifierPart() = isIdentifierStart() || isAsciiDigit()
}
