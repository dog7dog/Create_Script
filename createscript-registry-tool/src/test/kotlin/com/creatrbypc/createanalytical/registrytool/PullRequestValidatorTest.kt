package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.registry.Owner
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import com.creatrbypc.createanalytical.project.registry.Withdrawal
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.alice
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.entry
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.library
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.version
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.with
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

// S8.3 Phase 2: レジストリの PR の自動検証（GitHub はメモリーの中の代わり）
class PullRequestValidatorTest {
    private val github = FakeGitHub()
    private val aliceOwner = Owner("alice", 1001)

    // 既に登録済みの numbers 3.0.0（持ち主 olga）
    private val numbersZip = library("numbers", "3.0.0")
    private val numbers = entry("numbers", Owner("olga", 2001), 900, mapOf("3.0.0" to version(GitHubReleases.sha256Hex(numbersZip), pullRequest = 3)))

    // alice が登録する vector-math 1.2.0（numbers 3.0.0 に依存）
    private val vectorZip = library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to "github:olga/numbers")))
    private val vectorSha = GitHubReleases.sha256Hex(vectorZip)
    private val vector = entry("vector-math", aliceOwner, 555, mapOf("1.2.0" to version(vectorSha, mapOf("numbers" to "3.0.0"))))

    private val base = tree(listOf(numbers))

    init {
        github.repository("github:olga/numbers", 900, "olga", 2001)
        github.publish("github:olga/numbers", "3.0.0", numbersZip)
        github.repository("github:alice/vector-math", 555, "alice", 1001)
        github.publish("github:alice/vector-math", "1.2.0", vectorZip)
    }

    private fun validate(head: RegistryTree, pr: PullRequest = alice, base: RegistryTree = this.base) =
        PullRequestValidator(github, ToolFixtures.today, ToolFixtures.now).validate(base, head, pr, null)

    private fun errors(head: RegistryTree, pr: PullRequest = alice, base: RegistryTree = this.base): List<String> {
        val report = validate(head, pr, base)
        assertTrue(!report.ok, "expected errors, got ${report.lines()}")
        return report.errors
    }

    private fun assertError(head: RegistryTree, fragment: String, pr: PullRequest = alice, base: RegistryTree = this.base) {
        val errors = errors(head, pr, base)
        assertTrue(errors.any { fragment in it }, "no error with '$fragment' in $errors")
    }

    @Test
    fun acceptsANormalRegistration() {
        val report = validate(tree(listOf(numbers, vector)))
        assertEquals(emptyList<String>(), report.errors)
        // GitHub から読んだのは、新しい版の分だけ（既存の numbers は読み直さない）
        assertEquals(listOf("repository github:alice/vector-math", "release github:alice/vector-math@1.2.0", "download github:alice/vector-math@1.2.0"), github.calls)
    }

    @Test
    fun refusesSha256Problems() {
        // 登録の SHA-256 が、GitHub の digest ともダウンロードしたものとも違う
        val other = "f".repeat(64)
        assertError(tree(listOf(numbers, vector.copy(versions = mapOf("1.2.0" to version(other, mapOf("numbers" to "3.0.0")))))), "but GitHub reports $vectorSha")
        // GitHub の digest は登録と同じだが、ダウンロードしたものが違う（途中での差し替え）
        github.publish("github:alice/vector-math", "1.2.0", vectorZip.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, digest = vectorSha)
        assertError(tree(listOf(numbers, vector)), "the downloaded createscript-package.zip has the SHA-256")
        // digest が無い・変更できない Release でない
        github.publish("github:alice/vector-math", "1.2.0", vectorZip, digest = null)
        assertError(tree(listOf(numbers, vector)), "reports no digest")
        github.publish("github:alice/vector-math", "1.2.0", vectorZip, immutable = false)
        assertError(tree(listOf(numbers, vector)), "is not immutable")
        github.publish("github:alice/vector-math", "1.2.0", vectorZip, immutable = null)
        assertError(tree(listOf(numbers, vector)), "is not immutable")
        // Release が無い
        github.assets.clear()
        assertError(tree(listOf(numbers, vector)), "cannot read the release v1.2.0")
    }

    @Test
    fun refusesInvalidArchives() {
        fun publishAndCheck(bytes: ByteArray, fragment: String) {
            github.publish("github:alice/vector-math", "1.2.0", bytes)
            val sha = GitHubReleases.sha256Hex(bytes)
            assertError(tree(listOf(numbers, vector.copy(versions = mapOf("1.2.0" to version(sha, mapOf("numbers" to "3.0.0")))))), fragment)
        }
        publishAndCheck("not a zip".toByteArray(), "CS_PKG_ARCHIVE")
        publishAndCheck(library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to "github:olga/numbers")), manifestName = "vector-maths"), "CS_PKG_ARCHIVE_NAME_MISMATCH")
        publishAndCheck(library("vector-math", "1.2.1", mapOf("numbers" to ("3.0.0" to "github:olga/numbers"))), "CS_PKG_ARCHIVE_VERSION_MISMATCH")
        // 中身は正しく読めるが、createscript pack の正規形でない ZIP（最初の項目のローカルヘッダーの時刻だけを変えたもの）
        val retimed = vectorZip.copyOf().also { it[10] = 1 }
        assertTrue(com.creatrbypc.createanalytical.project.PackageArchives.read(retimed, "vector-math", "1.2.0").archive != null, "the reader accepts it")
        publishAndCheck(retimed, "is not in the canonical form that createscript pack writes")
        // アーカイブの依存が、登録と違う・取得元がレジストリと違う
        publishAndCheck(library("vector-math", "1.2.0"), "differ from the registration")
        publishAndCheck(library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to "github:mallory/numbers"))), "but the registry has it from github:olga/numbers")
    }

    @Test
    fun refusesUnregisteredDependencies() {
        val unknown = vector.copy(versions = mapOf("1.2.0" to version(vectorSha, mapOf("numbers" to "9.9.9"))))
        assertError(tree(listOf(numbers, unknown)), "CS_REG_UNKNOWN_DEPENDENCY")
        val missing = vector.copy(versions = mapOf("1.2.0" to version(vectorSha, mapOf("colors" to "1.0.0"))))
        assertError(tree(listOf(numbers, missing)), "CS_REG_UNKNOWN_DEPENDENCY")
    }

    @Test
    fun refusesReservedAndSimilarNames() {
        assertError(tree(listOf(numbers, entry("create-script", aliceOwner, 556, mapOf("1.0.0" to version("a".repeat(64)))))), "CS_REG_RESERVED_PACKAGE_NAME")
        assertError(tree(listOf(numbers, entry("numb3rs", aliceOwner, 556, mapOf("1.0.0" to version("a".repeat(64)))).copy(name = "num-bers", source = "github:alice/num-bers"))), "CS_REG_SIMILAR_NAME")
        // 1 文字違いはエラーではなく、審査する人への知らせ
        val zip = library("numberz", "1.0.0")
        github.repository("github:alice/numberz", 557, "alice", 1001)
        github.publish("github:alice/numberz", "1.0.0", zip)
        val report = validate(tree(listOf(numbers, entry("numberz", aliceOwner, 557, mapOf("1.0.0" to version(GitHubReleases.sha256Hex(zip)))))))
        assertEquals(emptyList<String>(), report.errors)
        assertTrue(report.notices.any { it.startsWith("numberz is similar to existing packages: numbers") }, report.notices.toString())
    }

    @Test
    fun checksGitHubNumericIds() {
        // リポジトリの ID が違う（同じ名前で作り直したリポジトリ）
        github.repository("github:alice/vector-math", 999, "alice", 1001)
        assertError(tree(listOf(numbers, vector)), "repositoryId is 555, but GitHub says github:alice/vector-math has the ID 999")
        // 名前が変わったリポジトリ
        github.repository("github:alice/vector-math", 555, "alice", 1001, fullName = "alice/vector-mathematics")
        assertError(tree(listOf(numbers, vector)), "is now called alice/vector-mathematics")
        // リポジトリの持ち主（ユーザー）が、owners にいない
        github.repository("github:alice/vector-math", 555, "alice", 4242)
        assertError(tree(listOf(numbers, vector)), "the owner of the repository (alice, 4242) must be one of the owners")
        // 組織のリポジトリは、審査する人に確認を求める
        github.repository("github:alice/vector-math", 555, "alice-org", 5000, ownerType = "Organization")
        val report = validate(tree(listOf(numbers, vector)))
        assertTrue(report.ok, report.lines().toString())
        assertTrue(report.notices.any { "belongs to the organization alice-org" in it })
    }

    @Test
    fun checksTheAuthorsPermission() {
        // 新しい登録: 作成者が持ち主でない（同じ名前でも、ID が違えば別人）
        assertError(tree(listOf(numbers, vector)), "is not one of its owners", pr = PullRequest(7, "alice", 9999))
        // 更新: 持ち主でない人は版を足せない
        val zip = library("numbers", "3.1.0")
        github.publish("github:olga/numbers", "3.1.0", zip)
        val update = numbers.copy(versions = numbers.versions + ("3.1.0" to version(GitHubReleases.sha256Hex(zip))))
        assertError(tree(listOf(update)), "only its owners (olga) can add versions")
        assertEquals(emptyList<String>(), validate(tree(listOf(update)), pr = PullRequest(7, "olga", 2001)).errors)
        // 取り下げは、持ち主かメンテナー。ほかの人は不可
        val yanked = numbers.copy(versions = mapOf("3.0.0" to numbers.versions.getValue("3.0.0").copy(yanked = Withdrawal("broken", LocalDate.of(2026, 11, 20)))))
        assertError(tree(listOf(yanked)), "only its owners or maintainers")
        assertTrue(validate(tree(listOf(yanked)), pr = PullRequest(7, "bea", 2)).ok)
        // 持ち主の変更は、持ち主だけ（運営責任者の承認が要ることを知らせる）
        val newOwners = numbers.copy(owners = numbers.owners + aliceOwner)
        assertError(tree(listOf(newOwners)), "change its owners", pr = PullRequest(7, "bea", 2))
        assertTrue(validate(tree(listOf(newOwners)), pr = PullRequest(7, "olga", 2001)).notices.any { "the lead must approve" in it })
    }

    @Test
    fun reviewedVersionsCannotChange() {
        val changed = numbers.copy(versions = mapOf("3.0.0" to version("e".repeat(64), pullRequest = 3)))
        assertError(tree(listOf(changed)), "CS_REG_SHA256_CHANGED", pr = PullRequest(7, "olga", 2001))
        val dependencies = numbers.copy(versions = mapOf("3.0.0" to numbers.versions.getValue("3.0.0").copy(dependencies = mapOf("colors" to "1.0.0"))))
        assertError(tree(listOf(dependencies)), "CS_REG_DEPENDENCIES_CHANGED", pr = PullRequest(7, "olga", 2001))
        val review = numbers.copy(versions = mapOf("3.0.0" to numbers.versions.getValue("3.0.0").copy(review = numbers.versions.getValue("3.0.0").review.copy(reviewers = listOf("bea", "dara")))))
        assertError(tree(listOf(review)), "the review record of a reviewed version cannot change", pr = PullRequest(7, "olga", 2001))
        assertError(tree(emptyList()), "CS_REG_PACKAGE_DISAPPEARED", pr = PullRequest(7, "olga", 2001))
        val repository = numbers.copy(repositoryId = 901)
        assertError(tree(listOf(repository)), "CS_REG_REPOSITORY_CHANGED", pr = PullRequest(7, "olga", 2001))
    }

    @Test
    fun checksTheReviewRecordOfNewVersions() {
        fun withReview(pullRequest: Long = 7, reviewers: List<String> = listOf("bea", "chen")) =
            tree(listOf(numbers, vector.copy(versions = mapOf("1.2.0" to version(vectorSha, mapOf("numbers" to "3.0.0"), pullRequest, reviewers)))))
        assertError(withReview(pullRequest = 8), "must be this pull request (#7)")
        assertError(withReview(reviewers = listOf("bea")), "at least 2 reviewers")
        assertError(withReview(reviewers = listOf("bea", "mallory")), "the reviewer mallory is not a maintainer")
        assertError(withReview(), "the author of the pull request cannot be a reviewer", pr = PullRequest(7, "bea", 2))
        // メンテナーが持ち主のパッケージは、そのメンテナーが審査できない
        val owned = vector.copy(owners = listOf(aliceOwner, Owner("chen", 3)))
        assertError(tree(listOf(numbers, owned)), "chen owns the package and cannot review it")
    }

    @Test
    fun closedRegistryAndMixedChanges() {
        val two = ToolFixtures.maintainers.copy(maintainers = listOf(ToolFixtures.lead, ToolFixtures.maintainerB))
        assertError(tree(listOf(numbers, vector), maintainers = two), "the registry is closed: it has 2 of the 3 maintainers", base = tree(listOf(numbers), maintainers = two))
        // パッケージと運営のファイルを 1 つの PR で変える
        assertError(tree(listOf(numbers, vector)).with("README.md", "changed\n".toByteArray()), "must change only one kind of files")
        // 運営の PR は、運営責任者の承認が要ることを知らせる
        val maintenance = validate(base.with("POLICY.md", "policy\n".toByteArray()))
        assertTrue(maintenance.ok, maintenance.lines().toString())
        assertTrue(maintenance.notices.any { it.startsWith("maintenance pull request") })
        // 予約名を足して既存のパッケージと衝突させる運営の PR
        val reserved = base.with("reserved-names.json", com.creatrbypc.createanalytical.project.registry.RegistryFiles.writeReserved(com.creatrbypc.createanalytical.project.registry.ReservedNames(mapOf("numbers" to "taken"))).toByteArray())
        assertError(reserved, "CS_REG_RESERVED_PACKAGE_NAME")
    }

    @Test
    fun removalAndYankRules() {
        val head = tree(listOf(numbers, vector))
        val base2 = head
        // numbers を削除するなら、numbers に依存する版は同じ PR で取り下げる
        val removed = numbers.copy(removed = Withdrawal("malicious code", LocalDate.of(2026, 11, 20)))
        assertError(tree(listOf(removed, vector)), "CS_REG_DEPENDENCY_ON_REMOVED", pr = PullRequest(8, "bea", 2), base = base2)
        val yankedVector = vector.copy(versions = mapOf("1.2.0" to vector.versions.getValue("1.2.0").copy(yanked = Withdrawal("depends on a removed package", LocalDate.of(2026, 11, 20)))))
        val report = validate(tree(listOf(removed, yankedVector)), pr = PullRequest(8, "bea", 2), base = base2)
        assertTrue(report.ok, report.lines().toString())
        assertTrue(report.notices.any { it.startsWith("numbers is removed") })
        // 新しい版を、取り下げた状態で登録することはできない
        val bornYanked = vector.copy(versions = mapOf("1.2.0" to vector.versions.getValue("1.2.0").copy(yanked = Withdrawal("x", LocalDate.of(2026, 11, 20)))))
        assertError(tree(listOf(numbers, bornYanked)), "a new version cannot be yanked")
    }

    @Test
    fun onlyDataFilesAreRead() {
        // パッケージのディレクトリに、パッケージのファイル以外を置く
        assertError(tree(listOf(numbers)).with("packages/n/run.sh", "echo hi\n".toByteArray()), "only packages/<first letter>/<name>.json files")
        // 名前と置き場所が合わないファイル
        assertError(tree(listOf(numbers)).with("packages/x/numbers.json", com.creatrbypc.createanalytical.project.registry.RegistryFiles.writeEntry(numbers).toByteArray()), "CS_REG_ENTRY_PATH_MISMATCH")
        assertEquals("packages/v/vector-math.json", RegistryRules.entryPath(vector.name))
    }
}
