package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonBoolean
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString

// ruleset の必須チェック（context と、それを作ってよい GitHub App の ID）
data class RequiredCheck(val context: String, val integrationId: Long?)

// PR のコミットに付いたチェック（名前・作った GitHub App・コミット・結論）。時間の順
data class CheckRun(val name: String, val appId: Long, val headSha: String, val conclusion: String)

// GitHub の設定の案（github-settings/main-ruleset.json）の検査（S8.3 Phase 5、P4-H1）。
// 必須チェックを名前だけで指定すると、fork の PR が自分のワークフローに同じ名前のジョブを置いて、成功のチェックを作れる（P4-H1）。
// GitHub Actions のジョブのチェックは、どのワークフローのものでも GitHub Actions（App）が作るので、App を指定しても区別できない。
// そこで、検査の結果は、レジストリ専用の GitHub App（CreateScript Registry Checks）が、信頼できるワークフローのジョブ（PR のデータを扱わない
// report ジョブ）から、PR の head のコミットに付ける。ruleset は、その App の ID（integration_id）で必須チェックを指定する。
// 実際の GitHub の判定は、ここでは確かめていない（satisfied は、GitHub の文書の規則のモデル）
object GitHubSettings {
    // GitHub Actions の App の ID（GitHub の文書の値。この App の ID を必須チェックに使っても、PR のワークフローと区別できない）
    const val GITHUB_ACTIONS_APP_ID = 15368L
    val REQUIRED_CONTEXTS = listOf("registry/validate", "registry/approvals")

    private fun obj(value: Any?) = value as? JsonObject

    fun requiredChecks(ruleset: JsonObject): List<RequiredCheck> {
        val rules = (ruleset.members["rules"] as? JsonArray)?.elements.orEmpty().mapNotNull(::obj)
        val checks = rules.firstOrNull { (it.members["type"] as? JsonString)?.value == "required_status_checks" }
            ?.let { obj(it.members["parameters"])?.members?.get("required_status_checks") as? JsonArray }?.elements.orEmpty().mapNotNull(::obj)
        return checks.map { RequiredCheck((it.members["context"] as? JsonString)?.value ?: "", (it.members["integration_id"] as? JsonInteger)?.value) }
    }

    // ruleset の案の問題（無ければ空）
    fun rulesetProblems(bytes: ByteArray): List<String> {
        val ruleset = try {
            JsonParser.parse(bytes, 1 shl 20).value as? JsonObject
        } catch (error: Exception) {
            null
        } ?: return listOf("the ruleset is not a JSON object")
        val problems = ArrayList<String>()
        if ((ruleset.members["bypass_actors"] as? JsonArray)?.elements?.isEmpty() != true) problems += "bypass_actors must be empty (nobody bypasses the rules)"
        if ((ruleset.members["enforcement"] as? JsonString)?.value != "active") problems += "enforcement must be active"
        val rules = (ruleset.members["rules"] as? JsonArray)?.elements.orEmpty().mapNotNull(::obj)
        fun rule(type: String) = rules.firstOrNull { (it.members["type"] as? JsonString)?.value == type }
        for (type in listOf("deletion", "non_fast_forward", "required_linear_history")) if (rule(type) == null) problems += "the rule $type is missing"
        val pr = obj(rule("pull_request")?.members?.get("parameters"))
        if (pr == null) {
            problems += "the rule pull_request is missing"
        } else {
            if (((pr.members["required_approving_review_count"] as? JsonInteger)?.value ?: 0) < 2) problems += "required_approving_review_count must be at least 2"
            for (flag in listOf("dismiss_stale_reviews_on_push", "require_code_owner_review", "require_last_push_approval")) {
                if ((pr.members[flag] as? JsonBoolean)?.value != true) problems += "$flag must be true"
            }
        }
        val status = obj(rule("required_status_checks")?.members?.get("parameters"))
        if (status == null) {
            problems += "the rule required_status_checks is missing"
            return problems
        }
        if ((status.members["strict_required_status_checks_policy"] as? JsonBoolean)?.value != true) problems += "strict_required_status_checks_policy must be true"
        val checks = requiredChecks(ruleset)
        if (checks.map { it.context }.sorted() != REQUIRED_CONTEXTS.sorted()) problems += "the required checks must be exactly ${REQUIRED_CONTEXTS.joinToString(", ")}"
        for (check in checks) {
            when (val id = check.integrationId) {
                null -> problems += "the required check ${check.context} has no integration_id, so a check with the same name from any workflow (also from a fork) would satisfy it"
                0L -> problems += "the required check ${check.context}: integration_id is not set yet (the ID of the registry's GitHub App; GOVERNANCE.md)"
                GITHUB_ACTIONS_APP_ID -> problems += "the required check ${check.context}: integration_id must be the registry's own GitHub App, not GitHub Actions (any workflow can make GitHub Actions checks)"
                else -> if (id < 0) problems += "the required check ${check.context}: invalid integration_id"
            }
        }
        if (checks.map { it.integrationId }.distinct().size > 1) problems += "all required checks must come from the same GitHub App"
        return problems
    }

    // GitHub の文書の規則のモデル: 必須チェックごとに、同じ名前・同じ App（指定があれば）・head のコミットの、最後のチェックが成功していること
    fun satisfied(required: List<RequiredCheck>, runs: List<CheckRun>, headSha: String): Boolean = required.isNotEmpty() && required.all { r ->
        runs.lastOrNull { it.name == r.context && it.headSha == headSha && (r.integrationId == null || it.appId == r.integrationId) }?.conclusion == "success"
    }
}
