package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.PackageArchives
import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

// S8.2 Phase 2: GitHub Releases からの取得（ローカルのサーバーで GitHub を再現する）、SHA-256・digest の照合、キャッシュ
class FetchTest {
    @TempDir
    lateinit var directory: Path

    private val download = LocalServer()
    private val api = LocalServer()
    private val waits = ArrayList<Duration>()
    private val token = GitHubToken("ghp_TESTSECRET0123456789")

    @AfterEach
    fun stop() {
        download.close()
        api.close()
    }

    private val archive = PackageArchives.write(
        mapOf(
            "createscript.json" to """{"schemaVersion": 2, "kind": "library", "name": "vector-math", "version": "1.2.0", "modules": ["vector_math"]}""".toByteArray(),
            "modules/vector_math.cscr" to "fun one(): Int {\n    return 1\n}\n".toByteArray(),
        ),
    )
    private val sha = GitHubReleases.sha256Hex(archive)
    private val assetPath = "/owner/vector-math/releases/download/v1.2.0/createscript-package.zip"
    private val apiPath = "/repos/owner/vector-math/releases/tags/v1.2.0"

    private fun releaseJson(digest: String? = "sha256:$sha", immutable: Boolean? = true, name: String = "createscript-package.zip"): ByteArray {
        val digestField = digest?.let { "\"digest\": \"$it\", " } ?: ""
        val immutableField = immutable?.let { "\"immutable\": $it, " } ?: ""
        return """{"tag_name": "v1.2.0", $immutableField"assets": [{"name": "$name", ${digestField}"size": ${archive.size}, "id": 1}]}""".toByteArray()
    }

    private fun limits(maxDownloadBytes: Long = 1024 * 1024, totalTimeout: Duration = Duration.ofSeconds(5), maxAttempts: Int = 4) =
        NetworkLimits(maxDownloadBytes = maxDownloadBytes, maxApiBytes = 64 * 1024, connectTimeout = Duration.ofSeconds(2), totalTimeout = totalTimeout, maxAttempts = maxAttempts)

    private fun releases(limits: NetworkLimits = limits(), withToken: Boolean = true) =
        GitHubReleases(testEndpoints(download, api), limits, if (withToken) token else null, { waits.add(it) }, "createscript-test/1")

    private fun servePackage(json: ByteArray = releaseJson()) {
        download.route(assetPath, LocalServer.redirect("${download.base}/assets/42?signature=SECRETQUERY"))
        download.route("/assets/42", LocalServer.ok(archive))
        api.route(apiPath, LocalServer.ok(json))
    }

    private fun fetcher(limits: NetworkLimits = limits(), cache: ArchiveCache? = ArchiveCache(directory.resolve("cache"))) = PackageFetcher(releases(limits), cache)

    // ---- 取得と照合 ----

    @Test
    fun downloadsVerifiesAndCaches() {
        servePackage()
        val fetched = fetcher().fetch("github:owner/vector-math", "1.2.0", null)
        assertArrayEquals(archive, fetched.bytes)
        assertEquals(sha, fetched.sha256)
        assertEquals(PackageFetcher.Origin.DOWNLOAD, fetched.origin)
        assertFalse(fetched.locked)
        assertEquals(PackageFetcher.DigestCheck.Matched, fetched.digest)
        assertEquals(true, fetched.immutable)
        assertEquals(emptyList<String>(), fetched.warnings)
        // ダウンロードとリダイレクトの先にはトークンを送らず、API にだけ送る。どれにも User-Agent を付ける
        assertNull(download.requestsTo(assetPath).single().authorization)
        assertNull(download.requestsTo("/assets/42").single().authorization)
        assertEquals("Bearer ${token.value}", api.requestsTo(apiPath).single().authorization)
        assertTrue((download.requests + api.requests).all { it.userAgent == "createscript-test/1" })
        // キャッシュに入り、ロックファイルの SHA-256 があれば通信せずに使う
        val before = download.requests.size + api.requests.size
        val cached = fetcher().fetch("github:owner/vector-math", "1.2.0", sha)
        assertEquals(PackageFetcher.Origin.CACHE, cached.origin)
        assertTrue(cached.locked)
        assertEquals(PackageFetcher.DigestCheck.NotNeeded, cached.digest)
        assertEquals(before, download.requests.size + api.requests.size)
        // オフラインでもキャッシュから使える。キャッシュに無ければ Offline
        assertArrayEquals(archive, fetcher().fetch("github:owner/vector-math", "1.2.0", sha, offline = true).bytes)
        assertThrows(FetchFailure.Offline::class.java) { fetcher().fetch("github:owner/vector-math", "1.2.0", "0".repeat(64), offline = true) }
        assertThrows(FetchFailure.Offline::class.java) { fetcher().fetch("github:owner/vector-math", "1.2.0", null, offline = true) }
    }

