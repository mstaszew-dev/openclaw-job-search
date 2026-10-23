package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.mstaszew.campaign.worker.WorkerProperties
import dev.mstaszew.campaign.common.mcp.McpClientException
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ports cv_upload.py's contract: the model never picks a path. The uploader
 * verifies the canonical PL CV, clicks the upload control if given, uploads
 * through the file chooser, and falls back to CDP DOM.setFileInputFiles for
 * drop zones that never open a native chooser. Never throws: a broken upload
 * degrades one application, not the tick.
 */
class CvUploaderTest {

    @TempDir
    lateinit var dir: Path

    private val cv: Path
        get() = dir.resolve("michael-staszewski-cv-pl.pdf")

    private fun writeCv(bytes: Long = 42): Path {
        Files.write(cv, ByteArray(bytes.toInt()))
        return cv
    }

    private fun mcp(
        fileUpload: (() -> String)? = null,
        click: (() -> String)? = null,
        evaluate: (() -> String)? = null,
    ): McpStdioClient = mockk {
        if (click != null) {
            every { callToolText(eq("browser_click"), any(), any()) } answers { click() }
        }
        if (fileUpload != null) {
            every { callToolText(eq("browser_file_upload"), any(), any()) } answers { fileUpload() }
        }
        if (evaluate != null) {
            every { callToolText(eq("browser_evaluate"), any(), any()) } answers { evaluate() }
        }
    }

    private fun cdp(result: String = "via CDP setFileInputFiles on input[type=file]"): CdpFileUploader = mockk {
        every { setFileInputFiles(any(), any(), any(), any()) } returns result
    }

    private fun uploader(fallback: CdpFileUploader, cdpUrl: String = "http://cdp:9222") = CvUploader(
        cdp = fallback,
        props = WorkerProperties().apply { this.cdpUrl = cdpUrl },
    )

    @Test
    fun `rejects a missing CV before touching the browser`() {
        val browser = mcp(fileUpload = { "unused" })

        val result = uploader(cdp()).upload(browser, cv.toString())

        assertThat(result).startsWith("Error: CV not found at ")
        verify { browser wasNot Called }
    }

    @Test
    fun `rejects an empty CV file`() {
        val browser = mcp(fileUpload = { "unused" })
        writeCv(bytes = 0)

        val result = uploader(cdp()).upload(browser, cv.toString())

        assertThat(result).isEqualTo("Error: CV at $cv is empty (0 bytes)")
        verify { browser wasNot Called }
    }

    @Test
    fun `uploads through the file chooser when it opens`() {
        val fallback = cdp()
        val browser = mcp(fileUpload = { "chooser accepted the file" })
        writeCv()

        val result = uploader(fallback).upload(browser, cv.toString())

        assertThat(result).isEqualTo("CV uploaded (${cv.fileName}): chooser accepted the file")
        verify(exactly = 0) { fallback.setFileInputFiles(any(), any(), any(), any()) }
    }

    /**
     * The 2026-10-22 NFJ deaths (media-adtech, darize, upvanta x2) were
     * undebuggable: take(100)/take(150) cut the combined chooser+CDP error
     * exactly before the real refusal and the real CDP detail. Both tails
     * must survive into the returned error text.
     */
    @Test
    fun `combined error keeps long chooser and cdp details visible`() {
        val longRefusal = "tool browser_file_upload failed: " + "x".repeat(140) + " CHOOSER-TAIL-4711"
        val browser = mcp(
            fileUpload = { throw McpClientException(longRefusal) },
            evaluate = { "https://form.erecruiter.example/form/1" },
        )
        writeCv()
        val cdpDetail = "d".repeat(280) + " CDP-TAIL-9902"

        val result = uploader(cdp("Error: CDP fallback failed ($cdpDetail)")).upload(browser, cv.toString())

        assertThat(result).contains("CHOOSER-TAIL-4711")
        assertThat(result).contains("CDP-TAIL-9902")
    }

