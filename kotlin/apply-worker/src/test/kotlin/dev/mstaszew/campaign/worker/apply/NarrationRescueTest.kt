package dev.mstaszew.campaign.worker.apply

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Narration rescue: free-tier models occasionally write the tool call as
 * TEXT ("browser_snapshot()") instead of emitting the tool_calls mechanism.
 * The prose-rescue parser must accept exactly one call in the trimmed content
 * and reject prose that merely mentions a tool (the 2026-10-29 Angular alert
 * burned a listing on three such tries).
 */
class NarrationRescueTest {

    @Test
    fun `parses bare call with no arguments`() {
        val call = BrowserAgent.parseProseToolCall("browser_snapshot()")

        assertThat(call).isNotNull
        assertThat(call!!.name).isEqualTo("browser_snapshot")
        assertThat(call.arguments.isEmpty).isTrue()
    }

    @Test
    fun `parses call with keyword arguments`() {
        val call = BrowserAgent.parseProseToolCall("""skip_task(reason="portal-block")""")

        assertThat(call).isNotNull
        assertThat(call!!.name).isEqualTo("skip_task")
        assertThat(call.arguments.path("reason").asText()).isEqualTo("portal-block")
    }

    @Test
    fun `parses call with multiple keyword arguments`() {
        val call = BrowserAgent.parseProseToolCall(
            """browser_tabs(action="select", index=2)""",
        )

        assertThat(call).isNotNull
        assertThat(call!!.name).isEqualTo("browser_tabs")
        assertThat(call.arguments.path("action").asText()).isEqualTo("select")
        assertThat(call.arguments.path("index").asInt()).isEqualTo(2)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val call = BrowserAgent.parseProseToolCall("  browser_navigate( url=\"https://x.pl\" )  ")

        assertThat(call).isNotNull
        assertThat(call!!.name).isEqualTo("browser_navigate")
        assertThat(call.arguments.path("url").asText()).isEqualTo("https://x.pl")
    }

    @Test
    fun `rejects unknown tool names`() {
        assertThat(BrowserAgent.parseProseToolCall("some_random_call()")).isNull()
        assertThat(BrowserAgent.parseProseToolCall("reply_to_user(reason=\"hi\")")).isNull()
    }

    @Test
    fun `rejects prose that merely mentions a tool`() {
        assertThat(
            BrowserAgent.parseProseToolCall("I'll call browser_snapshot() next."),
        ).isNull()
        assertThat(
            BrowserAgent.parseProseToolCall("Let me take a snapshot with browser_snapshot() and see."),
        ).isNull()
    }

    @Test
    fun `rejects calls with trailing prose after the paren block`() {
        assertThat(
            BrowserAgent.parseProseToolCall("browser_snapshot() then I will decide"),
        ).isNull()
    }

    @Test
    fun `rejects unbalanced or missing parens`() {
        assertThat(BrowserAgent.parseProseToolCall("browser_snapshot")).isNull()
        assertThat(BrowserAgent.parseProseToolCall("browser_snapshot()extra")).isNull()
        assertThat(BrowserAgent.parseProseToolCall("(browser_snapshot())")).isNull()
    }

    @Test
    fun `narration exhaustion details classify as serious failure`() {
        // The detail text the loop produces on NARRATION_LIMIT prose trips
        // contains "no tool call", so the retry path records it in attempt
        // history (2026-10-29 fix: the failure class is retryable AND learned).
        val detail = "model produced no tool call after 3 tries: browser_snapshot()"

        assertThat(BrowserAgent.isSeriousFailure(detail)).isTrue()
    }
}
