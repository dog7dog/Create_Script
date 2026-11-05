package com.creatrbypc.createanalytical.install

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// 通信の上限（S8.2 Phase 2）。テストは小さな値を渡す
data class NetworkLimits(
    // アセットの大きさの上限（実際に受け取ったバイト数で数える。Content-Length がこれを超えれば受け取らずに止める）
    val maxDownloadBytes: Long = 32L * 1024 * 1024,
    // API の応答の大きさの上限
    val maxApiBytes: Long = 4L * 1024 * 1024,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    // 1 回の試行（リダイレクトをたどって本文を受け取り終えるまで）の時間の上限
    val totalTimeout: Duration = Duration.ofSeconds(60),
    val maxRedirects: Int = 5,
    // 試行の回数（最初の 1 回 + 再試行 3 回）と、再試行の前に待つ時間の始まり（1 秒・2 秒・4 秒）、待つ時間の上限
    val maxAttempts: Int = 4,
    val firstBackoff: Duration = Duration.ofSeconds(1),
    val maxWait: Duration = Duration.ofSeconds(60),
)

// 待つ処理（テストでは実際には待たずに記録する）
fun interface Sleeper {
    fun sleep(duration: Duration)

    companion object {
        val REAL = Sleeper { Thread.sleep(it.toMillis()) }
    }
}

// 取得の失敗（S8.2 Phase 2）。メッセージにはトークンを入れない。network は通信の問題（コマンドの終了コード 69）、それ以外は内容の問題
sealed class FetchFailure(message: String, val network: Boolean) : Exception(message) {
    class Network(message: String) : FetchFailure(message, true)
    class RateLimited(message: String) : FetchFailure(message, true)
    class NotFound(val uri: URI) : FetchFailure("not found: $uri", false)
    class Status(val status: Int, val uri: URI) : FetchFailure("HTTP $status from $uri", true)
    class Forbidden(val uri: String, reason: String) : FetchFailure("refused to connect to $uri: $reason", false)
    class TooLarge(val limit: Long) : FetchFailure("the response is larger than $limit bytes", false)
    class HashMismatch(val expected: String, val actual: String) : FetchFailure("SHA-256 mismatch: expected $expected, got $actual", false)
    class DigestMismatch(val github: String, val actual: String) : FetchFailure("the SHA-256 that GitHub reports ($github) differs from the downloaded file ($actual)", false)
    class Offline(message: String) : FetchFailure(message, true)
    class InvalidResponse(message: String) : FetchFailure(message, false)
}

// 受け取った応答（本文は上限までに収まったもの）
internal class Response(val status: Int, val body: ByteArray, val headers: HttpHeaders, val uri: URI)

