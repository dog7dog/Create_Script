package com.creatrbypc.createanalytical.project

/**
 * The kinds of problems in project files and packages. [id] (`CS_PKG_...`, `CS_REG_...`) is the stable identifier
 * for messages and translation: an id is never changed or reused for another meaning. New codes are added in later
 * releases, so a `when` over this type needs an `else` branch.
 *
 * @property id the stable identifier, for example `CS_PKG_INVALID_NAME`.
 */
public enum class ProjectDiagnosticCode(public val id: String) {
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

    // Windows の予約名（CON・PRN・AUX・NUL・COM0〜9・LPT0〜9。大文字・小文字・拡張子によらない）を、パッケージ名・モジュール名に使った（S8.2 L-7。引数: 名前）
    RESERVED_NAME("CS_PKG_RESERVED_NAME"),

    // schemaVersion 2（S8.2 Phase 1）。kind が application・library でない（引数: 値）、その kind では書けない項目（引数: 項目、kind）、
    // import のモジュール名として使えない名前（引数: 名前）、同じモジュール名の重複（引数: 名前）、library の modules が空
    INVALID_KIND("CS_PKG_INVALID_KIND"),
    FIELD_NOT_ALLOWED("CS_PKG_FIELD_NOT_ALLOWED"),
    INVALID_MODULE_NAME("CS_PKG_INVALID_MODULE_NAME"),
    DUPLICATE_MODULE("CS_PKG_DUPLICATE_MODULE"),
    NO_MODULES("CS_PKG_NO_MODULES"),
    // プロジェクトに、modules に書いたモジュールのファイル（modules/名前.cscr）が無い（CLI が確かめる。引数: 名前）
    MODULE_FILE_NOT_FOUND("CS_PKG_MODULE_FILE_NOT_FOUND"),

    // エントリーのファイル（CLI が確かめる。引数: パス）
    ENTRY_NOT_FOUND("CS_PKG_ENTRY_NOT_FOUND"),
    ENTRY_NOT_A_FILE("CS_PKG_ENTRY_NOT_A_FILE"),
    ENTRY_OUTSIDE_PROJECT("CS_PKG_ENTRY_OUTSIDE_PROJECT"),

    // createscript.json・createscript.lock そのもの（CLI が確かめる。引数: ファイル名。S8.1 の追加検証）。シンボリックリンクがプロジェクトの外を指す、
    // 通常のファイルでない（ディレクトリ・FIFO・デバイスなど）、読んでいる間に差し替えられた・変わった
    FILE_OUTSIDE_PROJECT("CS_PKG_FILE_OUTSIDE_PROJECT"),
    NOT_A_REGULAR_FILE("CS_PKG_NOT_A_REGULAR_FILE"),
    FILE_CHANGED_WHILE_READING("CS_PKG_FILE_CHANGED_WHILE_READING"),

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

    // パッケージアーカイブ（createscript-package.zip。S8.2 Phase 1）。位置は 1 行 1 列。
    // 大きすぎる（引数: 上限のバイト数）、ZIP として正しくない（引数: 理由）、使えない ZIP の機能（引数: 機能。ZIP64・暗号化・データディスクリプター・圧縮方式など）、
    // 使えないパス（引数: パス、理由）、重複したパス（大文字・小文字だけの違いを含む。引数: パス）、通常のファイル・ディレクトリでない項目（シンボリックリンクなど。引数: パス）、
    // 項目が多すぎる（引数: 上限）、ファイルが大きすぎる（引数: パス、上限）、展開後の合計が大きすぎる（引数: 上限）、圧縮率が高すぎる（引数: パス、上限）、
    // 中身が CRC と合わない・宣言した大きさと違う（引数: パス）、createscript.json が無い、library のマニフェストでない、
    // 名前・版がインストールを指示したものと違う（引数: 期待した値、アーカイブの値）、modules のファイルが無い（引数: 名前）、
    // .cscr が使えない（引数: パス、理由。UTF-8 でない・BOM・長すぎる）
    ARCHIVE_TOO_LARGE("CS_PKG_ARCHIVE_TOO_LARGE"),
    ARCHIVE_MALFORMED("CS_PKG_ARCHIVE_MALFORMED"),
    ARCHIVE_UNSUPPORTED("CS_PKG_ARCHIVE_UNSUPPORTED"),
    ARCHIVE_INVALID_PATH("CS_PKG_ARCHIVE_INVALID_PATH"),
    ARCHIVE_DUPLICATE_PATH("CS_PKG_ARCHIVE_DUPLICATE_PATH"),
    ARCHIVE_SPECIAL_FILE("CS_PKG_ARCHIVE_SPECIAL_FILE"),
    ARCHIVE_TOO_MANY_FILES("CS_PKG_ARCHIVE_TOO_MANY_FILES"),
    ARCHIVE_FILE_TOO_LARGE("CS_PKG_ARCHIVE_FILE_TOO_LARGE"),
    ARCHIVE_TOTAL_TOO_LARGE("CS_PKG_ARCHIVE_TOTAL_TOO_LARGE"),
    ARCHIVE_COMPRESSION_RATIO("CS_PKG_ARCHIVE_COMPRESSION_RATIO"),
    ARCHIVE_CORRUPT_ENTRY("CS_PKG_ARCHIVE_CORRUPT_ENTRY"),
    ARCHIVE_MISSING_MANIFEST("CS_PKG_ARCHIVE_MISSING_MANIFEST"),
    ARCHIVE_NOT_A_LIBRARY("CS_PKG_ARCHIVE_NOT_A_LIBRARY"),
    ARCHIVE_NAME_MISMATCH("CS_PKG_ARCHIVE_NAME_MISMATCH"),
    ARCHIVE_VERSION_MISMATCH("CS_PKG_ARCHIVE_VERSION_MISMATCH"),
    ARCHIVE_MISSING_MODULE("CS_PKG_ARCHIVE_MISSING_MODULE"),
    ARCHIVE_INVALID_SOURCE("CS_PKG_ARCHIVE_INVALID_SOURCE"),

