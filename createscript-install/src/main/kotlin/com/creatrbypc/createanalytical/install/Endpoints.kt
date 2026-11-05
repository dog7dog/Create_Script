package com.creatrbypc.createanalytical.install

import java.net.URI

// 接続先（S8.2 Phase 2）。本番は GITHUB だけで、コマンドのオプション・環境変数・設定ファイルで変える手段は作らない（ダウンロード先を差し替えられないようにする）。
// テストだけが、internal のコンストラクターでローカルのサーバーを指す設定を作る。
// - download: リリースのアセットの URL の始まり（https://github.com）。api: GitHub API の始まり（https://api.github.com）
// - hosts: 接続してよいホスト（リダイレクトの先を含む）。小文字で、完全に一致するものだけ
// - ports: 使ってよいポート（本番は 443 だけ。URL にポートが無いときは、その scheme の既定のポートとみなす）
// - https: HTTPS だけを使うか。ipLiterals: ホストに IP アドレスを直接書いた URL を許すか（本番は許さない）
class Endpoints internal constructor(
    val download: URI,
    val api: URI,
    internal val hosts: Set<String>,
    internal val ports: Set<Int>,
    internal val https: Boolean,
    internal val ipLiterals: Boolean,
) {
    companion object {
        // github.com（リリースのページ・アセットの URL）、api.github.com（リリースの情報・digest）、アセットの配信先（2026-11 の時点で
        // release-assets.githubusercontent.com へリダイレクトされることを確認済み。以前の objects.githubusercontent.com も許す）
        val GITHUB = Endpoints(
            download = URI("https://github.com"),
            api = URI("https://api.github.com"),
            hosts = setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            ports = setOf(443),
            https = true,
            ipLiterals = false,
        )
    }

    // 接続してよい URL か（よければ null、だめなら理由）。最初の要求とすべてのリダイレクトの先に使う
    fun problem(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return "the URL has no scheme"
        if (https && scheme != "https") return "only https is allowed"
        if (!https && scheme != "http" && scheme != "https") return "only http and https are allowed"
        if (uri.rawUserInfo != null) return "user information in the URL is not allowed"
        if (uri.rawAuthority != null && '@' in uri.rawAuthority) return "user information in the URL is not allowed"
        val host = uri.host ?: return "the URL has no host"
        val normalized = host.lowercase()
        if (!ipLiterals && isIpLiteral(normalized)) return "IP addresses are not allowed"
        if (normalized !in hosts) return "the host $normalized is not allowed"
        if (effectivePort(uri) !in ports) return "the port ${effectivePort(uri)} is not allowed"
        if (uri.rawFragment != null) return "fragments are not allowed"
        return null
    }

    // トークンを送ってよい URL か（API と同じ scheme・ホスト・ポートだけ。リダイレクトの先・ダウンロードには送らない）
    internal fun mayCarryToken(uri: URI): Boolean =
        problem(uri) == null && uri.scheme.lowercase() == api.scheme && uri.host.lowercase() == api.host.lowercase() && effectivePort(uri) == effectivePort(api)

    private fun effectivePort(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme?.lowercase() == "https") 443 else 80

    private fun isIpLiteral(host: String): Boolean =
        host.startsWith("[") || host.contains(':') || Regex("[0-9]+(\\.[0-9]+){0,3}").matches(host) || Regex("0x[0-9a-f]+(\\.[0-9a-fx]+)*").matches(host)
}

// GitHub のトークン（任意。S8.2 Phase 2）。toString では値を出さない（ログ・例外の文に入らないようにする）
class GitHubToken(internal val value: String) {
    init {
        require(value.isNotBlank() && value.none { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E }) { "invalid token" }
    }

    override fun toString() = "GitHubToken(****)"

    companion object {
        // 環境変数 CREATESCRIPT_GITHUB_TOKEN（無ければ null。ほかの場所・ファイルからは読まない）
        const val ENVIRONMENT_VARIABLE = "CREATESCRIPT_GITHUB_TOKEN"

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): GitHubToken? =
            environment[ENVIRONMENT_VARIABLE]?.takeIf { it.isNotBlank() }?.let(::GitHubToken)
    }
}
