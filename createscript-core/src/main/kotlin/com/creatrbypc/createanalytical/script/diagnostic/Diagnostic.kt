package com.creatrbypc.createanalytical.script.diagnostic

import com.creatrbypc.createanalytical.script.InternalCreateScriptApi

@InternalCreateScriptApi
public object DiagnosticLimits {
    public const val MAX_DIAGNOSTICS: Int = 16
    public const val MAX_ARGUMENTS: Int = 4
    public const val MAX_STRING_ARGUMENT_LENGTH: Int = 64
}

public enum class DiagnosticSeverity { ERROR, WARNING }

// 診断を出した処理の段階。後続の段階を実装するときに追加する
// MODULE はモジュールの解決（第8.0段階。末尾に追加）
public enum class DiagnosticPhase { LEXER, PARSER, SEMANTIC, COMPILER, RUNTIME, MODULE }

// 診断の引数は Int と String のみ（表示文言はクライアント側で翻訳キーから組み立てる）
public sealed interface DiagnosticArgument {
    public data class IntArgument(val value: Int) : DiagnosticArgument

    public data class StringArgument(val value: String) : DiagnosticArgument {
        init {
            require(value.length <= DiagnosticLimits.MAX_STRING_ARGUMENT_LENGTH) { "String argument too long: ${value.length}" }
        }

        public companion object {
            // 上限を超える文字列は、サロゲートペアを分断せずに短縮してから引数にする
            public fun truncated(value: String): StringArgument = StringArgument(truncateUtf16(value, DiagnosticLimits.MAX_STRING_ARGUMENT_LENGTH))
        }
    }
}

public data class Diagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val phase: DiagnosticPhase,
    val position: SourcePosition,
    val arguments: List<DiagnosticArgument> = emptyList(),
) {
    init {
        require(arguments.size <= DiagnosticLimits.MAX_ARGUMENTS) { "Too many arguments: ${arguments.size}" }
    }
}

// 診断を最大 MAX_DIAGNOSTICS 件まで保持する。上限を超えた分は捨て、isTruncated で省略があったことを示す
@InternalCreateScriptApi
public class DiagnosticCollector {
    private val entries = ArrayList<Diagnostic>()

    public val diagnostics: List<Diagnostic> get() = entries.toList()

    public var isTruncated: Boolean = false
        private set

    // 捨てたエラーも含めて、エラーが1件でも報告されたか（エラーがあればバイトコードを生成しない）
    public var hasErrors: Boolean = false
        private set

    // 前の段階（Lexer など）の診断を引き継ぐ。件数上限・省略の有無・エラーの有無は段階をまたいで共通
    public fun merge(previous: List<Diagnostic>, previousTruncated: Boolean, previousHasErrors: Boolean) {
        previous.forEach(::report)
        if (previousTruncated) isTruncated = true
        if (previousHasErrors) hasErrors = true
    }

    public fun report(diagnostic: Diagnostic) {
        if (diagnostic.severity == DiagnosticSeverity.ERROR) hasErrors = true
        if (entries.size < DiagnosticLimits.MAX_DIAGNOSTICS) {
            entries.add(diagnostic)
            return
        }
        isTruncated = true
        // 上限に達していても、エラーは警告より優先して残す（最後に追加された警告と入れ替える）
        if (diagnostic.severity == DiagnosticSeverity.ERROR) {
            val warningIndex = entries.indexOfLast { it.severity == DiagnosticSeverity.WARNING }
            if (warningIndex >= 0) {
                entries.removeAt(warningIndex)
                entries.add(diagnostic)
            }
        }
    }
}