    // ---- レジストリ（S8.3 Phase 1。docs/registry.md）。JSON の誤り・項目の誤り・名前・版・取得元・SHA-256 の形は、上の CS_PKG_… を使う ----

    // 版（引数: 書かれた版、対応している版）: registry.json・reserved-names.json・パッケージのファイル（registryVersion）、index.json（indexVersion）、
    // index.json.sig（signatureVersion）
    UNSUPPORTED_REGISTRY_VERSION("CS_REG_UNSUPPORTED_REGISTRY_VERSION"),
    UNSUPPORTED_INDEX_VERSION("CS_REG_UNSUPPORTED_INDEX_VERSION"),
    UNSUPPORTED_SIGNATURE_VERSION("CS_REG_UNSUPPORTED_SIGNATURE_VERSION"),

    // 正規形でない（引数: 説明）。index.json・index.json.sig は 1 行の正規形、リポジトリのファイルは字下げした正規形
    REGISTRY_NOT_CANONICAL("CS_REG_NOT_CANONICAL"),

    // 値の形。ビルドメタデータ（+…）のある版（引数: 版）、GitHub の数値 ID・PR の番号・通し番号が 1〜2^53-1 でない（引数: 項目、値）、
    // GitHub のユーザー名でない（引数: 値）、説明・理由の文字列が使えない（引数: 項目、理由）、日付・日時の形が正しくない（引数: 項目、値）、
    // 鍵の ID の形が正しくない（引数: 値）、設定の値が範囲の外（引数: 項目、値、範囲）、同じ値の重複（引数: 項目、値）、版が 1 つも無い（引数: パッケージ）、
    // パッケージのファイルの置き場所が名前と合わない（引数: パス、正しいパス）
    VERSION_BUILD_METADATA("CS_REG_VERSION_BUILD_METADATA"),
    INVALID_ID("CS_REG_INVALID_ID"),
    INVALID_LOGIN("CS_REG_INVALID_LOGIN"),
    INVALID_TEXT("CS_REG_INVALID_TEXT"),
    INVALID_DATE("CS_REG_INVALID_DATE"),
    INVALID_KEY_ID("CS_REG_INVALID_KEY_ID"),
    INVALID_SETTING("CS_REG_INVALID_SETTING"),
    DUPLICATE_VALUE("CS_REG_DUPLICATE_VALUE"),
    NO_VERSIONS("CS_REG_NO_VERSIONS"),
    ENTRY_PATH_MISMATCH("CS_REG_ENTRY_PATH_MISMATCH"),

    // 名前（引数: 名前、予約名・似た名前）。予約名と同じか似ている、ほかのパッケージと似ている（ハイフン・0/o・1/l の違いだけ）、同じ名前のパッケージが 2 つ
    RESERVED_PACKAGE_NAME("CS_REG_RESERVED_PACKAGE_NAME"),
    SIMILAR_NAME("CS_REG_SIMILAR_NAME"),
    DUPLICATE_PACKAGE("CS_REG_DUPLICATE_PACKAGE"),
    // 同じ取得元（大文字・小文字を区別しない）・同じリポジトリの ID を、2 つのパッケージが使う（引数: 値、パッケージ、パッケージ）
    DUPLICATE_SOURCE("CS_REG_DUPLICATE_SOURCE"),
    DUPLICATE_REPOSITORY("CS_REG_DUPLICATE_REPOSITORY"),

    // 依存（引数: パッケージ@版、…）。レジストリに無い名前・版（依存@版）、削除したパッケージへの依存（依存）、循環、
    // 1 つの版の依存の中に、同じ名前の別の版がある（名前、版、版。S8.2 の 1 つのロックファイルに入らない）
    UNKNOWN_DEPENDENCY("CS_REG_UNKNOWN_DEPENDENCY"),
    DEPENDENCY_ON_REMOVED("CS_REG_DEPENDENCY_ON_REMOVED"),
    DEPENDENCY_CYCLE("CS_REG_DEPENDENCY_CYCLE"),
    DEPENDENCY_CONFLICT("CS_REG_DEPENDENCY_CONFLICT"),

