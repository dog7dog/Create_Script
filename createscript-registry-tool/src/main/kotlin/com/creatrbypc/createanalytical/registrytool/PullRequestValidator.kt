package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.registry.IndexHistory
import com.creatrbypc.createanalytical.project.registry.PackageEntry
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import java.time.Instant
import java.time.LocalDate

// GitHub から読むもの（本番は LiveGitHub。テストはメモリーの中の実装）。どれも読み取りだけで、トークンは使わなくてもよい
interface RegistryGitHub {
    fun repository(source: String): GitHubReleases.RepositoryInfo

    fun release(source: String, version: String): GitHubReleases.ReleaseInfo

    fun download(source: String, version: String): ByteArray
}

// S8.2 の GitHubReleases（接続先は GitHub に固定・大きさと時間の上限・リダイレクトの確認）を使う
class LiveGitHub(private val releases: GitHubReleases) : RegistryGitHub {
    override fun repository(source: String) = releases.repositoryInfo(source)

    override fun release(source: String, version: String) = releases.releaseInfo(source, version)

    override fun download(source: String, version: String) = releases.download(source, version)
}

// PR の番号と作成者（GitHub のイベントから。作成者は数値 ID で照合する）
data class PullRequest(val number: Long, val authorLogin: String, val authorId: Long)

// レジストリの PR の自動検証（S8.3 Phase 2。CONTRIBUTING.md・POLICY.md）。PR のファイルはデータとしてだけ読む（PR のコードを実行しない）。
// base は PR の向かう先（main）の作業ツリー、head は PR の作業ツリー
class PullRequestValidator(private val github: RegistryGitHub, private val today: LocalDate, private val now: Instant) {
    fun validate(base: RegistryTree, head: RegistryTree, pr: PullRequest, published: PublishedIndex?, allowTestKeys: Boolean = false, fromFork: Boolean = false): Report {
        val report = Report()
        val before = LoadedRegistry.load(base)
        if (!before.ok) {
            before.problems.forEach { report.error("the base branch is invalid: $it") }
            return report
        }
        val after = LoadedRegistry.load(head)
        after.problems.forEach(report::error)
        val changes = Changes.between(base, head)
        if (changes.paths.isEmpty()) {
            report.notice("the pull request changes nothing")
            return report
        }
        // fork の PR は、ワークフロー・CODEOWNERS（.github/）を変えられない（S8.3 Phase 5、P4-H1 の多層の対策）。運営の変更は、このリポジトリのブランチから出す
        if (fromFork && changes.paths.any { it.startsWith(".github/") }) {
            report.error("a pull request from a fork cannot change .github/ (workflows and CODEOWNERS); maintenance changes are opened from a branch of the registry repository")
        }
        if (changes.kinds.size > 1) {
            report.error("a pull request must change only one kind of files: packages/ (registration and updates), signed/ (publication) or the other files (maintenance); this one changes ${changes.kinds.sorted().joinToString(", ") { it.name.lowercase() }}")
        }
        if (!after.ok) return report
        // パッケージの間の規則（名前・予約名・似た名前・依存・循環・削除）は、どの種類の PR でも確かめる（予約名の追加が既存のパッケージと衝突するなど）
        RegistryIndexes.build(after.config!!, after.reserved!!, after.entries, 1, now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).diagnostics.forEach { report.error(it.format("packages")) }
        val maintainers = before.maintainers!!
        when {
            ChangeKind.PACKAGES in changes.kinds -> {
                if (!maintainers.open) report.error(maintainers.closedReason())
                packages(before, after, changes, pr, report)
            }
            ChangeKind.PUBLICATION in changes.kinds -> {
                report.addAll(IndexPublishing.publishCheck(after, after.signedIndex, after.signedSignature, published, now, allowTestKeys))
            }
            else -> {
                report.notice("maintenance pull request (${changes.paths.joinToString(", ")}): it needs ${maintainers.requiredApprovals} maintainers other than the author, and the lead as the author or one of them (check-approvals)")
                val newMaintainers = after.maintainers!!
                if (newMaintainers != maintainers) report.notice("maintainers change: ${maintainers.maintainers.map { it.login }} -> ${newMaintainers.maintainers.map { it.login }}; open: ${newMaintainers.open}")
                if (LoadedRegistry.KEYS in changes.paths) report.notice("trusted keys change: this only changes the check before publishing; createscript has its own keys built in")
            }
        }
        return report
    }

