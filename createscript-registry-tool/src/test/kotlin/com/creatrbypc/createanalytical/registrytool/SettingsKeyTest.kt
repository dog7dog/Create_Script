package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import java.time.Duration
import java.time.Instant

// S8.3 U-1: スナップショットの署名鍵の運用（docs/operations/registry-settings-key.md）。snapshotVersion 2 の期限、T3（ツールに固定した指紋）、
// sign-ruleset-snapshot、鍵の交換・失効。鍵は、試験の中でメモリーの中だけに作る（TestOnlyKey。ファイルには書かない）。
// 本番の経路の確認には、test-only- でない ID の、その場で作った鍵（settings-unit-…）を、試験の中だけの指紋の一覧とともに使う
class SettingsKeyTest {
    @TempDir
    lateinit var directory: Path

    private val repositoryName = "dog7dog/CreateScript-Registry-Test"
    private val publisher = 5738726L
    private val checks = 5724854L
    private lateinit var repo: Path
    private lateinit var live: Path
    private var now: Instant = Instant.parse("2026-11-13T07:00:00Z")
    private val testKey = TestOnlyKey("test-only-settings-1")

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/p20/$name")!!.use { it.readAllBytes() }

    @BeforeEach
    fun setUp() {
        repo = directory.resolve("repo")
        Files.createDirectories(repo.resolve("github-settings"))
        Files.createDirectories(repo.resolve("keys"))
        val template = Paths.get("..").toAbsolutePath().normalize().resolve("registry-template/github-settings")
        Files.writeString(repo.resolve(TagRulesets.CREATION_FILE), Files.readString(template.resolve("index-tags-creation-ruleset.json")).replace("\"actor_id\": 0", "\"actor_id\": $publisher"))
        Files.copy(template.resolve("index-tags-protection-ruleset.json"), repo.resolve(TagRulesets.PROTECTION_FILE))
        Files.writeString(repo.resolve(RulesetSnapshots.MAIN_FILE), Files.readString(template.resolve("main-ruleset.json")).replace("\"integration_id\": 0", "\"integration_id\": $checks"))
        Files.write(directory.resolve("repository.json"), resource("FABRICATED-repository.json"))
        live = Files.createDirectories(directory.resolve("live"))
        Files.write(live.resolve("ruleset-27176846.json"), resource("owner-ruleset-A.json"))
        Files.write(live.resolve("ruleset-27176940.json"), resource("owner-ruleset-B.json"))
        // main の ruleset（版 3）: GitHub の実際の応答はまだ無いので、雛形から作った応答（FABRICATED）
        Files.write(live.resolve("ruleset-27200001.json"), resource("FABRICATED-owner-ruleset-main.json"))
        keys(testKey)
    }

    private fun keys(vararg list: TestOnlyKey) = Files.writeString(repo.resolve(RulesetSnapshots.KEYS_FILE), TrustedKeysFile.write(list.map { it.trusted }))

