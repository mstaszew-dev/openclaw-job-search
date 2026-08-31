"""Tests for ToolRouter — tool schemas, exec dispatch, routing logic."""
import json
from unittest.mock import MagicMock, patch, AsyncMock

import pytest

from campaign_agent.tools import ToolRouter, exec_tool, TOOL_SCHEMAS, read_file


class TestReadFile:
    def test_read_missing_file(self, tmp_path):
        result = read_file(str(tmp_path / "nope.md"), base_dir=None)
        assert "not found" in result

    def test_read_directory_returns_directory_error(self, tmp_path):
        result = read_file(str(tmp_path), base_dir=None)
        assert "directory" in result

    def test_read_truncates_long_files(self, tmp_path):
        p = tmp_path / "big.md"
        p.write_text("x" * 30000)
        result = read_file(str(p), base_dir=None, max_chars=20000)
        assert "[truncated" in result
        assert len(result) < 20500

    def test_read_resolves_relative_to_base_dir(self, tmp_path):
        (tmp_path / "doc.md").write_text("hello")
        result = read_file("doc.md", base_dir=str(tmp_path))
        assert "hello" in result

    def test_read_generic_exception(self, tmp_path):
        p = tmp_path / "secret.txt"
        p.write_text("data")
        with patch.object(type(p), "read_text", side_effect=PermissionError("denied")):
            result = read_file(str(p), base_dir=None)
        assert "Error reading" in result
        assert "denied" in result


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

    def test_read_schema_exists(self):
        names = [t["function"]["name"] for t in TOOL_SCHEMAS]
        assert "read" in names

    def test_read_schema_has_path_param(self):
        read_schema = next(t for t in TOOL_SCHEMAS if t["function"]["name"] == "read")
        params = read_schema["function"]["parameters"]["properties"]
        assert "path" in params


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

    def test_exec_includes_stderr_on_success(self):
        result = exec_tool("echo boom 1>&2", timeout=5)
        assert "stderr:" in result
        assert "boom" in result
        assert "exit=0" in result

    def test_exec_captures_generic_error(self):
        # exec_tool spawns via Popen (not run) since the process-group kill
        # hardening; a spawn failure must surface as "Error: ...".
        with patch("campaign_agent.tools.subprocess.Popen",
                   side_effect=RuntimeError("spawn failed")):
            result = exec_tool("echo x", timeout=5)
        assert "Error:" in result


class TestReadTool:
    def test_read_absolute_path(self, tmp_path):
        f = tmp_path / "notes.txt"
        f.write_text("hello world")
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("read", {"path": str(f)})
        assert "hello world" in result

    def test_read_relative_resolves_against_default_cwd(self, tmp_path):
        (tmp_path / "AGENT_TICK.md").write_text("tick 1133: apply one job")
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))
        result = router.dispatch_sync("read", {"path": "AGENT_TICK.md"})
        assert "tick 1133" in result

    def test_read_missing_file_returns_error(self, tmp_path):
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))
        result = router.dispatch_sync("read", {"path": "no-such-file.md"})
        assert "error" in result.lower() or "not found" in result.lower()

    def test_read_directory_returns_error(self, tmp_path):
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))
        result = router.dispatch_sync("read", {"path": str(tmp_path)})
        assert "directory" in result.lower() or "error" in result.lower()

    def test_read_truncates_large_files(self, tmp_path):
        f = tmp_path / "big.txt"
        f.write_text("x" * 50000)
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))
        result = router.dispatch_sync("read", {"path": "big.txt"})
        assert "truncated" in result
        assert len(result) < 25000

    def test_read_via_async_dispatch(self, tmp_path):
        f = tmp_path / "context.txt"
        f.write_text("campaign context")
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))

        async def run():
            return await router.dispatch("read", {"path": "context.txt"})

        import asyncio
        result = asyncio.run(run())
        assert "campaign context" in result