    /**
     * Review B1: when the model arms the chooser with its own click,
     * playwright-mcp refuses our pre-click with "does not handle the modal
     * state". The chooser is armed in that case, so the upload must proceed
     * instead of dying on the early-return error.
     */
    @Test
    fun `a modal-state click refusal means the chooser is already armed and the upload proceeds`() {
        val browser = mcp(
            click = { throw McpClientException("Error: Tool \"browser_click\" does not handle the modal state.") },
            fileUpload = { "chooser accepted the file" },
        )
        writeCv()

        val result = uploader(cdp()).upload(browser, cv.toString(), selector = "ref-upload-3")

        assertThat(result).isEqualTo("CV uploaded (${cv.fileName}): chooser accepted the file")
        verify(exactly = 1) { browser.callToolText(eq("browser_click"), any(), any()) }
        verify(exactly = 1) { browser.callToolText(eq("browser_file_upload"), any(), any()) }
    }

    @Test
    fun `clicks the upload control first when a selector is given`() {
        val browser = mcp(click = { "clicked" }, fileUpload = { "accepted" })
        writeCv()

        val result = uploader(cdp()).upload(browser, cv.toString(), selector = "ref-upload-3")

        assertThat(result).startsWith("CV uploaded (${cv.fileName}):")
        verify {
            browser.callToolText(
                eq("browser_click"),
                match { it.path("target").asText() == "ref-upload-3" },
                any(),
            )
        }
    }

    /**
     * A failed pre-click (hidden input timing out the click, stale ref, etc.)
     * must never preempt the CDP fallback: setFileInputFiles handles inputs
     * the click path cannot. The flow-through is click fail -> upload refused
     * (no chooser was armed) -> CDP fallback.
     */
    /**
     * playwright-mcp wraps browser_evaluate output in a code fence, so the
     * last non-blank line is the closing ``` - not the url. The fallback
     * must extract the url line itself; feeding the fence into the host
     * correlation refuses the scan ("no evaluated url").
     */
    @Test
    fun `extracts the evaluated url from a fenced evaluate result`() {
        val fallback = cdp()
        val browser = mcp(evaluate = { "### Result\n```\n\"https://form.example.com/apply\"\n```\n" })
        writeCv()

        uploader(fallback).upload(browser, cv.toString(), selector = null)

        verify(exactly = 1) {
            fallback.setFileInputFiles(any(), any(), any(), eq("https://form.example.com/apply"))
        }
    }

    @Test
    fun `falls through to the upload and the cdp fallback when the chooser click fails`() {
        val fallback = cdp()
        val browser = mcp(
            click = { throw McpClientException("tool browser_click failed: TimeoutError: browserBackend.callTool: Timeout 5000ms exceeded.") },
            fileUpload = { throw McpClientException("tool browser_file_upload failed: can only be used when there is related modal state present.") },
            evaluate = { "https://form.example.com/apply" },
        )
        writeCv()

        val result = uploader(fallback).upload(browser, cv.toString(), selector = "#file")

        assertThat(result).isEqualTo("CV uploaded (${cv.fileName}) via CDP setFileInputFiles on input[type=file]")
        verify(exactly = 1) { browser.callToolText(eq("browser_click"), any(), any()) }
        verify(exactly = 1) { fallback.setFileInputFiles(any(), any(), any(), any()) }
    }

    @Test
    fun `re-arms the chooser once when the first upload finds no modal state`() {
        val fallback = cdp()
        var uploads = 0
        val browser = mcp(
            click = { "clicked" },
            fileUpload = {
                if (uploads++ == 0) throw McpClientException("can only be used when there is related modal state")
                else "chooser accepted the file"
            },
        )
        writeCv()

        val result = uploader(fallback).upload(browser, cv.toString(), selector = "ref-upload-3")

        assertThat(result).isEqualTo("CV uploaded (${cv.fileName}): chooser accepted the file")
        verify(exactly = 2) { browser.callToolText(eq("browser_click"), any(), any()) }
        verify(exactly = 2) { browser.callToolText(eq("browser_file_upload"), any(), any()) }
        verify(exactly = 0) { fallback.setFileInputFiles(any(), any(), any(), any()) }
    }

