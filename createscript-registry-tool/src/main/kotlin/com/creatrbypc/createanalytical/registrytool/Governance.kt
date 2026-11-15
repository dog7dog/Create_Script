package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.registry.RegistryKeys
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import com.creatrbypc.createanalytical.project.registry.TrustedKey
import java.util.Base64

class Parsed<T>(val value: T?, val problems: List<String>)

// 運営のファイルの JSON を読む小さな手順（厳密な JSON は createscript-project の JsonParser。項目の誤りは文で返す）
internal class StrictObject(private val obj: JsonObject, private val path: String, private val problems: MutableList<String>) {
    private fun name(field: String) = if (path.isEmpty()) field else "$path.$field"

    fun allowOnly(fields: Set<String>) {
        for (key in obj.members.keys) if (key !in fields) problems += "unknown field ${name(key)}"
    }

    fun value(field: String): JsonValue? = obj.members[field] ?: null.also { problems += "missing field ${name(field)}" }

    fun string(field: String): String? = value(field)?.let { (it as? JsonString)?.value ?: null.also { _ -> problems += "${name(field)} must be a string" } }

    fun integer(field: String): Long? = value(field)?.let { (it as? JsonInteger)?.value ?: null.also { _ -> problems += "${name(field)} must be an integer" } }

    fun array(field: String): JsonArray? = value(field)?.let { it as? JsonArray ?: null.also { _ -> problems += "${name(field)} must be an array" } }

    companion object {
        fun document(bytes: ByteArray, maxBytes: Int, problems: MutableList<String>): StrictObject? {
            val json = JsonParser.parse(bytes, maxBytes)
            val root = json.value ?: return null.also { json.diagnostics.forEach { d -> problems += d.format("").removePrefix(":") } }
            val obj = root as? JsonObject ?: return null.also { problems += "the file must be a JSON object" }
            val reader = StrictObject(obj, "", problems)
            val version = reader.integer("registryVersion") ?: return null
            if (version != 1L) return null.also { problems += "unsupported registryVersion $version (supported: 1)" }
            return reader
        }

        // 正規形（JsonWriter の 2 字下げの形）と同じバイト列か
        fun canonical(bytes: ByteArray, expected: String, problems: MutableList<String>) {
            if (!expected.toByteArray(Charsets.UTF_8).contentEquals(bytes)) problems += "the file is not in the canonical form; expected exactly:\n$expected"
        }
    }
}

// maintainers.json: 共同メンテナー制（GOVERNANCE.md）。メンテナーは GitHub のユーザー名と、名前を変えても変わらない数値 ID で記録する。
// - lead: 運営責任者（メンテナーが 1 人以上いれば、その中の 1 人）
// - requiredApprovals: PR の作成者以外のメンテナーの承認の数（2 以上）
// - minimumMaintainers: これだけのメンテナーがそろうまで、パッケージの登録・更新と index の公開を止める（requiredApprovals + 1 以上）
data class Maintainer(val login: String, val id: Long)

data class Maintainers(val lead: String, val minimumMaintainers: Int, val requiredApprovals: Int, val maintainers: List<Maintainer>) {
    val open: Boolean get() = maintainers.size >= minimumMaintainers

    fun find(id: Long): Maintainer? = maintainers.firstOrNull { it.id == id }

    fun findLogin(login: String): Maintainer? = maintainers.firstOrNull { it.login.equals(login, ignoreCase = true) }

    val leadMaintainer: Maintainer? get() = findLogin(lead)

    fun closedReason() = "the registry is closed: it has ${maintainers.size} of the $minimumMaintainers maintainers it needs, so packages are not registered or updated and no index is published"

