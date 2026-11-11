package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString

// PR のレビュー（GitHub の API の GET /repos/…/pulls/<番号>/reviews の 1 件）。state は APPROVED・CHANGES_REQUESTED・COMMENTED・DISMISSED
data class PullReview(val login: String, val id: Long, val state: String, val commitId: String)

// 共同メンテナー制の承認の確認（S8.3 Phase 2。GOVERNANCE.md）。GitHub のブランチの保護（承認の数・CODEOWNERS）に加えて、GitHub の設定では表せない条件を確かめる:
// - 承認は、メンテナー（maintainers.json の数値 ID）で、PR の作成者でなく、変更するパッケージの持ち主でもない人（利益相反）の、最新のコミットへのもの
// - メンテナーの最新のレビューが変更の要求なら止める
// - 持ち主の変更・運営の変更（maintainers.json・文書・ワークフローなど）は、運営責任者が作成者か承認者
// - 新しい版の review.reviewers は、承認した人
// - レジストリが閉じている間（メンテナーが足りない間）は、パッケージの変更・公開を止める。運営の変更は、ほかの全メンテナーの承認で進める
object Approvals {
    const val MAX_REVIEWS = 1000

    // API の応答（配列、または gh api --paginate --slurp の配列の配列）
    fun parseReviews(bytes: ByteArray): List<PullReview> {
        val root = JsonParser.parse(bytes, 8 * 1024 * 1024).value as? JsonArray ?: throw ToolFailure("cannot read the reviews (a JSON array is expected)")
        val items = root.elements.flatMap { (it as? JsonArray)?.elements ?: listOf(it) }
        if (items.size > MAX_REVIEWS) throw ToolFailure("more than $MAX_REVIEWS reviews")
        return items.map { item ->
            val obj = item as? JsonObject ?: throw ToolFailure("a review is not an object")
            val user = obj.members["user"] as? JsonObject ?: throw ToolFailure("a review has no user")
            PullReview(
                (user.members["login"] as? JsonString)?.value ?: throw ToolFailure("a review has no user login"),
                (user.members["id"] as? JsonInteger)?.value ?: throw ToolFailure("a review has no user id"),
                (obj.members["state"] as? JsonString)?.value ?: throw ToolFailure("a review has no state"),
                (obj.members["commit_id"] as? JsonString)?.value ?: "",
            )
        }
    }

    fun check(base: RegistryTree, head: RegistryTree, pr: PullRequest, headSha: String, reviews: List<PullReview>): Report {
        val report = Report()
        val before = LoadedRegistry.load(base)
        val after = LoadedRegistry.load(head)
        if (!before.ok) {
            before.problems.forEach { report.error("the base branch is invalid: $it") }
            return report
        }
        val maintainers = before.maintainers!!
        val changes = Changes.between(base, head)
        // 利益相反: 変更するパッケージの持ち主（変更の前と後）
        val conflicted = HashSet<Long>()
        val changedNames = changes.packagePaths.map { it.substringAfterLast('/').removeSuffix(".json") }
        for (name in changedNames) {
            before.entry(name)?.owners?.forEach { conflicted += it.id }
            after.entry(name)?.owners?.forEach { conflicted += it.id }
        }
        // 人ごとの最新の判断（COMMENTED は判断ではない）
        val latest = LinkedHashMap<Long, PullReview>()
        for (review in reviews) if (review.state != "COMMENTED") latest[review.id] = review
        val approvers = ArrayList<Maintainer>()
        for (review in latest.values) {
            val maintainer = maintainers.find(review.id)
            when {
                maintainer == null -> if (review.state == "APPROVED") report.notice("the approval by ${review.login} does not count: not a maintainer")
                review.state == "CHANGES_REQUESTED" -> report.error("${maintainer.login} requested changes")
                review.state != "APPROVED" -> {}
                review.id == pr.authorId -> report.error("${maintainer.login} is the author and cannot approve")
                review.id in conflicted -> report.notice("the approval by ${maintainer.login} does not count: they own a package that this pull request changes (conflict of interest)")
                review.commitId != headSha -> report.notice("the approval by ${maintainer.login} is for an older commit; approve the latest commit again")
                else -> approvers += maintainer
            }
        }
        val lead = maintainers.leadMaintainer
        val leadInvolved = lead != null && (lead.id == pr.authorId || approvers.any { it.id == lead.id })
        if (!maintainers.open) {
            if (ChangeKind.PACKAGES in changes.kinds || ChangeKind.PUBLICATION in changes.kinds) {
                report.error(maintainers.closedReason())
                return report
            }
            // 閉じている間の運営の変更（メンテナーの追加など）: 作成者以外の全メンテナーと、運営責任者
            val others = maintainers.maintainers.filter { it.id != pr.authorId }
            val missing = others.filter { other -> approvers.none { it.id == other.id } }
            if (missing.isNotEmpty()) report.error("while the registry is closed, every other maintainer must approve; missing: ${missing.joinToString(", ") { it.login }}")
            if (lead != null && !leadInvolved) report.error("the lead ${maintainers.lead} must approve")
            report.notice("approved by ${approvers.joinToString(", ") { it.login }.ifEmpty { "nobody" }} (registry closed: ${maintainers.closedReason()})")
            return report
        }
        if (approvers.size < maintainers.requiredApprovals) {
            report.error("${approvers.size} of the ${maintainers.requiredApprovals} approvals needed from maintainers other than the author and the owners of the changed packages")
        }
        val ownersChanged = changedNames.any { name -> before.entry(name)?.let { old -> after.entry(name)?.let { new -> old.owners.toSet() != new.owners.toSet() } } == true }
        if ((ChangeKind.MAINTENANCE in changes.kinds || ownersChanged) && !leadInvolved) report.error("the lead ${maintainers.lead} must approve changes of owners and maintenance changes")
        // 新しい版の審査の記録は、実際に承認した人
        for (name in changedNames) {
            val new = after.entry(name) ?: continue
            val old = before.entry(name)
            for ((version, value) in new.versions) {
                if (old?.versions?.containsKey(version) == true) continue
                for (reviewer in value.review.reviewers) {
                    if (approvers.none { it.login.equals(reviewer, ignoreCase = true) }) report.error("$name@$version: the recorded reviewer $reviewer has not approved the latest commit")
                }
            }
        }
        if (report.ok) report.notice("approved by ${approvers.joinToString(", ") { it.login }}")
        return report
    }
}
