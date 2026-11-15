package com.creatrbypc.createanalytical.project

/**
 * A dependency in `createscript.json`.
 *
 * @property name the package name ([ProjectRules.isValidName]).
 * @property version the exact version (SemVer; ranges are not supported).
 * @property source where the package comes from: `github:owner/repository` ([ProjectRules.isValidSource]).
 */
public data class Dependency(val name: String, val version: String, val source: String)

/**
 * The kind of a project (`kind` in schema version 2).
 *
 * @property id the value in the file.
 */
public enum class PackageKind(public val id: String) {
    /** A program that runs its `entry`. */
    APPLICATION("application"),

    /** A package that offers its `modules` for `import`. */
    LIBRARY("library"),
    ;

    public companion object {
        /** The kind with this [id], or `null`. */
        public fun of(id: String): PackageKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The contents of `createscript.json` (schema version 1 or 2). Every value is checked when the object is made.
 *
 * Schema version 1 has only applications. An application needs [entry] and has no [modules]; a library (schema
 * version 2) needs at least one module, and [entry] is optional.
 *
 * @property name the package name ([ProjectRules.isValidName]).
 * @property version the version (SemVer, [ProjectRules.isValidVersion]).
 * @property entry the path of the file to run, relative to the project ([ProjectRules.pathProblem]), or `null`.
 * @property license an SPDX license expression, or `null`.
 * @property dependencies sorted by name, without duplicates, at most [Manifests.MAX_DEPENDENCIES].
 * @property schemaVersion 1 or 2.
 * @property kind application or library.
 * @property modules the module names a library offers, sorted, without duplicates, at most [Manifests.MAX_MODULES].
 * @throws IllegalArgumentException if a value breaks these rules.
 */
public data class Manifest(
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

/**
 * The result of [Manifests.parse].
 *
 * @property manifest the manifest, or `null` when there is an error.
 * @property diagnostics the problems found (empty when [manifest] is not `null`).
 * @property truncated `true` when more diagnostics were dropped.
 * @property entryPosition where the value of `entry` is, to report a missing file there.
 * @property dependencyPositions where the name of each dependency is.
 * @property modulePositions where each name in `modules` is.
 */
public class ManifestResult(
    public val manifest: Manifest?,
    public val diagnostics: List<ProjectDiagnostic>,
    public val truncated: Boolean,
    public val entryPosition: FilePosition = FilePosition(1, 1),
    public val dependencyPositions: Map<String, FilePosition> = emptyMap(),
    public val modulePositions: Map<String, FilePosition> = emptyMap(),
)

/**
 * Reads, checks and writes `createscript.json`. It works on bytes and strings only; it reads no files. Safe to use
 * from several threads.
 */
public object Manifests {
    /** The file name. */
    public const val FILE_NAME: String = "createscript.json"

    /** The schema version that a new project gets (`createscript init`). */
    public const val SCHEMA_VERSION: Long = 1L

    /** The newest schema version that can be read and written. */
    public const val LATEST_SCHEMA_VERSION: Long = 2L

    /** The schema versions that can be read and written. */
    public val SCHEMA_VERSIONS: Set<Long> = setOf(1L, 2L)

    /** The largest file, in bytes. */
    public const val MAX_BYTES: Int = 64 * 1024

    /** The most dependencies. */
    public const val MAX_DEPENDENCIES: Int = 1024

    /** The most modules of a library. */
    public const val MAX_MODULES: Int = 1024

    private val FIELDS = setOf("schemaVersion", "name", "version", "entry", "license", "dependencies")
    private val FIELDS_2 = FIELDS + setOf("kind", "modules")
    private val DEPENDENCY_FIELDS = setOf("version", "source")

    /**
     * Reads and checks [bytes] (UTF-8 JSON). Strict: unknown or duplicate keys, wrong types and values that break
     * the rules are diagnostics, not exceptions. `schemaVersion` is checked first; for an unsupported version
     * nothing else is checked. Files over [MAX_BYTES] are refused.
     */
    public fun parse(bytes: ByteArray): ManifestResult {
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
        val name = fields.string("name")?.also { ProjectRules.nameCode(it.value, ProjectRules.isValidName(it.value), ProjectDiagnosticCode.INVALID_NAME)?.let { code -> diagnostics.add(code, it, it.value) } }
        val version = fields.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
        // entry は application では必須、library では任意（kind が無い・正しくないときは、その診断だけにする）
        val entry = fields.string("entry", required = kind == PackageKind.APPLICATION)?.also { value ->
            ProjectRules.pathProblem(value.value)?.let { diagnostics.add(ProjectDiagnosticCode.INVALID_PATH, value, value.value, it) }
        }
        val license = fields.string("license", required = false)?.also { if (!ProjectRules.isValidLicense(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_LICENSE, it, it.value) }
        val dependencies = ArrayList<Dependency>()
        val dependencyPositions = HashMap<String, FilePosition>()
        fields.obj("dependencies", required = false)?.let { section ->
            if (section.members.size > MAX_DEPENDENCIES) diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, section, "dependencies", MAX_DEPENDENCIES.toString())
            for ((dependencyName, value) in section.members) {
                val position = section.keyPositions[dependencyName]
                dependencyPositions[dependencyName] = position?.let { FilePosition(it.first, it.second) } ?: FilePosition(1, 1)
                val path = "dependencies.$dependencyName"
                ProjectRules.nameCode(dependencyName, ProjectRules.isValidName(dependencyName), ProjectDiagnosticCode.INVALID_NAME)?.let { diagnostics.add(it, position, dependencyName) }
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
        val modulePositions = HashMap<String, FilePosition>()
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
                        !ProjectRules.isValidModuleName(text.value) -> diagnostics.add(ProjectRules.nameCode(text.value, false, ProjectDiagnosticCode.INVALID_MODULE_NAME)!!, text, text.value)
                        text.value in modulePositions -> diagnostics.add(ProjectDiagnosticCode.DUPLICATE_MODULE, text, text.value)
                        else -> {
                            modules.add(text.value)
                            modulePositions[text.value] = FilePosition(text.line, text.column)
                        }
                    }
                }
            }
        }
        val entryPosition = entry?.let { FilePosition(it.line, it.column) } ?: FilePosition(1, 1)
        if (!diagnostics.isEmpty || name == null || version == null || kind == null || (entry == null && !library)) {
            return ManifestResult(null, diagnostics.list, diagnostics.truncated, entryPosition, dependencyPositions, modulePositions)
        }
        val manifest = Manifest(name.value, version.value, entry?.value, license?.value, dependencies.sortedBy { it.name }, schema.value.toInt(), kind, modules.sorted())
        return ManifestResult(manifest, emptyList(), false, entryPosition, dependencyPositions, modulePositions)
    }

    /**
     * The canonical text of [manifest]: `schemaVersion`, `kind` (version 2), `name`, `version`, `entry` and
     * `license` (when set), `modules` (libraries) and `dependencies` (by name, each with `version` and `source`).
     * The same manifest always gives the same text.
     */
    public fun write(manifest: Manifest): String {
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
