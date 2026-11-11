package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.install.TestRegistry.pkg
import com.creatrbypc.createanalytical.install.TestRegistry.version
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.registry.RegistryStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// S8.3 Phase 3: install <名前>@<版> と、直接の指定・復元での審査の状態（Installer と RegistryClient）
class RegistryInstallTest {
    @TempDir
    lateinit var directory: Path

    private val releases = MemoryReleases()
    private lateinit var h: RegistryHarness
    private lateinit var project: Path
    private lateinit var numbers: ByteArray
    private lateinit var vector: ByteArray

    private fun sha(bytes: ByteArray) = GitHubReleases.sha256Hex(bytes)

    // プロジェクトのファイルとパッケージ（install.lock と、それを置く .createscript 自体を除く）
    private fun state(root: Path) = snapshot(root).filterKeys { it != ".createscript" && it != ".createscript/install.lock" }

    @BeforeEach
    fun setUp() {
        h = RegistryHarness(directory.resolve("cache"))
        project = createProject(directory.resolve("app"))
        numbers = Packages.library("numbers", "3.0.0")
        vector = Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to Packages.source("numbers"))))
        releases.publish(Packages.source("numbers"), "3.0.0", numbers)
        releases.publish(Packages.source("vector-math"), "1.2.0", vector)
    }

    private fun registered(
        vectorSha: String = sha(vector),
        numbersVersion: com.creatrbypc.createanalytical.project.registry.IndexVersion = version(sha(numbers), pullRequest = 7),
        vectorRemoved: Boolean = false,
    ) = mapOf(
        "numbers" to pkg(Packages.source("numbers"), mapOf("3.0.0" to numbersVersion)),
        "vector-math" to pkg(Packages.source("vector-math"), mapOf("1.2.0" to version(vectorSha, mapOf("numbers" to "3.0.0"), pullRequest = 12)), removed = if (vectorRemoved) withdrawal("malware") else null),
    )

    private fun installer(root: Path = project, cache: Path = directory.resolve("archives")) = Installer(root, PackageFetcher(releases, ArchiveCache(cache)))

    private fun lookup(offline: Boolean = false) = h.client().lookup(offline)

    private fun failure(kind: InstallFailure.Kind, contains: String, block: () -> Unit): InstallFailure {
        val failure = assertThrows(InstallFailure::class.java) { block() }
        assertEquals(kind, failure.kind, failure.message)
        assertTrue(failure.message!!.contains(contains), failure.message)
        return failure
    }

    // 1. 署名済みの index から、審査済みのパッケージと依存をインストールする
    @Test
    fun installsAReviewedPackageByName() {
        h.publish(registered(), serial = 1)
        val result = installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup())
        assertEquals(listOf("numbers", "vector-math"), result.packages.map { it.name })
        assertTrue(result.packages.all { it.installed })
        assertEquals(listOf(RegistryStatus.REVIEWED, RegistryStatus.REVIEWED), result.reviews.map { it.status })
        assertEquals("reviewed (pull request #12; index serial 1)", result.reviews.single { it.name == "vector-math" }.text)
        // マニフェスト・ロックファイルは S8.2 の形のまま（取得元は github:）
        val manifest = com.creatrbypc.createanalytical.project.Manifests.parse(Files.readAllBytes(project.resolve("createscript.json"))).manifest!!
        assertEquals(listOf(com.creatrbypc.createanalytical.project.Dependency("vector-math", "1.2.0", "github:owner/vector-math")), manifest.dependencies)
        val lock = Lockfiles.parse(Files.readAllBytes(project.resolve("createscript.lock"))).lockfile!!
        assertEquals(listOf(sha(numbers), sha(vector)), lock.packages.map { it.sha256 })
        assertTrue(Files.isRegularFile(project.resolve(".createscript/packages/vector-math/1.2.0/modules/vector_math.cscr")))
        // 再実行は何も変えない
        val again = installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup())
        assertFalse(again.manifestChanged || again.lockChanged || again.packages.any { it.installed })
    }

    // 8. 登録された SHA-256 と ZIP が違う・9. 未登録・10. yanked と removed
    @Test
    fun refusesWhatTheRegistryDoesNotAllow() {
        val before = state(project)
        // ZIP が審査済みのものと違う（リリースのアセットが差し替えられた。別の保存場所の index で試す: 同じ保存場所では、審査済みの SHA-256 の変更として index が拒否される）
        val replaced = RegistryHarness(directory.resolve("cache-replaced"), h.key)
        replaced.publish(registered(vectorSha = "0".repeat(64)), serial = 1)
        failure(InstallFailure.Kind.INVALID, "does not match the SHA-256 reviewed in the CreateScript-Registry") { installer().install(RegistryRequest("vector-math", "1.2.0"), false, replaced.client().lookup(false)) }
        // 未登録の名前・版（似た名前を示す）
        h.publish(registered(), serial = 2, generated = TestRegistry.GENERATED.plusSeconds(1))
        failure(InstallFailure.Kind.INVALID, "vectormath is not in the CreateScript-Registry (index serial 2); similar names: vector-math") {
            installer().install(RegistryRequest("vectormath", "1.2.0"), false, lookup())
        }
        failure(InstallFailure.Kind.INVALID, "has no reviewed version 9.9.9") { installer().install(RegistryRequest("vector-math", "9.9.9"), false, lookup()) }
        // 取り下げられた版・取り下げられた依存
        h.publish(registered(numbersVersion = version(sha(numbers), yanked = withdrawal("wrong results"))), serial = 3, generated = TestRegistry.GENERATED.plusSeconds(2))
        failure(InstallFailure.Kind.INVALID, "3.0.0 was yanked") { installer().install(RegistryRequest("numbers", "3.0.0"), false, lookup()) }
        failure(InstallFailure.Kind.INVALID, "depends on numbers 3.0.0, which was yanked") { installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup()) }
        // 削除されたパッケージ
        h.publish(registered(numbersVersion = version(sha(numbers), yanked = withdrawal("wrong results")), vectorRemoved = true), serial = 4, generated = TestRegistry.GENERATED.plusSeconds(3))
        failure(InstallFailure.Kind.INVALID, "was removed from the CreateScript-Registry") { installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup()) }
        // どれも、何も変えていない
        assertEquals(before, state(project))
    }

    // 拒否された index・index が無いときは、インストールを始めない（.createscript も作らない）
    @Test
    fun doesNotStartWithoutAnAcceptedIndex() {
        val before = snapshot(project)
        val index = TestRegistry.index(registered())
        h.store.publish(index, TestOnlyRegistryKey("test-only-stranger").sign(index))
        failure(InstallFailure.Kind.INVALID, "CS_REG_SIGNATURE_UNKNOWN_KEY") { installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup()) }
        failure(InstallFailure.Kind.NETWORK, "no registry index has been downloaded yet") { installer().install(RegistryRequest("vector-math", "1.2.0"), true, lookup(offline = true)) }
        val official = RegistryClient(RegistryTrust.official(), h.store, RegistryCache(directory.resolve("cache"), RegistryTrust.OFFICIAL_REPOSITORY))
        failure(InstallFailure.Kind.NETWORK, "has not been published yet") { installer().install(RegistryRequest("vector-math", "1.2.0"), false, official.lookup(false)) }
        assertEquals(before, snapshot(project))
        assertTrue(releases.downloads.isEmpty())
    }

    // 11. 依存の競合（プロジェクトの別の依存と、index の依存の版が違う）
    @Test
    fun refusesConflictsWithTheProject() {
        h.publish(registered(), serial = 1)
        val older = Packages.library("numbers", "2.0.0")
        releases.publish(Packages.source("numbers"), "2.0.0", older)
        installer().install(PackageSpec(Packages.source("numbers"), "2.0.0"), false, lookup())
        val before = state(project)
        failure(InstallFailure.Kind.INVALID, "conflicting requirements for numbers") { installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup()) }
        assertEquals(before, state(project))
        // ロックファイルに、index と違う SHA-256 が記録されている
        val other = createProject(directory.resolve("other"))
        installer(other).install(PackageSpec(Packages.source("numbers"), "3.0.0"), false, lookup())
        val lockPath = other.resolve("createscript.lock")
        val changedNumbers = Packages.library("numbers", "3.0.0", body = "fun one(): Int {\n    return 2\n}\n")
        // ロックファイルの SHA-256 が index の審査済みのものと違えば、取得する前に止まる
        Files.writeString(lockPath, Files.readString(lockPath).replace(sha(numbers), sha(changedNumbers)))
        failure(InstallFailure.Kind.INVALID, "but the CreateScript-Registry reviewed") { installer(other).install(RegistryRequest("vector-math", "1.2.0"), false, lookup()) }
    }

    // 13. オフラインのインストール（保存した index とキャッシュのアーカイブだけ。通信しない）
    @Test
    fun installsOfflineFromSavedData() {
        h.publish(registered(), serial = 1)
        installer().install(RegistryRequest("vector-math", "1.2.0"), false, lookup())
        val second = createProject(directory.resolve("second"))
        releases.network = false
        h.store.network = false
        val offline = lookup(offline = true)
        val result = installer(second).install(RegistryRequest("vector-math", "1.2.0"), true, offline)
        assertTrue(result.packages.all { it.installed && it.fetch.origin == PackageFetcher.Origin.CACHE })
        assertTrue(result.notes.single().contains("using the registry index saved before"), result.notes.toString())
        // アーカイブがキャッシュに無ければ、はっきりと失敗する
        val third = createProject(directory.resolve("third"))
        failure(InstallFailure.Kind.NETWORK, "not in the cache") { installer(third, directory.resolve("empty-archives")).install(RegistryRequest("vector-math", "1.2.0"), true, offline) }
        // 期限切れの index は使わない
        h.now = TestRegistry.GENERATED.plus(java.time.Duration.ofDays(31))
        failure(InstallFailure.Kind.NETWORK, "has expired") { installer(third).install(RegistryRequest("vector-math", "1.2.0"), true, lookup(offline = true)) }
    }

    // 16. 直接の指定（github:…）: 審査済みなら状態を示し、未審査なら警告し、審査済みの版と SHA-256 が違えば拒否する
    @Test
    fun directSourcesAreCheckedAgainstTheRegistry() {
        h.publish(registered(), serial = 1)
        val reviewed = installer().install(PackageSpec(Packages.source("numbers"), "3.0.0"), false, lookup())
        assertEquals(RegistryStatus.REVIEWED, reviewed.reviews.single().status)
        assertTrue(reviewed.warnings.none { it.contains("review") }, reviewed.warnings.toString())
        // 未審査（レジストリに無い）: 警告して入れる
        releases.publish(Packages.source("extra"), "1.0.0", Packages.library("extra", "1.0.0"))
        val unreviewed = installer().install(PackageSpec(Packages.source("extra"), "1.0.0"), false, lookup())
        assertTrue(unreviewed.warnings.any { it == "extra 1.0.0 from github:owner/extra is not reviewed by the CreateScript-Registry (index serial 1)" }, unreviewed.warnings.toString())
        // 審査済みの版と SHA-256 が違う: 拒否する
        val other = createProject(directory.resolve("other"))
        h.publish(registered(numbersVersion = version("0".repeat(64))), serial = 1)
        val mismatch = RegistryHarness(directory.resolve("cache2"), h.key).also { it.store.publish(h.store.index!!, h.store.signature) }
        val before = state(other)
        failure(InstallFailure.Kind.INVALID, "but the CreateScript-Registry (index serial 1) reviewed ${"0".repeat(64)}") {
            installer(other).install(PackageSpec(Packages.source("numbers"), "3.0.0"), false, mismatch.client().lookup(false))
        }
        assertEquals(before, state(other))
        // レジストリを確かめられない: 「未審査」とは言わず「確かめられない」
        h.store.network = false
        val third = createProject(directory.resolve("third"))
        val unknown = installer(third).install(PackageSpec(Packages.source("extra"), "1.0.0"), false, RegistryHarness(directory.resolve("cache3"), h.key).also { it.store.network = false }.client().lookup(false))
        assertTrue(unknown.warnings.any { it.startsWith("the review status of github:owner/extra@v1.0.0 could not be checked: the registry index could not be downloaded") }, unknown.warnings.toString())
        assertTrue(unknown.warnings.none { it.contains("is not reviewed") })
        // registry を渡さない（S8.2 の API）は、今までどおり
        assertTrue(installer(createProject(directory.resolve("fourth"))).install(PackageSpec(Packages.source("extra"), "1.0.0"), false).reviews.isEmpty())
    }

    // 17. S8.2 のロックファイルの復元: yanked は警告して復元し、removed は拒否する。オフラインで index が無くても復元できる
    @Test
    fun restoresS82LockFiles() {
        // S8.2 の手順（レジストリ無し）で作ったロックファイル
        installer().install(PackageSpec(Packages.source("vector-math"), "1.2.0"), false)
        val lock = Files.readAllBytes(project.resolve("createscript.lock"))
        fun reset() {
            project.resolve(".createscript").toFile().deleteRecursively()
        }
        // 審査済み: 状態を示して復元する
        h.publish(registered(), serial = 1)
        reset()
        val restored = installer().install(null, false, lookup())
        assertEquals(listOf(RegistryStatus.REVIEWED, RegistryStatus.REVIEWED), restored.reviews.map { it.status })
        assertTrue(Files.readAllBytes(project.resolve("createscript.lock")).contentEquals(lock))
        // 取り下げ: 警告して復元する
        h.publish(registered(numbersVersion = version(sha(numbers), yanked = withdrawal("wrong results"))), serial = 2, generated = TestRegistry.GENERATED.plusSeconds(1))
        reset()
        val yanked = installer().install(null, false, lookup())
        assertTrue(yanked.warnings.any { it.contains("numbers 3.0.0 from github:owner/numbers was yanked") && it.contains("restored from createscript.lock") }, yanked.warnings.toString())
        assertTrue(yanked.packages.all { it.installed })
        // 削除: 復元しない（何も変えない）
        h.publish(registered(numbersVersion = version(sha(numbers), yanked = withdrawal("wrong results")), vectorRemoved = true), serial = 3, generated = TestRegistry.GENERATED.plusSeconds(2))
        reset()
        val before = state(project)
        failure(InstallFailure.Kind.INVALID, "was removed from the CreateScript-Registry") { installer().install(null, false, lookup()) }
        assertEquals(before, state(project))
        // オフラインで使える index が無い: 審査の状態を確かめられないと知らせて、ロックファイルから復元する（S8.2 の機能を保つ）
        val offlineProject = directory.resolve("offline")
        Files.createDirectories(offlineProject)
        Files.copy(project.resolve("createscript.json"), offlineProject.resolve("createscript.json"))
        Files.write(offlineProject.resolve("createscript.lock"), lock)
        releases.network = false
        val noIndex = RegistryHarness(directory.resolve("empty-cache"), h.key).client().lookup(true)
        val offline = installer(offlineProject).install(null, true, noIndex)
        assertTrue(offline.packages.all { it.installed })
        assertTrue(offline.warnings.any { it.startsWith("the review status of the packages could not be checked: no registry index has been downloaded yet") }, offline.warnings.toString())
    }
}