    private fun run(args: List<String>, allowTestKeys: Boolean = true, pins: Map<String, String> = SettingsKeyPins.OFFICIAL): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = RegistryTool(PrintStream(out, true), PrintStream(out, true), { now }, { FakeGitHub() }, allowTestKeys = allowTestKeys, settingsKeyPins = pins).run(args)
        return code to out.toString(Charsets.UTF_8)
    }

    private fun record(days: String? = null): Pair<Int, ByteArray?> {
        val out = directory.resolve("snapshot-${System.nanoTime()}.json")
        val (code, text) = run(listOf("record-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository-info", "${directory.resolve("repository.json")}", "--repository", repositoryName, "--recorded-by", "dog7dog",
            "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "$out") + (days?.let { listOf("--valid-days", it) } ?: emptyList()))
        return code to (if (code == 0) Files.readAllBytes(out) else null).also { if (code != 0) assertTrue(text.isNotEmpty()) }
    }

    private fun signature(snapshot: ByteArray, key: TestOnlyKey): IndexSignature = IndexSignature(key.keyId, Signature.getInstance("Ed25519").run {
        initSign(key.pair.private)
        update(RulesetSnapshots.message(key.keyId, snapshot))
        sign()
    })

    private fun place(snapshot: ByteArray, vararg signatures: IndexSignature) {
        Files.write(repo.resolve(RulesetSnapshots.FILE), snapshot)
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), IndexSignatures.write(signatures.toList()))
    }

    private fun check(allowTestKeys: Boolean = true, pins: Map<String, String> = SettingsKeyPins.OFFICIAL) =
        run(listOf("check-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository-info", "${directory.resolve("repository.json")}", "--repository", repositoryName, "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--actor", "workflow"), allowTestKeys, pins)

    private fun assertFails(result: Pair<Int, String>, expected: String) {
        assertEquals(1, result.first, result.second)
        assertTrue(result.second.contains(expected), result.second)
    }

    // 署名済みの正しいスナップショットに、期限などを書き換えて署名し直したもの
    private fun resigned(snapshot: ByteArray, from: String, to: String): ByteArray {
        val text = String(snapshot)
        assertTrue(text.contains(from), text)
        val changed = text.replace(from, to).toByteArray()
        place(changed, signature(changed, testKey))
        return changed
    }

    @Test
    fun theSnapshotHasVersion2AndExpires() {
        val (code, snapshot) = record()
        assertEquals(0, code)
        val text = String(snapshot!!)
        assertTrue(text.contains("\"snapshotVersion\":3") && text.contains("\"recordedAt\":\"2026-11-13T07:00:00Z\"") && text.contains("\"expires\":\"2027-05-12T07:00:00Z\""), text)
        assertEquals(0, record("1").first)
        assertEquals(0, record("180").first)
        assertEquals(1, record("181").first)
        assertEquals(1, record("0").first)
        assertEquals(2, record("x").first)
    }

    @Test
    fun expiryAndTimeOrderAreChecked() {
        val snapshot = record().second!!
        place(snapshot, signature(snapshot, testKey))
        assertEquals(0, check().first, check().second)
        // 期限の直前は通り、期限ちょうど・その後は失敗
        now = Instant.parse("2027-05-12T06:59:59Z")
        assertEquals(0, check().first, check().second)
        now = Instant.parse("2027-05-12T07:00:00Z")
        assertFails(check(), "expired at 2027-05-12T07:00:00Z")
        now = Instant.parse("2026-11-13T07:00:00Z")
        // 記録の時刻が未来（許容の 5 分を超える）
        resigned(snapshot, "\"recordedAt\":\"2026-11-13T07:00:00Z\"", "\"recordedAt\":\"2026-11-13T07:06:00Z\"")
        assertFails(check(), "is in the future")
        resigned(snapshot, "\"recordedAt\":\"2026-11-13T07:00:00Z\"", "\"recordedAt\":\"2026-11-13T07:04:00Z\"")
        assertEquals(0, check().first, check().second)
        // 境界: ちょうど 5 分先は通り、その 1 秒後は未来とみなす
        resigned(snapshot, "\"recordedAt\":\"2026-11-13T07:00:00Z\"", "\"recordedAt\":\"2026-11-13T07:05:00Z\"")
        assertEquals(0, check().first, check().second)
        resigned(snapshot, "\"recordedAt\":\"2026-11-13T07:00:00Z\"", "\"recordedAt\":\"2026-11-13T07:05:01Z\"")
        assertFails(check(), "is in the future")
        // 境界: ちょうど 180 日は通る（記録の既定）。1 秒前に縮めても通る
        resigned(snapshot, "\"expires\":\"2027-05-12T07:00:00Z\"", "\"expires\":\"2027-05-12T06:59:59Z\"")
        assertEquals(0, check().first, check().second)
        // 秒までの UTC でない形（小数・日付として存在しない日）
        resigned(snapshot, "\"expires\":\"2027-05-12T07:00:00Z\"", "\"expires\":\"2027-05-12T07:00:00.000Z\"")
        assertFails(check(), "expires is not a UTC timestamp in seconds")
        resigned(snapshot, "\"recordedAt\":\"2026-11-13T07:00:00Z\"", "\"recordedAt\":\"2026-11-31T07:00:00Z\"")
        assertFails(check(), "recordedAt is not a UTC timestamp in seconds")
        // 180 日を超える期間・前後が逆・同じ時刻・秒までの UTC でない形
        resigned(snapshot, "\"expires\":\"2027-05-12T07:00:00Z\"", "\"expires\":\"2027-05-12T07:00:01Z\"")
        assertFails(check(), "valid for more than 180 days")
        resigned(snapshot, "\"expires\":\"2027-05-12T07:00:00Z\"", "\"expires\":\"2026-11-13T06:00:00Z\"")
        assertFails(check(), "is not after recordedAt")
        resigned(snapshot, "\"expires\":\"2027-05-12T07:00:00Z\"", "\"expires\":\"2026-11-13T07:00:00Z\"")
        assertFails(check(), "is not after recordedAt")
        resigned(snapshot, "\"expires\":\"2027-05-12T07:00:00Z\"", "\"expires\":\"2027-05-12T16:00:00+09:00\"")
        assertFails(check(), "expires is not a UTC timestamp in seconds")
        // 古い版（ここでは期限の無い形で版 2 を名乗るもの）は受け入れない
        val v1 = String(snapshot).replace("\"expires\":\"2027-05-12T07:00:00Z\",", "").replace("\"snapshotVersion\":3", "\"snapshotVersion\":1").toByteArray()
        place(v1, signature(v1, testKey))
        assertFails(check(), "snapshotVersion must be 3")
    }

    // T3: 本番の経路（allowTestKeys = false）は、ツールに固定した指紋と一致する鍵だけを信頼する
    @Test
    fun productionTrustsOnlyPinnedKeys() {
        val production = TestOnlyKey("settings-unit-1")
        keys(production)
        val snapshot = record().second!!
        place(snapshot, signature(snapshot, production))
        val pin = mapOf(production.keyId to RulesetSnapshots.fingerprint(production.trusted.publicKey))
        assertEquals(0, check(allowTestKeys = false, pins = pin).first, check(allowTestKeys = false, pins = pin).second)
        // 本番のツールの既定（指紋の一覧は空）では信頼しない
        assertTrue(SettingsKeyPins.OFFICIAL.isEmpty())
        assertFails(check(allowTestKeys = false), "this build of the tool pins no settings key")
        // 一覧の鍵が差し替えられた（指紋の不一致）・固定から外された
        val other = TestOnlyKey("settings-unit-1")
        keys(other)
        place(snapshot, signature(snapshot, other))
        assertFails(check(allowTestKeys = false, pins = pin), "does not match the fingerprint pinned")
        keys(production)
        place(snapshot, signature(snapshot, production))
        assertFails(check(allowTestKeys = false, pins = mapOf("settings-unit-9" to "0".repeat(64))), "is not pinned in this build")
        // テスト用の鍵は、本番では指紋があっても拒否する
        keys(testKey)
        place(snapshot, signature(snapshot, testKey))
        assertFails(check(allowTestKeys = false, pins = mapOf(testKey.keyId to RulesetSnapshots.fingerprint(testKey.trusted.publicKey))), "has test keys")
    }

    // 本番のツールの既定（allowTestKeys = false、SettingsKeyPins.OFFICIAL は空）は、どの鍵の構成でも必ず失敗する
    @Test
    fun theProductionDefaultIsAlwaysClosed() {
        val snapshot = record().second!!
        val production = TestOnlyKey("settings-unit-1")
        val cases = listOf(
            "a test key" to { keys(testKey); place(snapshot, signature(snapshot, testKey)) },
            "a production key" to { keys(production); place(snapshot, signature(snapshot, production)) },
            "both" to { keys(testKey, production); place(snapshot, signature(snapshot, testKey), signature(snapshot, production)) },
            "no keys file" to { keys(production); place(snapshot, signature(snapshot, production)); Files.delete(repo.resolve(RulesetSnapshots.KEYS_FILE)) },
            "an empty keys file" to { Files.writeString(repo.resolve(RulesetSnapshots.KEYS_FILE), TrustedKeysFile.write(emptyList())); place(snapshot, signature(snapshot, production)) },
        )
        for ((name, prepare) in cases) {
            prepare()
            val result = check(allowTestKeys = false)
            assertEquals(1, result.first, "$name: ${result.second}")
            assertFalse(result.second.contains("match the signed snapshot"), name)
        }
    }

    // 署名の重複・知らない鍵・失効（一覧から外した鍵）・鍵の交換の順序
    @Test
    fun signaturesAndKeyRotation() {
        val snapshot = record().second!!
        val old = testKey
        val new = TestOnlyKey("test-only-settings-2")
        // 同じ鍵の ID の署名が 2 つある
        place(snapshot, signature(snapshot, old), signature(snapshot, old))
        assertFails(check(), "is not a valid signature file")
        // 知らない鍵
        place(snapshot, signature(snapshot, new))
        assertFails(check(), "the key test-only-settings-2 is not in keys/settings-keys.json")
        // 交換: 新しい鍵を加え、両方で署名 → 通る
        keys(old, new)
        place(snapshot, signature(snapshot, old), signature(snapshot, new))
        assertEquals(0, check().first, check().second)
        // 誤った順序: 古い鍵の署名を残したまま、古い鍵を一覧から外す（失効）→ 止まる（手順の注意を表示）
        keys(new)
        assertFails(check(), "sign with the new key first and remove the old key's signature before removing the key")
        // 正しい順序: 古い鍵の署名を外してから → 通る
        place(snapshot, signature(snapshot, new))
        assertEquals(0, check().first, check().second)
        // 失効した鍵だけの署名 → 止まる
        place(snapshot, signature(snapshot, old))
        assertFails(check(), "is not in keys/settings-keys.json")
    }

    // sign-ruleset-snapshot（関数）: 鍵はメモリーの中だけ
    @Test
    fun signingChecksTheKeyAndAppends() {
        val snapshot = record().second!!
        val keysFile = Files.readAllBytes(repo.resolve(RulesetSnapshots.KEYS_FILE))
        val signed = RulesetSnapshots.sign(snapshot, testKey.pair.private, testKey.keyId, keysFile, null, allowTestKeys = false, pins = SettingsKeyPins.OFFICIAL)
        assertEquals(emptyList<String>(), signed.problems)
        assertEquals(emptyList<String>(), RulesetSnapshots.signatureProblems(snapshot, signed.value, keysFile, allowTestKeys = true))
        // 別の鍵の秘密鍵で、一覧の鍵の ID を名乗った
        val wrong = TestOnlyKey("test-only-settings-1")
        assertTrue(RulesetSnapshots.sign(snapshot, wrong.pair.private, testKey.keyId, keysFile, null, true, SettingsKeyPins.OFFICIAL).problems.single().contains("is not the private key of"))
        // 一覧に無い鍵の ID
        assertTrue(RulesetSnapshots.sign(snapshot, wrong.pair.private, "test-only-settings-3", keysFile, null, true, SettingsKeyPins.OFFICIAL).problems.single().contains("is not in keys/settings-keys.json"))
        // 既存の署名に同じ鍵の ID を加えない。別の鍵は加えられる（交換のあいだ）
        assertTrue(RulesetSnapshots.sign(snapshot, testKey.pair.private, testKey.keyId, keysFile, signed.value, true, SettingsKeyPins.OFFICIAL).problems.single().contains("already has a signature by"))
        val second = TestOnlyKey("test-only-settings-2")
        keys(testKey, second)
        val bothKeys = Files.readAllBytes(repo.resolve(RulesetSnapshots.KEYS_FILE))
        val both = RulesetSnapshots.sign(snapshot, second.pair.private, second.keyId, bothKeys, signed.value, true, SettingsKeyPins.OFFICIAL)
        assertEquals(emptyList<String>(), both.problems)
        assertEquals(2, IndexSignatures.parse(both.value!!).value!!.size)
        // 本番の鍵（test-only- でない）は、儀式の承認まで署名できない
        assertFalse(RulesetSnapshots.PRODUCTION_SIGNING_ENABLED)
        val production = TestOnlyKey("settings-unit-1")
        assertTrue(RulesetSnapshots.sign(snapshot, production.pair.private, production.keyId, keysFile, null, false, mapOf(production.keyId to RulesetSnapshots.fingerprint(production.trusted.publicKey))).problems.single().contains("is refused"))
    }

    // sign-ruleset-snapshot（CLI）: 署名の前に要約を出し、方針・期限に反するものには署名しない。本番の鍵のファイルは読まない
    @Test
    fun signingCommandShowsASummaryAndRefuses() {
        val snapshot = record().second!!
        val snapshotFile = Files.write(directory.resolve("to-sign.json"), snapshot)
        val out = directory.resolve("out.sig")
        fun sign(keyId: String) = run(listOf("sign-ruleset-snapshot", "--repo", "$repo", "--snapshot", "$snapshotFile", "--key", "${directory.resolve("no-such-key.pk8")}", "--key-id", keyId,
            "--repository", repositoryName, "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "$out"))
        // 本番の鍵の ID: 鍵のファイル（存在しない）を読まずに拒否する
        val production = sign("createscript-registry-settings-2027-1")
        assertFails(production, "is refused")
        assertFalse(production.second.contains("no-such-key"), production.second)
        // 期限切れのスナップショット: 要約を出し、鍵を読まずに拒否する
        now = Instant.parse("2027-06-01T00:00:00Z")
        val expired = sign(testKey.keyId)
        assertFails(expired, "expired at")
        for (part in listOf("snapshot sha256 ", "repository $repositoryName", "recorded by dog7dog at 2026-11-13T07:00:00Z, expires 2027-05-12T07:00:00Z", "bypass_actors [{\"actor_id\":$publisher", "signing key ${testKey.keyId}")) {
            assertTrue(expired.second.contains(part), "$part\n${expired.second}")
        }
        assertFalse(Files.exists(out))
        // 方針に反する記録（B に回避者）
        now = Instant.parse("2026-11-13T07:00:00Z")
        Files.writeString(snapshotFile, String(snapshot).replace("\"bypass_actors\":[]", "\"bypass_actors\":[{\"actor_id\":$publisher,\"actor_type\":\"Integration\",\"bypass_mode\":\"always\"}]"))
        assertFails(sign(testKey.keyId), "bypass_actors must be empty")
        assertFalse(Files.exists(out))
        assertNull(RulesetSnapshots.seconds("2026-11-13T07:00:00.000Z"))
        assertEquals(Duration.ofDays(180), Duration.ofDays(RulesetSnapshots.MAX_VALIDITY_DAYS))
    }
}
