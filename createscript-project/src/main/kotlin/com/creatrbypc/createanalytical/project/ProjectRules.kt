package com.creatrbypc.createanalytical.project

// プロジェクトのファイルの値の規則（S8.1。docs/packages.md に記載）。どれも ASCII だけを受け付ける（見た目の似た文字による取り違えを防ぐ）
object ProjectRules {
    const val MAX_NAME_LENGTH = 64
    const val MAX_VERSION_LENGTH = 128
    const val MAX_PATH_LENGTH = 256
    const val MAX_LICENSE_LENGTH = 64

    // パッケージ名: 英小文字で始まり、英小文字・数字と、間に 1 つずつのハイフン（例: vector-math）。import のモジュール名とは別の概念で、自動では変換しない
    private val NAME = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")

    // Semantic Versioning 2.0.0（https://semver.org/ の正規表現と同じ規則。先頭の 0・空の識別子は不可）
    private val VERSION = Regex(
        "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(-((0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(\\.(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(\\+([0-9a-zA-Z-]+(\\.[0-9a-zA-Z-]+)*))?",
    )

    // SPDX のライセンス式に使う文字（例: MIT、Apache-2.0、MIT OR Apache-2.0）。SPDX の一覧との照合はしない
    private val LICENSE = Regex("[A-Za-z0-9.+()-]+( [A-Za-z0-9.+()-]+)*")

    // 取得元: github:持ち主/リポジトリ（GitHub の名前の規則: 持ち主は英数字と、間に 1 つずつのハイフンで 39 文字まで、リポジトリは英数字・. _ - で 100 文字まで）
    private val GITHUB = Regex("github:([A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38})/([A-Za-z0-9._-]{1,100})")

    // SHA-256: 小文字の 16 進数 64 桁（大文字は正規形でないため受け付けない）
    private val SHA256 = Regex("[0-9a-f]{64}")

    // パスの区切りごとの文字
    private val SEGMENT = Regex("[A-Za-z0-9._-]+")

    // Windows で使えない名前（拡張子を除いた名前。大文字・小文字を区別しない）
    private val WINDOWS_RESERVED = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    fun isValidName(name: String) = name.length <= MAX_NAME_LENGTH && NAME.matches(name)

    // ---- モジュール名（S8.2 Phase 1） ----

    // import のモジュール名の長さ（Core の LexerLimits.MAX_IDENTIFIER_LENGTH と同じ。一致は CLI のテストで確かめる）
    const val MAX_MODULE_NAME_LENGTH = 32

    // Core の識別子（英字・_ で始まり、英数字・_。_ だけのものは不可）で、Kotlin のハードキーワードでも、import できない組み込み関数の名前でもないもの。
    // createscript-project は Core に依存しないため、キーワード・組み込み関数の一覧をここにも持つ（Core の HARD_KEYWORDS・BuiltinFunctions.NAMES との一致は CLI のテストで確かめる）。
    // パッケージ名（ハイフンを使える）とは別の概念で、自動では変換しない
    private val MODULE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    val HARD_KEYWORDS: Set<String> = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )
    val BUILTIN_FUNCTIONS: Set<String> = setOf("println", "waitTicks", "listOf", "mutableListOf")

    fun isValidModuleName(name: String) = name.length <= MAX_MODULE_NAME_LENGTH && MODULE_NAME.matches(name) && !name.all { it == '_' } &&
        name !in HARD_KEYWORDS && name !in BUILTIN_FUNCTIONS

    // ---- ソースファイルの大きさ（S8.2 Phase 1） ----

    // .cscr 1 ファイルの上限（UTF-16 単位）と、それを UTF-8 にしたときの最大のバイト数（1 単位あたり最大 3 バイト。BMP の外の文字は 4 バイトで 2 単位）。
    // Core の CompileOptions.STANDALONE.maxSourceLength・CLI の CliLimits.MAX_SOURCE_BYTES と同じ値（一致は CLI のテストで確かめる）
    const val MAX_SOURCE_LENGTH = 1_000_000
    const val MAX_SOURCE_BYTES = MAX_SOURCE_LENGTH * 3

    fun isValidVersion(version: String) = version.length <= MAX_VERSION_LENGTH && VERSION.matches(version)

    fun isValidLicense(license: String) = license.length <= MAX_LICENSE_LENGTH && LICENSE.matches(license)

    fun isValidSource(source: String): Boolean {
        val match = GITHUB.matchEntire(source) ?: return false
        val repository = match.groupValues[2]
        return repository != "." && repository != ".."
    }

    fun isValidSha256(sha256: String) = SHA256.matches(sha256)

    // プロジェクトのルートからの相対パス（区切りは /）として正しくなければ、その理由。正しければ null。
    // 絶対パス・\・:（Windows のドライブ）・空の区切り・. と ..・使えない文字・Windows の予約名・末尾の . を拒否し、.cscr で終わることを求める。
    // これは字面だけの検査で、シンボリックリンクがルートの外を指さないことは、ファイルを読む側（CLI）が確かめる
    fun pathProblem(path: String): String? = when {
        path.isEmpty() -> "the path is empty"
        path.length > MAX_PATH_LENGTH -> "the path is longer than $MAX_PATH_LENGTH characters"
        path.startsWith("/") -> "absolute paths are not allowed"
        '\\' in path -> "use '/' to separate directories"
        ':' in path -> "':' is not allowed (no drive letters or URLs)"
        else -> path.split('/').firstNotNullOfOrNull(::segmentProblem) ?: if (!path.endsWith(".cscr") || path.substringAfterLast('/') == ".cscr") "the entry must be a .cscr file" else null
    }

    private fun segmentProblem(segment: String): String? = when {
        segment.isEmpty() -> "empty directory names ('//' or a trailing '/') are not allowed"
        segment == "." || segment == ".." -> "'.' and '..' are not allowed"
        !SEGMENT.matches(segment) -> "only letters, digits, '.', '_' and '-' are allowed in names"
        segment.endsWith(".") -> "names must not end with '.'"
        segment.substringBefore('.').lowercase() in WINDOWS_RESERVED -> "'$segment' is a reserved name on Windows"
        else -> null
    }
}

