package app.tasker.core.ai.claude

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A request the fake API received. Header names are lower-cased. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.joinToString(",")
}

/**
 * Local stand-in for the Claude API on the loopback interface: records requests and replies with a canned
 * status and JSON body. Tests never reach the real network.
 */
class FakeClaudeApi : Closeable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    @Volatile var status: Int = 200

    @Volatile var responseBody: String = "{}"

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use {
                requests += RecordedRequest(
                    method = it.requestMethod,
                    path = it.requestURI.path,
                    query = it.requestURI.query,
                    headers = it.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() },
                    body = it.requestBody.readBytes().decodeToString(),
                )
                val bytes = responseBody.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.responseHeaders.add("request-id", "req_test")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
    }

    fun reply(status: Int, body: String) {
        this.status = status
        this.responseBody = body
    }

    override fun close() = server.stop(0)
}