    @Test
    fun `a refused upload without a selector goes straight to the CDP fallback`() {
        val fallback = cdp()
        val browser = mcp(fileUpload = { throw McpClientException("no modal state") })
        writeCv()

        val result = uploader(fallback).upload(browser, cv.toString())

        assertThat(result).startsWith("CV uploaded (${cv.fileName}) via CDP")
        verify(exactly = 1) { browser.callToolText(eq("browser_file_upload"), any(), any()) }
        verify(exactly = 0) { browser.callToolText(eq("browser_click"), any(), any()) }
    }

    @Test
    fun `a failed re-click still reaches the CDP fallback instead of aborting`() {
        val fallback = cdp()
        var clicks = 0
        val browser = mcp(
            click = {
                if (clicks++ == 0) "clicked"
                else throw McpClientException("overlay intercepted the re-click")
            },
            fileUpload = { throw McpClientException("no modal state") },
        )
        writeCv()

        val result = uploader(fallback).upload(browser, cv.toString(), selector = "ref-upload-3")

        assertThat(result).startsWith("CV uploaded (${cv.fileName}) via CDP")
        verify(exactly = 2) { browser.callToolText(eq("browser_click"), any(), any()) }
        verify(exactly = 1) { browser.callToolText(eq("browser_file_upload"), any(), any()) }
        verify(exactly = 1) { fallback.setFileInputFiles(any(), any(), any(), any()) }
    }

    @Test
    fun `both upload refusals survive into the combined error detail`() {
        var n = 0
        val browser = mcp(
            click = { "clicked" },
            fileUpload = { throw McpClientException("refusal-${++n}") },
        )
        val uploader = uploader(cdp(result = "Error: CDP fallback failed (boom)"))
        writeCv()

        val result = uploader.upload(browser, cv.toString(), selector = "ref-upload-3")

        assertThat(result).startsWith("Error: CV upload failed via chooser")
        assertThat(result).contains("refusal-1")
        assertThat(result).contains("refusal-2")
        assertThat(result).contains("and via CDP")
        assertThat(result).contains(cv.toString())
    }

    @Test
    fun `falls back to CDP with the active tab URL when the chooser refuses`() {
        val fallback = cdp()
        val browser = mcp(
            fileUpload = { throw McpClientException("no file chooser open") },
            evaluate = { "- Result:\nhttps://apply.example.com/job/123" },
        )
        writeCv()

        val result = uploader(fallback).upload(browser, cv.toString())

        assertThat(result)
            .isEqualTo("CV uploaded (${cv.fileName}) via CDP setFileInputFiles on input[type=file]")
        verify {
            fallback.setFileInputFiles(
                "http://cdp:9222",
                cv.toString(),
                "input[type=file]",
                "https://apply.example.com/job/123",
            )
        }
    }

    @Test
    fun `falls back with a null page URL when the active URL is unreadable`() {
        val fallback = cdp()
        val browser = mcp(
            fileUpload = { throw McpClientException("no file chooser open") },
            evaluate = { throw McpClientException("evaluate failed") },
        )
        writeCv()

        uploader(fallback).upload(browser, cv.toString())

        verify { fallback.setFileInputFiles("http://cdp:9222", cv.toString(), "input[type=file]", null) }
    }

    @Test
    fun `reports both failures and the manual path when chooser and CDP fail`() {
        val browser = mcp(
            fileUpload = { throw McpClientException("no file chooser open") },
            evaluate = { "https://apply.example.com/job" },
        )
        val uploader = uploader(cdp(result = "Error: CDP fallback failed (boom)"))
        writeCv()

        val result = uploader.upload(browser, cv.toString())

        assertThat(result).startsWith("Error: CV upload failed via chooser")
        assertThat(result).contains("and via CDP")
        assertThat(result).contains(cv.toString())
    }

    @Test
    fun `uses a custom css input selector when given`() {
        val fallback = cdp()
        val browser = mcp(
            fileUpload = { throw McpClientException("no file chooser open") },
            evaluate = { "https://apply.example.com/job" },
        )
        writeCv()

        uploader(fallback).upload(browser, cv.toString(), cssInput = "input[name=cv_upload]")

        verify {
            fallback.setFileInputFiles(
                "http://cdp:9222",
                cv.toString(),
                "input[name=cv_upload]",
                "https://apply.example.com/job",
            )
        }
    }
}
