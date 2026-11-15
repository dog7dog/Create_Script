package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonBoolean
import com.creatrbypc.createanalytical.project.JsonInteger
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

    // リポジトリの数値 ID と持ち主（S8.3 Phase 2。レジストリの PR の検証が、名前を変えても変わらない ID を照合するために使う）。type は "User" か "Organization"
    class RepositoryInfo(val id: Long, val fullName: String, val ownerId: Long, val ownerLogin: String, val ownerType: String)

    // GET https://api.github.com/repos/持ち主/リポジトリ から読む（名前を変えたリポジトリは、GitHub がリダイレクトし、新しい full_name を返す）。
    // 応答は厳密な JSON として読み、id・full_name・owner（login・id・type）が無ければ InvalidResponse
    fun repositoryInfo(source: String): RepositoryInfo {
        require(ProjectRules.isValidSource(source)) { "invalid source: $source" }
        val (owner, repository) = source.removePrefix("github:").split('/')
        val uri = URI(endpoints.api.scheme, endpoints.api.rawAuthority, "/repos/$owner/$repository", null, null)
        val response = http.get(uri, limits.maxApiBytes, "application/vnd.github+json")
        val json = JsonParser.parse(response.body, limits.maxApiBytes.toInt()).value as? JsonObject
            ?: throw FetchFailure.InvalidResponse("cannot read the GitHub API response for $owner/$repository")
        val ownerJson = json.members["owner"] as? JsonObject ?: throw FetchFailure.InvalidResponse("the GitHub API response has no owner")
        fun long(obj: JsonObject, key: String) = (obj.members[key] as? JsonInteger)?.value?.takeIf { it > 0 } ?: throw FetchFailure.InvalidResponse("the GitHub API response has no $key")
        fun text(obj: JsonObject, key: String) = (obj.members[key] as? JsonString)?.value ?: throw FetchFailure.InvalidResponse("the GitHub API response has no $key")
        return RepositoryInfo(long(json, "id"), text(json, "full_name"), long(ownerJson, "id"), text(ownerJson, "login"), text(ownerJson, "type"))
    }

    // 最新のリリースのアセット（S8.3 Phase 3: レジストリの署名済み index）。https://github.com/持ち主/リポジトリ/releases/latest/download/<asset>。
    // repository は呼び出し側が組み込みの設定から渡す（利用者の入力からは受け取らない）。接続先・リダイレクト・大きさは、パッケージのダウンロードと同じ規則
    fun latestAsset(repository: String, asset: String, maxBytes: Long): ByteArray {
        require(ProjectRules.isValidSource("github:$repository")) { "invalid repository: $repository" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(asset)) { "invalid asset name: $asset" }
        val uri = URI(endpoints.download.scheme, endpoints.download.rawAuthority, "/$repository/releases/latest/download/$asset", null, null)
        return http.get(uri, minOf(maxBytes, limits.maxDownloadBytes), "application/octet-stream").body
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