    private fun packages(before: LoadedRegistry, after: LoadedRegistry, changes: Changes, pr: PullRequest, report: Report) {
        // 審査済みの SHA-256・依存は変えられない。パッケージ・版は消せない（取り下げ・削除で表す）
        IndexHistory.compareEntries(before.entries, after.entries).forEach { report.error(it.format("packages")) }
        val maintainers = before.maintainers!!
        val changedNames = changes.packagePaths.mapNotNull { path -> path.substringAfterLast('/').removeSuffix(".json").takeIf { RegistryRules.entryPath(it) == path } }.toSortedSet()
        for (name in changedNames) {
            val old = before.entry(name)
            val new = after.entry(name) ?: continue
            val owners = (old?.owners.orEmpty() + new.owners)
            val newVersions = if (old == null) new.versions.keys else new.versions.keys - old.versions.keys
            if (old == null) {
                // 新しい登録: 作成者は持ち主の 1 人。似た名前は審査する人に知らせる
                if (new.owners.none { it.id == pr.authorId }) report.error("$name: the pull request author ${pr.authorLogin} (${pr.authorId}) is not one of its owners")
                RegistryRules.similarNames(name, before.entries.map { it.name }).takeIf { it.isNotEmpty() }?.let { report.notice("$name is similar to existing packages: ${it.joinToString(", ")}; check that it is not meant to be confused with them") }
                repository(new, report)
            } else {
                val ownersChanged = old.owners.toSet() != new.owners.toSet()
                val sourceChanged = old.source != new.source
                val isOwner = old.owners.any { it.id == pr.authorId }
                val isMaintainer = maintainers.find(pr.authorId) != null
                if ((newVersions.isNotEmpty() || ownersChanged || sourceChanged) && !isOwner) {
                    report.error("$name: only its owners (${old.owners.joinToString(", ") { it.login }}) can add versions or change its owners or source; ${pr.authorLogin} is not one of them")
                } else if (!isOwner && !isMaintainer) {
                    report.error("$name: only its owners or maintainers can change its description, license, yanked or removed state")
                }
                if (ownersChanged) report.notice("$name: owners change ${old.owners.map { it.login }} -> ${new.owners.map { it.login }}; the lead must approve it")
                if (sourceChanged) {
                    report.notice("$name: source change ${old.source} -> ${new.source} (the repository ID must stay ${old.repositoryId})")
                    repository(new, report)
                }
                for ((version, value) in old.versions) {
                    val now = new.versions[version] ?: continue
                    if (now.review != value.review) report.error("$name@$version: the review record of a reviewed version cannot change")
                    if (now.yanked != null && value.yanked == null) report.notice("$name@$version is yanked: ${now.yanked!!.reason}")
                    if (now.yanked == null && value.yanked != null) report.notice("$name@$version is no longer yanked; the maintainers must agree")
                }
                if (new.removed != null && old.removed == null) report.notice("$name is removed: ${new.removed!!.reason}")
                if (new.removed == null && old.removed != null) report.notice("$name is no longer removed; the maintainers must agree")
            }
            for (version in newVersions.sorted()) newVersion(new, version, after, maintainers, owners.map { it.id }.toSet(), pr, report)
        }
    }

    // リポジトリの数値 ID と、今の名前・持ち主
    private fun repository(entry: PackageEntry, report: Report) {
        val info = try {
            github.repository(entry.source)
        } catch (failure: FetchFailure) {
            report.error("${entry.name}: cannot read the repository ${entry.source} from GitHub: ${failure.message}")
            return
        }
        if (info.id != entry.repositoryId) report.error("${entry.name}: repositoryId is ${entry.repositoryId}, but GitHub says ${entry.source} has the ID ${info.id}")
        if (!info.fullName.equals(entry.source.removePrefix("github:"), ignoreCase = true)) {
            report.error("${entry.name}: ${entry.source} is now called ${info.fullName} on GitHub; use the current name")
        }
        when (info.ownerType) {
            "User" -> if (entry.owners.none { it.id == info.ownerId }) report.error("${entry.name}: the owner of the repository (${info.ownerLogin}, ${info.ownerId}) must be one of the owners")
            "Organization" -> report.notice("${entry.name}: the repository belongs to the organization ${info.ownerLogin}; reviewers must confirm that the owners may publish for it")
            else -> report.error("${entry.name}: unexpected owner type ${info.ownerType}")
        }
    }

