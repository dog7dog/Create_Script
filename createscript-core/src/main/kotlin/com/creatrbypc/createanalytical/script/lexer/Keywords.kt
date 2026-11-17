package com.creatrbypc.createanalytical.script.lexer

import com.creatrbypc.createanalytical.script.InternalCreateScriptApi

// Kotlin 2.0 のハードキーワード。識別子には使えない（ソフトキーワード・修飾子キーワードは識別子として扱う）
@InternalCreateScriptApi
public val HARD_KEYWORDS: Set<String> = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
)
