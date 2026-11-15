package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import org.junit.jupiter.api.Assertions.assertEquals
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

// S8.3 U-2: スナップショット版 3 による main ブランチの保護の照合（main の ruleset と既定のブランチ）。
// A・B はテスト用のリポジトリでの実際の応答（src/test/resources/p20/owner-ruleset-*.json）。main の ruleset と GET /repos の応答は、
// GitHub の実際の応答がまだ無いので、雛形から作ったもの（FABRICATED-*.json）。読み取りのトークンでの見え方は、タグの ruleset での
// 実測（bypass_actors が無い、時刻は Z）に合わせて作っている。ブランチの ruleset でも同じかは GitHub で未確認
class MainRulesetSnapshotTest {
    @TempDir
    lateinit var directory: Path

    private val repositoryName = "dog7dog/CreateScript-Registry-Test"
    private val publisher = 5738726L
    private val checks = 5724854L
    private val idA = 27176846L
    private val idB = 27176940L
    private val idMain = 27200001L
    private lateinit var repo: Path
    private lateinit var live: Path
    private lateinit var repoInfo: Path
    private val key = TestOnlyKey("test-only-settings-1")
    private val now = Instant.parse("2026-11-14T07:00:00Z")

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/p20/$name")!!.use { it.readAllBytes() }

    private fun edit(bytes: ByteArray, changes: Map<String, JsonValue?>): ByteArray {
        val members = LinkedHashMap(TagRulesets.parse(bytes)!!.members)
        for ((k, v) in changes) if (v == null) members.remove(k) else members[k] = v
        return CanonicalJson.write(JsonWriter.obj(*members.toList().toTypedArray()))
    }

    private fun json(text: String): JsonValue = TagRulesets.parse("""{"v": $text}""".toByteArray())!!.members.getValue("v")

    private fun readOnly(bytes: ByteArray, canBypass: String): ByteArray {
        val obj = TagRulesets.parse(bytes)!!
        fun utc(field: String) = JsonWriter.string(RulesetSnapshots.timestamp((obj.members[field] as JsonString).value)!!.toString())
        return edit(bytes, mapOf("bypass_actors" to null, "created_at" to utc("created_at"), "updated_at" to utc("updated_at"), "current_user_can_bypass" to JsonWriter.string(canBypass)))
    }

    private val ownerMain get() = resource("FABRICATED-owner-ruleset-main.json")
    private val workflowA get() = readOnly(resource("owner-ruleset-A.json"), "never")
    private val workflowB get() = readOnly(resource("owner-ruleset-B.json"), "never")
    private val workflowMain get() = readOnly(ownerMain, "never")

    // main の規則を変えたもの（承認の人数・必須チェック）
    private fun mainRules(approvals: Int = 2, checksList: String = """[{"context": "registry/validate", "integration_id": $checks}, {"context": "registry/approvals", "integration_id": $checks}]""") = json(
        """[{"type": "deletion"}, {"type": "non_fast_forward"}, {"type": "required_linear_history"},
            {"type": "pull_request", "parameters": {"required_approving_review_count": $approvals, "dismiss_stale_reviews_on_push": true, "require_code_owner_review": true, "require_last_push_approval": true, "required_review_thread_resolution": true}},
            {"type": "required_status_checks", "parameters": {"strict_required_status_checks_policy": true, "required_status_checks": $checksList}}]""",
    )

    @BeforeEach
    fun setUp() {
        repo = directory.resolve("repo")
        Files.createDirectories(repo.resolve("github-settings"))
        Files.createDirectories(repo.resolve("keys"))
        val template = Paths.get("..").toAbsolutePath().normalize().resolve("registry-template/github-settings")
        Files.writeString(repo.resolve(TagRulesets.CREATION_FILE), Files.readString(template.resolve("index-tags-creation-ruleset.json")).replace("\"actor_id\": 0", "\"actor_id\": $publisher"))
        Files.copy(template.resolve("index-tags-protection-ruleset.json"), repo.resolve(TagRulesets.PROTECTION_FILE))
        Files.writeString(repo.resolve(RulesetSnapshots.MAIN_FILE), Files.readString(template.resolve("main-ruleset.json")).replace("\"integration_id\": 0", "\"integration_id\": $checks"))
        Files.writeString(repo.resolve(RulesetSnapshots.KEYS_FILE), TrustedKeysFile.write(listOf(key.trusted)))
        repoInfo = Files.write(directory.resolve("repository.json"), resource("FABRICATED-repository.json"))
        live = Files.createDirectories(directory.resolve("live"))
    }

