package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// 案 B'（S8.3 Phase 10B-2A）: index-* のタグの ruleset A・B の、管理者が記録したスナップショットとの照合。
// GitHub は bypass_actors を、その ruleset に書き込める利用者にだけ返す（文書、P-19 は実測）。公開のワークフローの読み取りのトークンでは
// 回避者が見えないので、管理者（所有者）が取得した ruleset（bypass_actors を含む）を github-settings/verified-rulesets.json に記録し、
// 設定の証明用の鍵（keys/settings-keys.json。index の署名鍵とは別）で署名する。公開の前には、見える項目と updated_at を記録と照合する。
// 前提 P-20（回避者だけの変更・元に戻す変更でも updated_at が新しい値になる）は、テスト用のリポジトリの試験用 ruleset で確かめた範囲
// （回避者の追加と、元に戻す変更。C-2・C-3）だけで、それ以外の変更の種類や、GitHub の将来の挙動は保証しない。
// 確かめられない項目（欠けている・解釈できない・一致しない）は、すべて失敗にする（fail-closed）
object RulesetSnapshots {
    const val FILE = "github-settings/verified-rulesets.json"
    const val SIGNATURE_FILE = "github-settings/verified-rulesets.json.sig"
    const val KEYS_FILE = "keys/settings-keys.json"
    const val SNAPSHOT_VERSION = 1L
    const val MAX_BYTES = 64 * 1024

    // ドメインを分ける前置き。index の署名（IndexSignatures.DOMAIN）とは別の用途として区別する
    const val DOMAIN = "CreateScript-Registry ruleset snapshot signature v1"

