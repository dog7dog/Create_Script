package com.creatrbypc.createanalytical.install

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// テスト用のローカルの HTTP サーバー（JDK の jdk.httpserver。127.0.0.1 だけで待ち受ける）。GitHub の振る舞いを再現し、受けた要求を記録する
internal class LocalServer : AutoCloseable {
    class Recorded(val path: String, val query: String?, val authorization: String?, val userAgent: String?)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val routes = HashMap<String, (HttpExchange) -> Unit>()
    val requests = CopyOnWriteArrayList<Recorded>()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            requests.add(Recorded(exchange.requestURI.rawPath, exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("User-Agent")))
            val handler = routes[exchange.requestURI.rawPath]
            try {
                if (handler == null) respond(exchange, 404, "not found".toByteArray()) else handler(exchange)
            } catch (error: Exception) {
                // 接続を切る応答など
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    val port: Int get() = server.address.port
    val base: URI get() = URI("http://127.0.0.1:$port")

    fun route(path: String, handler: (HttpExchange) -> Unit): LocalServer {
        routes[path] = handler
        return this
    }

    // 決まった回数だけ別の応答を返してから、最後の応答を返す
    fun sequence(path: String, vararg handlers: (HttpExchange) -> Unit): LocalServer {
        var count = 0
        return route(path) { exchange -> handlers[minOf(count++, handlers.size - 1)](exchange) }
    }

    fun requestsTo(path: String) = requests.filter { it.path == path }

    override fun close() {
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    companion object {
        fun respond(exchange: HttpExchange, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
        }

        fun ok(body: ByteArray, headers: Map<String, String> = emptyMap()): (HttpExchange) -> Unit = { respond(it, 200, body, headers) }

        fun status(status: Int, headers: Map<String, String> = emptyMap()): (HttpExchange) -> Unit = { respond(it, status, ByteArray(0), headers) }

        fun redirect(location: String, status: Int = 302): (HttpExchange) -> Unit = { respond(it, status, ByteArray(0), mapOf("Location" to location)) }

        // 応答を返す前に待つ
        fun delayed(delay: Duration, then: (HttpExchange) -> Unit): (HttpExchange) -> Unit = {
            Thread.sleep(delay.toMillis())
            then(it)
        }
    }
}

// テスト用の接続先（2 つのローカルのサーバー: ダウンロード用と API 用。127.0.0.1 と、その 2 つのポートだけを許す）
internal fun testEndpoints(download: LocalServer, api: LocalServer): Endpoints =
    Endpoints(download.base, api.base, setOf("127.0.0.1"), setOf(download.port, api.port), https = false, ipLiterals = true)
