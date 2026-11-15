package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.FieldReader
import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectDiagnostics

// CreateScript-Registry のリポジトリのファイル（registry.json・reserved-names.json・packages/<頭文字>/<名前>.json）の読み取り・検査・書き出し（S8.3 Phase 1）。
// ファイルの読み書きはしない（バイト列を受け取る）。どのファイルも、write の出力（2 字下げの決まった形）と同じバイト列であることを求める
// （PR の差分に、審査で見落としやすい書き方の違いを入れないため）
object RegistryFiles {
    const val REGISTRY_VERSION = 1L
    const val CONFIG_FILE = "registry.json"
    const val RESERVED_FILE = "reserved-names.json"

    private val CONFIG_FIELDS = setOf("registryVersion", "indexValidityDays", "signingKeyIds")
    private val RESERVED_FIELDS = setOf("registryVersion", "names")
    private val ENTRY_FIELDS = setOf("registryVersion", "name", "source", "repositoryId", "owners", "description", "license", "removed", "versions")
    private val OWNER_FIELDS = setOf("login", "id")
    private val VERSION_FIELDS = setOf("sha256", "dependencies", "review", "yanked")
    private val REVIEW_FIELDS = setOf("pullRequest", "reviewers", "date")

    // ---- registry.json ----

    fun parseConfig(bytes: ByteArray): RegistryResult<RegistryConfig> {
        val diagnostics = ProjectDiagnostics()
        val reader = RegistryReader(diagnostics)
        val (_, fields) = reader.document(bytes, RegistryLimits.MAX_CONFIG_BYTES, CONFIG_FILE, "registryVersion", REGISTRY_VERSION, ProjectDiagnosticCode.UNSUPPORTED_REGISTRY_VERSION)
            ?: return failed(diagnostics)
        fields.allowOnly(CONFIG_FIELDS)
        val days = fields.integer("indexValidityDays")?.let {
            if (it.value in 1..RegistryLimits.MAX_VALIDITY_DAYS) it.value.toInt()
            else null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_SETTING, it, "indexValidityDays", it.value.toString(), "1..${RegistryLimits.MAX_VALIDITY_DAYS}") }
        }
        val keyIds = fields.array("signingKeyIds")?.let { list -> stringList(reader, list, "signingKeyIds", 1, RegistryLimits.MAX_SIGNING_KEYS) { value, field -> reader.keyId(value, field) } }
        val config = if (days != null && keyIds != null) RegistryConfig(days, keyIds) else null
        return finish(diagnostics, config) { canonical(bytes, writeConfig(it), diagnostics) }
    }

    fun writeConfig(config: RegistryConfig): String = JsonWriter.write(
        JsonWriter.obj(
            "registryVersion" to JsonWriter.integer(REGISTRY_VERSION),
            "indexValidityDays" to JsonWriter.integer(config.indexValidityDays.toLong()),
            "signingKeyIds" to JsonWriter.array(config.signingKeyIds.map(JsonWriter::string)),
        ),
    )

    // ---- reserved-names.json ----

    fun parseReserved(bytes: ByteArray): RegistryResult<ReservedNames> {
        val diagnostics = ProjectDiagnostics()
        val reader = RegistryReader(diagnostics)
        val (_, fields) = reader.document(bytes, RegistryLimits.MAX_RESERVED_BYTES, RESERVED_FILE, "registryVersion", REGISTRY_VERSION, ProjectDiagnosticCode.UNSUPPORTED_REGISTRY_VERSION)
            ?: return failed(diagnostics)
        fields.allowOnly(RESERVED_FIELDS)
        val names = fields.obj("names")?.let { section ->
            if (section.members.size > RegistryLimits.MAX_RESERVED_NAMES) {
                diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, section, "names", RegistryLimits.MAX_RESERVED_NAMES.toString())
                return failed(diagnostics)
            }
            val result = LinkedHashMap<String, String>()
            for ((name, reason) in section.members) {
                val valid = reader.name(name, section.keyPositions[name])
                val text = reason as? JsonString
                if (text == null) diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, reason, "names.$name", "string")
                val why = reader.text(text, "names.$name")
                if (valid != null && why != null) result[valid] = why
            }
            result
        }
        return finish(diagnostics, names?.let(::ReservedNames)) { canonical(bytes, writeReserved(it), diagnostics) }
    }

    fun writeReserved(reserved: ReservedNames): String = JsonWriter.write(
        JsonWriter.obj(
            "registryVersion" to JsonWriter.integer(REGISTRY_VERSION),
            "names" to JsonWriter.obj(*reserved.names.toSortedMap().map { (name, reason) -> name to JsonWriter.string(reason) }.toTypedArray()),
        ),
    )

    // ---- packages/<頭文字>/<名前>.json ----

    // path はリポジトリの中のパス（区切りは /）。名前から決まるパス（RegistryRules.entryPath）でなければエラー
    fun parseEntry(path: String, bytes: ByteArray): RegistryResult<PackageEntry> {
        val diagnostics = ProjectDiagnostics()
        val reader = RegistryReader(diagnostics)
        val (_, fields) = reader.document(bytes, RegistryLimits.MAX_ENTRY_BYTES, path, "registryVersion", REGISTRY_VERSION, ProjectDiagnosticCode.UNSUPPORTED_REGISTRY_VERSION)
            ?: return failed(diagnostics)
        fields.allowOnly(ENTRY_FIELDS)
        val nameValue = fields.string("name")
        val name = reader.name(nameValue)
        if (name != null && path != RegistryRules.entryPath(name)) diagnostics.add(ProjectDiagnosticCode.ENTRY_PATH_MISMATCH, nameValue, path, RegistryRules.entryPath(name))
        val source = reader.source(fields.string("source"))
        val repositoryId = reader.id(fields.integer("repositoryId"), "repositoryId")
        val owners = fields.array("owners")?.let { list -> owners(reader, list) }
        val description = reader.text(fields.string("description"), "description")
        val license = reader.license(fields.string("license"))
        val removed = reader.withdrawal(fields.obj("removed", required = false), "removed")
        val versions = LinkedHashMap<String, EntryVersion>()
        reader.versionKeys(fields.obj("versions"), "versions", name)?.forEach { (version, value) ->
            val path2 = "versions.$version"
            val obj = value as? JsonObject
            if (obj == null) {
                diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, path2, "object")
                return@forEach
            }
            val versionFields = FieldReader(diagnostics, obj, path2)
            versionFields.allowOnly(VERSION_FIELDS)
            val sha256 = reader.sha256(versionFields.string("sha256"))
            val dependencies = reader.dependencies(versionFields.obj("dependencies"), "$path2.dependencies")
            val review = versionFields.obj("review")?.let { review(reader, it, "$path2.review") }
            val yanked = reader.withdrawal(versionFields.obj("yanked", required = false), "$path2.yanked")
            if (sha256 != null && dependencies != null && review != null) versions[version] = EntryVersion(sha256, dependencies, review, yanked)
        }
        // 自分自身への依存
        if (name != null) {
            for ((version, entry) in versions) if (name in entry.dependencies) diagnostics.add(ProjectDiagnosticCode.DEPENDS_ON_ITSELF, nameValue, "$name@$version")
        }
        val entry = if (name != null && source != null && repositoryId != null && owners != null && description != null && license != null) {
            PackageEntry(name, source, repositoryId, owners, description, license, removed, versions)
        } else {
            null
        }
        return finish(diagnostics, entry) { canonical(bytes, writeEntry(it), diagnostics) }
    }

    // 決まった形: registryVersion・name・source・repositoryId・owners（ID の順）・description・license・removed（あれば）・versions（版の文字列の順）。
    // 各版は sha256・dependencies（名前の順）・review（pullRequest・reviewers（名前の順）・date）・yanked（あれば）
    fun writeEntry(entry: PackageEntry): String {
        val members = arrayListOf<Pair<String, JsonValue>>(
            "registryVersion" to JsonWriter.integer(REGISTRY_VERSION),
            "name" to JsonWriter.string(entry.name),
            "source" to JsonWriter.string(entry.source),
            "repositoryId" to JsonWriter.integer(entry.repositoryId),
            "owners" to JsonWriter.array(entry.owners.sortedBy { it.id }.map { JsonWriter.obj("login" to JsonWriter.string(it.login), "id" to JsonWriter.integer(it.id)) }),
            "description" to JsonWriter.string(entry.description),
            "license" to JsonWriter.string(entry.license),
        )
        entry.removed?.let { members += "removed" to withdrawal(it) }
        members += "versions" to JsonWriter.obj(
            *entry.versions.toSortedMap().map { (version, value) ->
                val fields = arrayListOf<Pair<String, JsonValue>>(
                    "sha256" to JsonWriter.string(value.sha256),
                    "dependencies" to dependencies(value.dependencies),
                    "review" to JsonWriter.obj(
                        "pullRequest" to JsonWriter.integer(value.review.pullRequest),
                        "reviewers" to JsonWriter.array(value.review.reviewers.sorted().map(JsonWriter::string)),
                        "date" to JsonWriter.string(value.review.date.toString()),
                    ),
                )
                value.yanked?.let { fields += "yanked" to withdrawal(it) }
                version to JsonWriter.obj(*fields.toTypedArray())
            }.toTypedArray(),
        )
        return JsonWriter.write(JsonWriter.obj(*members.toTypedArray()))
    }

    // ---- 共通 ----

    internal fun withdrawal(value: Withdrawal) = JsonWriter.obj("reason" to JsonWriter.string(value.reason), "date" to JsonWriter.string(value.date.toString()))

    internal fun dependencies(value: Map<String, String>) = JsonWriter.obj(*value.toSortedMap().map { (name, version) -> name to JsonWriter.string(version) }.toTypedArray())

    private fun owners(reader: RegistryReader, list: JsonArray): List<Owner>? {
        val diagnostics = reader.diagnostics
        if (list.elements.isEmpty() || list.elements.size > RegistryLimits.MAX_OWNERS) {
            diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, list, "owners", "1..${RegistryLimits.MAX_OWNERS}")
            return null
        }
        val result = ArrayList<Owner>()
        var ok = true
        for ((index, element) in list.elements.withIndex()) {
            val path = "owners[$index]"
            val obj = element as? JsonObject ?: run {
                diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, element, path, "object")
                ok = false
                null
            } ?: continue
            val fields = FieldReader(diagnostics, obj, path)
            fields.allowOnly(OWNER_FIELDS)
            val login = reader.login(fields.string("login"), "$path.login")
            val id = reader.id(fields.integer("id"), "$path.id")
            if (login == null || id == null) {
                ok = false
                continue
            }
            if (result.any { it.id == id || it.login.lowercase() == login.lowercase() }) {
                diagnostics.add(ProjectDiagnosticCode.DUPLICATE_VALUE, obj, "owners", login)
                ok = false
                continue
            }
            result.add(Owner(login, id))
        }
        return if (ok) result else null
    }

    private fun review(reader: RegistryReader, obj: JsonObject, path: String): Review? {
        val fields = FieldReader(reader.diagnostics, obj, path)
        fields.allowOnly(REVIEW_FIELDS)
        val pullRequest = reader.id(fields.integer("pullRequest"), "$path.pullRequest")
        val reviewers = fields.array("reviewers")?.let { list -> stringList(reader, list, "$path.reviewers", 1, RegistryLimits.MAX_REVIEWERS, caseInsensitive = true) { value, field -> reader.login(value, field) } }
        val date = reader.date(fields.string("date"), "$path.date")
        return if (pullRequest != null && reviewers != null && date != null) Review(pullRequest, reviewers, date) else null
    }

    // 文字列の配列（件数 min..max、重複なし）
    private fun stringList(reader: RegistryReader, list: JsonArray, path: String, min: Int, max: Int, caseInsensitive: Boolean = false, read: (JsonValue, String) -> String?): List<String>? {
        if (list.elements.size !in min..max) {
            reader.diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, list, path, "$min..$max")
            return null
        }
        val result = ArrayList<String>()
        var ok = true
        for ((index, element) in list.elements.withIndex()) {
            val value = read(element, "$path[$index]")
            when {
                value == null -> ok = false
                result.any { if (caseInsensitive) it.lowercase() == value.lowercase() else it == value } -> {
                    reader.diagnostics.add(ProjectDiagnosticCode.DUPLICATE_VALUE, element, path, value)
                    ok = false
                }
                else -> result.add(value)
            }
        }
        return if (ok) result else null
    }

    // 正規形（expected）と同じバイト列か。違えば、最初に違う行を知らせる
    private fun canonical(bytes: ByteArray, expected: String, diagnostics: ProjectDiagnostics) {
        val canonical = expected.toByteArray(Charsets.UTF_8)
        if (canonical.contentEquals(bytes)) return
        val actual = bytes.toString(Charsets.UTF_8).split('\n')
        val lines = expected.split('\n')
        val line = actual.indices.firstOrNull { it >= lines.size || actual[it] != lines[it] } ?: actual.size
        diagnostics.add(ProjectDiagnostic(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL, line + 1, 1, listOf("the file is not in the canonical form (2-space indentation, fixed order of fields, sorted names and versions); expected: ${lines.getOrElse(line) { "the end of the file" }.trim()}")))
    }

    private fun <T> failed(diagnostics: ProjectDiagnostics) = RegistryResult<T>(null, diagnostics.list, diagnostics.truncated)

    // 値の検査を通ったものだけ、正規形を確かめる
    private inline fun <T : Any> finish(diagnostics: ProjectDiagnostics, value: T?, canonicalCheck: (T) -> Unit): RegistryResult<T> {
        if (value != null && diagnostics.isEmpty) canonicalCheck(value)
        return if (value != null && diagnostics.isEmpty) RegistryResult(value, emptyList()) else RegistryResult(null, diagnostics.list, diagnostics.truncated)
    }
}
