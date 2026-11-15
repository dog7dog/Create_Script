package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// T3（S8.3 U-1）: 設定の証明用の鍵（スナップショットの署名鍵）の、ツールに固定した信頼済みの指紋（鍵の ID → 32 バイトの公開鍵の SHA-256、
// 16 進の小文字）。keys/settings-keys.json の本番の鍵（test-only- でないもの）は、ここに同じ指紋で無ければ信頼しない。
// 本番の鍵はまだ無い（鍵の作成の儀式は未承認）ので空。空のあいだ、本番の経路はスナップショットを信頼できず、公開は止まる（fail-closed）。
// 指紋を変えるのは CreateScript のソースの変更（レビューとリリース）で、レジストリのリポジトリの管理者だけでは変えられない。
// ただし、レジストリのワークフローの TOOL_COMMIT を別のコミットに替えれば、別の固定値のツールを使わせることはできる
// （docs/registry-settings-key.md の「信頼の起点の限界」）
object SettingsKeyPins {
    val OFFICIAL: Map<String, String> = emptyMap()
}

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
    const val SNAPSHOT_VERSION = 3L

    // 版 3（S8.3 U-2）: main ブランチの ruleset と、リポジトリの既定のブランチも記録して照合する。
    // ワークフロー（validate.yml・publish.yml）は main への push・PR で動くので、既定のブランチは main でなければならない
    const val EXPECTED_DEFAULT_BRANCH = "main"
    const val MAIN_FILE = "github-settings/main-ruleset.json"
    const val DEFAULT_BRANCH_CONDITION = "~DEFAULT_BRANCH"
    const val MAX_BYTES = 64 * 1024

    // 記録の有効期間の上限（U-1 で決定）と、記録の時刻が未来にあるとみなすまでの時計のずれの許容
    const val MAX_VALIDITY_DAYS = 180L
    val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

    // 本番の鍵での署名は、設定の鍵の作成の儀式が承認されるまで行わない（テスト用の鍵 test-only- だけが署名できる）
    const val PRODUCTION_SIGNING_ENABLED = false

    // ドメインを分ける前置き。index の署名（IndexSignatures.DOMAIN）とは別の用途として区別する
    const val DOMAIN = "CreateScript-Registry ruleset snapshot signature v1"

    // 記録する項目（GitHub の「Get a repository ruleset」の応答の項目）
    val RULESET_FIELDS = listOf("id", "name", "target", "source_type", "source", "enforcement", "conditions", "rules", "bypass_actors", "created_at", "updated_at")
    private val TOP_FIELDS = setOf("snapshotVersion", "repository", "recordedAt", "expires", "recordedBy", "defaultBranch", "rulesets")
    // recordedAt・expires は秒までの UTC（record-ruleset-snapshot が書く形）
    private val SECONDS = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
    private const val INDEX_PREFIX = "index-"
    private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val REPOSITORY = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}")

    // GitHub の時刻: 秒の小数 3 桁と、Z または ±hh:mm の時差がある形だけを受け入れる（実測: 所有者には +09:00、App には Z で同じ時刻が返る）
    private val TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})")

    // 公開の前の確認を行う主体。current_user_can_bypass の期待値が違う（P-21、実測は Publisher App・Checks App・所有者。GITHUB_TOKEN は未検証）
    // main の ruleset は、どちらの主体も回避できない（never）こと。GITHUB_TOKEN・Publisher App でのブランチの ruleset の値は、
    // GitHub では未確認（タグの ruleset でだけ実測）。違えば失敗する
    enum class Actor(val creation: String, val protection: String, val main: String) {
        WORKFLOW("never", "never", "never"),
        PUBLISHER("always", "never", "never"),
    }

    fun seconds(text: String?): Instant? {
        if (text == null || !SECONDS.matches(text)) return null
        return try {
            Instant.parse(text)
        } catch (error: DateTimeParseException) {
            null
        }
    }

    // 公開鍵（32 バイト）の指紋: SHA-256 の 16 進（小文字）
    fun fingerprint(rawPublicKey: ByteArray): String = GitHubReleases.sha256Hex(rawPublicKey)

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

    // ブランチの ruleset が、既定のブランチ（main）に当たり得ないことが文字列だけで言えるか（~DEFAULT_BRANCH・~ALL・refs/heads/m* などは当たり得る）
    fun cannotMatchDefaultBranch(obj: JsonObject): Boolean {
        val include = ((obj.members["conditions"] as? JsonObject)?.members?.get("ref_name") as? JsonObject)?.members?.get("include") as? JsonArray ?: return false
        if (include.elements.isEmpty()) return false
        return include.elements.all { element ->
            val pattern = (element as? JsonString)?.value ?: return false
            if (!pattern.startsWith("refs/heads/")) return false
            val literal = pattern.removePrefix("refs/heads/").takeWhile { it !in "*?[]{}\\!" }
            !(EXPECTED_DEFAULT_BRANCH.startsWith(literal) || literal.startsWith(EXPECTED_DEFAULT_BRANCH))
        }
    }

    // main の ruleset の方針（記録の中身に対して）: 雛形と同じ名前の、ブランチの ruleset で、既定のブランチだけを対象にし、
    // GitHubSettings.rulesetProblems（回避者なし・有効・削除と強制 push の禁止・直線的な履歴・2 人以上の承認と 3 つの条件・2 つの必須チェック）
    // に通り、必須チェックの App が Registry Checks App であること
    fun mainProblems(expectedMain: ByteArray, recorded: JsonObject?, checksAppId: Long): List<String> {
        val what = "the main branch ruleset"
        val name = TagRulesets.parse(expectedMain)?.let { string(it, "name") } ?: return listOf("$MAIN_FILE is missing or has no name")
        recorded ?: return listOf("$what (\"$name\") is missing")
        val problems = ArrayList<String>()
        if (string(recorded, "name") != name) problems += "$what must be named \"$name\" as in $MAIN_FILE"
        if (string(recorded, "target") != "branch") problems += "$what: target must be branch"
        val refName = (recorded.members["conditions"] as? JsonObject)?.members?.get("ref_name") as? JsonObject
        val include = (refName?.members?.get("include") as? JsonArray)?.elements?.map { (it as? JsonString)?.value }
        val exclude = refName?.members?.get("exclude")
        if (include != listOf(DEFAULT_BRANCH_CONDITION)) problems += "$what: conditions.ref_name.include must be exactly [\"$DEFAULT_BRANCH_CONDITION\"]"
        if (exclude != null && (exclude as? JsonArray)?.elements?.isEmpty() != true) problems += "$what: conditions.ref_name.exclude must be empty"
        problems += GitHubSettings.rulesetProblems(CanonicalJson.write(recorded)).map { "$what: $it" }
        for (check in GitHubSettings.requiredChecks(recorded)) {
            if (check.integrationId != checksAppId) problems += "$what: the required check ${check.context} comes from App ${check.integrationId}, not the Registry Checks App ($checksAppId)"
        }
        return problems
    }

    // GET /repos/{owner}/{repo} の応答（既定のブランチ・名前）。読めなければ確かめられないので失敗
    fun repositoryProblems(info: JsonObject?, repository: String, expectedBranch: String = EXPECTED_DEFAULT_BRANCH): List<String> {
        info ?: return listOf("the repository information (GET /repos/{owner}/{repo}) is missing, so the default branch cannot be verified")
        val problems = ArrayList<String>()
        if (!string(info, "full_name").equals(repository, ignoreCase = true)) problems += "the repository information is for ${string(info, "full_name")}, not $repository"
        when (val branch = string(info, "default_branch")) {
            null -> problems += "the repository information has no default_branch"
            expectedBranch -> {}
            else -> problems += "the default branch is $branch, not $expectedBranch: ~DEFAULT_BRANCH no longer protects $expectedBranch, where the workflows run"
        }
        return problems
    }

    // 管理者の権限で取得した応答（bypass_actors を含む）から、スナップショットを作る。雛形の A・B と App の ID の検査に通るものだけ
    fun record(
        expectedCreation: ByteArray,
        expectedProtection: ByteArray,
        expectedMain: ByteArray,
        live: List<JsonObject>,
        repositoryInfo: JsonObject?,
        repository: String,
        recordedBy: String,
        recordedAt: Instant,
        publisherAppId: Long,
        checksAppId: Long,
        validDays: Long = MAX_VALIDITY_DAYS,
    ): Parsed<ByteArray> {
        val problems = ArrayList<String>()
        if (validDays !in 1..MAX_VALIDITY_DAYS) problems += "the snapshot must be valid for 1 to $MAX_VALIDITY_DAYS days"
        if (!REPOSITORY.matches(repository)) problems += "the repository must be owner/name"
        if (!LOGIN.matches(recordedBy)) problems += "--recorded-by must be a GitHub login"
        problems += TagRulesets.liveProblems(expectedCreation, expectedProtection, live, publisherAppId, checksAppId)
        problems += repositoryProblems(repositoryInfo, repository)
        val mainName = TagRulesets.parse(expectedMain)?.let { string(it, "name") }
        val mainLive = live.filter { mainName != null && string(it, "name") == mainName && string(it, "target") == "branch" }
        if (mainLive.size != 1) problems += "the repository must have exactly one branch ruleset named \"$mainName\" (it has ${mainLive.size})"
        problems += mainProblems(expectedMain, mainLive.singleOrNull(), checksAppId)
        val names = listOfNotNull(TagRulesets.parse(expectedCreation)?.let { string(it, "name") }, TagRulesets.parse(expectedProtection)?.let { string(it, "name") })
        val chosen = names.mapNotNull { name -> live.singleOrNull { string(it, "name") == name } } + listOfNotNull(mainLive.singleOrNull())
        for (ruleset in chosen) {
            val what = "the ruleset \"${string(ruleset, "name")}\""
            for (field in RULESET_FIELDS) if (ruleset.members[field] == null) problems += "$what: $field is missing (record the ruleset with the administrator's own token, which can see bypass_actors)"
            if (id(ruleset) == null) problems += "$what: id must be a number"
            if (string(ruleset, "source_type") != "Repository" || string(ruleset, "source") != repository) problems += "$what: it must be a ruleset of $repository itself"
            for (field in listOf("created_at", "updated_at")) if (timestamp(string(ruleset, field)) == null) problems += "$what: $field is not a GitHub timestamp"
        }
        val livePublic = live.filter { obj -> chosen.none { it === obj } && string(obj, "target") == "tag" && !cannotMatchIndexTags(obj) }
        for (other in livePublic) problems += "the tag ruleset \"${string(other, "name")}\" (${id(other)}) may apply to index-* tags; it must be removed or added to the templates before recording"
        for (other in live.filter { obj -> chosen.none { it === obj } && string(obj, "target") == "branch" && !cannotMatchDefaultBranch(obj) }) {
            problems += "the branch ruleset \"${string(other, "name")}\" (${id(other)}) may apply to the default branch; it must be removed or added to the templates before recording"
        }
        if (problems.isNotEmpty() || chosen.size != 3) return Parsed(null, problems.ifEmpty { listOf("the two tag rulesets and the main branch ruleset were not found") })
        val rulesets = chosen.sortedBy { string(it, "name") }.map { ruleset ->
            JsonWriter.obj(*RULESET_FIELDS.map { field -> field to ruleset.members.getValue(field) }.toTypedArray())
        }
        val at = recordedAt.truncatedTo(ChronoUnit.SECONDS)
        val snapshot = JsonWriter.obj(
            "snapshotVersion" to JsonWriter.integer(SNAPSHOT_VERSION),
            "repository" to JsonWriter.string(repository),
            "recordedAt" to JsonWriter.string(at.toString()),
            "expires" to JsonWriter.string(at.plus(Duration.ofDays(validDays)).toString()),
            "recordedBy" to JsonWriter.string(recordedBy),
            "defaultBranch" to JsonWriter.string(EXPECTED_DEFAULT_BRANCH),
            "rulesets" to JsonWriter.array(rulesets),
        )
        return Parsed(CanonicalJson.write(snapshot), emptyList())
    }

    // 署名するバイト列: DOMAIN（ASCII）・0x00・鍵の ID（ASCII）・0x00・スナップショットのバイト列
    fun message(keyId: String, snapshot: ByteArray): ByteArray =
        DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + keyId.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + snapshot

    // 署名の形は index.json.sig と同じ（signatureVersion 1）。署名はすべて、keys/settings-keys.json の鍵の正しい署名であること。
    // テスト用の鍵（test-only-）は、テスト専用のビルド（allowTestKeys）でだけ受け入れる
    // 鍵の一覧（keys/settings-keys.json）と T3 の指紋の照合。本番の鍵（test-only- でないもの）は、すべて pins に同じ指紋で載っていること。
    // テスト用の鍵は、テスト専用のビルド（allowTestKeys）でだけ受け入れる（指紋の照合はしない。テスト用の鍵は実行のたびに作るため）
    fun keyProblems(keysFile: ByteArray?, allowTestKeys: Boolean, pins: Map<String, String>): Pair<TrustedKeysFile?, List<String>> {
        keysFile ?: return null to listOf("$KEYS_FILE is missing, so the snapshot's signature cannot be checked")
        val keys = TrustedKeysFile.parse(keysFile)
        val trusted = keys.value ?: return null to keys.problems.map { "$KEYS_FILE: $it" }
        val problems = ArrayList<String>()
        if (trusted.keys.keys.isEmpty()) problems += "$KEYS_FILE has no keys"
        if (trusted.hasTestKeys && !allowTestKeys) problems += "$KEYS_FILE has test keys (${TrustedKeysFile.TEST_KEY_PREFIX}…); a real registry never trusts a test key"
        for (key in trusted.keys.keys) {
            if (key.keyId.startsWith(TrustedKeysFile.TEST_KEY_PREFIX)) continue
            when (val pinned = pins[key.keyId]) {
                null -> problems += if (pins.isEmpty()) {
                    "$KEYS_FILE: the key ${key.keyId} cannot be trusted: this build of the tool pins no settings key (none has been made yet)"
                } else {
                    "$KEYS_FILE: the key ${key.keyId} is not pinned in this build of the tool (removed or never added)"
                }
                fingerprint(key.publicKey) -> {}
                else -> problems += "$KEYS_FILE: the key ${key.keyId} does not match the fingerprint pinned in this build of the tool"
            }
        }
        return trusted to problems
    }

    // 署名の形は index.json.sig と同じ（signatureVersion 1、署名は最大 4 つ、同じ鍵の ID は 1 回だけ）。署名はすべて、
    // keys/settings-keys.json の鍵（本番の鍵は T3 の指紋とも一致するもの）の正しい署名であること。一覧に無い鍵（外した鍵）の署名があれば失敗する
    fun signatureProblems(snapshot: ByteArray, signatureFile: ByteArray?, keysFile: ByteArray?, allowTestKeys: Boolean, pins: Map<String, String> = SettingsKeyPins.OFFICIAL): List<String> {
        signatureFile ?: return listOf("$SIGNATURE_FILE is missing, so the snapshot is not signed")
        val (trusted, problems) = keyProblems(keysFile, allowTestKeys, pins).let { it.first to it.second.toMutableList() }
        trusted ?: return problems
        val parsed = IndexSignatures.parse(signatureFile)
        val signatures = parsed.value ?: return problems + "$SIGNATURE_FILE is not a valid signature file (at most ${IndexSignatures.MAX_SIGNATURES} signatures, each key once)"
        for (signature in signatures) {
            val key = trusted.keys.find(signature.keyId)
            if (key == null) {
                problems += "$SIGNATURE_FILE: the key ${signature.keyId} is not in $KEYS_FILE (when replacing a key, sign with the new key first and remove the old key's signature before removing the key)"
                continue
            }
            if (!verify(key.publicKey, message(signature.keyId, snapshot), signature.signature)) problems += "$SIGNATURE_FILE: the signature by ${signature.keyId} does not match the snapshot"
        }
        return problems
    }

    // 署名（sign-ruleset-snapshot）。署名するのは、方針・期限の検査に通るスナップショットだけ。署名した後、鍵の一覧の公開鍵で確かめる
    // （別の鍵のファイルで署名した誤りを見つける）。existing は、鍵を替えるあいだに並べる既存の署名のファイル（同じ鍵の ID は加えない）
    fun sign(snapshot: ByteArray, key: PrivateKey, keyId: String, keysFile: ByteArray?, existing: ByteArray?, allowTestKeys: Boolean, pins: Map<String, String>): Parsed<ByteArray> {
        val testKey = keyId.startsWith(TrustedKeysFile.TEST_KEY_PREFIX)
        if (!PRODUCTION_SIGNING_ENABLED && !testKey) {
            return Parsed(null, listOf("signing with the key $keyId is refused: only test keys (${TrustedKeysFile.TEST_KEY_PREFIX}…) may sign until the settings key ceremony is approved (docs/registry-settings-key.md)"))
        }
        val (trusted, problems) = keyProblems(keysFile, allowTestKeys || testKey, pins)
        if (trusted == null || problems.isNotEmpty()) return Parsed(null, problems)
        val listed = trusted.keys.find(keyId) ?: return Parsed(null, listOf("the key $keyId is not in $KEYS_FILE; add its public key first"))
        val others = if (existing == null) emptyList() else IndexSignatures.parse(existing).value ?: return Parsed(null, listOf("the existing signature file is not valid"))
        if (others.any { it.keyId == keyId }) return Parsed(null, listOf("the existing signature file already has a signature by $keyId"))
        if (others.size >= IndexSignatures.MAX_SIGNATURES) return Parsed(null, listOf("the existing signature file already has ${IndexSignatures.MAX_SIGNATURES} signatures"))
        val signature = try {
            Signature.getInstance("Ed25519").run {
                initSign(key)
                update(message(keyId, snapshot))
                sign()
            }
        } catch (error: GeneralSecurityException) {
            return Parsed(null, listOf("the key file cannot sign with Ed25519"))
        }
        if (!verify(listed.publicKey, message(keyId, snapshot), signature)) return Parsed(null, listOf("the key file is not the private key of $keyId in $KEYS_FILE; nothing was written"))
        val all = others + IndexSignature(keyId, signature)
        val bytes = IndexSignatures.write(all)
        val check = signatureProblems(snapshot, bytes, keysFile, allowTestKeys || testKey, pins)
        return if (check.isEmpty()) Parsed(bytes, emptyList()) else Parsed(null, check)
    }

    // 署名する人が確かめる要約（秘密の情報は含まない）
    fun summary(snapshot: ByteArray, keyId: String): List<String> {
        val obj = TagRulesets.parse(snapshot) ?: return listOf("the snapshot cannot be read")
        val lines = ArrayList<String>()
        lines += "snapshot sha256 ${GitHubReleases.sha256Hex(snapshot)}, version ${(obj.members["snapshotVersion"] as? JsonInteger)?.value}"
        lines += "repository ${string(obj, "repository")} (default branch ${string(obj, "defaultBranch")}), recorded by ${string(obj, "recordedBy")} at ${string(obj, "recordedAt")}, expires ${string(obj, "expires")}"
        for (ruleset in (obj.members["rulesets"] as? JsonArray)?.elements.orEmpty().mapNotNull { it as? JsonObject }) {
            lines += "ruleset ${id(ruleset)} \"${string(ruleset, "name")}\": target ${string(ruleset, "target")}, enforcement ${string(ruleset, "enforcement")}, " +
                "conditions ${conditions(ruleset.members["conditions"])}, rules ${unordered(ruleset.members["rules"])}, bypass_actors ${unordered(ruleset.members["bypass_actors"])}, updated_at ${string(ruleset, "updated_at")}"
        }
        lines += "signing key $keyId"
        return lines
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

    // スナップショットそのものの検査（形・版・リポジトリ・期限・方針）。署名・実際の ruleset との照合は別。
    // 期限: recordedAt <= now + CLOCK_SKEW（未来の記録は失敗）、recordedAt < expires、expires - recordedAt <= MAX_VALIDITY_DAYS、now < expires
    fun snapshotProblems(
        expectedCreation: ByteArray,
        expectedProtection: ByteArray,
        expectedMain: ByteArray,
        snapshotBytes: ByteArray,
        repository: String,
        publisherAppId: Long,
        checksAppId: Long,
        now: Instant,
    ): Pair<List<String>, List<JsonObject>?> {
        val problems = ArrayList<String>()
        if (snapshotBytes.size > MAX_BYTES) return listOf("$FILE is too large") to null
        val snapshot = TagRulesets.parse(snapshotBytes) ?: return listOf("$FILE is not a JSON object") to null
        if (!CanonicalJson.write(snapshot).contentEquals(snapshotBytes)) problems += "$FILE must be canonical JSON (as written by record-ruleset-snapshot)"
        if (snapshot.members.keys != TOP_FIELDS) problems += "$FILE must have exactly the fields ${TOP_FIELDS.sorted().joinToString(", ")}"
        if ((snapshot.members["snapshotVersion"] as? JsonInteger)?.value != SNAPSHOT_VERSION) problems += "$FILE: snapshotVersion must be $SNAPSHOT_VERSION (record it again with this tool)"
        if (string(snapshot, "repository") != repository) problems += "$FILE was recorded for ${string(snapshot, "repository")}, not $repository"
        val recordedAt = seconds(string(snapshot, "recordedAt"))
        val expires = seconds(string(snapshot, "expires"))
        when {
            recordedAt == null -> problems += "$FILE: recordedAt is not a UTC timestamp in seconds (YYYY-MM-DDTHH:MM:SSZ)"
            expires == null -> problems += "$FILE: expires is not a UTC timestamp in seconds (YYYY-MM-DDTHH:MM:SSZ)"
            else -> {
                if (recordedAt.isAfter(now.plus(CLOCK_SKEW))) problems += "$FILE: recordedAt $recordedAt is in the future"
                if (!expires.isAfter(recordedAt)) problems += "$FILE: expires $expires is not after recordedAt $recordedAt"
                else if (Duration.between(recordedAt, expires) > Duration.ofDays(MAX_VALIDITY_DAYS)) problems += "$FILE: valid for more than $MAX_VALIDITY_DAYS days ($recordedAt to $expires)"
                if (!now.isBefore(expires)) problems += "$FILE expired at $expires: check the rulesets and record and sign the snapshot again"
            }
        }
        if (string(snapshot, "recordedBy")?.let(LOGIN::matches) != true) problems += "$FILE: recordedBy is not a GitHub login"
        if (string(snapshot, "defaultBranch") != EXPECTED_DEFAULT_BRANCH) problems += "$FILE: defaultBranch must be $EXPECTED_DEFAULT_BRANCH"
        val recorded = (snapshot.members["rulesets"] as? JsonArray)?.elements?.map { it as? JsonObject }
        if (recorded == null || recorded.size != 3 || recorded.any { it == null }) return (problems + "$FILE must record exactly the two tag rulesets and the main branch ruleset") to null
        val rulesets = recorded.filterNotNull()
        for (ruleset in rulesets) if (ruleset.members.keys != RULESET_FIELDS.toSet()) problems += "$FILE: a recorded ruleset must have exactly the fields ${RULESET_FIELDS.joinToString(", ")}"
        // 記録そのものが、雛形の A・B と App の ID の方針に合うこと（回避者を含む。記録には bypass_actors がある）
        val tags = rulesets.filter { string(it, "target") == "tag" }
        problems += TagRulesets.liveProblems(expectedCreation, expectedProtection, tags, publisherAppId, checksAppId).map { "snapshot: $it" }
        val mains = rulesets.filter { string(it, "target") == "branch" }
        if (tags.size != 2 || mains.size != 1) problems += "$FILE must record two tag rulesets and one branch ruleset"
        problems += mainProblems(expectedMain, mains.singleOrNull(), checksAppId).map { "snapshot: $it" }
        return problems to rulesets
    }

    // スナップショット（署名は別に確かめる）と、公開の前に読み取りのトークンで取得した応答との照合
    fun checkProblems(
        expectedCreation: ByteArray,
        expectedProtection: ByteArray,
        expectedMain: ByteArray,
        snapshotBytes: ByteArray,
        live: List<JsonObject>,
        repositoryInfo: JsonObject?,
        repository: String,
        publisherAppId: Long,
        checksAppId: Long,
        actor: Actor,
        now: Instant,
    ): List<String> {
        val (staticProblems, recorded) = snapshotProblems(expectedCreation, expectedProtection, expectedMain, snapshotBytes, repository, publisherAppId, checksAppId, now)
        val problems = ArrayList(staticProblems)
        // 既定のブランチ: 変えられると ~DEFAULT_BRANCH の ruleset は main を守らなくなるが、ruleset の updated_at は変わらない
        problems += repositoryProblems(repositoryInfo, repository)
        val rulesets = recorded ?: return problems
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
            val expected = when {
                string(ruleset, "target") == "branch" -> actor.main
                name == creationName -> actor.creation
                else -> actor.protection
            }
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
        // 記録に無いブランチの ruleset で、既定のブランチに当たり得るもの（重ねても制限が強まるだけだが、記録と違う構成は止める）
        for (other in live) {
            if (string(other, "target") != "branch" || id(other) in recordedIds) continue
            if (other.members["conditions"] == null) {
                problems += "the branch ruleset ${id(other)} has no visible conditions, so it cannot be shown to leave the default branch alone"
            } else if (!cannotMatchDefaultBranch(other)) {
                problems += "the branch ruleset \"${string(other, "name")}\" (${id(other)}) is not in the snapshot and may apply to the default branch"
            }
        }
        return problems
    }
}
