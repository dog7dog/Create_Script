package com.creatrbypc.createanalytical.project

// 依存（S8.1）。version は厳密な版（範囲の指定は未対応）、source は取得元（github:持ち主/リポジトリ）。S8.1 では形を確かめるだけで、取得・解決はしない
data class Dependency(val name: String, val version: String, val source: String)

// createscript.json（schemaVersion 1）。dependencies は名前の順
data class Manifest(
    val name: String,
    val version: String,
    val entry: String,
    val license: String? = null,
    val dependencies: List<Dependency> = emptyList(),
) {
    init {
        require(ProjectRules.isValidName(name)) { "invalid name: $name" }
        require(ProjectRules.isValidVersion(version)) { "invalid version: $version" }
        require(ProjectRules.pathProblem(entry) == null) { "invalid entry: $entry" }
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
class ManifestResult(
    val manifest: Manifest?,
    val diagnostics: List<ProjectDiagnostic>,
    val truncated: Boolean,
    val entryPosition: Pair<Int, Int> = 1 to 1,
    val dependencyPositions: Map<String, Pair<Int, Int>> = emptyMap(),
)

// createscript.json の読み取り・検査・書き出し（S8.1。ファイルの読み書きはしない。バイト列・文字列だけを扱う）
object Manifests {
    const val FILE_NAME = "createscript.json"
    const val SCHEMA_VERSION = 1L
    const val MAX_BYTES = 64 * 1024
    const val MAX_DEPENDENCIES = 1024

    private val FIELDS = setOf("schemaVersion", "name", "version", "entry", "license", "dependencies")
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
        if (schema.value != SCHEMA_VERSION) {
            diagnostics.add(ProjectDiagnosticCode.UNSUPPORTED_SCHEMA_VERSION, schema, schema.value.toString(), SCHEMA_VERSION.toString())
            return ManifestResult(null, diagnostics.list, diagnostics.truncated)
        }
        fields.allowOnly(FIELDS)
        val name = fields.string("name")?.also { if (!ProjectRules.isValidName(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_NAME, it, it.value) }
        val version = fields.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
        val entry = fields.string("entry")?.also { value ->
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
        val entryPosition = entry?.let { it.line to it.column } ?: (1 to 1)
        if (!diagnostics.isEmpty || name == null || version == null || entry == null) {
            return ManifestResult(null, diagnostics.list, diagnostics.truncated, entryPosition, dependencyPositions)
        }
        val manifest = Manifest(name.value, version.value, entry.value, license?.value, dependencies.sortedBy { it.name })
        return ManifestResult(manifest, emptyList(), false, entryPosition, dependencyPositions)
    }

    // 決まった形の書き出し（項目は schemaVersion・name・version・entry・license（あれば）・dependencies の順。依存は名前の順で、各依存は version・source の順）
    fun write(manifest: Manifest): String {
        val members = ArrayList<Pair<String, JsonValue>>()
        members += "schemaVersion" to JsonWriter.integer(SCHEMA_VERSION)
        members += "name" to JsonWriter.string(manifest.name)
        members += "version" to JsonWriter.string(manifest.version)
        members += "entry" to JsonWriter.string(manifest.entry)
        manifest.license?.let { members += "license" to JsonWriter.string(it) }
        members += "dependencies" to JsonWriter.obj(
            *manifest.dependencies.map { it.name to JsonWriter.obj("version" to JsonWriter.string(it.version), "source" to JsonWriter.string(it.source)) }.toTypedArray(),
        )
        return JsonWriter.write(JsonWriter.obj(*members.toTypedArray()))
    }
}