    // 既知の SHA-256（ロックファイル）と違えば、必ず拒否する（GitHub の digest が合っていても）。拒否したものはキャッシュに入れない
    @Test
    fun rejectsAHashMismatch() {
        servePackage()
        val expected = "f".repeat(64)
        val failure = assertThrows(FetchFailure.HashMismatch::class.java) { fetcher().fetch("github:owner/vector-math", "1.2.0", expected) }
        assertEquals(expected, failure.expected)
        assertEquals(sha, failure.actual)
        assertFalse(Files.exists(directory.resolve("cache/archives/sha256/$sha.zip")))
        assertTrue(fetcher().fetch("github:owner/vector-math", "1.2.0", sha).locked)
    }

    @Test
    fun comparesTheGitHubDigest() {
        servePackage(releaseJson(digest = "sha256:" + "0".repeat(64)))
        assertThrows(FetchFailure.DigestMismatch::class.java) { fetcher(cache = null).fetch("github:owner/vector-math", "1.2.0", null) }
        // digest を得られない場合は「未検証」と示して続ける（理由つき）。immutable の有無も報告する
        val unverified = mapOf(
            "no digest field" to { servePackage(releaseJson(digest = null, immutable = false)) },
            "API not found" to { servePackage(); api.route(apiPath, LocalServer.status(404)) },
            "asset not in the release" to { servePackage(releaseJson(name = "other.zip")) },
            "invalid JSON" to { servePackage("{\"assets\": [".toByteArray()) },
            "duplicate keys" to { servePackage("""{"assets": [], "assets": []}""".toByteArray()) },
            "rate limited" to { servePackage(); api.route(apiPath, LocalServer.status(403, mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "${System.currentTimeMillis() / 1000 + 3600}"))) },
        )
        for ((label, setUp) in unverified) {
            setUp()
            val fetched = fetcher(cache = null).fetch("github:owner/vector-math", "1.2.0", null)
            assertTrue(fetched.digest is PackageFetcher.DigestCheck.Unverified, label)
            assertArrayEquals(archive, fetched.bytes, label)
        }
        servePackage(releaseJson(digest = null, immutable = false))
        assertEquals(false, fetcher(cache = null).fetch("github:owner/vector-math", "1.2.0", null).immutable)
        // digest の形が正しくない
        servePackage(releaseJson(digest = "md5:abc"))
        assertTrue(fetcher(cache = null).fetch("github:owner/vector-math", "1.2.0", null).digest is PackageFetcher.DigestCheck.Unverified)
    }

    // ---- 接続先・リダイレクト ----

    @Test
    fun checksRedirects() {
        val cases = mapOf<String, (HttpExchange) -> Unit>(
            "another host" to LocalServer.redirect("http://localhost:${download.port}/assets/42"),
            "another port" to LocalServer.redirect("http://127.0.0.1:1/assets/42"),
            "user information" to LocalServer.redirect("http://user:pass@127.0.0.1:${download.port}/assets/42"),
            "another scheme" to LocalServer.redirect("ftp://127.0.0.1:${download.port}/assets/42"),
            "invalid location" to LocalServer.redirect("http://[bad"),
        )
        for ((label, redirect) in cases) {
            download.route(assetPath, redirect)
            val failure = assertThrows(FetchFailure::class.java, { releases().download("github:owner/vector-math", "1.2.0") }, label)
            assertTrue(failure is FetchFailure.Forbidden, "$label: $failure")
        }
        // リダイレクトの回数の上限・Location の無いリダイレクト
        download.route(assetPath, LocalServer.redirect(assetPath))
        assertTrue(assertThrows(FetchFailure.Network::class.java) { releases().download("github:owner/vector-math", "1.2.0") }.message!!.contains("too many redirects"))
        download.route(assetPath, LocalServer.status(302))
        assertThrows(FetchFailure.InvalidResponse::class.java) { releases().download("github:owner/vector-math", "1.2.0") }
        // 相対の Location はたどる
        download.route(assetPath, LocalServer.redirect("/assets/42"))
        download.route("/assets/42", LocalServer.ok(archive))
        assertArrayEquals(archive, releases().download("github:owner/vector-math", "1.2.0"))
    }

    // ---- 大きさ・時間 ----

    @Test
    fun limitsTheResponseSize() {
        val small = limits(maxDownloadBytes = 1000)
        // Content-Length が上限を超える（受け取らずに止める）
        download.route(assetPath, LocalServer.ok(ByteArray(1001)))
        assertEquals(1000, assertThrows(FetchFailure.TooLarge::class.java) { releases(small).download("github:owner/vector-math", "1.2.0") }.limit)
        // Content-Length の無い（チャンク形式の）応答も、受け取った量で止める
        download.route(assetPath) { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out -> repeat(100) { out.write(ByteArray(100)) } }
        }
        assertThrows(FetchFailure.TooLarge::class.java) { releases(small).download("github:owner/vector-math", "1.2.0") }
        // ちょうど上限は受け取る
        download.route(assetPath, LocalServer.ok(ByteArray(1000)))
        assertEquals(1000, releases(small).download("github:owner/vector-math", "1.2.0").size)
        // 既定の上限は 32 MiB
        assertEquals(32L * 1024 * 1024, NetworkLimits().maxDownloadBytes)
    }

    @Test
    fun timesOut() {
        val quick = limits(totalTimeout = Duration.ofMillis(300), maxAttempts = 2)
        // 応答の前に止まるサーバー
        download.route(assetPath, LocalServer.delayed(Duration.ofSeconds(3), LocalServer.ok(archive)))
        val start = System.nanoTime()
        assertTrue(assertThrows(FetchFailure.Network::class.java) { releases(quick).download("github:owner/vector-math", "1.2.0") }.message!!.contains("timed out"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_500)
        assertEquals(2, download.requestsTo(assetPath).size)
        // 本文の途中で止まるサーバー（ヘッダーの後で待つ）も、試行の時間の上限で止める
        download.route(assetPath) { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(ByteArray(10))
            exchange.responseBody.flush()
            Thread.sleep(3000)
        }
        assertThrows(FetchFailure.Network::class.java) { releases(quick).download("github:owner/vector-math", "1.2.0") }
    }

    // ---- 再試行・レート制限 ----

    @Test
    fun retries() {
        download.sequence(assetPath, LocalServer.status(500), LocalServer.status(503), LocalServer.ok(archive))
        assertArrayEquals(archive, releases().download("github:owner/vector-math", "1.2.0"))
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits)
        // 4 回とも失敗すれば諦める（待つのは 3 回）
        waits.clear()
        download.route(assetPath, LocalServer.status(502))
        assertEquals(502, assertThrows(FetchFailure.Status::class.java) { releases().download("github:owner/vector-math", "1.2.0") }.status)
        assertEquals(listOf(1L, 2L, 4L), waits.map { it.seconds })
        // 429 は Retry-After に従う
        waits.clear()
        download.sequence(assetPath, LocalServer.status(429, mapOf("Retry-After" to "7")), LocalServer.ok(archive))
        assertArrayEquals(archive, releases().download("github:owner/vector-math", "1.2.0"))
        assertEquals(listOf(Duration.ofSeconds(7)), waits)
        // レート制限の解除が遠い（上限の 60 秒を超える）なら、待たずに諦める
        waits.clear()
        download.route(assetPath, LocalServer.status(403, mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "${System.currentTimeMillis() / 1000 + 3600}")))
        assertTrue(assertThrows(FetchFailure.RateLimited::class.java) { releases().download("github:owner/vector-math", "1.2.0") }.message!!.contains(GitHubToken.ENVIRONMENT_VARIABLE))
        assertEquals(emptyList<Duration>(), waits)
        // 404・401・ふつうの 403 は再試行しない
        for (code in listOf(404, 401, 403)) {
            download.requests.clear()
            download.route(assetPath, LocalServer.status(code))
            assertThrows(FetchFailure::class.java) { releases().download("github:owner/vector-math", "1.2.0") }
            assertEquals(1, download.requestsTo(assetPath).size, "$code")
        }
        // 接続できない（閉じたポート）は再試行してから諦める
        waits.clear()
        val closed = LocalServer().also { it.close() }
        val unreachable = GitHubReleases(Endpoints(closed.base, closed.base, setOf("127.0.0.1"), setOf(closed.port), https = false, ipLiterals = true), limits(), null, { waits.add(it) }, "t")
        assertThrows(FetchFailure.Network::class.java) { unreachable.download("github:owner/vector-math", "1.2.0") }
        assertEquals(3, waits.size)
    }

    // トークン・署名付きの問い合わせを、失敗のメッセージに出さない
    @Test
    fun keepsSecretsOutOfMessages() {
        assertEquals("GitHubToken(****)", token.toString())
        download.route(assetPath, LocalServer.redirect("${download.base}/assets/9?signature=SECRETQUERY"))
        download.route("/assets/9", LocalServer.status(410))
        api.route(apiPath, LocalServer.status(500))
        val failures = listOf(
            assertThrows(FetchFailure::class.java) { releases().download("github:owner/vector-math", "1.2.0") },
            assertThrows(FetchFailure::class.java) { releases(limits(maxAttempts = 1)).releaseInfo("github:owner/vector-math", "1.2.0") },
        )
        for (failure in failures) {
            assertFalse(failure.message!!.contains("SECRETQUERY"), failure.message)
            assertFalse(failure.message!!.contains(token.value), failure.message)
        }
        assertEquals(null, GitHubToken.fromEnvironment(mapOf("OTHER" to "x")))
        assertEquals(token.value, GitHubToken.fromEnvironment(mapOf(GitHubToken.ENVIRONMENT_VARIABLE to token.value))!!.value)
        assertThrows(IllegalArgumentException::class.java) { GitHubToken("a b") }
    }

    // ---- キャッシュ ----

    @Test
    fun cacheIsNotTrusted() {
        servePackage()
        val cacheRoot = directory.resolve("cache")
        fetcher().fetch("github:owner/vector-math", "1.2.0", null)
        val entry = cacheRoot.resolve("archives/sha256/$sha.zip")
        assertTrue(Files.isRegularFile(entry))
        // 作ったディレクトリは所有者だけ（0700）
        for (dir in listOf(cacheRoot, cacheRoot.resolve("archives"), cacheRoot.resolve("archives/sha256"))) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)), "$dir")
        }
        // 中身が書き換わった項目は使わず、ダウンロードし直す（壊れた項目は消さず、上書きもしない）
        Files.write(entry, archive.copyOf().also { it[10] = (it[10] + 1).toByte() })
        val requestsBefore = download.requests.size
        val again = fetcher().fetch("github:owner/vector-math", "1.2.0", sha)
        assertEquals(PackageFetcher.Origin.DOWNLOAD, again.origin)
        assertTrue(again.warnings.any { it.contains("does not match its SHA-256") }, "${again.warnings}")
        assertTrue(again.warnings.any { it.contains("was not replaced") }, "${again.warnings}")
        assertTrue(download.requests.size > requestsBefore)
        assertFalse(archive.contentEquals(Files.readAllBytes(entry)))
        // 項目がシンボリックリンクなら、たどらずに使わない（リンクもその先も変えない）
        Files.delete(entry)
        val outside = Files.write(directory.resolve("outside.zip"), archive)
        Files.createSymbolicLink(entry, outside)
        val linked = ArchiveCache(cacheRoot).read(sha)
        assertTrue(linked is ArchiveCache.Lookup.Unusable && linked.reason.contains("not a regular file"), "$linked")
        assertTrue(ArchiveCache(cacheRoot).write(sha, archive)!!.contains("was not replaced"))
        assertTrue(Files.isSymbolicLink(entry))
        // キャッシュの中のディレクトリがシンボリックリンクなら、キャッシュを使わない
        val other = directory.resolve("cache2")
        Files.createDirectories(other.resolve("archives"))
        Files.createSymbolicLink(other.resolve("archives/sha256"), Files.createDirectories(directory.resolve("elsewhere")))
        assertTrue(ArchiveCache(other).read(sha) is ArchiveCache.Lookup.Unusable)
        assertTrue(ArchiveCache(other).write(sha, archive)!!.contains("symbolic link"))
        assertEquals(0, Files.list(directory.resolve("elsewhere")).use { it.count() })
        // キャッシュの置き場所そのものは、リンクでもよい（利用者の設定）
        val alias = Files.createSymbolicLink(directory.resolve("cache-alias"), Files.createDirectories(directory.resolve("cache-real")))
        assertEquals(null, ArchiveCache(alias).write(sha, archive))
        assertTrue(ArchiveCache(alias).read(sha) is ArchiveCache.Lookup.Hit)
        // 名前と中身の違うものは書けない
        assertThrows(IllegalArgumentException::class.java) { ArchiveCache(cacheRoot).write("0".repeat(64), archive) }
    }

    @Test
    fun defaultCacheLocations() {
        assertEquals(Path.of("/u/Library/Caches/CreateScript"), ArchiveCache.defaultRoot(emptyMap(), "Mac OS X", "/u"))
        assertEquals(Path.of("/u/.cache/createscript"), ArchiveCache.defaultRoot(emptyMap(), "Linux", "/u"))
        assertEquals(Path.of("/x/createscript"), ArchiveCache.defaultRoot(mapOf("XDG_CACHE_HOME" to "/x"), "Linux", "/u"))
        assertEquals(Path.of("/c"), ArchiveCache.defaultRoot(mapOf("CREATESCRIPT_CACHE_DIR" to "/c"), "Linux", "/u"))
    }

    // アセットの URL は、名前から決まる（API を使わない）
    @Test
    fun assetUrls() {
        val production = GitHubReleases()
        assertEquals(URI("https://github.com/owner/vector-math/releases/download/v1.2.0-beta.1+build.5/createscript-package.zip"), production.assetUri("github:owner/vector-math", "1.2.0-beta.1+build.5"))
        assertThrows(IllegalArgumentException::class.java) { production.assetUri("github:owner/../x", "1.2.0") }
        assertThrows(IllegalArgumentException::class.java) { production.assetUri("github:owner/x", "1.2") }
        production.close()
    }
}
