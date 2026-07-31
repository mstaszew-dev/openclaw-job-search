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
