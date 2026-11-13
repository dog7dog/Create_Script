package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.Signature
import java.time.Instant

// 案 B'（S8.3 Phase 10B-2A）: 署名されたスナップショットと、公開の前の照合。
// 土台は、テスト用のリポジトリ dog7dog/CreateScript-Registry-Test で 2026-11-13 に所有者のトークンで取得した、実際の応答
// （src/test/resources/p20/。秘密情報は含まない）。読み取りのトークンの表示は、実測（U1・U5: bypass_actors が無い、時刻は Z の表記、
// current_user_can_bypass は Publisher が A=always・B=never、Checks が両方 never）に合わせて、所有者の応答から作る。
// GITHUB_TOKEN の表示は未検証（Actions が必要）で、ここでは Checks App と同じ（両方 never）と仮定している。
// 署名には、実行のたびにメモリーの中で作るテスト用の鍵（TestOnlyKey、test-only-）だけを使い、ファイルの鍵は作らない
class RulesetSnapshotTest {
    @TempDir
    lateinit var directory: Path

    private val repositoryName = "dog7dog/CreateScript-Registry-Test"
    private val publisher = 5738726L
    private val checks = 5724854L
    private val idA = 27176846L
    private val idB = 27176940L
    private lateinit var repo: Path
    private lateinit var live: Path
    private val key = TestOnlyKey("test-only-settings-1")

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/p20/$name")!!.use { it.readAllBytes() }

    private val ownerA get() = resource("owner-ruleset-A.json")
    private val ownerB get() = resource("owner-ruleset-B.json")
    private val ownerC get() = resource("owner-ruleset-C.json")

    // 応答の一部を変えたもの（null は項目を消す）
    private fun edit(bytes: ByteArray, changes: Map<String, JsonValue?>): ByteArray {
        val obj = TagRulesets.parse(bytes)!!
        val members = LinkedHashMap(obj.members)
        for ((k, v) in changes) if (v == null) members.remove(k) else members[k] = v
        return CanonicalJson.write(JsonWriter.obj(*members.toList().toTypedArray()))
    }

    private fun json(text: String): JsonValue = TagRulesets.parse("""{"v": $text}""".toByteArray())!!.members.getValue("v")

    private fun utc(bytes: ByteArray, field: String): JsonValue =
        JsonWriter.string(RulesetSnapshots.timestamp((TagRulesets.parse(bytes)!!.members[field] as com.creatrbypc.createanalytical.project.JsonString).value)!!.toString())

    // 読み取りのトークンの表示: bypass_actors が無く、時刻は Z の表記
    private fun readOnly(bytes: ByteArray, canBypass: String) =
        edit(bytes, mapOf("bypass_actors" to null, "created_at" to utc(bytes, "created_at"), "updated_at" to utc(bytes, "updated_at"), "current_user_can_bypass" to JsonWriter.string(canBypass)))

    @BeforeEach
    fun setUp() {
        repo = directory.resolve("repo")
        Files.createDirectories(repo.resolve("github-settings"))
        Files.createDirectories(repo.resolve("keys"))
        val template = Paths.get("..").toAbsolutePath().normalize().resolve("registry-template/github-settings")
        Files.writeString(repo.resolve(TagRulesets.CREATION_FILE), Files.readString(template.resolve("index-tags-creation-ruleset.json")).replace("\"actor_id\": 0", "\"actor_id\": $publisher"))
        Files.copy(template.resolve("index-tags-protection-ruleset.json"), repo.resolve(TagRulesets.PROTECTION_FILE))
        Files.writeString(repo.resolve(RulesetSnapshots.KEYS_FILE), TrustedKeysFile.write(listOf(key.trusted)))
        live = Files.createDirectories(directory.resolve("live"))
    }

    private fun setLive(vararg rulesets: Pair<Long, ByteArray>) {
        Files.list(live).use { s -> s.toList() }.forEach(Files::delete)
        for ((id, bytes) in rulesets) Files.write(live.resolve("ruleset-$id.json"), bytes)
    }

    private var now: Instant = Instant.parse("2026-11-13T07:00:00Z")

    private fun tool(allowTestKeys: Boolean = true, out: ByteArrayOutputStream = ByteArrayOutputStream(), pins: Map<String, String> = SettingsKeyPins.OFFICIAL) =
        RegistryTool(PrintStream(out, true), PrintStream(out, true), { now }, { FakeGitHub() }, allowTestKeys = allowTestKeys, settingsKeyPins = pins)

