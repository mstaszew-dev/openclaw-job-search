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
    async def test_call_tool_times_out(self):
        pw = PlaywrightMCP("node", [])
        mock_session = AsyncMock()

        async def never_completes(*_a, **_k):
            await asyncio.sleep(30)

        mock_session.call_tool = never_completes
        pw._session = mock_session
        result = await pw.call_tool("browser_snapshot", {}, timeout=0.05)
        assert "timed out" in result.lower()

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
