package com.creatrbypc.createanalytical.script.lexer

import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition

// lexeme はソース上の綴りそのもの。intValue は INT_LITERAL の値（Int32 の範囲検証は Semantic 段階で行う）、
// stringValue は STRING_LITERAL のエスケープ解除後の値、floatValue・doubleValue は FLOAT_LITERAL・DOUBLE_LITERAL の値（第3.11a段階。範囲は検証済み）
data class Token(
    val type: TokenType,
    val lexeme: String,
    val position: SourcePosition,
    val intValue: Long? = null,
    val stringValue: String? = null,
    val floatValue: Float? = null,
    val doubleValue: Double? = null,
)

// tokens は常に EOF で終わる。hasErrors は件数上限で捨てたエラーも含む
data class LexResult(
    val tokens: List<Token>,
    val diagnostics: List<Diagnostic>,
    val diagnosticsTruncated: Boolean,
    val hasErrors: Boolean,
)
