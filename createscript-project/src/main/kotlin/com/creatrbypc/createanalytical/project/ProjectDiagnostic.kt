package com.creatrbypc.createanalytical.project

// プロジェクトのファイル（createscript.json・createscript.lock）の診断（S8.1）。id は表示・翻訳に使う安定した識別子なので、一度決めたら変更・再利用しない。
// 位置は行・列（1 始まり。UTF-16 単位）で、ファイル全体の問題は 1 行 1 列
enum class ProjectDiagnosticCode(val id: String) {
    // ファイル（引数: 上限のバイト数）・文字コード
    FILE_TOO_LARGE("CS_PKG_FILE_TOO_LARGE"),
    NOT_UTF8("CS_PKG_NOT_UTF8"),
    BYTE_ORDER_MARK("CS_PKG_BYTE_ORDER_MARK"),

    // JSON（RFC 8259 の厳密な形）。構文の誤り（引数: 説明）、入れ子が深すぎる（引数: 上限）、同じオブジェクトの中の同じキー（引数: キー）、
    // 整数でない数値（小数点・指数）、64 ビットに収まらない整数
    JSON_SYNTAX("CS_PKG_JSON_SYNTAX"),
    JSON_TOO_DEEP("CS_PKG_JSON_TOO_DEEP"),
    JSON_DUPLICATE_KEY("CS_PKG_JSON_DUPLICATE_KEY"),
    JSON_NUMBER_NOT_INTEGER("CS_PKG_JSON_NUMBER_NOT_INTEGER"),
    JSON_NUMBER_OUT_OF_RANGE("CS_PKG_JSON_NUMBER_OUT_OF_RANGE"),

    // 項目（引数: 項目の名前。WRONG_TYPE は期待する型も、TOO_MANY_ENTRIES は上限も）
    NOT_AN_OBJECT("CS_PKG_NOT_AN_OBJECT"),
    MISSING_FIELD("CS_PKG_MISSING_FIELD"),
    UNKNOWN_FIELD("CS_PKG_UNKNOWN_FIELD"),
    WRONG_TYPE("CS_PKG_WRONG_TYPE"),
    TOO_MANY_ENTRIES("CS_PKG_TOO_MANY_ENTRIES"),

    // 版（引数: 書かれた版、対応している版）
    UNSUPPORTED_SCHEMA_VERSION("CS_PKG_UNSUPPORTED_SCHEMA_VERSION"),
    UNSUPPORTED_LOCK_VERSION("CS_PKG_UNSUPPORTED_LOCK_VERSION"),

    // 値の形（引数: 値。INVALID_PATH は理由も）
    INVALID_NAME("CS_PKG_INVALID_NAME"),
    INVALID_VERSION("CS_PKG_INVALID_VERSION"),
    INVALID_PATH("CS_PKG_INVALID_PATH"),
    INVALID_LICENSE("CS_PKG_INVALID_LICENSE"),
    INVALID_SOURCE("CS_PKG_INVALID_SOURCE"),
    INVALID_SHA256("CS_PKG_INVALID_SHA256"),
    DEPENDS_ON_ITSELF("CS_PKG_DEPENDS_ON_ITSELF"),

    // エントリーのファイル（CLI が確かめる。引数: パス）
    ENTRY_NOT_FOUND("CS_PKG_ENTRY_NOT_FOUND"),
    ENTRY_NOT_A_FILE("CS_PKG_ENTRY_NOT_A_FILE"),
    ENTRY_OUTSIDE_PROJECT("CS_PKG_ENTRY_OUTSIDE_PROJECT"),

    // ロックファイル。依存があるのに無い、正規形でない、同じ名前のパッケージが複数ある（引数: 名前）、パッケージの依存の重複（引数: パッケージ、依存）、
    // ロックファイルに無いパッケージへの依存（引数: パッケージ、依存）、依存の循環（引数: 循環の上のパッケージ）、
    // root がマニフェストと違う（引数: 項目、マニフェストの値、ロックファイルの値）、マニフェストの依存がロックファイルに無い（引数: 名前）、
    // 版・取得元がマニフェストと違う（引数: 名前、マニフェストの値、ロックファイルの値）、どこからも使われないパッケージ（引数: 名前）
    LOCK_MISSING("CS_PKG_LOCK_MISSING"),
    LOCK_NOT_CANONICAL("CS_PKG_LOCK_NOT_CANONICAL"),
    LOCK_DUPLICATE_PACKAGE("CS_PKG_LOCK_DUPLICATE_PACKAGE"),
    LOCK_DUPLICATE_DEPENDENCY("CS_PKG_LOCK_DUPLICATE_DEPENDENCY"),
    LOCK_UNKNOWN_DEPENDENCY("CS_PKG_LOCK_UNKNOWN_DEPENDENCY"),
    LOCK_DEPENDENCY_CYCLE("CS_PKG_LOCK_DEPENDENCY_CYCLE"),
    LOCK_ROOT_MISMATCH("CS_PKG_LOCK_ROOT_MISMATCH"),
    LOCK_MISSING_PACKAGE("CS_PKG_LOCK_MISSING_PACKAGE"),
    LOCK_VERSION_MISMATCH("CS_PKG_LOCK_VERSION_MISMATCH"),
    LOCK_SOURCE_MISMATCH("CS_PKG_LOCK_SOURCE_MISMATCH"),
    LOCK_UNUSED_PACKAGE("CS_PKG_LOCK_UNUSED_PACKAGE"),
}

data class ProjectDiagnostic(val code: ProjectDiagnosticCode, val line: Int, val column: Int, val arguments: List<String> = emptyList()) {
    // file:line:column: error CODE ("引数", …)（createscript run のコンパイルの診断と同じ形）
    fun format(file: String): String =
        "$file:$line:$column: error ${code.id}" + if (arguments.isEmpty()) "" else arguments.joinToString(", ", " (", ")") { "\"${escape(it)}\"" }

    private fun escape(text: String): String {
        val shown = if (text.length > MAX_ARGUMENT_LENGTH) text.take(MAX_ARGUMENT_LENGTH) + "…" else text
        return buildString {
            for (c in shown) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' || c == '\u007f' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }
    }

    companion object {
        // 表示する引数の長さ（それを超える分は「…」で省く）
        const val MAX_ARGUMENT_LENGTH = 80
    }
}