    // index の日時（引数: 説明・日時）。generated・expires の関係が正しくない、期限切れ、生成日時が未来
    INVALID_VALIDITY("CS_REG_INVALID_VALIDITY"),
    INDEX_EXPIRED("CS_REG_INDEX_EXPIRED"),
    INDEX_NOT_YET_VALID("CS_REG_INDEX_NOT_YET_VALID"),

    // 署名。署名が無い、署名のファイルの形が正しくない（引数: 理由）、信頼する鍵の署名が無い（引数: 鍵の ID）、署名が合わない（引数: 鍵の ID）
    SIGNATURE_MISSING("CS_REG_SIGNATURE_MISSING"),
    SIGNATURE_MALFORMED("CS_REG_SIGNATURE_MALFORMED"),
    SIGNATURE_UNKNOWN_KEY("CS_REG_SIGNATURE_UNKNOWN_KEY"),
    SIGNATURE_INVALID("CS_REG_SIGNATURE_INVALID"),

    // 以前に受け入れた index との比較。通し番号の巻き戻し（引数: 以前、今回）、同じ通し番号で違う内容（引数: 番号）、生成日時の巻き戻し（引数: 以前、今回）、
    // パッケージ・版が消えた（引数: パッケージ（@版））、審査済みの SHA-256・依存が変わった（引数: パッケージ@版、以前、今回）、リポジトリの ID が変わった（引数: パッケージ、以前、今回）
    INDEX_ROLLBACK("CS_REG_INDEX_ROLLBACK"),
    INDEX_SERIAL_REUSED("CS_REG_INDEX_SERIAL_REUSED"),
    INDEX_TIME_ROLLBACK("CS_REG_INDEX_TIME_ROLLBACK"),
    PACKAGE_DISAPPEARED("CS_REG_PACKAGE_DISAPPEARED"),
    VERSION_DISAPPEARED("CS_REG_VERSION_DISAPPEARED"),
    SHA256_CHANGED("CS_REG_SHA256_CHANGED"),
    DEPENDENCIES_CHANGED("CS_REG_DEPENDENCIES_CHANGED"),
    REPOSITORY_CHANGED("CS_REG_REPOSITORY_CHANGED"),
}

/**
 * A problem in a project file.
 *
 * @property code what the problem is.
 * @property line the line, starting at 1 (1 for a problem of the whole file).
 * @property column the column in UTF-16 code units, starting at 1.
 * @property arguments values for the message; they come from the file and are not trusted.
 */
public data class ProjectDiagnostic(val code: ProjectDiagnosticCode, val line: Int, val column: Int, val arguments: List<String> = emptyList()) {
    /**
     * `file:line:column: error CODE ("argument", ...)`, the form the CLI uses. Arguments are quoted; control,
     * format and line separator characters and lone surrogates are written as `\uXXXX`, and each argument is cut to
     * [MAX_ARGUMENT_LENGTH] UTF-16 code units, so the text is safe to show on a terminal.
     */
    public fun format(file: String): String =
        "$file:$line:$column: error ${code.id}" + if (arguments.isEmpty()) "" else arguments.joinToString(", ", " (", ")") { "\"${escape(it)}\"" }

    // 引数はファイルの中の文字列（信頼できない）なので、端末の表示を変えうる文字を \uXXXX にする: C0・C1 の制御文字と DEL（ESC・CSI による端末の操作）、
    // 書式の制御文字（Unicode の Cf。双方向テキストの制御文字 U+202A〜U+202E・U+2066〜U+2069 などによる表示の入れ替え）、行・段落の区切り、対にならないサロゲート。
    // 長い引数は MAX_ARGUMENT_LENGTH 単位で切る（サロゲートの対は分けない）
    private fun escape(text: String): String {
        var end = minOf(text.length, MAX_ARGUMENT_LENGTH)
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
        val shown = if (end < text.length) text.substring(0, end) + "…" else text
        return buildString {
            var i = 0
            while (i < shown.length) {
                val c = shown[i]
                val paired = c.isHighSurrogate() && i + 1 < shown.length && shown[i + 1].isLowSurrogate()
                when {
                    paired -> {
                        append(c).append(shown[i + 1])
                        i += 2
                        continue
                    }
                    c == '"' || c == '\\' -> append('\\').append(c)
                    unsafe(c) -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
                i++
            }
        }
    }

    private fun unsafe(c: Char): Boolean {
        if (c < ' ' || c.code in 0x7f..0x9f || c.isSurrogate()) return true
        val type = Character.getType(c).toByte()
        return type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
    }

    public companion object {
        /** The longest argument that [format] shows; longer ones are cut and end with `…`. */
        public const val MAX_ARGUMENT_LENGTH: Int = 80
    }
}
