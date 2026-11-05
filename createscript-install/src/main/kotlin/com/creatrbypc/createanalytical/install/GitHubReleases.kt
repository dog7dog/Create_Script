package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonBoolean
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.ProjectRules
import java.net.URI
import java.security.MessageDigest

// GitHub Releases のパッケージアーカイブ（S8.2 Phase 2）。source は github:持ち主/リポジトリ、タグは v<version>、アセットは固定の createscript-package.zip。
// アセットの URL は名前から決まるため、ダウンロードには API を使わない（レート制限を受けない）。API は、アセットの digest とリリースの immutable を読むときだけ使う。
// GitHub が自動で作るソースの ZIP・tar.gz は使わない。ダウンロードしたものは、バイト列として返すだけで、展開も実行もしない
// リリースのアセットを得る相手（S8.2 Phase 3）。本番は GitHubReleases だけ。テストはメモリーの中の実装を渡す（照合は PackageFetcher が行う）
interface ReleaseStore {
    fun download(source: String, version: String): ByteArray

    fun releaseInfo(source: String, version: String): GitHubReleases.ReleaseInfo
}

class GitHubReleases internal constructor(
    private val endpoints: Endpoints,
    private val limits: NetworkLimits,
    token: GitHubToken?,
    sleeper: Sleeper,
    userAgent: String,
) : ReleaseStore, AutoCloseable {
    constructor(token: GitHubToken? = null, userAgent: String = "createscript") : this(Endpoints.GITHUB, NetworkLimits(), token, Sleeper.REAL, userAgent)

    private val http = HttpGetter(endpoints, limits, token, sleeper, userAgent)

    override fun close() = http.close()

    // リリースの情報（API で読めたもの）。digest は "sha256:" の後の小文字の 16 進数 64 桁（GitHub が値を返さなければ null）、immutable はリリースが変更できないか（無ければ null）
    class ReleaseInfo(val digest: String?, val immutable: Boolean?)

    private class Coordinates(val owner: String, val repository: String, val tag: String)

    private fun coordinates(source: String, version: String): Coordinates {
        require(ProjectRules.isValidSource(source)) { "invalid source: $source" }
        require(ProjectRules.isValidVersion(version)) { "invalid version: $version" }
        val (owner, repository) = source.removePrefix("github:").split('/')
        return Coordinates(owner, repository, "v$version")
    }

    // https://github.com/持ち主/リポジトリ/releases/download/v<version>/createscript-package.zip
    fun assetUri(source: String, version: String): URI {
        val c = coordinates(source, version)
        return URI(endpoints.download.scheme, endpoints.download.rawAuthority, "/${c.owner}/${c.repository}/releases/download/${c.tag}/${PackageArchives.FILE_NAME}", null, null)
    }

    // アセットをダウンロードする（最大 limits.maxDownloadBytes バイト）
    override fun download(source: String, version: String): ByteArray = http.get(assetUri(source, version), limits.maxDownloadBytes, "application/octet-stream").body

    // GET https://api.github.com/repos/持ち主/リポジトリ/releases/tags/v<version> から、アセットの digest とリリースの immutable を読む。
    // 応答は厳密な JSON として読む（重複キー・整数でない数値などがあれば InvalidResponse）。アセットが無ければ NotFound
    override fun releaseInfo(source: String, version: String): ReleaseInfo {
        val c = coordinates(source, version)
        val uri = URI(endpoints.api.scheme, endpoints.api.rawAuthority, "/repos/${c.owner}/${c.repository}/releases/tags/${c.tag}", null, null)
        val response = http.get(uri, limits.maxApiBytes, "application/vnd.github+json")
        val json = JsonParser.parse(response.body, limits.maxApiBytes.toInt()).value as? JsonObject
            ?: throw FetchFailure.InvalidResponse("cannot read the GitHub API response for ${c.tag}")
        val assets = json.members["assets"] as? JsonArray ?: throw FetchFailure.InvalidResponse("the GitHub API response has no assets")
        val asset = assets.elements.filterIsInstance<JsonObject>().singleOrNull { (it.members["name"] as? JsonString)?.value == PackageArchives.FILE_NAME }
            ?: throw FetchFailure.NotFound(uri)
        val digest = when (val value = asset.members["digest"]) {
            null -> null
            is JsonString -> value.value.removePrefix("sha256:").takeIf { value.value.startsWith("sha256:") && ProjectRules.isValidSha256(it) }
                ?: throw FetchFailure.InvalidResponse("unexpected digest form in the GitHub API response")
            else -> null
        }
        val immutable = (json.members["immutable"] as? JsonBoolean)?.value
        return ReleaseInfo(digest, immutable)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
