package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString

// index-* のタグの ruleset の検査（S8.3 Phase 10A、W2・W3。docs/design/registry-github-architecture.md の 3 章）。
// - A（作成）：規則 creation。回避者は Registry Publisher App（Integration）だけ
// - B（更新・削除）：規則 update・deletion・non_fast_forward。回避者なし
// 雛形・案のファイルは exact（規則も回避者も、ちょうどその形）。GitHub の API の応答（live）は、知っている項目だけを読む:
// GitHub の文書（REST「Get a repository ruleset」）によると、bypass_actors は、その ruleset に書き込める利用者にだけ返される。
// 返されない（項目が無い）ときは、回避者を確かめられないので、推測で通さずに失敗する（fail-closed）。
// GitHub が実際にこれらの規則を強制するかは、ここでは確かめていない（Phase 10B）
object TagRulesets {
    const val PATTERN = "refs/tags/index-*"
    const val CREATION_FILE = "github-settings/index-tags-creation-ruleset.json"
    const val PROTECTION_FILE = "github-settings/index-tags-protection-ruleset.json"
    val PROTECTION_RULES = setOf("update", "deletion", "non_fast_forward")

    fun parse(bytes: ByteArray): JsonObject? = try {
        JsonParser.parse(bytes, 1 shl 20).value as? JsonObject
    } catch (error: Exception) {
        null
    }

    private fun string(obj: JsonObject, key: String) = (obj.members[key] as? JsonString)?.value

    private fun ruleTypes(obj: JsonObject): List<String>? =
        (obj.members["rules"] as? JsonArray)?.elements?.map { (it as? JsonObject)?.let { r -> string(r, "type") } ?: return null }

    // 対象・有効・条件（両方に共通）
    private fun common(obj: JsonObject, what: String, problems: MutableList<String>) {
        if (string(obj, "target") != "tag") problems += "$what: target must be tag"
        if (string(obj, "enforcement") != "active") problems += "$what: enforcement must be active"
        val refName = (obj.members["conditions"] as? JsonObject)?.members?.get("ref_name") as? JsonObject
        val include = (refName?.members?.get("include") as? JsonArray)?.elements?.map { (it as? JsonString)?.value }
        val exclude = refName?.members?.get("exclude")
        if (include != listOf(PATTERN)) problems += "$what: conditions.ref_name.include must be exactly [\"$PATTERN\"]"
        if (exclude != null && (exclude as? JsonArray)?.elements?.isEmpty() != true) problems += "$what: conditions.ref_name.exclude must be empty"
    }

    private fun appId(id: Long?, name: String, problems: MutableList<String>) {
        if (id != null && (id <= 0 || id == GitHubSettings.GITHUB_ACTIONS_APP_ID)) problems += "the $name App ID must be a real GitHub App ID (not 0 or GitHub Actions)"
    }

    // A（作成）。publisherAppId・checksAppId は期待する値（与えられたときだけ照合する）
    fun creationProblems(obj: JsonObject?, publisherAppId: Long?, checksAppId: Long?, exact: Boolean): List<String> {
        val what = "the tag creation ruleset (A)"
        obj ?: return listOf("$what is missing or not a JSON object")
        val problems = ArrayList<String>()
        common(obj, what, problems)
        val types = ruleTypes(obj)
        when {
            types == null -> problems += "$what: rules cannot be read"
            exact && types != listOf("creation") -> problems += "$what: rules must be exactly [creation]"
            "creation" !in types -> problems += "$what: the rule creation is missing"
        }
        val bypass = obj.members["bypass_actors"]
        if (bypass == null) {
            problems += "$what: bypass_actors is not visible, so the bypass actors cannot be verified (GitHub returns them only to users who can write the ruleset)"
            return problems
        }
        val actors = (bypass as? JsonArray)?.elements?.map { it as? JsonObject }
        if (actors == null || actors.size != 1 || actors[0] == null) {
            problems += "$what: bypass_actors must be exactly one actor, the Registry Publisher App"
            return problems
        }
        val actor = actors[0]!!
        if (string(actor, "actor_type") != "Integration") problems += "$what: the bypass actor must be a GitHub App (actor_type Integration)"
        if (string(actor, "bypass_mode") != "always") problems += "$what: the bypass mode must be always"
        when (val id = (actor.members["actor_id"] as? JsonInteger)?.value) {
            null -> problems += "$what: the bypass actor has no actor_id"
            0L -> problems += "$what: the Publisher App ID (actor_id) is not set yet"
            GitHubSettings.GITHUB_ACTIONS_APP_ID -> problems += "$what: the bypass actor must not be GitHub Actions (every workflow is GitHub Actions)"
            else -> {
                if (publisherAppId != null && id != publisherAppId) problems += "$what: the bypass actor $id is not the Registry Publisher App ($publisherAppId)"
                if (checksAppId != null && id == checksAppId) problems += "$what: the bypass actor must not be the Registry Checks App"
            }
        }
        return problems
    }

