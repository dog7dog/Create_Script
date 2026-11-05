package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.ProjectRules

// パッケージアーカイブを得る手順（S8.2 Phase 2）。展開・インストール・依存の解決はしない（Phase 3）。得たバイト列は実行しない。
// 信頼の境界（docs/packages.md の「Trust」）:
// - expectedSha256（ロックファイルの値）があれば、それと違うものは必ず拒否する（キャッシュの中身も、ダウンロードしたものも）
// - 無ければ、HTTPS で受け取ったものの SHA-256 を記録する（TOFU: 初めて使うときに信頼する。作者の真正性の証明ではない）
// - GitHub API の digest は、GitHub がサーバーで計算した値（ダウンロードと同じ相手）。違えば拒否し、得られなければ「未検証」と示す
// - キャッシュは信頼しない。expectedSha256 があるときだけ使い、読むたびに SHA-256 を確かめる
class PackageFetcher(private val releases: GitHubReleases, private val cache: ArchiveCache?) {
    // GitHub の digest と比べた結果
    sealed interface DigestCheck {
        data object Matched : DigestCheck

        // 比べられなかった（理由）。ダウンロードしたものは SHA-256 で記録するが、GitHub の値とは照合していない
        class Unverified(val reason: String) : DigestCheck

        // キャッシュから使った（ロックファイルの SHA-256 と照合済みで、通信していない）
        data object NotNeeded : DigestCheck
    }

    enum class Origin { CACHE, DOWNLOAD }

    // 得たアーカイブ。locked は expectedSha256 と照合したか（false なら初回の TOFU）、immutable はリリースが変更できないか（分からなければ null）
    class Fetched(
        val source: String,
        val version: String,
        val bytes: ByteArray,
        val sha256: String,
        val origin: Origin,
        val locked: Boolean,
        val digest: DigestCheck,
        val immutable: Boolean?,
        val warnings: List<String>,
    )

    // offline なら通信しない（キャッシュに無ければ Offline）
    fun fetch(source: String, version: String, expectedSha256: String?, offline: Boolean = false): Fetched {
        require(expectedSha256 == null || ProjectRules.isValidSha256(expectedSha256)) { "invalid SHA-256: $expectedSha256" }
        val warnings = ArrayList<String>()
        if (expectedSha256 != null && cache != null) {
            when (val lookup = cache.read(expectedSha256)) {
                is ArchiveCache.Lookup.Hit -> return Fetched(source, version, lookup.bytes, expectedSha256, Origin.CACHE, true, DigestCheck.NotNeeded, null, warnings)
                is ArchiveCache.Lookup.Unusable -> warnings += "cache not used: ${lookup.reason}"
                ArchiveCache.Lookup.Miss -> {}
            }
        }
        if (offline) throw FetchFailure.Offline("$source $version is not in the cache" + if (expectedSha256 == null) " (and has no SHA-256 in the lock file)" else "")
        val bytes = releases.download(source, version)
        val sha256 = GitHubReleases.sha256Hex(bytes)
        if (expectedSha256 != null && sha256 != expectedSha256) throw FetchFailure.HashMismatch(expectedSha256, sha256)
        var immutable: Boolean? = null
        val digest = try {
            val info = releases.releaseInfo(source, version)
            immutable = info.immutable
            when (val github = info.digest) {
                null -> DigestCheck.Unverified("GitHub reported no digest for the asset")
                sha256 -> DigestCheck.Matched
                else -> throw FetchFailure.DigestMismatch(github, sha256)
            }
        } catch (failure: FetchFailure.DigestMismatch) {
            throw failure
        } catch (failure: FetchFailure) {
            DigestCheck.Unverified("the GitHub API could not be used: ${failure.message}")
        }
        cache?.write(sha256, bytes)?.let { warnings += "cache not written: $it" }
        return Fetched(source, version, bytes, sha256, Origin.DOWNLOAD, expectedSha256 != null, digest, immutable, warnings)
    }
}
