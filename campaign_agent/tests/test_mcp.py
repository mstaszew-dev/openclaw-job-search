"""Tests for Playwright MCP and RAG MCP wrappers — connection, tool calls, error handling."""
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from campaign_agent.playwright_mcp import PlaywrightMCP
from campaign_agent.rag_mcp import RAGMCP


class TestPlaywrightMCP:
    def test_init_stores_params(self):
        pw = PlaywrightMCP("/usr/bin/node", ["--cdp-endpoint", "http://localhost:9222"])
        assert pw.params.command == "/usr/bin/node"
        assert "--cdp-endpoint" in pw.params.args

    def test_init_empty_session(self):
        pw = PlaywrightMCP("node", [])
        assert pw._session is None

    @pytest.mark.asyncio
    async def test_call_tool_without_connection(self):
        pw = PlaywrightMCP("node", [])
        result = await pw.call_tool("browser_navigate", {"url": "https://example.com"})
        assert "not connected" in result.lower()

    @pytest.mark.asyncio
    async def test_call_tool_with_mock_session(self):
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.content = [MagicMock(text="Page loaded successfully")]
        mock_session.call_tool = AsyncMock(return_value=mock_result)
        pw._session = mock_session

        result = await pw.call_tool("browser_navigate", {"url": "https://example.com"})
        assert "Page loaded" in result

    @pytest.mark.asyncio
    async def test_call_tool_handles_exception(self):
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()
        mock_session.call_tool = AsyncMock(side_effect=RuntimeError("Connection lost"))
        pw._session = mock_session

        result = await pw.call_tool("browser_navigate", {"url": "https://example.com"})
        assert "Error" in result
        assert "Connection lost" in result

    @pytest.mark.asyncio
    async def test_call_tool_handles_dict_content(self):
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.content = [{"text": "Dict content"}]
        mock_session.call_tool = AsyncMock(return_value=mock_result)
        pw._session = mock_session

        result = await pw.call_tool("browser_snapshot", {})
        assert "Dict content" in result

    @pytest.mark.asyncio
    async def test_close_clears_session(self):
        pw = PlaywrightMCP("node", [])
        pw._session = MagicMock()
        pw._ctx_stack = [MagicMock()]
        await pw.close()
        assert pw._session is None
        assert len(pw._ctx_stack) == 0

    @pytest.mark.asyncio
    async def test_connect_spawns_and_initializes(self):
        pw = PlaywrightMCP("node", ["--cdp-endpoint", "http://127.0.0.1:9222"])
        mock_read_write = MagicMock()
        mock_read_write.__aenter__ = AsyncMock(return_value=("read_stream", "write_stream"))
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(return_value=mock_session)
        mock_session.initialize = AsyncMock()
        with patch("campaign_agent.playwright_mcp.stdio_client", return_value=mock_read_write), \
             patch("campaign_agent.playwright_mcp.ClientSession", return_value=mock_session):
            await pw.connect()
        assert pw._session is mock_session
        mock_session.initialize.assert_awaited_once()
        # ClientSession was constructed with the stdio streams
        stream_args = mock_session.await_args  # not needed; assert session stored

    @pytest.mark.asyncio
    async def test_connect_failure_propagates(self):
        pw = PlaywrightMCP("node", [])
        mock_read_write = MagicMock()
        mock_read_write.__aenter__ = AsyncMock(return_value=("r", "w"))
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(side_effect=RuntimeError("spawn failed"))
        with patch("campaign_agent.playwright_mcp.stdio_client", return_value=mock_read_write), \
             patch("campaign_agent.playwright_mcp.ClientSession", return_value=mock_session):
            with pytest.raises(RuntimeError, match="spawn failed"):
                await pw.connect()

    @pytest.mark.asyncio
    async def test_connect_failure_unwinds_ctx_stack_silently(self):
        """Lines 51-52: when the session enters the ctx stack but its
        __aenter__/initialize fails, the unwind must swallow a SECOND error
        from __aexit__ itself (session half-open) and still clear the stack."""
        pw = PlaywrightMCP("node", [])
        mock_read_write = MagicMock()
        mock_read_write.__aenter__ = AsyncMock(return_value=("r", "w"))
        # __aexit__ also fails: proves the swallow-guard, not just the unwind
        mock_read_write.__aexit__ = AsyncMock(side_effect=RuntimeError("exit also failed"))
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(side_effect=RuntimeError("init failed"))
        with patch("campaign_agent.playwright_mcp.stdio_client", return_value=mock_read_write), \
             patch("campaign_agent.playwright_mcp.ClientSession", return_value=mock_session):
            with pytest.raises(RuntimeError, match="init failed"):
                await pw.connect()
        assert pw._ctx_stack == []  # unwound despite the failing __aexit__
        mock_read_write.__aexit__.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_call_tool_times_out(self):
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()

        async def never_completes(*_a, **_k):
            await asyncio.sleep(30)

        mock_session.call_tool = never_completes
        pw._session = mock_session
        pw.close = AsyncMock()
        pw.connect = AsyncMock()
        result = await pw.call_tool("browser_snapshot", {}, timeout=0.05)
        assert "timed out" in result.lower()

    @pytest.mark.asyncio
    async def test_timeout_dismisses_leave_dialog_and_retries(self):
        """A pending native dialog ("Leave site?" beforeunload from form
        pages like ATS/registration portals) blocks every page-level
        operation and everything queued behind it. On timeout the wrapper
        must dismiss the dialog (accept = proceed with leaving) and retry
        the original tool once, instead of burning the full timeout and
        wedging the whole MCP session (2026-09-15: 6h of 120s timeouts)."""
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()
        calls = []
        mock_result = MagicMock()
        mock_result.content = [MagicMock(text="navigated")]

        async def scripted(name, arguments=None):
            calls.append(name)
            if name == "browser_handle_dialog":
                return MagicMock(content=[MagicMock(text="dialog accepted")])
            if len([c for c in calls if c == name]) <= 1:
                await asyncio.sleep(30)
            return MagicMock(content=[MagicMock(text="navigated ok")])

        mock_session.call_tool = scripted
        pw._session = mock_session
        result = await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert "navigated ok" in result
        assert "dialog was auto-accepted" in result
        assert calls == ["browser_navigate", "browser_handle_dialog", "browser_navigate"]
        assert pw._consecutive_failures == 0

    @pytest.mark.asyncio
    async def test_dialog_clear_then_retry_still_failing_counts_strike(self):
        """If the dialog dismiss answers but the retry still hangs, that is
        a real wedge: one strike is recorded (the respawn watchdog acts at 3)."""
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()

        async def scripted(name, arguments=None):
            if name == "browser_handle_dialog":
                return MagicMock(content=[MagicMock(text="no dialog open")])
            await asyncio.sleep(30)

        mock_session.call_tool = scripted
        pw._session = mock_session
        pw.close = AsyncMock()
        pw.connect = AsyncMock()
        result = await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert "timed out" in result
        assert pw._consecutive_failures == 1

    @pytest.mark.asyncio
    async def test_retry_error_string_reported_verbatim_no_strike(self):
        """When the post-dismiss retry ANSWERS with an error result (e.g.
        navigation refused), the session is alive: report the real error
        text verbatim (never rebranded as 'timed out') and reset the wedge
        counter - consistent with the normal path where error-string
        results are healthy responses."""
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()
        calls = []

        async def scripted(name, arguments=None):
            calls.append(name)
            if name == "browser_handle_dialog":
                return MagicMock(content=[MagicMock(text="dialog accepted")])
            if calls.count(name) == 1:
                await asyncio.sleep(30)
            return MagicMock(content=[MagicMock(text="Error: target closed")])

        mock_session.call_tool = scripted
        pw._session = mock_session
        result = await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert "target closed" in result
        assert "timed out" not in result
        assert pw._consecutive_failures == 0

    @pytest.mark.asyncio
    async def test_wedged_dialog_clear_goes_straight_to_strike(self):
        """When even the dismiss call hangs, the session is dead: no retry,
        straight to the wedge watchdog counting."""
        pw = PlaywrightMCP("node", [], dialog_clear_timeout=0.05)
        mock_session = AsyncMock()
        calls = []

        async def never_completes(name, arguments=None):
            calls.append(name)
            await asyncio.sleep(30)

        mock_session.call_tool = never_completes
        pw._session = mock_session
        pw.close = AsyncMock()
        pw.connect = AsyncMock()
        result = await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert "timed out" in result
        assert pw._consecutive_failures == 1
        # Exactly one original call + one dismiss attempt, then failure handling.
        assert calls == ["browser_navigate", "browser_handle_dialog"]

    @pytest.mark.asyncio
    async def test_consecutive_failures_respawn_mcp(self):
        """A wedged MCP CDP session (2026-09-15: EVERY browser tool timed out
        for 6h while Chrome's own CDP HTTP layer answered instantly) must be
        respawned after a bounded streak of consecutive failures, so the turn
        recovers in place instead of grinding timeout x steps until the
        Director kills the worker."""
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        mock_session = AsyncMock()

        async def never_completes(*_a, **_k):
            await asyncio.sleep(30)

        mock_session.call_tool = never_completes
        pw._session = mock_session
        pw.close = AsyncMock()
        pw.connect = AsyncMock()

        for _ in range(3):
            result = await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert pw.close.await_count == 1
        assert pw.connect.await_count == 1
        assert "respawned" in result.lower()
        assert pw._consecutive_failures == 0  # fresh session resets the counter

    @pytest.mark.asyncio
    async def test_success_resets_wedge_counter(self):
        """Interleaved success means the session is alive: no respawn."""
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.content = [MagicMock(text="ok")]
        calls = {"n": 0}

        async def flaky(*_a, **_k):
            calls["n"] += 1
            if calls["n"] % 2 == 1:
                await asyncio.sleep(30)
            return mock_result

        mock_session.call_tool = flaky
        pw._session = mock_session
        pw.close = AsyncMock()
        pw.connect = AsyncMock()

        for _ in range(4):
            await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert pw.close.await_count == 0
        assert pw.connect.await_count == 0

    @pytest.mark.asyncio
    async def test_respawn_failure_keeps_counting(self):
        """If the respawn itself fails (Chrome down), report it and keep the
        counter so later failures retry the respawn."""
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=2)
        mock_session = AsyncMock()

        async def never_completes(*_a, **_k):
            await asyncio.sleep(30)

        mock_session.call_tool = never_completes
        pw._session = mock_session
        pw.close = AsyncMock()
        pw.connect = AsyncMock(side_effect=RuntimeError("spawn failed"))

        await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        result = await pw.call_tool("browser_navigate", {"url": "x"}, timeout=0.05)
        assert pw.connect.await_count == 1
        assert "respawn failed" in result.lower()
        # Counter NOT reset: the next failure retries the respawn.
        assert pw._consecutive_failures == 2

    @pytest.mark.asyncio
    async def test_close_swallows_exit_exceptions(self):
        pw = PlaywrightMCP("node", [])
        bad_ctx = MagicMock()
        bad_ctx.__aexit__ = AsyncMock(side_effect=RuntimeError("exit failed"))
        pw._session = MagicMock()
        pw._ctx_stack = [bad_ctx]
        await pw.close()  # must not raise
        assert pw._session is None
        assert pw._ctx_stack == []


