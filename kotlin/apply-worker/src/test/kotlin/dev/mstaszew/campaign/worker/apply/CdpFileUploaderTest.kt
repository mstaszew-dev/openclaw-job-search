package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Ports cv_upload.py's set_file_input_via_cdp logic: resolve the tab the MCP
 * is actually driving by URL match (/json/list order is arbitrary in the
 * shared campaign Chrome - picking the first page silently uploaded the CV
 * into an unrelated tab's form), then DOM.getDocument -> DOM.querySelector ->
 * DOM.setFileInputFiles -> DOM.getAttributes. The HTTP + websocket shell in
 * CdpFileUploader.setFileInputFiles is thin glue around these two tested
 * pieces; every failure is an Error string, never a throw.
 */
class CdpFileUploaderTest {

    private val mapper = ObjectMapper()

    /** Requests recorded by [scripted], keyed by method (last wins). */
    private val seen = mutableMapOf<String, ObjectNode>()

    private fun scripted(replies: Map<String, ObjectNode>): CdpCommand = { method, params ->
        if (method != "DOM.getDocument") seen[method] = params.deepCopy()
        replies.getValue(method)
    }

    private fun reply(json: String): ObjectNode = mapper.readTree(json).deepCopy() as ObjectNode

    private fun targets(vararg entries: String): JsonNode =
        mapper.readTree(entries.joinToString(",", "[", "]"))

    private fun pageTarget(webSocketDebuggerUrl: String, url: String): String =
        """{"type":"page","url":"$url","webSocketDebuggerUrl":"$webSocketDebuggerUrl"}"""

    // --- matchPageTarget: which tab gets the CV ---

    @Test
    fun `no page url matches nothing - never guess the active tab`() {
        val uploader = CdpFileUploader()

        assertThat(
            uploader.matchPageTarget(
                targets(pageTarget("ws://x", "https://a.example.com")),
                pageUrl = null,
            ),
        ).isNull()
    }

    @Test
    fun `exact url match wins`() {
        val uploader = CdpFileUploader()
        val targets = targets(
            pageTarget("ws://other", "https://gmail.example.com"),
            pageTarget("ws://mine", "https://apply.example.com/job/123"),
        )

        val page = uploader.matchPageTarget(targets, pageUrl = "https://apply.example.com/job/123")

        assertThat(page?.path("webSocketDebuggerUrl")?.asText()).isEqualTo("ws://mine")
    }

    @Test
    fun `unique prefix match tolerates fragment and query drift`() {
        val uploader = CdpFileUploader()
        val targets = targets(pageTarget("ws://mine", "https://apply.example.com/job/123"))

        val page = uploader.matchPageTarget(targets, pageUrl = "https://apply.example.com/job/123?src=nl#form")

        assertThat(page?.path("webSocketDebuggerUrl")?.asText()).isEqualTo("ws://mine")
    }

    @Test
    fun `ambiguous prefix match is refused`() {
        val uploader = CdpFileUploader()
        val targets = targets(
            pageTarget("ws://a", "https://apply.example.com/apply"),
            pageTarget("ws://b", "https://apply.example.com/apply?step=2"),
        )

        assertThat(
            uploader.matchPageTarget(targets, pageUrl = "https://apply.example.com/apply?step=2&token=t"),
        ).isNull()
    }

    @Test
    fun `refuses a prefix that crosses a path segment boundary`() {
        val uploader = CdpFileUploader()
        val targets = targets(pageTarget("ws://a", "https://x.example.com/search"))

        assertThat(
            uploader.matchPageTarget(targets, pageUrl = "https://x.example.com/search-and-other-things"),
        ).isNull()
    }

    @Test
    fun `refuses a prefix that ends mid path segment`() {
        val uploader = CdpFileUploader()
        val targets = targets(pageTarget("ws://a", "https://x.example.com/job/12"))

        assertThat(
            uploader.matchPageTarget(targets, pageUrl = "https://x.example.com/job/123"),
        ).isNull()
    }

    @Test
    fun `non-page targets are never matched`() {
        val uploader = CdpFileUploader()
        val mixed = mapper.readTree(
            """[{"type":"iframe","url":"https://apply.example.com/job","webSocketDebuggerUrl":"ws://i"},
               {"type":"page","url":"https://gmail.example.com","webSocketDebuggerUrl":"ws://g"}]""",
        )

        assertThat(uploader.matchPageTarget(mixed, pageUrl = "https://apply.example.com/job")).isNull()
    }

    // --- scanForInput: which tab gets the CV when the URL match fails ---
    // Candidates are host-correlated against the evaluated url: a stale form
    // from an earlier attempt must never win as the unique owner, and probe
    // failures are counted instead of being read as "input absent".

    private fun pageNodes(vararg pairs: Pair<String, String>): List<JsonNode> =
        pairs.map { (ws, url) -> mapper.readTree(pageTarget(ws, url)) }

    // --- markerOwner: exact tab identity when several tabs share one form url ---

    @Test
    fun `marker owner wins when several tabs share the same form url`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes(
            "ws://stale" to "https://form.erecruiter.example/form/x",
            "ws://active" to "https://form.erecruiter.example/form/x",
        )

        val owner = uploader.markerOwner(pages, "cv-ab12cd34") { target ->
            if (target.path("webSocketDebuggerUrl").asText() == "ws://active") "cv-ab12cd34" else null
        }

        assertThat(owner?.path("webSocketDebuggerUrl")?.asText()).isEqualTo("ws://active")
    }

    @Test
    fun `marker probe errors read as not-holding, later pages still probed`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes(
            "ws://dead" to "https://form.erecruiter.example/form/x",
            "ws://active" to "https://form.erecruiter.example/form/x",
        )

        val owner = uploader.markerOwner(pages, "cv-ab12cd34") { target ->
            if (target.path("webSocketDebuggerUrl").asText() == "ws://dead") {
                throw IllegalStateException("websocket gone")
            }
            "cv-ab12cd34"
        }

        assertThat(owner?.path("webSocketDebuggerUrl")?.asText()).isEqualTo("ws://active")
    }

    @Test
    fun `no page holds the marker - markerOwner returns null for the fallback flow`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes("ws://a" to "https://form.erecruiter.example/form/x")

        assertThat(uploader.markerOwner(pages, "cv-ab12cd34") { null }).isNull()
    }

    @Test
    fun `input scan picks the single same-host page that has the input`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes(
            "ws://a" to "https://form.erecruiter.example/form/1",
            "ws://mine" to "https://form.erecruiter.example/form/2",
            "ws://c" to "https://y.example.com/form/3",
        )

        val scan = uploader.scanForInput(pages, host = "form.erecruiter.example") {
            it.path("webSocketDebuggerUrl").asText() == "ws://mine"
        }

        assertThat(scan.owner?.path("webSocketDebuggerUrl")?.asText()).isEqualTo("ws://mine")
    }

    @Test
    fun `input scan probes only pages sharing the evaluated host`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes(
            "ws://a" to "https://form.erecruiter.example/form/1",
            "ws://stale" to "https://stale.example.com/form/9",
        )
        val probed = mutableListOf<String>()

        val scan = uploader.scanForInput(pages, host = "form.erecruiter.example") {
            probed.add(it.path("webSocketDebuggerUrl").asText())
            true
        }

        assertThat(probed).containsExactly("ws://a")
        assertThat(scan.owner?.path("webSocketDebuggerUrl")?.asText()).isEqualTo("ws://a")
    }

    @Test
    fun `input scan is refused when there is no evaluated url to correlate against`() {
        val uploader = CdpFileUploader()

        val scan = uploader.scanForInput(pageNodes("ws://a" to "https://x.example.com"), host = null) { true }

        assertThat(scan.owner).isNull()
        assertThat(scan.detail).contains("refused")
    }

    @Test
    fun `input scan is refused when no open page shares the evaluated host`() {
        val uploader = CdpFileUploader()

        val scan = uploader.scanForInput(pageNodes("ws://a" to "https://x.example.com"), host = "form.erecruiter.example") { true }

        assertThat(scan.owner).isNull()
        assertThat(scan.detail).contains("form.erecruiter.example")
    }

    @Test
    fun `input scan refuses when no probed page has the input`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes("ws://a" to "https://x.example.com/1", "ws://b" to "https://x.example.com/2")

        val scan = uploader.scanForInput(pages, host = "x.example.com", probe = { false })

        assertThat(scan.owner).isNull()
        assertThat(scan.detail).contains("no page had the input")
    }

    @Test
    fun `input scan refuses when several same-host pages have the input`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes(
            "ws://a" to "https://form.example.com/1",
            "ws://b" to "https://form.example.com/2",
            "ws://c" to "https://form.example.com/3",
        )

        val scan = uploader.scanForInput(pages, host = "form.example.com") {
            it.path("webSocketDebuggerUrl").asText() != "ws://c"
        }

        assertThat(scan.owner).isNull()
        assertThat(scan.detail).contains("2 pages")
    }

    @Test
    fun `input scan counts probe errors instead of reading them as absent`() {
        val uploader = CdpFileUploader()
        val pages = pageNodes("ws://a" to "https://x.example.com/1", "ws://b" to "https://x.example.com/2")

        val scan = uploader.scanForInput(pages, host = "x.example.com") { throw IllegalStateException("socket gone") }

        assertThat(scan.owner).isNull()
        assertThat(scan.detail).contains("2/2 probes errored")
        assertThat(scan.detail).contains("socket gone")
    }

    // --- hasInput: the per-page DOM probe ---

    @Test
    fun `dom probe finds a present input`() {
        seen.clear()
        val uploader = CdpFileUploader()
        val command = scripted(
            mapOf(
                "DOM.getDocument" to reply("""{"result":{"root":{"nodeId":7}}}"""),
                "DOM.querySelector" to reply("""{"result":{"nodeId":42}}"""),
            ),
        )

        assertThat(uploader.hasInput(command, "input[name=\"cvFiles\"]")).isTrue()
    }

    @Test
    fun `dom probe misses when the selector matches nothing`() {
        seen.clear()
        val uploader = CdpFileUploader()
        val miss = scripted(
            mapOf(
                "DOM.getDocument" to reply("""{"result":{"root":{"nodeId":7}}}"""),
                "DOM.querySelector" to reply("""{"result":{"nodeId":0}}"""),
            ),
        )

        assertThat(uploader.hasInput(miss, "input[type=file]")).isFalse()
    }

    @Test
    fun `dom probe surfaces a cdp error instead of reading it as absent`() {
        seen.clear()
        val uploader = CdpFileUploader()
        val broken = scripted(
            mapOf(
                "DOM.getDocument" to reply("""{"error":{"message":"session gone"}}"""),
            ),
        )

        assertThrows<IllegalStateException> { uploader.hasInput(broken, "input[type=file]") }
    }

    // --- setFileInputFilesWith: the CDP command sequence ---

    @Test
    fun `sets the file on the first file input and verifies by re-reading`() {
        seen.clear()
        val command = scripted(
            mapOf(
                "DOM.getDocument" to reply("""{"result":{"root":{"nodeId":5}}}"""),
                "DOM.querySelector" to reply("""{"result":{"nodeId":9}}"""),
                "DOM.setFileInputFiles" to reply("""{"result":{}}"""),
                "DOM.getAttributes" to reply("""{"result":{"attributes":[]}}"""),
            ),
        )

        val result = CdpFileUploader().setFileInputFilesWith(command, "/cv/cv.pdf", "input[type=file]")

        assertThat(result).isEqualTo("via CDP setFileInputFiles on input[type=file]")
        assertThat(seen.getValue("DOM.querySelector")["selector"].asText()).isEqualTo("input[type=file]")
        val set = seen.getValue("DOM.setFileInputFiles")
        assertThat(set["files"][0].asText()).isEqualTo("/cv/cv.pdf")
        assertThat(set["nodeId"].asLong()).isEqualTo(9L)
    }

    @Test
    fun `reports a missing file input`() {
        val command = scripted(
            mapOf(
                "DOM.getDocument" to reply("""{"result":{"root":{"nodeId":5}}}"""),
                "DOM.querySelector" to reply("""{"result":{"nodeId":0}}"""),
            ),
        )

        val result = CdpFileUploader().setFileInputFilesWith(command, "/cv/cv.pdf", "input[type=file]")

        assertThat(result).isEqualTo("Error: CDP fallback failed (no input[type=file] found)")
    }

    @Test
    fun `surfaces a CDP reply error as an error string`() {
        val command = CdpCommand { _, _ ->
            reply("""{"error":{"code":-32601,"message":"not supported"}}""")
        }

        val result = CdpFileUploader().setFileInputFilesWith(command, "/cv/cv.pdf", "input[type=file]")

        assertThat(result).startsWith("Error: CDP fallback failed (")
    }

    @Test
    fun `fails when the input node cannot be re-read after the set`() {
        val command = scripted(
            mapOf(
                "DOM.getDocument" to reply("""{"result":{"root":{"nodeId":5}}}"""),
                "DOM.querySelector" to reply("""{"result":{"nodeId":9}}"""),
                "DOM.setFileInputFiles" to reply("""{"result":{}}"""),
                "DOM.getAttributes" to reply("""{"error":{"message":"node gone"}}"""),
            ),
        )

        val result = CdpFileUploader().setFileInputFilesWith(command, "/cv/cv.pdf", "input[type=file]")

        assertThat(result).isEqualTo("Error: CDP fallback failed (could not re-read the input node)")
    }
}
