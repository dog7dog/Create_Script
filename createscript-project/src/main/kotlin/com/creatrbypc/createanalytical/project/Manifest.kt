package com.creatrbypc.createanalytical.project

// 依存（S8.1）。version は厳密な版（範囲の指定は未対応）、source は取得元（github:持ち主/リポジトリ）。S8.1 では形を確かめるだけで、取得・解決はしない
data class Dependency(val name: String, val version: String, val source: String)

// プロジェクトの種類（schemaVersion 2: S8.2 Phase 1）。application は entry を実行するプログラム、library は modules を公開するパッケージ
enum class PackageKind(val id: String) {
    APPLICATION("application"),
    LIBRARY("library"),
    ;

    companion object {
        fun of(id: String): PackageKind? = entries.firstOrNull { it.id == id }
    }
}

// createscript.json（schemaVersion 1・2）。dependencies は名前の順、modules（library が公開する import のモジュール名）は名前の順。
// schemaVersion 1 は application だけで、entry が必須（S8.1 と同じ）。schemaVersion 2 の application は entry が必須で modules は書けない。
// library は modules が 1 つ以上必須で、entry は任意
data class Manifest(
    val name: String,
    val version: String,
    val entry: String?,
    val license: String? = null,
    val dependencies: List<Dependency> = emptyList(),
    val schemaVersion: Int = 1,
    val kind: PackageKind = PackageKind.APPLICATION,
    val modules: List<String> = emptyList(),
) {
    init {
        require(schemaVersion.toLong() in Manifests.SCHEMA_VERSIONS) { "unsupported schemaVersion: $schemaVersion" }
        require(schemaVersion >= 2 || kind == PackageKind.APPLICATION) { "schemaVersion 1 has only applications" }
        require(ProjectRules.isValidName(name)) { "invalid name: $name" }
        require(ProjectRules.isValidVersion(version)) { "invalid version: $version" }
        require(entry != null || kind == PackageKind.LIBRARY) { "an application needs an entry" }
        require(entry == null || ProjectRules.pathProblem(entry) == null) { "invalid entry: $entry" }
        require(kind == PackageKind.LIBRARY || modules.isEmpty()) { "only a library has modules" }
        require(kind != PackageKind.LIBRARY || modules.isNotEmpty()) { "a library needs modules" }
        require(modules == modules.sorted().distinct() && modules.all(ProjectRules::isValidModuleName) && modules.size <= Manifests.MAX_MODULES) { "invalid modules: $modules" }
        require(license == null || ProjectRules.isValidLicense(license)) { "invalid license: $license" }
        require(dependencies.map { it.name } == dependencies.map { it.name }.sorted().distinct()) { "dependencies must be sorted by name without duplicates" }
        require(dependencies.size <= Manifests.MAX_DEPENDENCIES) { "too many dependencies" }
        for (dependency in dependencies) {
            require(ProjectRules.isValidName(dependency.name) && dependency.name != name) { "invalid dependency name: ${dependency.name}" }
            require(ProjectRules.isValidVersion(dependency.version)) { "invalid dependency version: ${dependency.version}" }
            require(ProjectRules.isValidSource(dependency.source)) { "invalid dependency source: ${dependency.source}" }
        }
    }
}

// manifest はエラーが無いときだけ。entryPosition はエントリーのパスの値の位置（CLI がファイルの有無を報告するときに使う）、
// dependencyPositions は依存の名前（キー）の位置
// modulePositions は modules の各名前の位置（S8.2 Phase 1）
class ManifestResult(
    val manifest: Manifest?,
    val diagnostics: List<ProjectDiagnostic>,
    val truncated: Boolean,
    val entryPosition: Pair<Int, Int> = 1 to 1,
    val dependencyPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    val modulePositions: Map<String, Pair<Int, Int>> = emptyMap(),
)