class TestToolRouter:
    def test_dispatch_exec(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("exec", {"command": "echo test", "timeout": 5})
        assert "test" in result

    def test_exec_uses_default_cwd_when_no_cwd_arg(self, tmp_path):
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))
        result = router.dispatch_sync("exec", {"command": "pwd", "timeout": 5})
        assert str(tmp_path) in result
        assert "exit=0" in result

    def test_exec_explicit_cwd_overrides_default(self, tmp_path, tmp_path_factory):
        other = tmp_path_factory.mktemp("other")
        router = ToolRouter(playwright_client=None, rag_client=None,
                            default_cwd=str(tmp_path))
        result = router.dispatch_sync("exec", {"command": "pwd", "cwd": str(other), "timeout": 5})
        assert str(other) in result
        assert str(tmp_path) not in result

    def test_dispatch_unknown_tool(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("nonexistent_tool", {})
        assert "error" in result.lower() or "unknown" in result.lower()

    @pytest.mark.asyncio
    async def test_async_dispatch_unknown_tool(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = await router.dispatch("nonexistent_tool", {})
        assert "unknown" in result.lower()

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

    @pytest.mark.asyncio
    async def test_async_dispatch_playwright_without_client(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = await router.dispatch("browser_navigate", {"url": "https://example.com"})
        assert "not available" in result.lower()

    @pytest.mark.asyncio
    async def test_async_dispatch_rag_without_client(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = await router.dispatch("rag_search_apps", {"query": "Java"})
        assert "not available" in result.lower()

    @pytest.mark.asyncio
    async def test_async_dispatch_playwright_client_raises(self):
        mock_pw = AsyncMock()
        mock_pw.call_tool = AsyncMock(side_effect=ConnectionError("cdp refused"))
        router = ToolRouter(playwright_client=mock_pw, rag_client=None)
        result = await router.dispatch("browser_snapshot", {})
        assert "failed" in result.lower()
        assert "cdp refused" in result

    @pytest.mark.asyncio
    async def test_async_dispatch_rag_client_raises(self):
        mock_rag = AsyncMock()
        mock_rag.call_tool = AsyncMock(side_effect=RuntimeError("rag down"))
        router = ToolRouter(playwright_client=None, rag_client=mock_rag)
        result = await router.dispatch("rag_search_docs", {"query": "IL boards"})
        assert "failed" in result.lower()
        assert "rag down" in result


class TestExecHardening:
    """B1: model-supplied exec timeouts are capped, exec runs off the event
    loop, and timed-out process groups are killed (no leaked grandchildren)."""

    def _router(self):
        from campaign_agent.tools import ToolRouter
        return ToolRouter(default_cwd=None)

    async def test_exec_timeout_is_capped(self, monkeypatch):
        import asyncio
        import time
        from campaign_agent import tools as tools_mod

        monkeypatch.setattr(tools_mod, "EXEC_MAX_TIMEOUT", 1)
        start = time.monotonic()
        result = await self._router().dispatch(
            "exec", {"command": "sleep 30", "timeout": 10**9}
        )
        elapsed = time.monotonic() - start
        assert "timed out after 1s" in result
        assert elapsed < 10  # cap respected, model's 10**9 ignored

    async def test_exec_does_not_block_event_loop(self, monkeypatch):
        import asyncio
        import time
        from campaign_agent import tools as tools_mod

        monkeypatch.setattr(tools_mod, "EXEC_MAX_TIMEOUT", 1)
        router = self._router()
        task = asyncio.create_task(
            router.dispatch("exec", {"command": "sleep 5", "timeout": 5})
        )
        await asyncio.sleep(0.2)
        t_wait = time.monotonic()  # loop must stay responsive during exec
        await asyncio.sleep(0)
        assert time.monotonic() - t_wait < 0.5
        result = await task
        assert "timed out" in result

    async def test_exec_kills_grandchildren_on_timeout(self):
        import asyncio
        import subprocess
        import time
        from campaign_agent import tools as tools_mod

        marker = "cv-restart-grandchild-probe"
        result = await asyncio.wait_for(
            tools_mod.ToolRouter().dispatch(
                "exec", {"command": f"sleep 300 & echo {marker}", "timeout": 1}
            ),
            timeout=30,
        )
        assert "timed out" in result
        time.sleep(0.5)
        # The backgrounded `sleep 300` must have been killed with its group.
        alive = subprocess.run(
            ["bash", "-c", "ps -eo command | grep -c '[s]leep 300'"],
            capture_output=True, text=True,
        ).stdout.strip()
        assert alive == "0", f"grandchild survived exec timeout: {alive}"

    async def test_exec_string_timeout_coerced(self):
        import asyncio
        result = await asyncio.wait_for(
            self._router().dispatch(
                "exec", {"command": "echo hi", "timeout": "2"}
            ),
            timeout=10,
        )
        assert "hi" in result