class TestRAGMCP:
    def test_init_stores_params(self):
        rag = RAGMCP("/usr/bin/python", ["rag_server.py"])
        assert rag.params.command == "/usr/bin/python"
        assert "rag_server.py" in rag.params.args

    def test_init_empty_session(self):
        rag = RAGMCP("python", [])
        assert rag._session is None

    @pytest.mark.asyncio
    async def test_call_tool_without_connection(self):
        rag = RAGMCP("python", [])
        result = await rag.call_tool("rag_search_apps", {"query": "Java developer"})
        assert "not connected" in result.lower()

    @pytest.mark.asyncio
    async def test_call_tool_with_mock_session(self):
        rag = RAGMCP("python", [])
        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.content = [MagicMock(text='{"results": [], "score": 0.5}')]
        mock_session.call_tool = AsyncMock(return_value=mock_result)
        rag._session = mock_session

        result = await rag.call_tool("rag_search_apps", {"query": "Java developer"})
        assert "results" in result

    @pytest.mark.asyncio
    async def test_call_tool_handles_exception(self):
        rag = RAGMCP("python", [])
        mock_session = AsyncMock()
        mock_session.call_tool = AsyncMock(side_effect=RuntimeError("RAG server crashed"))
        rag._session = mock_session

        result = await rag.call_tool("rag_search_apps", {"query": "Java"})
        assert "Error" in result
        assert "RAG server crashed" in result

    @pytest.mark.asyncio
    async def test_close_clears_session(self):
        rag = RAGMCP("python", [])
        rag._session = MagicMock()
        rag._ctx_stack = [MagicMock()]
        await rag.close()
        assert rag._session is None
        assert len(rag._ctx_stack) == 0

    @pytest.mark.asyncio
    async def test_connect_spawns_and_initializes(self):
        rag = RAGMCP("/usr/bin/python", ["rag_server.py"])
        mock_read_write = MagicMock()
        mock_read_write.__aenter__ = AsyncMock(return_value=("read_stream", "write_stream"))
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(return_value=mock_session)
        mock_session.initialize = AsyncMock()
        with patch("campaign_agent.rag_mcp.stdio_client", return_value=mock_read_write), \
             patch("campaign_agent.rag_mcp.ClientSession", return_value=mock_session):
            await rag.connect()
        assert rag._session is mock_session
        mock_session.initialize.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_connect_failure_propagates(self):
        rag = RAGMCP("python", [])
        mock_read_write = MagicMock()
        mock_read_write.__aenter__ = AsyncMock(return_value=("r", "w"))
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(side_effect=RuntimeError("spawn failed"))
        with patch("campaign_agent.rag_mcp.stdio_client", return_value=mock_read_write), \
             patch("campaign_agent.rag_mcp.ClientSession", return_value=mock_session):
            with pytest.raises(RuntimeError, match="spawn failed"):
                await rag.connect()

    @pytest.mark.asyncio
    async def test_connect_failure_unwinds_ctx_stack_silently(self):
        """Lines 50-51: a failed connect unwinds the ctx stack, swallowing a
        second error from __aexit__ itself, leaving the stack empty for retry."""
        rag = RAGMCP("python", [])
        mock_read_write = MagicMock()
        mock_read_write.__aenter__ = AsyncMock(return_value=("r", "w"))
        mock_read_write.__aexit__ = AsyncMock(side_effect=RuntimeError("exit also failed"))
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(side_effect=RuntimeError("init failed"))
        with patch("campaign_agent.rag_mcp.stdio_client", return_value=mock_read_write), \
             patch("campaign_agent.rag_mcp.ClientSession", return_value=mock_session):
            with pytest.raises(RuntimeError, match="init failed"):
                await rag.connect()
        assert rag._ctx_stack == []
        mock_read_write.__aexit__.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_call_tool_times_out(self):
        rag = RAGMCP("python", [])
        mock_session = AsyncMock()

        async def never_completes(*_a, **_k):
            await asyncio.sleep(30)

        mock_session.call_tool = never_completes
        rag._session = mock_session
        result = await rag.call_tool("rag_search_apps", {"query": "Java"}, timeout=0.05)
        assert "timed out" in result.lower()

    @pytest.mark.asyncio
    async def test_close_swallows_exit_exceptions(self):
        rag = RAGMCP("python", [])
        bad_ctx = MagicMock()
        bad_ctx.__aexit__ = AsyncMock(side_effect=RuntimeError("exit failed"))
        rag._session = MagicMock()
        rag._ctx_stack = [bad_ctx]
        await rag.close()  # must not raise
        assert rag._session is None
        assert rag._ctx_stack == []

    @pytest.mark.asyncio
    async def test_call_tool_multiple_results(self):
        rag = RAGMCP("python", [])
        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.content = [
            MagicMock(text="Result 1"),
            MagicMock(text="Result 2"),
        ]
        mock_session.call_tool = AsyncMock(return_value=mock_result)
        rag._session = mock_session

        result = await rag.call_tool("rag_search_docs", {"query": "B2B"})
        assert "Result 1" in result
        assert "Result 2" in result

    @pytest.mark.asyncio
    async def test_call_tool_handles_dict_content(self):
        rag = RAGMCP("python", [])
        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.content = [{"text": "Dict content"}]
        mock_session.call_tool = AsyncMock(return_value=mock_result)
        rag._session = mock_session

        result = await rag.call_tool("rag_search_apps", {"query": "Java"})
        assert "Dict content" in result