    private fun newVersion(entry: PackageEntry, version: String, after: LoadedRegistry, maintainers: Maintainers, owners: Set<Long>, pr: PullRequest, report: Report) {
        val label = "${entry.name}@$version"
        val value = entry.versions.getValue(version)
        // 審査の記録: この PR の番号・メンテナー（作成者・パッケージの持ち主は除く）・今日以前の日付
        if (value.review.pullRequest != pr.number) report.error("$label: review.pullRequest must be this pull request (#${pr.number}), not #${value.review.pullRequest}")
        if (value.review.date.isAfter(today)) report.error("$label: the review date ${value.review.date} is in the future")
        for (reviewer in value.review.reviewers) {
            val maintainer = maintainers.findLogin(reviewer)
            when {
                maintainer == null -> report.error("$label: the reviewer $reviewer is not a maintainer")
                maintainer.id == pr.authorId -> report.error("$label: the author of the pull request cannot be a reviewer")
                maintainer.id in owners -> report.error("$label: $reviewer owns the package and cannot review it (conflict of interest)")
            }
        }
        if (value.review.reviewers.size < maintainers.requiredApprovals) report.error("$label: at least ${maintainers.requiredApprovals} reviewers are needed")
        if (value.yanked != null) report.error("$label: a new version cannot be yanked")
        // GitHub の Release: 変更できない・digest が登録の SHA-256 と同じ・ダウンロードしたものの SHA-256 も同じ・正規形のパッケージアーカイブ
        val info = try {
            github.release(entry.source, version)
        } catch (failure: FetchFailure) {
            report.error("$label: cannot read the release v$version of ${entry.source}: ${failure.message}")
            return
        }
        if (info.immutable != true) report.error("$label: the release v$version is not immutable (enable immutable releases before publishing it)")
        when (info.digest) {
            null -> report.error("$label: GitHub reports no digest for createscript-package.zip, so it cannot be checked")
            value.sha256 -> {}
            else -> report.error("$label: sha256 is ${value.sha256}, but GitHub reports ${info.digest} for createscript-package.zip")
        }
        val bytes = try {
            github.download(entry.source, version)
        } catch (failure: FetchFailure) {
            report.error("$label: cannot download createscript-package.zip: ${failure.message}")
            return
        }
        val actual = GitHubReleases.sha256Hex(bytes)
        if (actual != value.sha256) {
            report.error("$label: the downloaded createscript-package.zip has the SHA-256 $actual, not ${value.sha256}")
            return
        }
        val read = PackageArchives.read(bytes, entry.name, version)
        val archive = read.archive
        if (archive == null) {
            read.diagnostics.forEach { report.error("$label: createscript-package.zip: ${it.diagnostic.format(it.path ?: "createscript-package.zip")}") }
            return
        }
        if (!PackageArchives.isCanonical(bytes, archive)) report.error("$label: createscript-package.zip is not in the canonical form that createscript pack writes")
        // 依存: アーカイブのマニフェストと登録が同じで、取得元はレジストリのそのパッケージの取得元
        val declared = archive.manifest.dependencies.associate { it.name to it.version }
        if (declared != value.dependencies) report.error("$label: the dependencies in createscript-package.zip ($declared) differ from the registration (${value.dependencies})")
        for (dependency in archive.manifest.dependencies) {
            val registered = after.entry(dependency.name) ?: continue
            if (!RegistryRules.sameSource(registered.source, dependency.source)) {
                report.error("$label: the dependency ${dependency.name} comes from ${dependency.source} in createscript-package.zip, but the registry has it from ${registered.source}")
            }
        }
    }
}