    companion object {
        const val MAX_BYTES = 64 * 1024
        const val MAX_MAINTAINERS = 64

        fun write(value: Maintainers): String = JsonWriter.write(
            JsonWriter.obj(
                "registryVersion" to JsonWriter.integer(1),
                "lead" to JsonWriter.string(value.lead),
                "minimumMaintainers" to JsonWriter.integer(value.minimumMaintainers.toLong()),
                "requiredApprovals" to JsonWriter.integer(value.requiredApprovals.toLong()),
                "maintainers" to JsonWriter.array(value.maintainers.sortedBy { it.id }.map { JsonWriter.obj("login" to JsonWriter.string(it.login), "id" to JsonWriter.integer(it.id)) }),
            ),
        )

        fun parse(bytes: ByteArray): Parsed<Maintainers> {
            val problems = ArrayList<String>()
            val reader = StrictObject.document(bytes, MAX_BYTES, problems) ?: return Parsed(null, problems)
            reader.allowOnly(setOf("registryVersion", "lead", "minimumMaintainers", "requiredApprovals", "maintainers"))
            val lead = reader.string("lead")?.also { if (!RegistryRules.isValidLogin(it)) problems += "lead is not a GitHub login" }
            val required = reader.integer("requiredApprovals")?.also { if (it !in 2..16) problems += "requiredApprovals must be 2..16" }
            val minimum = reader.integer("minimumMaintainers")?.also { if (it !in 3..MAX_MAINTAINERS || (required != null && it < required + 1)) problems += "minimumMaintainers must be at least requiredApprovals + 1 (and 3..$MAX_MAINTAINERS)" }
            val list = ArrayList<Maintainer>()
            reader.array("maintainers")?.let { array ->
                if (array.elements.size > MAX_MAINTAINERS) problems += "at most $MAX_MAINTAINERS maintainers"
                for ((index, element) in array.elements.withIndex()) {
                    val obj = element as? JsonObject ?: run { problems += "maintainers[$index] must be an object"; null } ?: continue
                    val entry = StrictObject(obj, "maintainers[$index]", problems)
                    entry.allowOnly(setOf("login", "id"))
                    val login = entry.string("login")?.takeIf { RegistryRules.isValidLogin(it) } ?: run { problems += "maintainers[$index].login is not a GitHub login"; null }
                    val id = entry.integer("id")?.takeIf { RegistryRules.isValidId(it) } ?: run { problems += "maintainers[$index].id is not a GitHub ID"; null }
                    if (login == null || id == null) continue
                    if (list.any { it.id == id || it.login.equals(login, ignoreCase = true) }) problems += "maintainers[$index] is listed twice" else list += Maintainer(login, id)
                }
            }
            if (lead != null && list.isNotEmpty() && list.none { it.login.equals(lead, ignoreCase = true) }) problems += "the lead $lead must be one of the maintainers"
            if (problems.isNotEmpty() || lead == null || required == null || minimum == null) return Parsed(null, problems)
            val value = Maintainers(lead, minimum.toInt(), required.toInt(), list)
            StrictObject.canonical(bytes, write(value), problems)
            return if (problems.isEmpty()) Parsed(value, emptyList()) else Parsed(null, problems)
        }
    }
}

// keys/trusted-keys.json: index の署名を確かめる公開鍵（公開前の確認に使う）。利用者の createscript は、これを読まず、自分に組み込んだ鍵で確かめる（Phase 3）
class TrustedKeysFile(val keys: RegistryKeys) {
    val hasTestKeys: Boolean get() = keys.keys.any { it.keyId.startsWith(TEST_KEY_PREFIX) }

    companion object {
        const val MAX_BYTES = 16 * 1024
        const val TEST_KEY_PREFIX = "test-only-"

        fun write(keys: List<TrustedKey>): String = JsonWriter.write(
            JsonWriter.obj(
                "registryVersion" to JsonWriter.integer(1),
                "keys" to JsonWriter.array(keys.sortedBy { it.keyId }.map { JsonWriter.obj("keyId" to JsonWriter.string(it.keyId), "publicKey" to JsonWriter.string(Base64.getEncoder().encodeToString(it.publicKey))) }),
            ),
        )

        fun parse(bytes: ByteArray): Parsed<TrustedKeysFile> {
            val problems = ArrayList<String>()
            val reader = StrictObject.document(bytes, MAX_BYTES, problems) ?: return Parsed(null, problems)
            reader.allowOnly(setOf("registryVersion", "keys"))
            val keys = ArrayList<TrustedKey>()
            reader.array("keys")?.let { array ->
                if (array.elements.size > 4) problems += "at most 4 keys"
                for ((index, element) in array.elements.withIndex()) {
                    val obj = element as? JsonObject ?: run { problems += "keys[$index] must be an object"; null } ?: continue
                    val entry = StrictObject(obj, "keys[$index]", problems)
                    entry.allowOnly(setOf("keyId", "publicKey"))
                    val keyId = entry.string("keyId")
                    val text = entry.string("publicKey")
                    if (keyId == null || text == null) continue
                    val raw = try {
                        Base64.getDecoder().decode(text).takeIf { it.size == 32 && Base64.getEncoder().encodeToString(it) == text }
                    } catch (error: IllegalArgumentException) {
                        null
                    }
                    if (raw == null) {
                        problems += "keys[$index].publicKey must be 32 bytes in standard Base64"
                        continue
                    }
                    try {
                        keys += TrustedKey(keyId, raw)
                    } catch (error: IllegalArgumentException) {
                        problems += "keys[$index]: ${error.message}"
                    }
                }
            }
            val registryKeys = try {
                RegistryKeys(keys)
            } catch (error: IllegalArgumentException) {
                problems += error.message ?: "invalid keys"
                null
            }
            if (problems.isEmpty()) StrictObject.canonical(bytes, write(keys), problems)
            return if (problems.isEmpty() && registryKeys != null) Parsed(TrustedKeysFile(registryKeys), emptyList()) else Parsed(null, problems)
        }
    }
}
