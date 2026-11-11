package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.registry.Owner
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.entry
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.version
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.with
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.3 Phase 5（P4-M1・P4-L5）: CODEOWNERS とメンテナーの整合、運営責任者の PR が 2 人の独立したメンテナーの承認で通ること
class CodeOwnersTest {
    private val sha = "a".repeat(40)
    private val maintainers = ToolFixtures.maintainers

    private fun review(login: String, id: Long, state: String = "APPROVED") = PullReview(login, id, state, sha)

    private fun problems(text: String, m: Maintainers = maintainers) = CodeOwners.problems(text.toByteArray(), m)

    @Test
    fun ownersMustBeExactlyTheMaintainers() {
        assertEquals(emptyList<String>(), problems("# owners\n* @dog7dog @bea @chen @dara\n"))
        assertEquals(emptyList<String>(), problems("*   @DARA @chen @Bea @dog7dog"))
        assertTrue(problems("* @dog7dog @bea @chen\n").single().contains("does not list the maintainer @dara"))
        assertTrue(problems("* @dog7dog @bea @chen @dara @mallory\n").single().contains("lists @mallory, who is not a maintainer"))
        // 運営責任者だけ（P4-M1 のデッドロックの形）は拒否する
        assertEquals(3, problems("* @dog7dog\n").size)
        assertTrue(problems("* @dog7dog @bea @chen @dara\n/packages/ @dog7dog\n").single().contains("exactly one rule"))
        assertTrue(problems("/signed/ @dog7dog @bea @chen @dara\n").single().contains("for \"*\""))
        assertTrue(problems("* @dog7dog @bea @chen @org/team\n").single().contains("teams are not used"))
        assertTrue(problems("* @dog7dog @bea @bea @chen @dara\n").single().contains("twice"))
        assertTrue(CodeOwners.problems(null, maintainers).single().contains("is missing"))
        // 立ち上げの前（メンテナー 0 人）は、運営責任者だけ
        val empty = maintainers.copy(maintainers = emptyList())
        assertEquals(emptyList<String>(), problems("* @dog7dog\n", empty))
        // リポジトリの検査に含まれる（maintainers.json だけを変えた PR は、検査で止まる）
        val changed = tree().with("maintainers.json", Maintainers.write(maintainers.copy(maintainers = maintainers.maintainers.dropLast(1))).toByteArray())
        assertTrue(LoadedRegistry.load(changed).problems.any { it.contains("lists @dara, who is not a maintainer") })
    }

    // どのメンテナーが作成者でも、作成者以外のコードオーナーが必要な承認の数以上いる（デッドロックしない）
    @Test
    fun everyAuthorHasEnoughOtherCodeOwners() {
        for (author in maintainers.maintainers) {
            val others = maintainers.maintainers.count { it.id != author.id }
            assertTrue(others >= maintainers.requiredApprovals, author.login)
        }
    }

    // 運営責任者が作成した運営の PR・公開の PR は、ほかの 2 人のメンテナーの承認で通る。作成者本人と、持ち主の承認は数えない
    @Test
    fun theLeadsPullRequestsNeedTwoIndependentMaintainers() {
        val lead = PullRequest(9, "dog7dog", 1)
        val base = tree()
        val maintenance = base.with("README.md", "changed\n".toByteArray())
        assertTrue(Approvals.check(base, maintenance, lead, sha, listOf(review("bea", 2), review("chen", 3))).ok)
        val one = Approvals.check(base, maintenance, lead, sha, listOf(review("bea", 2), review("dog7dog", 1)))
        assertTrue(one.errors.any { it.contains("dog7dog is the author and cannot approve") }, one.lines().toString())
        // 運営責任者の PR で、持ち主のメンテナーの承認は数えない
        val owned = entry("vector-math", Owner("chen", 3), 555, mapOf("1.2.0" to version("c".repeat(64))))
        val packages = Approvals.check(base, tree(listOf(owned)), lead, sha, listOf(review("bea", 2), review("chen", 3)))
        assertTrue(packages.errors.any { it.contains("1 of the 2 approvals") }, packages.lines().toString())
        assertTrue(Approvals.check(base, tree(listOf(owned)), lead, sha, listOf(review("bea", 2), review("dara", 4))).errors.none { it.contains("approvals needed") })
    }

    // P4-L5: 立ち上げの前（運営責任者が maintainers.json にいない）は、何も承認できない
    @Test
    fun nothingIsApprovedBeforeTheBootstrap() {
        val empty = maintainers.copy(maintainers = emptyList())
        val base = tree(maintainers = empty)
        val adding = tree(maintainers = empty.copy(maintainers = listOf(Maintainer("mallory", 666))))
        val report = Approvals.check(base, adding, PullRequest(5, "mallory", 666), sha, emptyList())
        assertTrue(report.errors.single().contains("the bootstrap is not finished"), report.lines().toString())
    }
}
