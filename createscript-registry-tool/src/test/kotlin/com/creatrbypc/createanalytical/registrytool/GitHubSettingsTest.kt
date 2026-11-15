package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.with
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.3 Phase 5（P4-H1）: 必須チェックの偽装。GitHub の判定は、文書の規則のモデル（GitHubSettings.satisfied）で確かめる（実際の GitHub では未検証）
class GitHubSettingsTest {
    private val head = "c".repeat(40)
    private val old = "b".repeat(40)
    private val app = 987654L

    private fun ruleset(checks: String) = """
        {"name": "main", "target": "branch", "enforcement": "active", "bypass_actors": [],
         "rules": [{"type": "deletion"}, {"type": "non_fast_forward"}, {"type": "required_linear_history"},
          {"type": "pull_request", "parameters": {"required_approving_review_count": 2, "dismiss_stale_reviews_on_push": true,
            "require_code_owner_review": true, "require_last_push_approval": true, "required_review_thread_resolution": true}},
          {"type": "required_status_checks", "parameters": {"strict_required_status_checks_policy": true, "required_status_checks": [$checks]}}]}
    """.trimIndent().toByteArray()

    private fun required(bytes: ByteArray) = GitHubSettings.requiredChecks(com.creatrbypc.createanalytical.project.JsonParser.parse(bytes, 1 shl 20).value as com.creatrbypc.createanalytical.project.JsonObject)

    // PR に入れたワークフローのジョブ（GitHub Actions の App が作るチェック）
    private fun spoofed(name: String, sha: String = head) = CheckRun(name, GitHubSettings.GITHUB_ACTIONS_APP_ID, sha, "success")

    @Test
    fun namesAloneCanBeSpoofed() {
        // Phase 4 までの形（名前だけ）: PR のワークフローの同名のジョブで満たされてしまう（P4-H1 の再現のモデル）
        val byName = ruleset("""{"context": "registry/validate"}, {"context": "registry/approvals"}""")
        assertTrue(GitHubSettings.satisfied(required(byName), listOf(spoofed("registry/validate"), spoofed("registry/approvals")), head))
        assertTrue(GitHubSettings.rulesetProblems(byName).count { it.contains("has no integration_id") } == 2)
    }

    @Test
    fun theRegistryAppIsRequired() {
        val pinned = ruleset("""{"context": "registry/validate", "integration_id": $app}, {"context": "registry/approvals", "integration_id": $app}""")
        assertEquals(emptyList<String>(), GitHubSettings.rulesetProblems(pinned))
        val checks = required(pinned)
        // 同じ名前の、PR のワークフローのチェックは数えない
        assertFalse(GitHubSettings.satisfied(checks, listOf(spoofed("registry/validate"), spoofed("registry/approvals")), head))
        // App のチェックでも、古いコミットのものは数えない（PR に新しいコミットが積まれた後）
        val onOld = listOf(CheckRun("registry/validate", app, old, "success"), CheckRun("registry/approvals", app, old, "success"))
        assertFalse(GitHubSettings.satisfied(checks, onOld, head))
        // head のコミットへの App のチェックだけが数える
        val good = listOf(CheckRun("registry/validate", app, head, "success"), CheckRun("registry/approvals", app, head, "success"))
        assertTrue(GitHubSettings.satisfied(checks, onOld + good + spoofed("registry/validate"), head))
        // 同じコミットで、後から失敗が報告されれば満たさない（最後のものが数える）
        assertFalse(GitHubSettings.satisfied(checks, good + CheckRun("registry/approvals", app, head, "failure"), head))
        // GitHub Actions の App の ID を指定しても、区別できないので拒否する
        val actions = ruleset("""{"context": "registry/validate", "integration_id": 15368}, {"context": "registry/approvals", "integration_id": 15368}""")
        assertEquals(2, GitHubSettings.rulesetProblems(actions).count { it.contains("not GitHub Actions") })
        // 雛形は、App の ID がまだ無い（0）ことだけが問題
        val template = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../registry-template/github-settings/main-ruleset.json"))
        val problems = GitHubSettings.rulesetProblems(template)
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.all { it.contains("integration_id is not set yet") })
    }

    @Test
    fun otherRulesetProblems() {
        val weak = String(ruleset("""{"context": "registry/validate", "integration_id": $app}, {"context": "registry/approvals", "integration_id": $app}"""))
            .replace("\"bypass_actors\": []", "\"bypass_actors\": [{\"actor_id\": 1}]")
            .replace("\"required_approving_review_count\": 2", "\"required_approving_review_count\": 1")
            .replace("\"require_last_push_approval\": true", "\"require_last_push_approval\": false")
            .replace("\"strict_required_status_checks_policy\": true", "\"strict_required_status_checks_policy\": false")
        val problems = GitHubSettings.rulesetProblems(weak.toByteArray())
        for (fragment in listOf("bypass_actors", "at least 2", "require_last_push_approval", "strict_required_status_checks_policy")) {
            assertTrue(problems.any { fragment in it }, "$fragment: $problems")
        }
    }

    // fork の PR は .github/ を変えられない（多層の対策）
    @Test
    fun forksCannotChangeWorkflows() {
        val base = tree()
        val head = base.with(".github/workflows/validate.yml", "name: validate\n".toByteArray())
        val pr = ToolFixtures.alice
        val fromFork = PullRequestValidator(FakeGitHub(), ToolFixtures.today, ToolFixtures.now).validate(base, head, pr, null, fromFork = true)
        assertTrue(fromFork.errors.any { it.contains("a pull request from a fork cannot change .github/") }, fromFork.lines().toString())
        val fromBranch = PullRequestValidator(FakeGitHub(), ToolFixtures.today, ToolFixtures.now).validate(base, head, pr, null, fromFork = false)
        assertTrue(fromBranch.errors.none { it.contains("from a fork") }, fromBranch.lines().toString())
    }
}
