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

/** Outcome of the input scan: a unique owning page, or a refusal detail. */
internal data class InputScan(val owner: JsonNode?, val detail: String)

/**
 * Ports cv_upload.py's set_file_input_via_cdp: for drop zones that never open
 * a native chooser, resolve the tab the MCP is actually driving by URL match
 * against /json/list (first-page-wins uploaded a CV into an unrelated tab once
 * already), then DOM.getDocument -> DOM.querySelector -> DOM.setFileInputFiles
 * -> DOM.getAttributes. When the URL match fails, the pages sharing the
 * evaluated url's host are probed for the input and a UNIQUE owner is used;
 * zero owners, several owners, or no evaluated url to correlate against is a
 * refusal, never a guess. Never throws: every failure is an Error string so a
 * broken upload degrades one application, not the tick.
 */
@Component
class CdpFileUploader(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
) {

    private val log = LoggerFactory.getLogger(CdpFileUploader::class.java)
    private val mapper = ObjectMapper()

    fun setFileInputFiles(cdpUrl: String, filePath: String, cssInput: String, pageUrl: String?, marker: String? = null): String = try {
        val targets = fetchJson(cdpUrl.trimEnd('/') + "/json/list")
        val pages = targets.filter { it.path("type").asText("") == "page" }
        val page: JsonNode = marker
            ?.takeIf { it.isNotBlank() }
            ?.let { m -> markerOwner(pages, m, ::evaluateMarker) }
            ?: matchPageTarget(targets, pageUrl) ?: run {
            val scan = scanForInput(pages, host = hostOf(pageUrl ?: "")) { target ->
                probeHasInput(target, cssInput)
            }
            val owner = scan.owner
            if (owner == null) {
                return "Error: CDP fallback failed (no page target for ${pageUrl ?: "the evaluated url"}; ${scan.detail})"
            }
            log.info(
                "URL match failed ({}); input scan picked {}",
                pageUrl,
                owner.path("url").asText(""),
            )
            owner
        }
        val wsUrl = page.path("webSocketDebuggerUrl").asText("")
        if (wsUrl.isBlank()) return "Error: CDP fallback failed (no page target)"
        CdpWsSession.connect(http, wsUrl).use { session ->
            setFileInputFilesWith({ m, p -> session.command(m, p) }, filePath, cssInput)
        }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        err("${e.javaClass.simpleName}: ${e.message?.take(200)}")
    }

    /** Registrable host of [url], or null when it cannot be parsed. */
    internal fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    /**
     * Unique-input-owner scan for the no-URL-match case: probe the pages that
     * share [host] with the evaluated url and proceed only on a single owner.
     * No host (the evaluate failed), no same-host page, zero owners, or
     * several owners is a refusal, never a guess: a stale form from an
     * earlier attempt is a real scenario and uniqueness alone is not tab
     * identity. Probe failures are counted into [InputScan.detail] instead of
     * reading as "input absent" (a wedged CDP must not look like a form
     * problem).
     */
    internal fun scanForInput(pages: List<JsonNode>, host: String?, probe: (JsonNode) -> Boolean): InputScan {
        if (host.isNullOrBlank()) {
            return InputScan(null, "input scan refused: no evaluated url to correlate against")
        }
        val candidates = pages.filter { hostOf(it.path("url").asText("")) == host }
        if (candidates.isEmpty()) {
            return InputScan(null, "input scan refused: no open page on $host")
        }
        var errors = 0
        var lastError: String? = null
        val hits = candidates.take(MAX_SCAN_PROBES).mapNotNull { page ->
            try {
                if (probe(page)) page else null
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                errors += 1
                lastError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
                null
            }
        }
        val suffix = if (errors > 0) "; $errors/${candidates.size} probes errored (last: $lastError)" else ""
        return when (hits.size) {
            1 -> InputScan(hits[0], "unique page with the input")
            0 -> InputScan(null, "no page had the input$suffix")
            else -> InputScan(null, "input found on ${hits.size} pages$suffix")
        }
    }

    /**
     * True when [cssInput] resolves to a node in this command channel's
     * document. CDP errors throw (the scan counts them); only a genuine
     * "selector matches nothing" reads as false.
     */    internal fun hasInput(command: CdpCommand, cssInput: String): Boolean {
        val doc = command.command("DOM.getDocument", obj { put("depth", 0) })
        if (doc.path("error").isObject) {
            throw IllegalStateException("DOM.getDocument: ${doc.path("error").path("message").asText("unknown")}")
        }
        val root = doc.path("result").path("root").path("nodeId").asLong(0)
        if (root == 0L) return false
        val found = command.command(
            "DOM.querySelector",
            obj { put("nodeId", root); put("selector", cssInput) },
        )
        if (found.path("error").isObject) {
            throw IllegalStateException("DOM.querySelector: ${found.path("error").path("message").asText("unknown")}")
        }
        return found.path("result").path("nodeId").asLong(0) != 0L
    }

    /**
     * One-shot probe over the target's own websocket session with a short
     * deadline (a wedged target must not stall the scan); failures propagate
     * for the scan to count.
     */
    private fun probeHasInput(target: JsonNode, cssInput: String): Boolean {
        val ws = target.path("webSocketDebuggerUrl").asText("")
        if (ws.isBlank()) throw IllegalStateException("page target has no webSocketDebuggerUrl")
        CdpWsSession.connect(http, ws, PROBE_TIMEOUT).use { session ->
            return hasInput({ m, p -> session.command(m, p) }, cssInput)
        }
    }

    /**
     * Exact tab identity for the same-url-tabs case: [CvUploader] injects a
     * unique marker into the MCP-driven tab's window via browser_evaluate,
     * and the page whose Runtime.evaluate returns it IS that tab. Several
     * tabs can legitimately share one form url (a stale erecruiter tab made
     * both the url match and the unique-owner scan ambiguous), so an exact
     * in-page marker beats every URL heuristic. Probe errors read as
     * not-holding; when nothing holds the marker the caller falls back to
     * the url match and the unique-owner scan.
     */
    internal fun markerOwner(pages: List<JsonNode>, marker: String, eval: (JsonNode) -> String?): JsonNode? =
        pages.asSequence()
            .filter { it.path("webSocketDebuggerUrl").asText("").isNotBlank() }
            .take(MAX_SCAN_PROBES)
            .firstOrNull { target ->
                try {
                    eval(target) == marker
                } catch (e: Exception) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    false
                }
            }

    /** Reads window.__cvUploadMarker from [target]'s own session. */
    private fun evaluateMarker(target: JsonNode): String? {
        val ws = target.path("webSocketDebuggerUrl").asText("")
        if (ws.isBlank()) return null
        CdpWsSession.connect(http, ws, PROBE_TIMEOUT).use { session ->
            val reply = session.command(
                "Runtime.evaluate",
                obj { put("expression", "window.__cvUploadMarker"); put("returnByValue", true) },
            )
            val value = reply.path("result").path("result").path("value")
            return if (value.isTextual) value.asText() else null
        }
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

            fun connect(client: HttpClient, url: String, timeout: Duration = TIMEOUT): CdpWsSession {
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
                val ws = client.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(URI.create(url), listener)
                try {
                    session.ws = ws.get(timeout.toSeconds(), TimeUnit.SECONDS)
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

        /** Probes ride a short deadline: a wedged target must not stall the scan. */
        private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(4)
        private const val MAX_SCAN_PROBES = 8
    }
}