// HTTP の GET（S8.2 Phase 2）。リダイレクトは自分でたどり、最初の URL とすべての先を Endpoints で確かめる。トークンは API のホストへの要求にだけ付ける。
// 本文は上限のバイト数まで実際に受け取った量で数え、1 回の試行の時間の上限（本文の受け取りを含む）を CompletableFuture で守る（止まった接続でも待ち続けない）。
// 通信の失敗・5xx・429・レート制限の 403 は、待ってから再試行する（Retry-After・X-RateLimit-Reset に従い、待つ時間が上限を超えるなら諦める）
internal class HttpGetter(
    private val endpoints: Endpoints,
    private val limits: NetworkLimits,
    private val token: GitHubToken?,
    private val sleeper: Sleeper,
    private val userAgent: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(limits.connectTimeout)
        .proxy(HttpClient.Builder.NO_PROXY)
        .build()

    override fun close() = client.close()

    private class Retry(val wait: Duration?, val failure: FetchFailure) : Exception(null, null, false, false)

    fun get(uri: URI, maxBytes: Long, accept: String): Response {
        var attempt = 1
        var backoff = limits.firstBackoff
        while (true) {
            val retry = try {
                return once(uri, maxBytes, accept)
            } catch (retry: Retry) {
                retry
            }
            if (attempt >= limits.maxAttempts) throw retry.failure
            val wait = retry.wait ?: backoff
            if (wait > limits.maxWait) throw retry.failure
            sleeper.sleep(wait)
            backoff = backoff.multipliedBy(2)
            attempt++
        }
    }

    private fun once(start: URI, maxBytes: Long, accept: String): Response {
        val deadline = clock() + limits.totalTimeout.toMillis()
        var current = start
        var redirects = 0
        while (true) {
            endpoints.problem(current)?.let { throw FetchFailure.Forbidden(current.toString(), it) }
            val builder = HttpRequest.newBuilder(current).GET().timeout(limits.totalTimeout)
                .header("User-Agent", userAgent).header("Accept", accept)
            if (token != null && endpoints.mayCarryToken(current)) {
                builder.header("Authorization", "Bearer ${token.value}").header("X-GitHub-Api-Version", "2022-11-28")
            }
            val future = client.sendAsync(builder.build()) { info -> CappedBody(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1)) }
            val remaining = deadline - clock()
            val response = try {
                if (remaining <= 0) throw TimeoutException()
                future.get(remaining, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                future.cancel(true)
                throw Retry(null, FetchFailure.Network("timed out after ${limits.totalTimeout.toMillis()} ms: ${shown(current)}"))
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is FetchFailure) throw cause
                if (cause is java.net.http.HttpTimeoutException) throw Retry(null, FetchFailure.Network("timed out after ${limits.totalTimeout.toMillis()} ms: ${shown(current)}"))
                throw Retry(null, FetchFailure.Network("connection failed: ${shown(current)} (${cause?.javaClass?.simpleName ?: "error"})"))
            } catch (interrupted: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw FetchFailure.Network("interrupted")
            }
            val status = response.statusCode()
            if (status in REDIRECTS) {
                if (++redirects > limits.maxRedirects) throw FetchFailure.Network("too many redirects from ${shown(start)}")
                val location = response.headers().firstValue("Location").orElseThrow { FetchFailure.InvalidResponse("a redirect without a location from ${shown(current)}") }
                current = try {
                    current.resolve(URI(location))
                } catch (error: Exception) {
                    throw FetchFailure.Forbidden("(invalid location)", "the redirect location is not a valid URL")
                }
                continue
            }
            when {
                status == 200 -> return Response(status, response.body(), response.headers(), current)
                status == 404 -> throw FetchFailure.NotFound(URI(shown(current)))
                status == 429 || (status == 403 && response.headers().firstValue("X-RateLimit-Remaining").orElse("") == "0") ->
                    throw Retry(rateLimitWait(response.headers()), FetchFailure.RateLimited("rate limited by ${URI(shown(current)).host} (HTTP $status); try again later or set ${GitHubToken.ENVIRONMENT_VARIABLE}"))
                status >= 500 -> throw Retry(null, FetchFailure.Status(status, URI(shown(current))))
                else -> throw FetchFailure.Status(status, URI(shown(current)))
            }
        }
    }

    // 待つ時間: Retry-After（秒）か、X-RateLimit-Reset（UNIX 時刻の秒）までの時間。どちらも無ければ null（通常の待ち方）
    private fun rateLimitWait(headers: HttpHeaders): Duration? {
        headers.firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.let { return Duration.ofSeconds(maxOf(0, it)) }
        headers.firstValue("X-RateLimit-Reset").orElse(null)?.trim()?.toLongOrNull()?.let { return Duration.ofMillis(maxOf(0, it * 1000 - clock())) }
        return null
    }

    // 表示用の URL（問い合わせの部分を除く。アセットの配信先の URL の問い合わせには、期限付きの署名が入るため）
    private fun shown(uri: URI): String = URI(uri.scheme, uri.rawAuthority, uri.rawPath, null, null).toString()

    // 上限まで受け取る本文。Content-Length が上限を超えれば受け取らずに止め、受け取った量が上限を超えた時点でも止める
    private class CappedBody(private val max: Long, private val declared: Long) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val out = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            if (declared > max) {
                subscription.cancel()
                result.completeExceptionally(FetchFailure.TooLarge(max))
                return
            }
            subscription.request(1)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            for (buffer in item) {
                if (out.size().toLong() + buffer.remaining() > max) {
                    subscription?.cancel()
                    result.completeExceptionally(FetchFailure.TooLarge(max))
                    return
                }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
            subscription?.request(1)
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(if (throwable is IOException) throwable else IOException(throwable))
        }

        override fun onComplete() {
            result.complete(out.toByteArray())
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