def _make_wedged_session_mocks():
    """Mocks where session.initialize() hangs forever (the 2026-08-31 wedge
    class: MCP startup blocking connect() with no timeout)."""
    mock_read_write = MagicMock()
    mock_read_write.__aenter__ = AsyncMock(return_value=("r", "w"))
    mock_read_write.__aexit__ = AsyncMock(return_value=False)

    async def hang_initialize():
        await asyncio.sleep(60)

    mock_session = AsyncMock()
    mock_session.__aenter__ = AsyncMock(return_value=mock_session)
    mock_session.initialize = hang_initialize
    return mock_read_write, mock_session


@pytest.mark.asyncio
async def test_playwright_connect_initialize_timeout_cleans_up():
    pw = PlaywrightMCP("node", [])
    mock_read_write, mock_session = _make_wedged_session_mocks()
    with patch("campaign_agent.playwright_mcp.stdio_client", return_value=mock_read_write), \
         patch("campaign_agent.playwright_mcp.ClientSession", return_value=mock_session), \
         patch("campaign_agent.playwright_mcp.CONNECT_TIMEOUT_S", 0.2):
        with pytest.raises(asyncio.TimeoutError):
            await pw.connect()
    assert pw._session is None
    mock_read_write.__aexit__.assert_awaited_once()


@pytest.mark.asyncio
async def test_rag_connect_initialize_timeout_cleans_up():
    rag = RAGMCP("python", ["-m", "rag_server"])
    mock_read_write, mock_session = _make_wedged_session_mocks()
    with patch("campaign_agent.rag_mcp.stdio_client", return_value=mock_read_write), \
         patch("campaign_agent.rag_mcp.ClientSession", return_value=mock_session), \
         patch("campaign_agent.rag_mcp.CONNECT_TIMEOUT_S", 0.2):
        with pytest.raises(asyncio.TimeoutError):
            await rag.connect()
    assert rag._session is None
    mock_read_write.__aexit__.assert_awaited_once()
