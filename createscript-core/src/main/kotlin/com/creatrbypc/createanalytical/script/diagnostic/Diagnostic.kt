package com.creatrbypc.createanalytical.script.diagnostic

import com.creatrbypc.createanalytical.script.InternalCreateScriptApi

@InternalCreateScriptApi
public object DiagnosticLimits {
    public const val MAX_DIAGNOSTICS: Int = 16
    public const val MAX_ARGUMENTS: Int = 4
    public const val MAX_STRING_ARGUMENT_LENGTH: Int = 64
}

/** How serious a [Diagnostic] is. */
public enum class DiagnosticSeverity {
    /** The program cannot be compiled, or the run stopped. */
    ERROR,

    /** A remark; the program is still compiled. */
    WARNING,
}

/**
 * The step that reported a [Diagnostic]. New phases can be added in later releases (as [MODULE] was), so a `when`
 * over this type needs an `else` branch.
 */
public enum class DiagnosticPhase {
    /** Reading characters into tokens. */
    LEXER,

    /** Reading tokens into a syntax tree. */
    PARSER,

    /** Checking names and types. */
    SEMANTIC,

    /** Making and checking bytecode. */
    COMPILER,

    /** Running the program. */
    RUNTIME,

    /** Finding and checking imported modules. */
    MODULE,
}

/**
 * A value inserted in the message of a [Diagnostic]. Only numbers and short strings: the host builds the text from
 * [DiagnosticCode.id] (for example as a translation key) and these arguments.
 */
public sealed interface DiagnosticArgument {
    /**
     * A number.
     *
     * @property value the number.
     */
    public data class IntArgument(val value: Int) : DiagnosticArgument

    /**
     * A string of at most 64 UTF-16 code units.
     *
     * @property value the string.
     * @throws IllegalArgumentException if [value] is longer; use [truncated] to cut a longer string.
     */
    public data class StringArgument(val value: String) : DiagnosticArgument {
        init {
            require(value.length <= DiagnosticLimits.MAX_STRING_ARGUMENT_LENGTH) { "String argument too long: ${value.length}" }
        }

        public companion object {
            /** An argument from [value], cut to 64 UTF-16 code units without splitting a surrogate pair. */
            public fun truncated(value: String): StringArgument = StringArgument(truncateUtf16(value, DiagnosticLimits.MAX_STRING_ARGUMENT_LENGTH))
        }
    }
}

/**
 * A problem found while compiling or running a program.
 *
 * @property code what the problem is; [DiagnosticCode.id] is the stable identifier.
 * @property severity error or warning.
 * @property phase the step that found it.
 * @property position where in the source it is (for a program with `import`, see `ModuleCompileResult.locate`).
 * @property arguments values for the message, at most 4.
 * @throws IllegalArgumentException if there are more than 4 arguments.
 */
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
