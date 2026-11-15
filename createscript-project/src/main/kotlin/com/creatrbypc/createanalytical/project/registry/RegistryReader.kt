package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.FieldReader
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectDiagnostics
import com.creatrbypc.createanalytical.project.ProjectRules
import java.time.Instant
import java.time.LocalDate

// レジストリのファイルの値を読む手順（S8.3 Phase 1）。どれも、正しくなければ診断を加えて null を返す
internal class RegistryReader(val diagnostics: ProjectDiagnostics) {
    private fun at(value: JsonValue) = value.line to value.column

    // 文書の始まり: 大きさ・JSON・オブジェクト・版の項目。版が違えば、ほかは確かめない
    fun document(bytes: ByteArray, maxBytes: Int, file: String, versionField: String, version: Long, unsupported: ProjectDiagnosticCode): Pair<JsonObject, FieldReader>? {
        val json = JsonParser.parse(bytes, maxBytes)
        val root = json.value ?: run {
            json.diagnostics.forEach(diagnostics::add)
            return null
        }
        if (root !is JsonObject) {
            diagnostics.add(ProjectDiagnosticCode.NOT_AN_OBJECT, root, file)
            return null
        }
        val fields = FieldReader(diagnostics, root, "")
        val written = fields.integer(versionField) ?: return null
        if (written.value != version) {
            diagnostics.add(unsupported, written, written.value.toString(), version.toString())
            return null
        }
        return root to fields
    }

    fun name(text: String, position: Pair<Int, Int>?): String? {
        val code = ProjectRules.nameCode(text, ProjectRules.isValidName(text), ProjectDiagnosticCode.INVALID_NAME) ?: return text
        diagnostics.add(code, position, text)
        return null
    }

    fun name(value: JsonString?): String? = value?.let { name(it.value, at(it)) }

    fun version(text: String, position: Pair<Int, Int>?): String? = when {
        !ProjectRules.isValidVersion(text) -> null.also { diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, position, text) }
        !RegistryRules.isValidRegistryVersion(text) -> null.also { diagnostics.add(ProjectDiagnosticCode.VERSION_BUILD_METADATA, position, text) }
        else -> text
    }

    fun version(value: JsonString?): String? = value?.let { version(it.value, at(it)) }

    fun source(value: JsonString?): String? = value?.let {
        if (ProjectRules.isValidSource(it.value)) it.value else null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_SOURCE, it, it.value) }
    }

    fun sha256(value: JsonString?): String? = value?.let {
        if (ProjectRules.isValidSha256(it.value)) it.value else null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_SHA256, it, it.value) }
    }

    fun license(value: JsonString?): String? = value?.let {
        if (ProjectRules.isValidLicense(it.value)) it.value else null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_LICENSE, it, it.value) }
    }

    fun id(value: JsonInteger?, field: String): Long? = value?.let {
        if (RegistryRules.isValidId(it.value)) it.value else null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_ID, it, field, it.value.toString()) }
    }

    fun login(value: JsonValue?, field: String): String? {
        value ?: return null
        val text = value as? JsonString ?: return null.also { diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, field, "string") }
        return if (RegistryRules.isValidLogin(text.value)) text.value else null.also { diagnostics.add(ProjectDiagnosticCode.INVALID_LOGIN, text, text.value) }
    }

    fun text(value: JsonString?, field: String): String? = value?.let {
        val problem = RegistryRules.textProblem(it.value) ?: return it.value
        diagnostics.add(ProjectDiagnosticCode.INVALID_TEXT, it, field, problem)
        null
    }

    fun date(value: JsonString?, field: String): LocalDate? = value?.let {
        RegistryRules.parseDate(it.value) ?: null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_DATE, it, field, it.value) }
    }

    fun timestamp(value: JsonString?, field: String): Instant? = value?.let {
        RegistryRules.parseTimestamp(it.value) ?: null.also { _ -> diagnostics.add(ProjectDiagnosticCode.INVALID_DATE, it, field, it.value) }
    }

    fun keyId(value: JsonValue?, field: String): String? {
        value ?: return null
        val text = value as? JsonString ?: return null.also { diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, field, "string") }
        return if (RegistryRules.isValidKeyId(text.value)) text.value else null.also { diagnostics.add(ProjectDiagnosticCode.INVALID_KEY_ID, text, text.value) }
    }

    // {"reason": "...", "date": "YYYY-MM-DD"}
    fun withdrawal(value: JsonObject?, path: String): Withdrawal? {
        value ?: return null
        val fields = FieldReader(diagnostics, value, path)
        fields.allowOnly(setOf("reason", "date"))
        val reason = text(fields.string("reason"), "$path.reason")
        val date = date(fields.string("date"), "$path.date")
        return if (reason != null && date != null) Withdrawal(reason, date) else null
    }

    // {"名前": "版", …}（厳密な版。上限 RegistryLimits.MAX_DEPENDENCIES）
    fun dependencies(value: JsonObject?, path: String): Map<String, String>? {
        value ?: return null
        if (value.members.size > RegistryLimits.MAX_DEPENDENCIES) {
            diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, value, path, RegistryLimits.MAX_DEPENDENCIES.toString())
            return null
        }
        val result = LinkedHashMap<String, String>()
        var ok = true
        for ((dependency, dependencyVersion) in value.members) {
            val name = name(dependency, value.keyPositions[dependency])
            val text = dependencyVersion as? JsonString
            if (text == null) diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, dependencyVersion, "$path.$dependency", "string")
            val version = text?.let { version(it) }
            if (name != null && version != null) result[name] = version else ok = false
        }
        return if (ok) result else null
    }

    // 版の Map（キーは版）。上限と、1 つ以上あること
    fun versionKeys(value: JsonObject?, path: String, packageName: String?): List<Pair<String, JsonValue>>? {
        value ?: return null
        if (value.members.size > RegistryLimits.MAX_VERSIONS_PER_PACKAGE) {
            diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, value, path, RegistryLimits.MAX_VERSIONS_PER_PACKAGE.toString())
            return null
        }
        if (value.members.isEmpty()) {
            diagnostics.add(ProjectDiagnosticCode.NO_VERSIONS, value, packageName ?: path)
            return null
        }
        return value.members.entries.mapNotNull { (key, element) -> version(key, value.keyPositions[key])?.let { it to element } }
    }
}

// レジストリのファイルの大きさ・件数の上限（S8.3 Phase 1。docs/registry.md）
object RegistryLimits {
    const val MAX_CONFIG_BYTES = 16 * 1024
    const val MAX_RESERVED_BYTES = 256 * 1024
    const val MAX_RESERVED_NAMES = 4096
    const val MAX_ENTRY_BYTES = 1024 * 1024
    const val MAX_INDEX_BYTES = 16 * 1024 * 1024
    const val MAX_SIGNATURE_BYTES = 4 * 1024
    const val MAX_PACKAGES = 20_000
    const val MAX_VERSIONS_PER_PACKAGE = 1000
    const val MAX_TOTAL_VERSIONS = 200_000
    const val MAX_DEPENDENCIES = 256
    const val MAX_OWNERS = 16
    const val MAX_REVIEWERS = 16
    const val MAX_SIGNING_KEYS = 4

    // 1 つの版と、その依存をすべて合わせた数（S8.2 のロックファイルの上限と同じ）
    const val MAX_CLOSURE = 4096

    // index の有効期間（registry.json の indexValidityDays と、index の generated〜expires）
    const val MAX_VALIDITY_DAYS = 90
}