    private fun run(args: List<String>, allowTestKeys: Boolean = true, pins: Map<String, String> = SettingsKeyPins.OFFICIAL): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = tool(allowTestKeys, out, pins).run(args)
        return code to out.toString(Charsets.UTF_8)
    }

    private fun sign(snapshot: ByteArray, signer: TestOnlyKey = key): ByteArray {
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer.pair.private)
            update(RulesetSnapshots.message(signer.keyId, snapshot))
            sign()
        }
        return IndexSignatures.write(listOf(IndexSignature(signer.keyId, signature)))
    }

    // 所有者の表示から記録し、署名して、リポジトリに置く
    private fun recordAndSign(): ByteArray {
        setLive(idA to ownerA, idB to ownerB, 27179117L to ownerC)
        val out = directory.resolve("snapshot.json")
        val (code, text) = run(listOf("record-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository", repositoryName, "--recorded-by", "dog7dog", "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "$out"))
        assertEquals(0, code, text)
        val snapshot = Files.readAllBytes(out)
        Files.write(repo.resolve(RulesetSnapshots.FILE), snapshot)
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(snapshot))
        return snapshot
    }

    private fun check(actor: String = "workflow", allowTestKeys: Boolean = true, pins: Map<String, String> = SettingsKeyPins.OFFICIAL) =
        run(listOf("check-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository", repositoryName, "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--actor", actor), allowTestKeys, pins)

    private fun assertFails(result: Pair<Int, String>, expected: String) {
        assertEquals(1, result.first, result.second)
        assertTrue(result.second.contains(expected), result.second)
    }

    private val workflowA get() = readOnly(ownerA, "never")
    private val workflowB get() = readOnly(ownerB, "never")
    private val workflowC get() = readOnly(ownerC, "never")

    // L1: 所有者の表示（bypass_actors が見える）は、記録と直接比べて通る
    @Test
    fun ownerViewMatchesTheSnapshot() {
        val snapshot = recordAndSign()
        val text = String(snapshot)
        assertTrue(text.contains("\"actor_id\":$publisher"), text)
        assertFalse(text.contains("current_user_can_bypass"), "the snapshot does not record a per-token value")
        assertFalse(text.contains("p20 probe"), "only A and B are recorded")
        assertEquals(0, check().first, check().second)
    }

    // L2・L3: 読み取りのトークンの表示（bypass_actors が無く、時刻は Z）でも、updated_at が同じ時刻なら通る。主体ごとの current_user_can_bypass
    @Test
    fun readOnlyViewsMatchByUpdatedAt() {
        recordAndSign()
        setLive(idA to workflowA, idB to workflowB, 27179117L to workflowC)
        assertEquals(0, check("workflow").first, check("workflow").second)
        setLive(idA to readOnly(ownerA, "always"), idB to readOnly(ownerB, "never"), 27179117L to workflowC)
        assertEquals(0, check("publisher").first, check("publisher").second)
        // 主体が違う（Publisher の表示を workflow として確かめる、その逆）
        assertFails(check("workflow"), "current_user_can_bypass is always for this token, expected never")
        setLive(idA to workflowA, idB to workflowB)
        assertFails(check("publisher"), "current_user_can_bypass is never for this token, expected always")
        // current_user_can_bypass が無い
        setLive(idA to edit(workflowA, mapOf("current_user_can_bypass" to null)), idB to workflowB)
        assertFails(check(), "current_user_can_bypass is missing")
        // 既存の check-live-rulesets は弱めていない: 読み取りの表示では、引き続き失敗する
        setLive(idA to workflowA, idB to workflowB)
        val old = run(listOf("check-live-rulesets", "--repo", "$repo", "--live", "$live", "--checks-app-id", "$checks", "--publisher-app-id", "$publisher"))
        assertFails(old, "bypass_actors is not visible")
    }

    // L4: updated_at の変化（P-20: 回避者の追加・元に戻す変更でも新しい値になる、C-2・C-3 で実測）・欠落・形の誤り
    @Test
    fun anyChangeOfUpdatedAtFails() {
        recordAndSign()
        // C-1 → C-3 の実測: 設定は元と同じでも updated_at は新しい値（2026-11-13T05:41:11.194Z → 05:59:14.394Z）
        val c1 = RulesetSnapshots.timestamp("2026-11-13T14:41:11.194+09:00")!!
        val c3 = RulesetSnapshots.timestamp("2026-11-13T05:59:14.394Z")!!
        assertTrue(c3.isAfter(c1))
        fun withUpdated(text: String?) = setLive(idA to edit(workflowA, mapOf("updated_at" to text?.let(JsonWriter::string))), idB to workflowB)
        withUpdated("2026-11-13T04:35:14.035Z")
        assertFails(check(), "the ruleset was changed after the snapshot")
        withUpdated("2026-11-13T13:35:14.034+09:00") // 同じ時刻の別の表記は通る
        assertEquals(0, check().first, check().second)
        withUpdated(null)
        assertFails(check(), "updated_at is missing or not a GitHub timestamp")
        withUpdated("2026-11-13T04:35:14Z") // 小数の無い表記は受け入れない
        assertFails(check(), "updated_at is missing or not a GitHub timestamp")
        withUpdated("yesterday")
        assertFails(check(), "updated_at is missing or not a GitHub timestamp")
        setLive(idA to edit(workflowA, mapOf("created_at" to JsonWriter.string("2026-11-13T04:35:13.842Z"))), idB to workflowB)
        assertFails(check(), "created_at is")
    }

    // L5〜L9: 見える項目の変化
    @Test
    fun visibleChangesFail() {
        recordAndSign()
        setLive(idA to edit(workflowA, mapOf("id" to JsonWriter.integer(99))), idB to workflowB)
        assertFails(check(), "no ruleset with the recorded id $idA")
        for (enforcement in listOf("disabled", "evaluate")) {
            setLive(idA to workflowA, idB to edit(workflowB, mapOf("enforcement" to JsonWriter.string(enforcement))))
            assertFails(check(), "enforcement is $enforcement, not active")
        }
        setLive(idA to edit(workflowA, mapOf("conditions" to json("""{"ref_name": {"include": ["refs/tags/index-*"], "exclude": ["refs/tags/index-9*"]}}"""))), idB to workflowB)
        assertFails(check(), "conditions differ")
        setLive(idA to edit(workflowA, mapOf("conditions" to json("""{"ref_name": {"include": ["refs/tags/index-1*"], "exclude": []}}"""))), idB to workflowB)
        assertFails(check(), "conditions differ")
        setLive(idA to workflowA, idB to edit(workflowB, mapOf("rules" to json("""[{"type": "deletion"}, {"type": "update"}]"""))))
        assertFails(check(), "rules differ")
        setLive(idA to edit(workflowA, mapOf("rules" to json("""[{"type": "creation"}, {"type": "deletion"}]"""))), idB to workflowB)
        assertFails(check(), "rules differ")
        // 並びだけの違いは同じとみなす
        setLive(idA to workflowA, idB to edit(workflowB, mapOf("rules" to json("""[{"type": "non_fast_forward"}, {"type": "update"}, {"type": "deletion"}]"""))))
        assertEquals(0, check().first, check().second)
        setLive(idA to edit(workflowA, mapOf("name" to JsonWriter.string("renamed"))), idB to workflowB)
        assertFails(check(), "name differs")
        setLive(idB to workflowB)
        assertFails(check(), "no ruleset with the recorded id $idA")
        setLive()
        assertFails(check(), "no ruleset of the repository could be read")
        // 所有者の表示で bypass_actors が記録と違う（回避者が加わった）
        setLive(idA to ownerA, idB to edit(ownerB, mapOf("bypass_actors" to json("""[{"actor_id": $publisher, "actor_type": "Integration", "bypass_mode": "always"}]"""))))
        assertFails(check(), "bypass_actors differ from the snapshot")
    }

    // L13: 記録に無い tag ruleset。index-* に当たり得るものは失敗、当たり得ないことが言えるもの（試験用の C）は通る
    @Test
    fun extraTagRulesets() {
        recordAndSign()
        setLive(idA to workflowA, idB to workflowB, 27179117L to workflowC)
        assertEquals(0, check().first, check().second)
        for (pattern in listOf("refs/tags/*", "~ALL", "refs/tags/ind*", "refs/tags/index-1*", "index-*", "refs/tags/[i]ndex-*")) {
            val extra = edit(workflowC, mapOf("id" to JsonWriter.integer(5), "name" to JsonWriter.string("extra"), "conditions" to json("""{"ref_name": {"include": ["$pattern"], "exclude": []}}""")))
            setLive(idA to workflowA, idB to workflowB, 5L to extra)
            assertFails(check(), "is not in the snapshot and may apply to index-* tags")
        }
        setLive(idA to workflowA, idB to workflowB, 5L to edit(workflowC, mapOf("id" to JsonWriter.integer(5), "conditions" to null)))
        assertFails(check(), "has no visible conditions")
        // ブランチの ruleset は関係しない
        setLive(idA to workflowA, idB to workflowB, 6L to edit(workflowC, mapOf("id" to JsonWriter.integer(6), "target" to JsonWriter.string("branch"), "conditions" to json("""{"ref_name": {"include": ["~ALL"], "exclude": []}}"""))))
        assertEquals(0, check().first, check().second)
    }

    // L11・L12: スナップショットそのもの・署名
    @Test
    fun theSnapshotMustBeSignedAndFollowThePolicy() {
        val snapshot = recordAndSign()
        setLive(idA to workflowA, idB to workflowB)
        // 本番のツールは、テスト用の鍵（test-only-）を信頼しない
        assertFails(check(allowTestKeys = false), "has test keys")
        // テスト専用のビルドでは通る
        val err = ByteArrayOutputStream()
        val args = listOf("check-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository", repositoryName, "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--actor", "workflow")
        assertEquals(0, TestRegistryTool.run(args, repositoryName, PrintStream(err, true)) { allow -> tool(allow) })
        // 署名が無い・鍵が無い・別の鍵・改ざん
        Files.delete(repo.resolve(RulesetSnapshots.SIGNATURE_FILE))
        assertFails(check(), "is not signed")
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(snapshot, TestOnlyKey("test-only-settings-2")))
        assertFails(check(), "is not in keys/settings-keys.json")
        val other = TestOnlyKey("test-only-settings-1")
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(snapshot, other))
        assertFails(check(), "does not match the snapshot")
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(snapshot))
        Files.delete(repo.resolve(RulesetSnapshots.KEYS_FILE))
        assertFails(check(), "keys/settings-keys.json is missing")
        Files.writeString(repo.resolve(RulesetSnapshots.KEYS_FILE), TrustedKeysFile.write(listOf(key.trusted)))
        val tampered = String(snapshot).replace("\"recordedBy\":\"dog7dog\"", "\"recordedBy\":\"mallory\"").toByteArray()
        Files.write(repo.resolve(RulesetSnapshots.FILE), tampered)
        assertFails(check(), "does not match the snapshot")
        // 正しく署名されていても、方針に反する記録（A の回避者が Checks App、B に回避者）・別のリポジトリ・正規形でない記録は失敗
        fun signed(bytes: ByteArray) {
            Files.write(repo.resolve(RulesetSnapshots.FILE), bytes)
            Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(bytes))
        }
        signed(String(snapshot).replace("\"actor_id\":$publisher", "\"actor_id\":$checks").toByteArray())
        assertFails(check(), "snapshot: live: the tag creation ruleset (A): the bypass actor $checks is not the Registry Publisher App")
        signed(String(snapshot).replace("\"bypass_actors\":[]", "\"bypass_actors\":[{\"actor_id\":$publisher,\"actor_type\":\"Integration\",\"bypass_mode\":\"always\"}]").toByteArray())
        assertFails(check(), "bypass_actors must be empty")
        signed(String(snapshot).replace("\"repository\":\"$repositoryName\"", "\"repository\":\"dog7dog/CreateScript-Registry\"").toByteArray())
        assertFails(check(), "was recorded for dog7dog/CreateScript-Registry")
        signed(String(snapshot).replace(",", ", ").toByteArray())
        assertFails(check(), "must be canonical JSON")
        Files.delete(repo.resolve(RulesetSnapshots.FILE))
        assertFails(check(), "verified-rulesets.json is missing")
    }

    // 記録は、bypass_actors が見える管理者の応答からだけ作れる。index-* に当たり得る余分な ruleset があれば記録しない
    @Test
    fun recordingNeedsTheAdministratorsView() {
        fun record(): Pair<Int, String> = run(listOf("record-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository", repositoryName, "--recorded-by", "dog7dog", "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "${directory.resolve("s.json")}"))
        setLive(idA to workflowA, idB to workflowB)
        assertFails(record(), "bypass_actors is not visible")
        setLive(idA to ownerA, idB to ownerB, 5L to edit(ownerC, mapOf("id" to JsonWriter.integer(5), "conditions" to json("""{"ref_name": {"include": ["refs/tags/*"], "exclude": []}}"""))))
        assertFails(record(), "may apply to index-* tags")
        setLive(idA to ownerA, idB to ownerB)
        assertFails(run(listOf("record-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository", "dog7dog/Other", "--recorded-by", "dog7dog", "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "${directory.resolve("s.json")}")), "must be a ruleset of dog7dog/Other itself")
        assertEquals(2, run(listOf("check-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository", repositoryName, "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--actor", "admin")).first)
        assertFalse(Files.exists(directory.resolve("s.json")))
    }

    @Test
    fun timestampsAreStrict() {
        assertEquals(RulesetSnapshots.timestamp("2026-11-13T13:35:14.034+09:00"), RulesetSnapshots.timestamp("2026-11-13T04:35:14.034Z"))
        for (bad in listOf("2026-11-13T04:35:14Z", "2026-11-13T04:35:14.0340Z", "2026-11-13 04:35:14.034Z", "2026-13-13T04:35:14.034Z", "2026-11-13T04:35:14.034", "")) {
            assertEquals(null, RulesetSnapshots.timestamp(bad), bad)
        }
        assertTrue(RulesetSnapshots.cannotMatchIndexTags(TagRulesets.parse(ownerC)!!))
        assertFalse(RulesetSnapshots.cannotMatchIndexTags(TagRulesets.parse(ownerA)!!))
        assertFalse(RulesetSnapshots.cannotMatchIndexTags(JsonObject(emptyMap(), emptyMap(), 0, 0)))
    }
}
