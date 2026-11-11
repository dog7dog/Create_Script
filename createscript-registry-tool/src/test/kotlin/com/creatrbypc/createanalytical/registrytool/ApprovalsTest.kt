package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.registry.Owner
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.entry
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.version
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.with
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.3 Phase 2: 共同メンテナー制の承認（GitHub の設定では表せない条件）
class ApprovalsTest {
    private val sha = "a".repeat(40)
    private val alice = Owner("alice", 1001)
    private val vector = entry("vector-math", alice, 555, mapOf("1.2.0" to version("c".repeat(64))))
    private val base = tree()
    private val head = tree(listOf(vector))
    private val pr = ToolFixtures.alice

    private fun review(login: String, id: Long, state: String = "APPROVED", commit: String = sha) = PullReview(login, id, state, commit)

    private val bea = review("bea", 2)
    private val chen = review("chen", 3)

    private fun check(reviews: List<PullReview>, head: RegistryTree = this.head, base: RegistryTree = this.base, pr: PullRequest = this.pr) = Approvals.check(base, head, pr, sha, reviews)

    private fun assertRefused(reviews: List<PullReview>, fragment: String, head: RegistryTree = this.head, base: RegistryTree = this.base, pr: PullRequest = this.pr) {
        val report = check(reviews, head, base, pr)
        assertTrue(report.errors.any { fragment in it }, "no error with '$fragment': ${report.lines()}")
    }

    @Test
    fun twoMaintainersOtherThanTheAuthor() {
        val report = check(listOf(bea, chen))
        assertEquals(emptyList<String>(), report.errors)
        assertTrue(report.notices.contains("approved by bea, chen"))
        assertRefused(listOf(bea), "1 of the 2 approvals")
        assertRefused(emptyList(), "0 of the 2 approvals")
        // メンテナーでない人の承認は数えない（同じ名前でも ID が違えば別人）
        assertRefused(listOf(bea, review("chen", 3333)), "1 of the 2 approvals")
        // 古いコミットへの承認は数えない
        assertRefused(listOf(bea, review("chen", 3, commit = "b".repeat(40))), "1 of the 2 approvals")
        // 最新の判断が数える: 承認の後に変更を求めた・承認を取り消された
        assertRefused(listOf(bea, chen, review("chen", 3, "CHANGES_REQUESTED")), "chen requested changes")
        assertRefused(listOf(bea, chen, review("chen", 3, "DISMISSED")), "1 of the 2 approvals")
        // コメントだけのレビューは、判断を変えない
        assertTrue(check(listOf(bea, chen, review("chen", 3, "COMMENTED"))).ok)
        // ほかのメンテナーが変更を求めていれば止める
        assertRefused(listOf(bea, chen, review("dara", 4, "CHANGES_REQUESTED")), "dara requested changes")
    }

    @Test
    fun authorAndOwnersDoNotCount() {
        // 作成者がメンテナーでも、自分の PR は承認できない
        assertRefused(listOf(bea, chen), "bea is the author and cannot approve", pr = PullRequest(7, "bea", 2))
        // 利益相反: 変更するパッケージの持ち主は承認者に数えない
        val owned = tree(listOf(vector.copy(owners = listOf(alice, Owner("chen", 3)))))
        val report = check(listOf(bea, chen), head = owned)
        assertTrue(report.errors.any { "1 of the 2 approvals" in it }, report.lines().toString())
        assertTrue(report.notices.any { "chen does not count: they own a package" in it })
    }

    @Test
    fun recordedReviewersMustHaveApproved() {
        val recorded = tree(listOf(vector.copy(versions = mapOf("1.2.0" to version("c".repeat(64), reviewers = listOf("bea", "dara"))))))
        assertRefused(listOf(bea, chen), "the recorded reviewer dara has not approved", head = recorded)
        assertTrue(check(listOf(bea, chen, review("dara", 4)), head = recorded).ok)
    }

    @Test
    fun leadForMaintenanceAndOwnerChanges() {
        val maintenance = base.with("POLICY.md", "policy\n".toByteArray())
        assertRefused(listOf(bea, chen), "the lead dog7dog must approve", head = maintenance)
        assertTrue(check(listOf(bea, review("dog7dog", 1)), head = maintenance).ok)
        assertTrue(check(listOf(bea, chen), head = maintenance, pr = PullRequest(9, "dog7dog", 1)).ok)
        // 持ち主の変更
        val before = tree(listOf(vector))
        val after = tree(listOf(vector.copy(owners = listOf(alice, Owner("bob", 1002)))))
        assertRefused(listOf(bea, chen), "the lead dog7dog must approve", head = after, base = before)
        assertTrue(check(listOf(bea, review("dog7dog", 1)), head = after, base = before).ok)
    }

    @Test
    fun closedRegistry() {
        val two = ToolFixtures.maintainers.copy(maintainers = listOf(ToolFixtures.lead, ToolFixtures.maintainerB))
        val closed = tree(maintainers = two)
        assertRefused(listOf(bea, chen), "the registry is closed", head = tree(listOf(vector), maintainers = two), base = closed)
        // 閉じている間の運営の変更（メンテナーの追加）は、作成者以外の全メンテナーと運営責任者
        val added = tree(maintainers = two.copy(maintainers = two.maintainers + ToolFixtures.maintainerC))
        assertRefused(emptyList(), "every other maintainer must approve; missing: dog7dog, bea", head = added, base = closed, pr = PullRequest(9, "chen", 3))
        assertTrue(check(listOf(review("dog7dog", 1), bea), head = added, base = closed, pr = PullRequest(9, "chen", 3)).ok)
        assertTrue(check(listOf(bea), head = added, base = closed, pr = PullRequest(9, "dog7dog", 1)).ok)
    }

    @Test
    fun readsGitHubReviews() {
        val json = """[[{"id": 1, "user": {"login": "bea", "id": 2, "type": "User"}, "state": "APPROVED", "commit_id": "$sha", "body": ""}], [{"id": 2, "user": {"login": "chen", "id": 3}, "state": "COMMENTED", "commit_id": "$sha"}]]"""
        assertEquals(listOf(review("bea", 2), review("chen", 3, "COMMENTED")), Approvals.parseReviews(json.toByteArray()))
        assertEquals(listOf(review("bea", 2)), Approvals.parseReviews("""[{"user": {"login": "bea", "id": 2}, "state": "APPROVED", "commit_id": "$sha"}]""".toByteArray()))
        assertThrows(ToolFailure::class.java) { Approvals.parseReviews("""{"message": "Not Found"}""".toByteArray()) }
        assertThrows(ToolFailure::class.java) { Approvals.parseReviews("""[{"state": "APPROVED"}]""".toByteArray()) }
    }
}