    // B（更新・削除）
    fun protectionProblems(obj: JsonObject?, exact: Boolean): List<String> {
        val what = "the tag update and deletion ruleset (B)"
        obj ?: return listOf("$what is missing or not a JSON object")
        val problems = ArrayList<String>()
        common(obj, what, problems)
        val types = ruleTypes(obj)
        when {
            types == null -> problems += "$what: rules cannot be read"
            exact && types.toSet() != PROTECTION_RULES -> problems += "$what: rules must be exactly update, deletion and non_fast_forward"
            !types.containsAll(PROTECTION_RULES) -> problems += "$what: the rules ${(PROTECTION_RULES - types.toSet()).sorted().joinToString(", ")} are missing"
        }
        when (val bypass = obj.members["bypass_actors"]) {
            null -> problems += "$what: bypass_actors is not visible, so it cannot be verified that nobody bypasses it (GitHub returns them only to users who can write the ruleset)"
            !is JsonArray -> problems += "$what: bypass_actors cannot be read"
            else -> if (bypass.elements.isNotEmpty()) problems += "$what: bypass_actors must be empty (nobody, not even the Publisher App, may move or delete an index tag)"
        }
        return problems
    }

    // 雛形・案のファイルと App の ID（check-settings）
    fun fileProblems(creation: ByteArray, protection: ByteArray, publisherAppId: Long?, checksAppId: Long?): List<String> {
        val problems = ArrayList<String>()
        appId(publisherAppId, "Publisher", problems)
        appId(checksAppId, "Checks", problems)
        if (publisherAppId != null && checksAppId != null && publisherAppId == checksAppId) problems += "the Publisher App and the Checks App must be different Apps"
        problems += creationProblems(parse(creation), publisherAppId, checksAppId, exact = true)
        problems += protectionProblems(parse(protection), exact = true)
        return problems
    }

    // GitHub の API の応答（GET /repos/{owner}/{repo}/rulesets/{id} を保存したもの）と、リポジトリの案のファイルを比べる（check-live-rulesets）。
    // 案と同じ名前の ruleset がちょうど 1 つずつあり、どちらも検査に通ること（App の ID は、変数で渡す期待値と照合する）
    fun liveProblems(expectedCreation: ByteArray, expectedProtection: ByteArray, live: List<JsonObject>, publisherAppId: Long, checksAppId: Long): List<String> {
        val problems = ArrayList<String>()
        problems += fileProblems(expectedCreation, expectedProtection, publisherAppId, checksAppId)
        for ((expected, isCreation) in listOf(parse(expectedCreation) to true, parse(expectedProtection) to false)) {
            val name = expected?.let { string(it, "name") } ?: continue
            val matching = live.filter { string(it, "name") == name }
            if (matching.size != 1) {
                problems += "the repository must have exactly one ruleset named \"$name\" (it has ${matching.size})"
                continue
            }
            val found = matching.single()
            problems += if (isCreation) {
                creationProblems(found, publisherAppId, checksAppId, exact = false).map { "live: $it" }
            } else {
                protectionProblems(found, exact = false).map { "live: $it" }
            }
        }
        return problems
    }
}