// createscript.json の読み取り・検査・書き出し（S8.1。ファイルの読み書きはしない。バイト列・文字列だけを扱う）
object Manifests {
    const val FILE_NAME = "createscript.json"
    // 読み書きできる schemaVersion（1: S8.1、2: S8.2 Phase 1）。SCHEMA_VERSION は init が書く版（1 のまま）
    const val SCHEMA_VERSION = 1L
    const val LATEST_SCHEMA_VERSION = 2L
    val SCHEMA_VERSIONS = setOf(1L, 2L)
    const val MAX_BYTES = 64 * 1024
    const val MAX_DEPENDENCIES = 1024
    const val MAX_MODULES = 1024

    private val FIELDS = setOf("schemaVersion", "name", "version", "entry", "license", "dependencies")
    private val FIELDS_2 = FIELDS + setOf("kind", "modules")
    private val DEPENDENCY_FIELDS = setOf("version", "source")

    // schemaVersion を最初に確かめ、対応していない版なら他の項目は確かめない（項目の意味が版で変わりうるため）
    fun parse(bytes: ByteArray): ManifestResult {
        val json = JsonParser.parse(bytes, MAX_BYTES)
        val root = json.value ?: return ManifestResult(null, json.diagnostics, false)
        val diagnostics = ProjectDiagnostics()
        if (root !is JsonObject) {
            diagnostics.add(ProjectDiagnosticCode.NOT_AN_OBJECT, root, "createscript.json")
            return ManifestResult(null, diagnostics.list, diagnostics.truncated)
        }
        val fields = FieldReader(diagnostics, root, "")
        val schema = fields.integer("schemaVersion") ?: return ManifestResult(null, diagnostics.list, diagnostics.truncated)
        if (schema.value !in SCHEMA_VERSIONS) {
            diagnostics.add(ProjectDiagnosticCode.UNSUPPORTED_SCHEMA_VERSION, schema, schema.value.toString(), SCHEMA_VERSIONS.joinToString(", "))
            return ManifestResult(null, diagnostics.list, diagnostics.truncated)
        }
        val second = schema.value == 2L
        fields.allowOnly(if (second) FIELDS_2 else FIELDS)
        // kind（schemaVersion 2 では必須。schemaVersion 1 は常に application）
        var kind: PackageKind? = PackageKind.APPLICATION
        if (second) {
            kind = fields.string("kind")?.let { value -> PackageKind.of(value.value) ?: null.also { diagnostics.add(ProjectDiagnosticCode.INVALID_KIND, value, value.value) } }
        }
        val library = kind == PackageKind.LIBRARY
        val name = fields.string("name")?.also { if (!ProjectRules.isValidName(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_NAME, it, it.value) }
        val version = fields.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
        // entry は application では必須、library では任意（kind が無い・正しくないときは、その診断だけにする）
        val entry = fields.string("entry", required = kind == PackageKind.APPLICATION)?.also { value ->
            ProjectRules.pathProblem(value.value)?.let { diagnostics.add(ProjectDiagnosticCode.INVALID_PATH, value, value.value, it) }
        }
        val license = fields.string("license", required = false)?.also { if (!ProjectRules.isValidLicense(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_LICENSE, it, it.value) }
        val dependencies = ArrayList<Dependency>()
        val dependencyPositions = HashMap<String, Pair<Int, Int>>()
        fields.obj("dependencies", required = false)?.let { section ->
            if (section.members.size > MAX_DEPENDENCIES) diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, section, "dependencies", MAX_DEPENDENCIES.toString())
            for ((dependencyName, value) in section.members) {
                val position = section.keyPositions[dependencyName]
                dependencyPositions[dependencyName] = position ?: (1 to 1)
                val path = "dependencies.$dependencyName"
                if (!ProjectRules.isValidName(dependencyName)) diagnostics.add(ProjectDiagnosticCode.INVALID_NAME, position, dependencyName)
                if (dependencyName == name?.value) diagnostics.add(ProjectDiagnosticCode.DEPENDS_ON_ITSELF, position, dependencyName)
                val spec = value as? JsonObject
                if (spec == null) {
                    diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, path, "object")
                    continue
                }
                val reader = FieldReader(diagnostics, spec, path)
                reader.allowOnly(DEPENDENCY_FIELDS)
                val dependencyVersion = reader.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
                val source = reader.string("source")?.also { if (!ProjectRules.isValidSource(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_SOURCE, it, it.value) }
                if (dependencyVersion != null && source != null) dependencies.add(Dependency(dependencyName, dependencyVersion.value, source.value))
            }
        }
        // modules（schemaVersion 2 の library だけ。1 つ以上、import のモジュール名、重複なし）
        val modules = ArrayList<String>()
        val modulePositions = HashMap<String, Pair<Int, Int>>()
        if (second && kind == PackageKind.APPLICATION && fields.has("modules")) {
            diagnostics.add(ProjectDiagnosticCode.FIELD_NOT_ALLOWED, root.members["modules"], "modules", PackageKind.APPLICATION.id)
        } else if (library) {
            fields.array("modules")?.let { list ->
                if (list.elements.isEmpty()) diagnostics.add(ProjectDiagnosticCode.NO_MODULES, list, "modules")
                if (list.elements.size > MAX_MODULES) diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, list, "modules", MAX_MODULES.toString())
                for (element in list.elements) {
                    val text = element as? JsonString
                    when {
                        text == null -> diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, element, "modules", "string")
                        !ProjectRules.isValidModuleName(text.value) -> diagnostics.add(ProjectDiagnosticCode.INVALID_MODULE_NAME, text, text.value)
                        text.value in modulePositions -> diagnostics.add(ProjectDiagnosticCode.DUPLICATE_MODULE, text, text.value)
                        else -> {
                            modules.add(text.value)
                            modulePositions[text.value] = text.line to text.column
                        }
                    }
                }
            }
        }
        val entryPosition = entry?.let { it.line to it.column } ?: (1 to 1)
        if (!diagnostics.isEmpty || name == null || version == null || kind == null || (entry == null && !library)) {
            return ManifestResult(null, diagnostics.list, diagnostics.truncated, entryPosition, dependencyPositions, modulePositions)
        }
        val manifest = Manifest(name.value, version.value, entry?.value, license?.value, dependencies.sortedBy { it.name }, schema.value.toInt(), kind, modules.sorted())
        return ManifestResult(manifest, emptyList(), false, entryPosition, dependencyPositions, modulePositions)
    }

    // 決まった形の書き出し。項目は schemaVersion・kind（2 のとき）・name・version・entry（あれば）・license（あれば）・modules（library のとき）・dependencies の順。
    // 依存は名前の順で、各依存は version・source の順。schemaVersion 1 の出力は S8.1 と同じ
    fun write(manifest: Manifest): String {
        val members = ArrayList<Pair<String, JsonValue>>()
        members += "schemaVersion" to JsonWriter.integer(manifest.schemaVersion.toLong())
        if (manifest.schemaVersion >= 2) members += "kind" to JsonWriter.string(manifest.kind.id)
        members += "name" to JsonWriter.string(manifest.name)
        members += "version" to JsonWriter.string(manifest.version)
        manifest.entry?.let { members += "entry" to JsonWriter.string(it) }
        manifest.license?.let { members += "license" to JsonWriter.string(it) }
        if (manifest.kind == PackageKind.LIBRARY) members += "modules" to JsonWriter.array(manifest.modules.map(JsonWriter::string))
        members += "dependencies" to JsonWriter.obj(
            *manifest.dependencies.map { it.name to JsonWriter.obj("version" to JsonWriter.string(it.version), "source" to JsonWriter.string(it.source)) }.toTypedArray(),
        )
        return JsonWriter.write(JsonWriter.obj(*members.toTypedArray()))
    }
}
