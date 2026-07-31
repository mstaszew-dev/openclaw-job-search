"""Tests for ToolRouter — tool schemas, exec dispatch, routing logic."""
import json
from unittest.mock import MagicMock, patch, AsyncMock

import pytest

from campaign_agent.tools import ToolRouter, exec_tool, TOOL_SCHEMAS


class TestToolSchemas:
    def test_exec_schema_exists(self):
        names = [t["function"]["name"] for t in TOOL_SCHEMAS]
        assert "exec" in names

    def test_rag_search_apps_schema_exists(self):
        names = [t["function"]["name"] for t in TOOL_SCHEMAS]
        assert "rag_search_apps" in names

    def test_exec_schema_has_command_param(self):
        exec_schema = next(t for t in TOOL_SCHEMAS if t["function"]["name"] == "exec")
        params = exec_schema["function"]["parameters"]["properties"]
        assert "command" in params

    def test_all_schemas_have_type_function(self):
        for schema in TOOL_SCHEMAS:
            assert schema["type"] == "function"


class TestExecTool:
    def test_exec_echo(self):
        result = exec_tool("echo hello", timeout=5)
        assert "hello" in result

    def test_exec_exit_code_in_result(self):
        result = exec_tool("echo test", timeout=5)
        assert "exit=0" in result or "exit_code" in result.lower() or "test" in result

    def test_exec_failure_includes_stderr(self):
        result = exec_tool("false", timeout=5)
        # 'false' always returns exit code 1
        assert "1" in result

    def test_exec_timeout(self):
        result = exec_tool("sleep 10", timeout=1)
        # Should timeout and report it
        assert "timeout" in result.lower() or "timed out" in result.lower()


class TestToolRouter:
    def test_dispatch_exec(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("exec", {"command": "echo test", "timeout": 5})
        assert "test" in result

    def test_dispatch_unknown_tool(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("nonexistent_tool", {})
        assert "error" in result.lower() or "unknown" in result.lower()

    def test_dispatch_playwright_without_client(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("browser_navigate", {"url": "https://example.com"})
        assert "not available" in result.lower() or "error" in result.lower()

    def test_dispatch_rag_without_client(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("rag_search_apps", {"query": "Java developer"})
        assert "not available" in result.lower() or "error" in result.lower()

    @pytest.mark.asyncio
    async def test_dispatch_playwright_with_mock_client(self):
        mock_pw = AsyncMock()
        mock_pw.call_tool = AsyncMock(return_value="Page loaded")
        router = ToolRouter(playwright_client=mock_pw, rag_client=None)
        result = await router.dispatch("browser_navigate", {"url": "https://example.com"})
        assert "Page loaded" in result

    @pytest.mark.asyncio
    async def test_dispatch_rag_with_mock_client(self):
        mock_rag = AsyncMock()
        mock_rag.call_tool = AsyncMock(return_value="No matches found")
        router = ToolRouter(playwright_client=None, rag_client=mock_rag)
        result = await router.dispatch("rag_search_apps", {"query": "Java"})
        assert "No matches" in result
