package com.creatrbypc.createanalytical.script.lexer

import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticLimits
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LexerTest {
    private fun types(source: String): List<TokenType> {
        val result = Lexer.tokenize(source)
        assertEquals(emptyList<DiagnosticCode>(), result.diagnostics.map { it.code }, "unexpected diagnostics for: $source")
        return result.tokens.map { it.type }.dropLast(1)
    }

    private fun codes(source: String): List<DiagnosticCode> = Lexer.tokenize(source).diagnostics.map { it.code }

    private fun single(source: String): Token {
        val tokens = Lexer.tokenize(source).tokens
        assertEquals(2, tokens.size, "expected one token + EOF for: $source")
        return tokens[0]
    }

    // ---- 識別子とキーワード ----

    @Test
    fun identifiersAndKeywords() {
        assertEquals(listOf(TokenType.KEYWORD, TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.INT_LITERAL), types("val speed = 5"))
        assertEquals(TokenType.KEYWORD, single("while").type)
        assertEquals(TokenType.KEYWORD, single("typeof").type)
        // ソフトキーワードは識別子
        assertEquals(TokenType.IDENTIFIER, single("value").type)
        assertEquals(TokenType.IDENTIFIER, single("get").type)
        assertEquals(TokenType.IDENTIFIER, single("println").type)
    }

    @Test
    fun identifierLength() {
        assertEquals(TokenType.IDENTIFIER, single("a".repeat(32)).type)
        assertEquals(listOf(DiagnosticCode.LEX_IDENTIFIER_TOO_LONG), codes("a".repeat(33)))
    }

    @Test
    fun underscoreIdentifiers() {
        assertEquals(listOf(DiagnosticCode.LEX_UNDERSCORE_ONLY_IDENTIFIER), codes("_"))
        assertEquals(listOf(DiagnosticCode.LEX_UNDERSCORE_ONLY_IDENTIFIER), codes("__"))
        assertEquals(listOf(DiagnosticCode.LEX_UNDERSCORE_ONLY_IDENTIFIER), codes("___"))
        assertEquals("_speed", single("_speed").lexeme)
        assertEquals("gear_1", single("gear_1").lexeme)
    }

    @Test
    fun nonAsciiIdentifierAndBacktick() {
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_CHARACTER), codes("あ"))
        assertEquals(listOf(DiagnosticCode.LEX_BACKTICK_IDENTIFIER_UNSUPPORTED), codes("`name`"))
    }

    // ---- 整数 ----

    @Test
    fun integers() {
        assertEquals(0L, single("0").intValue)
        assertEquals(9999999999L, single("9999999999").intValue)
        // Int32 の範囲検証は Semantic 段階で行うため、Lexer は受理する
        assertEquals(2147483648L, single("2147483648").intValue)
        assertEquals(listOf(DiagnosticCode.LEX_INTEGER_TOO_MANY_DIGITS), codes("12345678901"))
        // 負号は数値トークンに含めない
        assertEquals(listOf(TokenType.MINUS, TokenType.INT_LITERAL), types("-2147483648"))
    }

    @Test
    fun leadingZeroAndUnsupportedNumbers() {
        assertEquals(listOf(DiagnosticCode.LEX_LEADING_ZERO), codes("012"))
        assertEquals(listOf(DiagnosticCode.LEX_LEADING_ZERO), codes("00"))
        // 1e3・1.5・.5 は第3.11a段階で浮動小数点数のリテラルになった（FloatingLiteralTest）
        for (source in listOf("0x1F", "0b101", "1_000", "1L", "1u", "1d", "1.5L", "123abc")) {
            assertEquals(listOf(DiagnosticCode.LEX_UNSUPPORTED_NUMBER_FORMAT), codes(source), source)
        }
        // 小数でなければ、数値の後の "." はメンバーアクセスや範囲演算子になる
        assertEquals(listOf(TokenType.INT_LITERAL, TokenType.DOT, TokenType.IDENTIFIER), types("1.foo"))
        assertEquals(listOf(TokenType.INT_LITERAL, TokenType.RANGE, TokenType.INT_LITERAL), types("1..5"))
    }

    // ---- 文字列 ----

    @Test
    fun stringsAndEscapes() {
        assertEquals("", single("\"\"").stringValue)
        assertEquals("Hello 世界", single("\"Hello 世界\"").stringValue)
        assertEquals("a\"b\\c\$d'e", single("\"a\\\"b\\\\c\\\$d\\'e\"").stringValue)
        for (escape in listOf("\\n", "\\r", "\\t", "\\b", "\\u0041", "\\x")) {
            assertEquals(listOf(DiagnosticCode.LEX_UNSUPPORTED_ESCAPE), codes("\"$escape\""), escape)
        }
    }

    @Test
    fun rawDollarIsRejected() {
        assertEquals(listOf(DiagnosticCode.LEX_STRING_TEMPLATE_UNSUPPORTED), codes("\"\$x\""))
        assertEquals(listOf(DiagnosticCode.LEX_STRING_TEMPLATE_UNSUPPORTED), codes("\"cost \$5\""))
        assertEquals(listOf(DiagnosticCode.LEX_STRING_TEMPLATE_UNSUPPORTED), codes("\"\${x}\""))
        assertEquals("\$5", single("\"\\\$5\"").stringValue)
    }

    @Test
    fun stringLength() {
        assertEquals(256, single("\"" + "a".repeat(256) + "\"").stringValue!!.length)
        assertEquals(listOf(DiagnosticCode.LEX_STRING_TOO_LONG), codes("\"" + "a".repeat(257) + "\""))
        // エスケープ解除後の長さで数える（ソース上は 512 単位）
        assertEquals(256, single("\"" + "\\\"".repeat(256) + "\"").stringValue!!.length)
        // 絵文字は 2 単位
        assertEquals(256, single("\"" + "😀".repeat(128) + "\"").stringValue!!.length)
        assertEquals(listOf(DiagnosticCode.LEX_STRING_TOO_LONG), codes("\"" + "😀".repeat(128) + "a\""))
    }

    @Test
    fun unsupportedStringForms() {
        assertEquals(listOf(DiagnosticCode.LEX_UNTERMINATED_STRING), codes("\"abc"))
        assertEquals(listOf(DiagnosticCode.LEX_UNTERMINATED_STRING), codes("\"abc\\"))
        // 改行で文字列が終わり、改行トークンと次の行は通常どおり読む
        val result = Lexer.tokenize("\"abc\nx")
        assertEquals(listOf(DiagnosticCode.LEX_UNTERMINATED_STRING), result.diagnostics.map { it.code })
        assertEquals(listOf(TokenType.NEWLINE, TokenType.IDENTIFIER, TokenType.EOF), result.tokens.map { it.type })
        assertEquals(listOf(DiagnosticCode.LEX_RAW_STRING_UNSUPPORTED), codes("\"\"\"abc\"\"\""))
        assertEquals(listOf(DiagnosticCode.LEX_CHAR_LITERAL_UNSUPPORTED), codes("'a'"))
        assertEquals(listOf(DiagnosticCode.LEX_STRING_CONTROL_CHARACTER), codes("\"a\tb\""))
    }

    // ---- 改行・空白・コメント ----

    @Test
    fun newlines() {
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.NEWLINE, TokenType.IDENTIFIER), types("a\nb"))
        val crlf = Lexer.tokenize("a\r\nb").tokens
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.NEWLINE, TokenType.IDENTIFIER, TokenType.EOF), crlf.map { it.type })
        assertEquals("\r\n", crlf[1].lexeme)
        assertEquals(SourcePosition(3, 2, 1), crlf[2].position)
        assertEquals(listOf(DiagnosticCode.LEX_LONE_CARRIAGE_RETURN), codes("a\rb"))
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.IDENTIFIER), types("a \t b"))
    }

    @Test
    fun comments() {
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.NEWLINE, TokenType.IDENTIFIER), types("a // コメント 😀\nb"))
        assertEquals(listOf(DiagnosticCode.LEX_BLOCK_COMMENT_UNSUPPORTED), codes("/* a */"))
    }

    // ---- 文字の検証 ----

    @Test
    fun invalidSurrogates() {
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_SURROGATE), codes("\"\uD83D\""))
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_SURROGATE), codes("\"\uDE00\""))
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_SURROGATE), codes("a\uD83D"))
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_SURROGATE), codes("// \uDE00"))
        assertEquals("😀", single("\"😀\"").stringValue)
    }

    @Test
    fun forbiddenCharacters() {
        assertEquals(listOf(DiagnosticCode.LEX_FORBIDDEN_CONTROL_CHARACTER), codes("a\u0000"))
        assertEquals(listOf(DiagnosticCode.LEX_FORBIDDEN_CONTROL_CHARACTER), codes("a\u007F"))
        assertEquals(listOf(DiagnosticCode.LEX_FORBIDDEN_CONTROL_CHARACTER), codes("a\u0085"))
        assertEquals(listOf(DiagnosticCode.LEX_BYTE_ORDER_MARK), codes("﻿val"))
        assertEquals(listOf(DiagnosticCode.LEX_BIDI_CONTROL_CHARACTER), codes("\"a‮b\""))
        assertEquals(listOf(DiagnosticCode.LEX_BIDI_CONTROL_CHARACTER), codes("\"a⁦b\""))
        // コメント内部も検証する
        assertEquals(listOf(DiagnosticCode.LEX_BIDI_CONTROL_CHARACTER), codes("// ‮"))
        assertEquals(listOf(DiagnosticCode.LEX_FORBIDDEN_CONTROL_CHARACTER), codes("// \u0001"))
        // タブはコメント内では許可
        assertEquals(emptyList<DiagnosticCode>(), codes("//\tok"))
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_CHARACTER), codes("　"))
    }

    // ---- 演算子 ----

    @Test
    fun longestMatchOperators() {
        assertEquals(listOf(TokenType.DECREMENT, TokenType.INT_LITERAL), types("--5"))
        assertEquals(listOf(TokenType.MINUS, TokenType.MINUS, TokenType.INT_LITERAL), types("- -5"))
        assertEquals(listOf(TokenType.IDENTITY_EQUALS, TokenType.EQUALS, TokenType.ASSIGN), types("=== == ="))
        assertEquals(listOf(TokenType.RANGE_UNTIL, TokenType.RANGE, TokenType.DOT), types("..< .. ."))
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.MINUS, TokenType.INT_LITERAL),
            types("x=-1"),
        )
        assertEquals(listOf(TokenType.PLUS_ASSIGN, TokenType.ARROW, TokenType.SAFE_ACCESS, TokenType.ELVIS), types("+= -> ?. ?:"))
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.DOT, TokenType.IDENTIFIER, TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN),
            types("gear.start()"),
        )
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_CHARACTER), codes("#"))
        assertEquals(listOf(DiagnosticCode.LEX_INVALID_CHARACTER), codes("&"))
    }

    @Test
    fun semicolonsAreTokens() {
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.SEMICOLON, TokenType.SEMICOLON), types("a;;"))
    }

    // ---- 上限 ----

    @Test
    fun tokenLimit() {
        val atLimit = Lexer.tokenize("a;".repeat(1024))
        assertFalse(atLimit.hasErrors)
        assertEquals(LexerLimits.MAX_TOKENS + 1, atLimit.tokens.size)
        assertEquals(listOf(DiagnosticCode.LEX_TOO_MANY_TOKENS), codes("a;".repeat(1024) + "a"))
        // 改行トークンも数える
        assertFalse(Lexer.tokenize("\n".repeat(2048)).hasErrors)
        assertEquals(listOf(DiagnosticCode.LEX_TOO_MANY_TOKENS), codes("\n".repeat(2049)))
    }

    @Test
    fun sourceLength() {
        assertFalse(Lexer.tokenize(" ".repeat(4096)).hasErrors)
        val tooLong = Lexer.tokenize(" ".repeat(4097))
        assertEquals(listOf(DiagnosticCode.LEX_SOURCE_TOO_LONG), tooLong.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(4097), DiagnosticArgument.IntArgument(4096)), tooLong.diagnostics[0].arguments)
        assertEquals(listOf(TokenType.EOF), tooLong.tokens.map { it.type })
    }

    // ---- 診断 ----

    @Test
    fun diagnosticPositionsUseUtf16() {
        // " (1) 😀 (2-3) " (4) 空白 (5) # (6)
        val diagnostic = Lexer.tokenize("\"😀\" #").diagnostics.single()
        assertEquals(SourcePosition(5, 1, 6), diagnostic.position)
        val second = Lexer.tokenize("a\r\n  #").diagnostics.single()
        assertEquals(SourcePosition(5, 2, 3), second.position)
    }

    @Test
    fun diagnosticCountLimit() {
        val result = Lexer.tokenize("#".repeat(17))
        assertEquals(DiagnosticLimits.MAX_DIAGNOSTICS, result.diagnostics.size)
        assertTrue(result.diagnosticsTruncated)
        assertTrue(result.hasErrors)
        val exact = Lexer.tokenize("#".repeat(16))
        assertEquals(16, exact.diagnostics.size)
        assertFalse(exact.diagnosticsTruncated)
    }

    @Test
    fun recoversAfterSafeErrors() {
        val result = Lexer.tokenize("012 x")
        assertEquals(listOf(DiagnosticCode.LEX_LEADING_ZERO), result.diagnostics.map { it.code })
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.EOF), result.tokens.map { it.type })
    }
}