// 診断を集める（最大 MAX 件。それを超えた分は捨て、truncated で示す）
class ProjectDiagnostics {
    private val entries = ArrayList<ProjectDiagnostic>()

    var truncated = false
        private set

    val list: List<ProjectDiagnostic> get() = entries.toList()

    val isEmpty: Boolean get() = entries.isEmpty() && !truncated

    fun add(diagnostic: ProjectDiagnostic) {
        if (entries.size < MAX) entries.add(diagnostic) else truncated = true
    }

    fun add(code: ProjectDiagnosticCode, at: JsonValue?, vararg arguments: String) = add(ProjectDiagnostic(code, at?.line ?: 1, at?.column ?: 1, arguments.toList()))

    fun add(code: ProjectDiagnosticCode, position: Pair<Int, Int>?, vararg arguments: String) =
        add(ProjectDiagnostic(code, position?.first ?: 1, position?.second ?: 1, arguments.toList()))

    companion object {
        const val MAX = 64
    }
}

// オブジェクトの項目を読む手順（マニフェスト・ロックファイルで共通）。path は診断に表示する項目の名前（例: dependencies.vector-math.version）
internal class FieldReader(private val diagnostics: ProjectDiagnostics, private val obj: JsonObject, private val path: String) {
    private fun name(field: String) = if (path.isEmpty()) field else "$path.$field"

    // 知らない項目はエラー
    fun allowOnly(fields: Set<String>) {
        for (key in obj.members.keys) if (key !in fields) diagnostics.add(ProjectDiagnosticCode.UNKNOWN_FIELD, obj.keyPositions[key], name(key))
    }

    fun has(field: String) = field in obj.members

    private fun required(field: String): JsonValue? = obj.members[field] ?: run {
        diagnostics.add(ProjectDiagnosticCode.MISSING_FIELD, obj, name(field))
        null
    }

    fun string(field: String, required: Boolean = true): JsonString? {
        val value = (if (required) required(field) else obj.members[field]) ?: return null
        return value as? JsonString ?: wrongType(field, value, "string")
    }

    fun integer(field: String): JsonInteger? {
        val value = required(field) ?: return null
        return value as? JsonInteger ?: wrongType(field, value, "integer")
    }

    fun obj(field: String, required: Boolean = true): JsonObject? {
        val value = (if (required) required(field) else obj.members[field]) ?: return null
        return value as? JsonObject ?: wrongType(field, value, "object")
    }

    fun array(field: String): JsonArray? {
        val value = required(field) ?: return null
        return value as? JsonArray ?: wrongType(field, value, "array")
    }

    private fun wrongType(field: String, value: JsonValue, expected: String): Nothing? {
        diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, name(field), expected)
        return null
    }
}