    // 記録する項目（GitHub の「Get a repository ruleset」の応答の項目）
    val RULESET_FIELDS = listOf("id", "name", "target", "source_type", "source", "enforcement", "conditions", "rules", "bypass_actors", "created_at", "updated_at")
    private val TOP_FIELDS = setOf("snapshotVersion", "repository", "recordedAt", "recordedBy", "rulesets")
    private const val INDEX_PREFIX = "index-"
    private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val REPOSITORY = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}")

    // GitHub の時刻: 秒の小数 3 桁と、Z または ±hh:mm の時差がある形だけを受け入れる（実測: 所有者には +09:00、App には Z で同じ時刻が返る）
    private val TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})")

    // 公開の前の確認を行う主体。current_user_can_bypass の期待値が違う（P-21、実測は Publisher App・Checks App・所有者。GITHUB_TOKEN は未検証）
    enum class Actor(val creation: String, val protection: String) {
        WORKFLOW("never", "never"),
        PUBLISHER("always", "never"),
    }

    fun timestamp(text: String?): Instant? {
        if (text == null || !TIMESTAMP.matches(text)) return null
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (error: DateTimeParseException) {
            null
        }
    }

    private fun string(obj: JsonObject, key: String) = (obj.members[key] as? JsonString)?.value

    private fun canonical(value: JsonValue?): String? = value?.let { String(CanonicalJson.write(it), Charsets.UTF_8) }

    // conditions は include・exclude の並びを問わない
    private fun conditions(value: JsonValue?): String? {
        val obj = value as? JsonObject ?: return null
        val refName = obj.members["ref_name"] as? JsonObject ?: return null
        if (obj.members.keys != setOf("ref_name")) return null
        fun sorted(name: String): JsonArray? {
            val list = (refName.members[name] ?: JsonWriter.array(emptyList())) as? JsonArray ?: return null
            val strings = list.elements.map { (it as? JsonString)?.value ?: return null }.sorted()
            return JsonWriter.array(strings.map { JsonWriter.string(it) })
        }
        if (!setOf("include", "exclude").containsAll(refName.members.keys)) return null
        val include = sorted("include") ?: return null
        val exclude = sorted("exclude") ?: return null
        return canonical(JsonWriter.obj("ref_name" to JsonWriter.obj("include" to include, "exclude" to exclude)))
    }

    // rules・bypass_actors は要素の並びを問わない（要素ごとの正規形を並べ替える）
    private fun unordered(value: JsonValue?): String? {
        val list = value as? JsonArray ?: return null
        val parts = list.elements.map { (it as? JsonObject)?.let(::canonical) ?: return null }.sorted()
        return parts.joinToString(",", "[", "]")
    }

    private fun id(obj: JsonObject): Long? = (obj.members["id"] as? JsonInteger)?.value

    // テスト用のリポジトリの試験用 ruleset（refs/tags/p20-probe-* など）のように、index-* のタグに当たり得ないことが文字列だけで言える条件か。
    // 言えなければ（~ALL、refs/tags/*、index- と前の部分が重なる、ほかの形）当たり得るとみなす
    fun cannotMatchIndexTags(obj: JsonObject): Boolean {
        val include = ((obj.members["conditions"] as? JsonObject)?.members?.get("ref_name") as? JsonObject)?.members?.get("include") as? JsonArray ?: return false
        if (include.elements.isEmpty()) return false
        return include.elements.all { element ->
            val pattern = (element as? JsonString)?.value ?: return false
            if (!pattern.startsWith("refs/tags/")) return false
            val rest = pattern.removePrefix("refs/tags/")
            val literal = rest.takeWhile { it !in "*?[]{}\\!" }
            !(INDEX_PREFIX.startsWith(literal) || literal.startsWith(INDEX_PREFIX))
        }
    }

    // 管理者の権限で取得した応答（bypass_actors を含む）から、スナップショットを作る。雛形の A・B と App の ID の検査に通るものだけ
    fun record(
        expectedCreation: ByteArray,
        expectedProtection: ByteArray,
        live: List<JsonObject>,
        repository: String,
        recordedBy: String,
        recordedAt: Instant,
        publisherAppId: Long,
        checksAppId: Long,
    ): Parsed<ByteArray> {
        val problems = ArrayList<String>()
        if (!REPOSITORY.matches(repository)) problems += "the repository must be owner/name"
        if (!LOGIN.matches(recordedBy)) problems += "--recorded-by must be a GitHub login"
        problems += TagRulesets.liveProblems(expectedCreation, expectedProtection, live, publisherAppId, checksAppId)
        val names = listOfNotNull(TagRulesets.parse(expectedCreation)?.let { string(it, "name") }, TagRulesets.parse(expectedProtection)?.let { string(it, "name") })
        val chosen = names.mapNotNull { name -> live.singleOrNull { string(it, "name") == name } }
        for (ruleset in chosen) {
            val what = "the ruleset \"${string(ruleset, "name")}\""
            for (field in RULESET_FIELDS) if (ruleset.members[field] == null) problems += "$what: $field is missing (record the ruleset with the administrator's own token, which can see bypass_actors)"
            if (id(ruleset) == null) problems += "$what: id must be a number"
            if (string(ruleset, "source_type") != "Repository" || string(ruleset, "source") != repository) problems += "$what: it must be a ruleset of $repository itself"
            for (field in listOf("created_at", "updated_at")) if (timestamp(string(ruleset, field)) == null) problems += "$what: $field is not a GitHub timestamp"
        }
        val livePublic = live.filter { obj -> chosen.none { it === obj } && string(obj, "target") == "tag" && !cannotMatchIndexTags(obj) }
        for (other in livePublic) problems += "the tag ruleset \"${string(other, "name")}\" (${id(other)}) may apply to index-* tags; it must be removed or added to the templates before recording"
        if (problems.isNotEmpty() || chosen.size != 2) return Parsed(null, problems.ifEmpty { listOf("the two tag rulesets were not found") })
        val rulesets = chosen.sortedBy { string(it, "name") }.map { ruleset ->
            JsonWriter.obj(*RULESET_FIELDS.map { field -> field to ruleset.members.getValue(field) }.toTypedArray())
        }
        val snapshot = JsonWriter.obj(
            "snapshotVersion" to JsonWriter.integer(SNAPSHOT_VERSION),
            "repository" to JsonWriter.string(repository),
            "recordedAt" to JsonWriter.string(recordedAt.toString()),
            "recordedBy" to JsonWriter.string(recordedBy),
            "rulesets" to JsonWriter.array(rulesets),
        )
        return Parsed(CanonicalJson.write(snapshot), emptyList())
    }

    // 署名するバイト列: DOMAIN（ASCII）・0x00・鍵の ID（ASCII）・0x00・スナップショットのバイト列
    fun message(keyId: String, snapshot: ByteArray): ByteArray =
        DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + keyId.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + snapshot

    // 署名の形は index.json.sig と同じ（signatureVersion 1）。署名はすべて、keys/settings-keys.json の鍵の正しい署名であること。
    // テスト用の鍵（test-only-）は、テスト専用のビルド（allowTestKeys）でだけ受け入れる
    fun signatureProblems(snapshot: ByteArray, signatureFile: ByteArray?, keysFile: ByteArray?, allowTestKeys: Boolean): List<String> {
        signatureFile ?: return listOf("$SIGNATURE_FILE is missing, so the snapshot is not signed")
        keysFile ?: return listOf("$KEYS_FILE is missing, so the snapshot's signature cannot be checked")
        val keys = TrustedKeysFile.parse(keysFile)
        val trusted = keys.value ?: return keys.problems.map { "$KEYS_FILE: $it" }
        val problems = ArrayList<String>()
        if (trusted.keys.keys.isEmpty()) problems += "$KEYS_FILE has no keys"
        if (trusted.hasTestKeys && !allowTestKeys) problems += "$KEYS_FILE has test keys (${TrustedKeysFile.TEST_KEY_PREFIX}…); a real registry never trusts a test key"
        val parsed = IndexSignatures.parse(signatureFile)
        val signatures = parsed.value ?: return problems + "$SIGNATURE_FILE is not a valid signature file"
        for (signature in signatures) {
            val key = trusted.keys.find(signature.keyId)
            if (key == null) {
                problems += "$SIGNATURE_FILE: the key ${signature.keyId} is not in $KEYS_FILE"
                continue
            }
            if (!verify(key.publicKey, message(signature.keyId, snapshot), signature.signature)) problems += "$SIGNATURE_FILE: the signature by ${signature.keyId} does not match the snapshot"
        }
        return problems
    }

    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    private fun verify(rawPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64 || rawPublicKey.size != 32) return false
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + rawPublicKey))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (error: GeneralSecurityException) {
            false
        }
    }

    // スナップショット（署名は別に確かめる）と、公開の前に読み取りのトークンで取得した応答との照合
    fun checkProblems(
        expectedCreation: ByteArray,
        expectedProtection: ByteArray,
        snapshotBytes: ByteArray,
        live: List<JsonObject>,
        repository: String,
        publisherAppId: Long,
        checksAppId: Long,
        actor: Actor,
    ): List<String> {
        val problems = ArrayList<String>()
        if (snapshotBytes.size > MAX_BYTES) return listOf("$FILE is too large")
        val snapshot = TagRulesets.parse(snapshotBytes) ?: return listOf("$FILE is not a JSON object")
        if (!CanonicalJson.write(snapshot).contentEquals(snapshotBytes)) problems += "$FILE must be canonical JSON (as written by record-ruleset-snapshot)"
        if (snapshot.members.keys != TOP_FIELDS) problems += "$FILE must have exactly the fields ${TOP_FIELDS.sorted().joinToString(", ")}"
        if ((snapshot.members["snapshotVersion"] as? JsonInteger)?.value != SNAPSHOT_VERSION) problems += "$FILE: snapshotVersion must be $SNAPSHOT_VERSION"
        if (string(snapshot, "repository") != repository) problems += "$FILE was recorded for ${string(snapshot, "repository")}, not $repository"
        if (string(snapshot, "recordedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } == null) problems += "$FILE: recordedAt is not a timestamp"
        if (string(snapshot, "recordedBy")?.let(LOGIN::matches) != true) problems += "$FILE: recordedBy is not a GitHub login"
        val recorded = (snapshot.members["rulesets"] as? JsonArray)?.elements?.map { it as? JsonObject }
        if (recorded == null || recorded.size != 2 || recorded.any { it == null }) return problems + "$FILE must record exactly the two tag rulesets"
        val rulesets = recorded.filterNotNull()
        for (ruleset in rulesets) if (ruleset.members.keys != RULESET_FIELDS.toSet()) problems += "$FILE: a recorded ruleset must have exactly the fields ${RULESET_FIELDS.joinToString(", ")}"
        // 記録そのものが、雛形の A・B と App の ID の方針に合うこと（回避者を含む。記録には bypass_actors がある）
        problems += TagRulesets.liveProblems(expectedCreation, expectedProtection, rulesets, publisherAppId, checksAppId).map { "snapshot: $it" }
        val creationName = TagRulesets.parse(expectedCreation)?.let { string(it, "name") }
        for (ruleset in rulesets) {
            val name = string(ruleset, "name")
            val what = "the ruleset \"$name\""
            val recordedId = id(ruleset)
            val matching = live.filter { recordedId != null && id(it) == recordedId }
            if (matching.size != 1) {
                problems += "$what: the repository has no ruleset with the recorded id $recordedId (deleted or re-created since the snapshot)"
                continue
            }
            val current = matching.single()
            for (field in listOf("name", "target", "source_type", "source")) {
                if (string(current, field) == null || string(current, field) != string(ruleset, field)) problems += "$what: $field differs from the snapshot or is missing"
            }
            if (string(current, "enforcement") != "active" || string(current, "enforcement") != string(ruleset, "enforcement")) problems += "$what: enforcement is ${string(current, "enforcement")}, not active as recorded"
            if (conditions(current.members["conditions"]) == null || conditions(current.members["conditions"]) != conditions(ruleset.members["conditions"])) problems += "$what: conditions differ from the snapshot or cannot be read"
            if (unordered(current.members["rules"]) == null || unordered(current.members["rules"]) != unordered(ruleset.members["rules"])) problems += "$what: rules differ from the snapshot or cannot be read"
            for (field in listOf("created_at", "updated_at")) {
                val now = timestamp(string(current, field))
                val then = timestamp(string(ruleset, field))
                when {
                    now == null -> problems += "$what: $field is missing or not a GitHub timestamp"
                    then == null -> problems += "$what: the snapshot's $field is not a GitHub timestamp"
                    now != then -> problems += "$what: $field is $now, not $then as recorded: the ruleset was changed after the snapshot (record it again after checking it)"
                }
            }
            // 書き込める利用者には bypass_actors も返る。そのときは記録と直接比べる
            current.members["bypass_actors"]?.let { visible ->
                if (unordered(visible) == null || unordered(visible) != unordered(ruleset.members["bypass_actors"])) problems += "$what: bypass_actors differ from the snapshot"
            }
            val expected = if (name == creationName) actor.creation else actor.protection
            when (val canBypass = string(current, "current_user_can_bypass")) {
                null -> problems += "$what: current_user_can_bypass is missing"
                expected -> {}
                else -> problems += "$what: current_user_can_bypass is $canBypass for this token, expected $expected"
            }
        }
        // 記録に無い tag ruleset で、index-* のタグに当たり得るもの（ほかの主体を回避者にした ruleset が加わっても、ここで止まる）
        val recordedIds = rulesets.mapNotNull(::id).toSet()
        for (other in live) {
            if (string(other, "target") != "tag" || id(other) in recordedIds) continue
            if (other.members["conditions"] == null) {
                problems += "the tag ruleset ${id(other)} has no visible conditions, so it cannot be shown to leave index-* tags alone"
            } else if (!cannotMatchIndexTags(other)) {
                problems += "the tag ruleset \"${string(other, "name")}\" (${id(other)}) is not in the snapshot and may apply to index-* tags"
            }
        }
        return problems
    }
}