    private fun setLive(vararg rulesets: Pair<Long, ByteArray>) {
        Files.list(live).use { s -> s.toList() }.forEach(Files::delete)
        for ((id, bytes) in rulesets) Files.write(live.resolve("ruleset-$id.json"), bytes)
    }

    private fun run(args: List<String>): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = RegistryTool(PrintStream(out, true), PrintStream(out, true), { now }, { FakeGitHub() }, allowTestKeys = true).run(args)
        return code to out.toString(Charsets.UTF_8)
    }

    private fun record(): Pair<Int, String> = run(listOf("record-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository-info", "$repoInfo", "--repository", repositoryName,
        "--recorded-by", "dog7dog", "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "${directory.resolve("snapshot-${System.nanoTime()}.json")}"))

    private fun sign(snapshot: ByteArray): ByteArray = IndexSignatures.write(listOf(IndexSignature(key.keyId, Signature.getInstance("Ed25519").run {
        initSign(key.pair.private)
        update(RulesetSnapshots.message(key.keyId, snapshot))
        sign()
    })))

    private fun recordAndSign(): ByteArray {
        setLive(idA to resource("owner-ruleset-A.json"), idB to resource("owner-ruleset-B.json"), idMain to ownerMain)
        val out = directory.resolve("snapshot.json")
        val (code, text) = run(listOf("record-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository-info", "$repoInfo", "--repository", repositoryName,
            "--recorded-by", "dog7dog", "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--out", "$out"))
        assertEquals(0, code, text)
        val snapshot = Files.readAllBytes(out)
        Files.write(repo.resolve(RulesetSnapshots.FILE), snapshot)
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(snapshot))
        return snapshot
    }

    private fun check(actor: String = "workflow") = run(listOf("check-ruleset-snapshot", "--repo", "$repo", "--live", "$live", "--repository-info", "$repoInfo",
        "--repository", repositoryName, "--checks-app-id", "$checks", "--publisher-app-id", "$publisher", "--actor", actor))

    private fun assertFails(result: Pair<Int, String>, expected: String) {
        assertEquals(1, result.first, result.second)
        assertTrue(result.second.contains(expected), result.second)
    }

    private fun liveWithMain(main: ByteArray?) = if (main == null) setLive(idA to workflowA, idB to workflowB) else setLive(idA to workflowA, idB to workflowB, idMain to main)

    @Test
    fun theSnapshotRecordsMainAndTheDefaultBranch() {
        val snapshot = String(recordAndSign())
        assertTrue(snapshot.contains("\"snapshotVersion\":3") && snapshot.contains("\"defaultBranch\":\"main\"") && snapshot.contains("\"target\":\"branch\""), snapshot)
        liveWithMain(workflowMain)
        assertEquals(0, check().first, check().second)
        // Publisher App の表示: A は always、B と main は never
        setLive(idA to readOnly(resource("owner-ruleset-A.json"), "always"), idB to workflowB, idMain to workflowMain)
        assertEquals(0, check("publisher").first, check("publisher").second)
        // Publisher App が main を回避できるなら失敗
        setLive(idA to readOnly(resource("owner-ruleset-A.json"), "always"), idB to workflowB, idMain to readOnly(ownerMain, "always"))
        assertFails(check("publisher"), "current_user_can_bypass is always for this token, expected never")
    }

    @Test
    fun changesToMainAreDetected() {
        recordAndSign()
        // 無効化・評価のみ
        for (enforcement in listOf("disabled", "evaluate")) {
            liveWithMain(edit(workflowMain, mapOf("enforcement" to JsonWriter.string(enforcement))))
            assertFails(check(), "enforcement is $enforcement, not active")
        }
        // 削除・作り直し（id が変わる）
        liveWithMain(null)
        assertFails(check(), "the repository has no ruleset with the recorded id $idMain")
        setLive(idA to workflowA, idB to workflowB, 999L to edit(workflowMain, mapOf("id" to JsonWriter.integer(999))))
        assertFails(check(), "the repository has no ruleset with the recorded id $idMain")
        // 回避者の追加: 読み取りのトークンには見えないので updated_at で（P-20 はタグの ruleset でだけ実測）、見える場合は直接
        liveWithMain(edit(workflowMain, mapOf("updated_at" to JsonWriter.string("2026-11-14T02:00:00.000Z"))))
        assertFails(check(), "the ruleset was changed after the snapshot")
        liveWithMain(edit(ownerMain, mapOf("bypass_actors" to json("""[{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]"""))))
        assertFails(check(), "bypass_actors differ from the snapshot")
        // 承認の人数の減少・必須チェックの削除（規則の違い）
        liveWithMain(edit(workflowMain, mapOf("rules" to mainRules(approvals = 1))))
        assertFails(check(), "rules differ from the snapshot")
        liveWithMain(edit(workflowMain, mapOf("rules" to mainRules(checksList = """[{"context": "registry/validate", "integration_id": $checks}]"""))))
        assertFails(check(), "rules differ from the snapshot")
        // 対象のブランチの変更
        liveWithMain(edit(workflowMain, mapOf("conditions" to json("""{"ref_name": {"include": ["refs/heads/other"], "exclude": []}}"""))))
        assertFails(check(), "conditions differ from the snapshot")
        // このトークンが main を回避できる
        liveWithMain(readOnly(ownerMain, "always"))
        assertFails(check(), "current_user_can_bypass is always for this token, expected never")
        // 応答の欠落
        liveWithMain(edit(workflowMain, mapOf("current_user_can_bypass" to null)))
        assertFails(check(), "current_user_can_bypass is missing")
        liveWithMain(edit(workflowMain, mapOf("updated_at" to null)))
        assertFails(check(), "updated_at is missing or not a GitHub timestamp")
    }

    @Test
    fun theDefaultBranchIsChecked() {
        recordAndSign()
        liveWithMain(workflowMain)
        Files.writeString(repoInfo, """{"full_name": "$repositoryName", "default_branch": "dev"}""")
        assertFails(check(), "the default branch is dev, not main")
        Files.writeString(repoInfo, """{"full_name": "$repositoryName"}""")
        assertFails(check(), "has no default_branch")
        Files.writeString(repoInfo, """{"full_name": "dog7dog/Other", "default_branch": "main"}""")
        assertFails(check(), "is for dog7dog/Other")
        Files.writeString(repoInfo, "not json")
        assertFails(check(), "the repository information (GET /repos/{owner}/{repo}) is missing")
        Files.delete(repoInfo)
        assertEquals(2, check().first)
    }

    @Test
    fun extraBranchRulesets() {
        recordAndSign()
        for (pattern in listOf("~ALL", "~DEFAULT_BRANCH", "refs/heads/main", "refs/heads/m*", "refs/heads/*", "main")) {
            setLive(idA to workflowA, idB to workflowB, idMain to workflowMain, 7L to edit(workflowMain, mapOf("id" to JsonWriter.integer(7), "name" to JsonWriter.string("extra"), "conditions" to json("""{"ref_name": {"include": ["$pattern"], "exclude": []}}"""))))
            assertFails(check(), "may apply to the default branch")
        }
        setLive(idA to workflowA, idB to workflowB, idMain to workflowMain, 7L to edit(workflowMain, mapOf("id" to JsonWriter.integer(7), "name" to JsonWriter.string("extra"), "conditions" to json("""{"ref_name": {"include": ["refs/heads/release-*"], "exclude": []}}"""))))
        assertEquals(0, check().first, check().second)
        setLive(idA to workflowA, idB to workflowB, idMain to workflowMain, 7L to edit(workflowMain, mapOf("id" to JsonWriter.integer(7), "conditions" to null)))
        assertFails(check(), "has no visible conditions")
    }

    @Test
    fun recordingRefusesAWeakMainRuleset() {
        setLive(idA to resource("owner-ruleset-A.json"), idB to resource("owner-ruleset-B.json"), idMain to ownerMain)
        assertEquals(0, record().first, record().second)
        fun withMain(main: ByteArray) = setLive(idA to resource("owner-ruleset-A.json"), idB to resource("owner-ruleset-B.json"), idMain to main)
        withMain(edit(ownerMain, mapOf("rules" to mainRules(approvals = 1))))
        assertFails(record(), "required_approving_review_count must be at least 2")
        withMain(edit(ownerMain, mapOf("rules" to mainRules(checksList = """[{"context": "registry/validate", "integration_id": 15368}, {"context": "registry/approvals", "integration_id": 15368}]"""))))
        assertFails(record(), "not the Registry Checks App ($checks)")
        withMain(edit(ownerMain, mapOf("bypass_actors" to json("""[{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]"""))))
        assertFails(record(), "bypass_actors must be empty")
        withMain(edit(ownerMain, mapOf("enforcement" to JsonWriter.string("disabled"))))
        assertFails(record(), "enforcement must be active")
        withMain(edit(ownerMain, mapOf("conditions" to json("""{"ref_name": {"include": ["refs/heads/main"], "exclude": []}}"""))))
        assertFails(record(), "include must be exactly [\"~DEFAULT_BRANCH\"]")
        // 読み取りの表示（bypass_actors が無い）からは記録しない
        withMain(workflowMain)
        assertFails(record(), "bypass_actors is missing")
        // main の ruleset が無い・既定のブランチが違う
        setLive(idA to resource("owner-ruleset-A.json"), idB to resource("owner-ruleset-B.json"))
        assertFails(record(), "exactly one branch ruleset named \"main\"")
        withMain(ownerMain)
        Files.writeString(repoInfo, """{"full_name": "$repositoryName", "default_branch": "trunk"}""")
        assertFails(record(), "the default branch is trunk, not main")
    }

    @Test
    fun aSignedButWeakSnapshotIsRefused() {
        val snapshot = String(recordAndSign())
        liveWithMain(workflowMain)
        fun signed(text: String) {
            val bytes = text.toByteArray()
            Files.write(repo.resolve(RulesetSnapshots.FILE), bytes)
            Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(bytes))
        }
        signed(snapshot.replace("\"required_approving_review_count\":2", "\"required_approving_review_count\":1"))
        assertFails(check(), "snapshot: the main branch ruleset: required_approving_review_count must be at least 2")
        signed(snapshot.replace("\"defaultBranch\":\"main\"", "\"defaultBranch\":\"dev\""))
        assertFails(check(), "defaultBranch must be main")
        // 署名の後の改ざん（署名し直さない）
        Files.writeString(repo.resolve(RulesetSnapshots.FILE), snapshot.replace("\"required_approving_review_count\":2", "\"required_approving_review_count\":1"))
        Files.write(repo.resolve(RulesetSnapshots.SIGNATURE_FILE), sign(snapshot.toByteArray()))
        assertFails(check(), "does not match the snapshot")
    }

    // U-2 の読み取りだけの検証（2026-11-14、所有者のトークンの GET）: テスト用のリポジトリの実際の GET /repos の応答で既定のブランチを確かめられ、
    // 実際の ruleset（タグの A・B・C、ブランチの ruleset は無い）からは、main の ruleset が無いので記録できない（fail-closed）
    @Test
    fun realResponsesFromTheTestRepository() {
        val info = TagRulesets.parse(resource("owner-repository-2026-11-14.json"))!!
        assertEquals(emptyList<String>(), RulesetSnapshots.repositoryProblems(info, repositoryName))
        assertTrue(RulesetSnapshots.repositoryProblems(info, "dog7dog/Create_Script").single().contains("not dog7dog/Create_Script"))
        Files.write(repoInfo, resource("owner-repository-2026-11-14.json"))
        setLive(
            idA to resource("owner-live-2026-11-14-ruleset-27176846.json"),
            idB to resource("owner-live-2026-11-14-ruleset-27176940.json"),
            27179117L to resource("owner-live-2026-11-14-ruleset-27179117.json"),
        )
        assertFails(record(), "is missing")
    }
}
