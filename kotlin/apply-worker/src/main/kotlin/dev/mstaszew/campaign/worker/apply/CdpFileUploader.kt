package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One synchronous CDP request/reply round trip. */
fun interface CdpCommand {
    fun command(method: String, params: ObjectNode): JsonNode
}

/**
 * Ports cv_upload.py's set_file_input_via_cdp: for drop zones that never open
 * a native chooser, resolve the tab the MCP is actually driving by URL match
 * against /json/list (first-page-wins uploaded a CV into an unrelated tab once
 * already), then DOM.getDocument -> DOM.querySelector -> DOM.setFileInputFiles
 * -> DOM.getAttributes. Never throws: every failure is an Error string so a
 * broken upload degrades one application, not the tick.
 */
@Component
class CdpFileUploader(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
) {

    private val log = LoggerFactory.getLogger(CdpFileUploader::class.java)
    private val mapper = ObjectMapper()

    fun setFileInputFiles(cdpUrl: String, filePath: String, cssInput: String, pageUrl: String?): String = try {
        val targets = fetchJson(cdpUrl.trimEnd('/') + "/json/list")
        val page = matchPageTarget(targets, pageUrl)
            ?: return "Error: CDP fallback failed (no page target)"
        val wsUrl = page.path("webSocketDebuggerUrl").asText("")
        if (wsUrl.isBlank()) return "Error: CDP fallback failed (no page target)"
        CdpWsSession.connect(wsUrl).use { session ->
            setFileInputFilesWith({ m, p -> session.command(m, p) }, filePath, cssInput)
        }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        err("${e.javaClass.simpleName}: ${e.message?.take(200)}")
    }

    /**
     * Which tab gets the file. The MCP-driven tab is matched by URL: exact
     * match first (must be unique), then a unique prefix (the evaluated
     * location.href can carry a query or fragment the target list omits).
     * Ambiguous or missing means refuse, never guess.
     */
    internal fun matchPageTarget(targets: JsonNode, pageUrl: String?): JsonNode? {
        if (pageUrl.isNullOrBlank()) return null
        val pages = targets.filter { it.path("type").asText("") == "page" }
        val exact = pages.filter { it.path("url").asText("") == pageUrl }
        if (exact.size > 1) return null
        exact.firstOrNull()?.let { return it }
        return pages
            .filter { isBoundaryPrefix(it.path("url").asText(""), pageUrl) }
            .singleOrNull()
    }

    /**
     * The evaluated URL may extend the target URL with a query or fragment
     * (".../job/123" vs ".../job/123?src=nl#form"). The extension must start
     * at a URL boundary, so ".../search" never prefix-matches the unrelated
     * ".../search-and-other-things" page.
     */
    private fun isBoundaryPrefix(prefix: String, full: String): Boolean {
        if (prefix.isBlank() || prefix == full || !full.startsWith(prefix)) return false
        return when (full.getOrNull(prefix.length)) {
            '/', '?', '#', '&' -> true
            else -> false
        }
    }

    /** The four-command DOM sequence, pure over the command channel. */
    internal fun setFileInputFilesWith(command: CdpCommand, filePath: String, cssInput: String): String = try {
        val doc = command.command("DOM.getDocument", obj { put("depth", 0) })
        if (doc.path("error").isObject) {
            return err("DOM.getDocument: ${doc.path("error").path("message").asText("?")}")
        }
        val root = doc.path("result").path("root").path("nodeId").asLong(0)
        if (root == 0L) return err("no document root")
        val found = command.command(
            "DOM.querySelector",
            obj { put("nodeId", root); put("selector", cssInput) },
        )
        if (found.path("error").isObject) {
            return err("DOM.querySelector: ${found.path("error").path("message").asText("?")}")
        }
        val node = found.path("result").path("nodeId").asLong(0)
        if (node == 0L) return "Error: CDP fallback failed (no $cssInput found)"
        val set = command.command(
            "DOM.setFileInputFiles",
            obj { putArray("files").add(filePath); put("nodeId", node) },
        )
        if (set.path("error").isObject) {
            return err("DOM.setFileInputFiles: ${set.path("error").path("message").asText("?")}")
        }
        val check = command.command("DOM.getAttributes", obj { put("nodeId", node) })
        if (!check.path("result").isObject) {
            return err("could not re-read the input node")
        }
        "via CDP setFileInputFiles on $cssInput"
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        err("${e.javaClass.simpleName}: ${e.message?.take(200)}")
    }

    private fun fetchJson(url: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return mapper.readTree(body)
    }

    private fun obj(block: ObjectNode.() -> Unit): ObjectNode = mapper.createObjectNode().apply(block)

    private fun err(detail: String): String = "Error: CDP fallback failed ($detail)"

    /**
     * Minimal synchronous CDP-over-websocket session on the JDK client (no new
     * dependencies; the python original used the websocket-client package for
     * the same 40 lines of glue). One command in flight at a time; CDP events
     * (no id) are skipped.
     */
    private class CdpWsSession private constructor() : AutoCloseable {

        lateinit var ws: WebSocket

        private val mapper = ObjectMapper()
        private val buffer = StringBuilder()
        private val replies = LinkedBlockingQueue<JsonNode>()
        private val nextId = AtomicLong()

        @Volatile
        private var failure: Throwable? = null

        fun receive(text: String, last: Boolean) {
            buffer.append(text)
            if (!last) return
            val message = buffer.toString()
            buffer.setLength(0)
            val node = try {
                mapper.readTree(message)
            } catch (e: Exception) {
                null
            } ?: return
            replies.put(node)
        }

        fun fail(error: Throwable) {
            failure = error
            replies.put(mapper.createObjectNode())
        }

        fun command(method: String, params: ObjectNode): JsonNode {
            val id = nextId.incrementAndGet()
            val message = mapper.createObjectNode().apply {
                put("id", id)
                put("method", method)
                set<ObjectNode>("params", params)
            }
            ws.sendText(mapper.writeValueAsString(message), true)
            val deadline = System.nanoTime() + TIMEOUT.toNanos()
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw IllegalStateException("CDP $method timed out after ${TIMEOUT.seconds}s")
                }
                val node = replies.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
                failure?.let { throw IllegalStateException("CDP connection failed: ${it.message}", it) }
                if (node.path("id").asLong(Long.MIN_VALUE) != id) continue
                return node
            }
        }

        override fun close() {
            runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
        }

        companion object {
            private val TIMEOUT = Duration.ofSeconds(15)

            fun connect(url: String): CdpWsSession {
                val session = CdpWsSession()
                val listener = object : WebSocket.Listener {
                    override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        session.receive(data.toString(), last)
                        ws.request(1)
                        return null
                    }

                    override fun onError(ws: WebSocket, error: Throwable) {
                        LoggerFactory.getLogger(CdpFileUploader::class.java).warn("CDP socket failed", error)
                        session.fail(error)
                    }
                }
                val ws = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .buildAsync(URI.create(url), listener)
                try {
                    session.ws = ws.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // .get(timeout) does not cancel the handshake; abort the
                    // socket if it completes late so the shared Chrome never
                    // keeps an orphaned connection.
                    ws.whenComplete { socket, _ -> socket?.abort() }
                    throw e
                }
                return session
            }
        }
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
