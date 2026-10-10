"""Tests for ToolRouter — tool schemas, exec dispatch, routing logic."""
import asyncio
import concurrent.futures
import json
import os
import subprocess
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

import campaign_agent.tools as tools_mod
from campaign_agent.tools import TOOL_SCHEMAS, ToolRouter, exec_tool, read_file


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

    def test_read_binary_file_replaces_invalid_utf8(self, tmp_path):
        p = tmp_path / "blob.png"
        p.write_bytes(b"\x89PNG\r\n\x1a\n ok")
        result = read_file(str(p), base_dir=None)
        assert "PNG" in result
        assert "\ufffd" in result


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

    def test_exec_timeout_stderr_included(self):
        """Line 273: a timed-out command's stderr must be reported too."""
        result = exec_tool("echo err-part 1>&2; sleep 10", timeout=1)
        assert "timed out after 1s" in result
        assert "stderr: err-part" in result

    def test_exec_timeout_killpg_process_gone_falls_back_to_kill(self):
        """Lines 266-267: when the process group is already reaped between
        SIGKILL and killpg (ProcessLookupError), the plain kill() fallback
        must still run and communicate() must return."""
        import campaign_agent.tools as t

        def fake_killpg(pid, sig):
            raise ProcessLookupError()

        orig_killpg = t.os.killpg
        t.os.killpg = fake_killpg
        try:
            result = exec_tool("sleep 5", timeout=1)
        finally:
            t.os.killpg = orig_killpg
        assert "timed out after 1s" in result

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

    async def test_dispatch_timeout_coercion_bad_value(self):
        """Lines 328-329: a non-numeric timeout falls back to the default
        instead of raising through dispatch."""
        import asyncio
        result = await asyncio.wait_for(
            self._router().dispatch(
                "exec", {"command": "echo coerced", "timeout": None}
            ),
            timeout=60,
        )
        assert "coerced" in result
        assert "exit=0" in result

    async def test_exec_string_timeout_coerced(self):
        import asyncio
        result = await asyncio.wait_for(
            self._router().dispatch(
                "exec", {"command": "echo hi", "timeout": "2"}
            ),
            timeout=10,
        )
        assert "hi" in result

    async def test_exec_binary_output_does_not_crash_dispatch(self):
        """Invalid-UTF-8 subprocess output must decode with U+FFFD replacement,
        never raise UnicodeDecodeError. The strict decode killed the whole
        campaign during a tesseract call (paste, 2026-09-08)."""
        result = await self._router().dispatch(
            "exec", {"command": "printf 'ok\\377PNG\\202end'", "timeout": 5}
        )
        assert "exit=0" in result
        assert "ok" in result
        assert "\ufffd" in result

    def test_exec_tool_replaces_invalid_utf8(self):
        result = exec_tool("printf 'ok\\377end'", timeout=5)
        assert "\ufffd" in result
        assert "exit=0" in result


class TestExecOutputCap:
    """2026-09-18 audit: exec had no output-size cap while read_file caps at
    20k chars - one `cat events.jsonl` could blow the context budget in a
    single step. exec must cap its combined output the same way."""

    def test_exec_output_capped_to_20k_chars(self):
        out = exec_tool("python3 -c \"print('x' * 100000)\"", timeout=30)
        assert len(out) <= 20100  # head + tail cap
        assert "truncated" in out
        assert out.rstrip().endswith("exit=0")  # tail survives for the gate
        assert len(out) < 100000

    def test_exec_small_output_untouched(self):
        out = exec_tool("echo hello", timeout=10)
        assert "hello" in out


class TestExecTimeoutPathCap:
    """2026-09-18 review S-2: the TimeoutExpired branch returned early and
    bypassed the cap - a command that floods stdout before timing out still
    blew the context budget. The timeout path must cap too."""

    def test_timeout_path_output_also_capped(self, monkeypatch):
        import subprocess as sp

        huge = "y" * 100_000

        def dead_group(pid, sig):
            raise ProcessLookupError()  # group already gone; handler path

        monkeypatch.setattr(tools_mod.os, "killpg", dead_group)

        class FakePopen:
            pid = 12345

            def kill(self):
                return None

            def communicate(self, timeout=None):
                if timeout is not None:
                    raise sp.TimeoutExpired(cmd="x", timeout=timeout)
                return huge, ""

        monkeypatch.setattr(tools_mod.subprocess, "Popen", lambda *a, **k: FakePopen())
        out = exec_tool("whatever", timeout=2)
        assert "timed out after 2s" in out
        assert len(out) <= 20100
        assert "truncated" in out


class TestUploadCvDispatch:
    """upload_cv routes through the router with config-pinned CV paths."""

    @pytest.mark.asyncio
    async def test_dispatches_with_pl_cv_when_it_verifies(self, tmp_path):
        base = tmp_path / "cv.pdf"
        base.write_bytes(b"%PDF-base")
        pl = tmp_path / "cv-pl.pdf"
        pl.write_bytes(b"%PDF-pl")
        client = MagicMock()
        client.call_tool = AsyncMock(return_value="uploaded 1 file")

        router = ToolRouter(
            playwright_client=client,
            cv_paths=(str(base), str(pl)),
        )
        result = await router.dispatch(
            "upload_cv", {"selector": "#up"}
        )

        assert not result.startswith("Error")
        calls = [c.args for c in client.call_tool.await_args_list]
        # click first, then upload with the PL path
        assert calls[0][0] == "browser_click"
        assert calls[1][0] == "browser_file_upload"
        assert calls[1][1]["paths"] == [str(pl)]

    @pytest.mark.asyncio
    async def test_no_playwright_client_is_an_error(self):
        router = ToolRouter(playwright_client=None)
        result = await router.dispatch("upload_cv", {})
        assert result.startswith("Error: Playwright MCP not available")

    @pytest.mark.asyncio
    async def test_missing_cv_is_rejected_without_browser_calls(self, tmp_path):
        client = MagicMock()
        client.call_tool = AsyncMock()
        router = ToolRouter(
            playwright_client=client,
            cv_paths=(str(tmp_path / "a.pdf"), str(tmp_path / "b.pdf")),
        )
        result = await router.dispatch("upload_cv", {})
        assert result.startswith("Error")
        assert "not found" in result
        client.call_tool.assert_not_awaited()


class TestRecordTrackerEvent:
    """The model used to record a submission by hand-building JSON inside an
    exec command line. It kept getting the quoting wrong (one unquoted colon
    failed as a shell error) and then fell back to editing tracker.json
    directly with python3 -c, which skips update_tracker.py's lock, evidence
    check, high-watermark guard, and append-only event log. This tool calls the
    same script with an argv list, so no shell quoting is involved."""

    @staticmethod
    def _fake_script(tmp_path, body="print('recorded')"):
        script = tmp_path / "update_tracker.py"
        script.write_text(
            "import json, sys, pathlib\n"
            "pathlib.Path('argv.json').write_text(json.dumps(sys.argv[1:]))\n"
            f"{body}\n",
            encoding="utf-8",
        )
        return script

    def test_missing_script_is_a_clear_error(self, tmp_path):
        result = tools_mod.record_tracker_event({"source": "x"}, str(tmp_path))
        assert result.startswith("Error")
        assert "update_tracker.py" in result

    def test_missing_required_fields_fails_before_running(self, tmp_path):
        self._fake_script(tmp_path)
        result = tools_mod.record_tracker_event({"company": "Acme"}, str(tmp_path))
        assert result.startswith("Error")
        assert not (tmp_path / "argv.json").exists()

    def test_arguments_become_script_argv_without_a_shell(self, tmp_path):
        self._fake_script(tmp_path)
        result = tools_mod.record_tracker_event(
            {
                "source": "nofluffjobs",
                "sourceJobId": "abc-123",
                "company": 'Acme "quoted" Ltd',
                "roleTitle": "Senior Java",
                "evidence": {"type": "portal_confirmation", "text": "thanks"},
                "emptyField": "",
                "noneField": None,
            },
            str(tmp_path),
        )
        assert "exit=0" in result

        argv = json.loads((tmp_path / "argv.json").read_text(encoding="utf-8"))
        action, payload = argv[0], json.loads(argv[1])
        assert action == "submitted"
        assert payload["source"] == "nofluffjobs"
        assert payload["sourceJobId"] == "abc-123"
        # Structured evidence survives; empty/None args are dropped.
        assert payload["evidence"]["type"] == "portal_confirmation"
        assert "emptyField" not in payload and "noneField" not in payload
        assert "action" not in payload and "timeout" not in payload

    def test_explicit_action_is_forwarded(self, tmp_path):
        self._fake_script(tmp_path)
        tools_mod.record_tracker_event(
            {"action": "skippedDuplicate", "source": "nofluffjobs",
             "sourceJobId": "abc-123"},
            str(tmp_path),
        )
        argv = json.loads((tmp_path / "argv.json").read_text(encoding="utf-8"))
        assert argv[0] == "skippedDuplicate"

    def test_script_failure_is_reported_not_swallowed(self, tmp_path):
        self._fake_script(tmp_path, body="raise SystemExit(3)")
        result = tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123"}, str(tmp_path)
        )
        assert "exit=3" in result
        assert not result.startswith("Error")

    def test_stderr_from_the_script_is_surfaced(self, tmp_path):
        self._fake_script(tmp_path, body="import sys; sys.stderr.write('warning: x')")
        result = tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123"}, str(tmp_path)
        )
        assert "stderr: warning: x" in result
        assert "exit=0" in result

    def test_timeout_is_reported(self, tmp_path):
        self._fake_script(tmp_path)
        with patch("campaign_agent.tools.subprocess.run",
                   side_effect=subprocess.TimeoutExpired("update_tracker.py", 30)):
            result = tools_mod.record_tracker_event(
                {"source": "nofluffjobs", "sourceJobId": "abc-123"}, str(tmp_path)
            )
        assert "timed out" in result

    def test_oserror_is_reported_as_could_not_be_run(self, tmp_path):
        self._fake_script(tmp_path)
        with patch("campaign_agent.tools.subprocess.run", side_effect=OSError("no exec")):
            result = tools_mod.record_tracker_event(
                {"source": "nofluffjobs", "sourceJobId": "abc-123"}, str(tmp_path)
            )
        assert "could not be run" in result

    def test_unexpected_error_is_reported_as_failed(self, tmp_path):
        self._fake_script(tmp_path)
        with patch("campaign_agent.tools.subprocess.run", side_effect=ValueError("boom")):
            result = tools_mod.record_tracker_event(
                {"source": "nofluffjobs", "sourceJobId": "abc-123"}, str(tmp_path)
            )
        assert "failed: boom" in result

    def test_dispatch_falls_back_on_a_bad_timeout(self, tmp_path):
        self._fake_script(tmp_path)
        router = ToolRouter(default_cwd=str(tmp_path))
        out = asyncio.run(router.dispatch(
            "record_submission",
            {"source": "nofluffjobs", "sourceJobId": "abc-123", "timeout": "soon"},
        ))
        assert "exit=0" in out

    def test_dispatch_routes_the_tool(self, tmp_path):
        self._fake_script(tmp_path)
        router = ToolRouter(default_cwd=str(tmp_path))
        out = asyncio.run(router.dispatch(
            "record_submission",
            {"source": "nofluffjobs", "sourceJobId": "abc-123"},
        ))
        assert "exit=0" in out


class TestRecordSubmissionHardening:
    """Follow-up review: the model chooses every argument, so the write path
    must refuse forged ledger identity and must not be reachable through exec."""

    def _script(self, tmp_path):
        s = tmp_path / "update_tracker.py"
        s.write_text(
            "import json, sys, pathlib\n"
            "pathlib.Path('argv.json').write_text(json.dumps(sys.argv[1:]))\n"
            "print('submitted: x')\n",
            encoding="utf-8",
        )
        return s

    def test_identity_and_counter_fields_are_dropped(self, tmp_path):
        self._script(tmp_path)
        tools_mod.record_tracker_event(
            {
                "source": "nofluffjobs", "sourceJobId": "REAL-123",
                "company": "Acme",
                "id": "nofluffjobs:FORGED-999", "status": "submitted",
                "appliedAt": "whenever", "at": "whenever",
                "stats": {"submitted": 9999}, "target": 1,
                "companyKey": "acme",
            },
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        for forged in ("id", "status", "appliedAt", "at", "stats", "target"):
            assert forged not in rec, forged
        # The model's companyKey is refused and the derived one is written:
        # the repeat-block guard reads `companyKey or company`, so a wrong key
        # would override a correct company and get it skipped forever.
        assert rec["companyKey"] == "acme"
        assert rec["sourceJobId"] == "REAL-123"
        assert rec["company"] == "Acme"

    def test_unknown_action_is_rejected_before_the_script_runs(self, tmp_path):
        self._script(tmp_path)
        result = tools_mod.record_tracker_event(
            {"action": "submitted exit=0", "source": "nofluffjobs",
             "sourceJobId": "abc-123"},
            str(tmp_path),
        )
        assert "unknown action" in result
        assert not (tmp_path / "argv.json").exists()

    def test_shell_metacharacters_reach_the_script_verbatim(self, tmp_path):
        self._script(tmp_path)
        nasty = "Acme; touch /tmp/PWNED $(id) `id`"
        tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123", "company": nasty},
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        assert rec["company"] == nasty
        assert not os.path.exists("/tmp/PWNED")

    def test_non_utf8_child_output_does_not_fail_a_successful_write(self, tmp_path):
        script = tmp_path / "update_tracker.py"
        script.write_bytes(
            b"import sys\n"
            b"sys.stdout.buffer.write(b'submitted: x\\n')\n"
            b"sys.stdout.buffer.write(b'caf\\xe9\\n')\n"
        )
        result = tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123"}, str(tmp_path)
        )
        assert "exit=0" in result
        assert "caf" in result

    def test_oversized_record_is_refused(self, tmp_path):
        self._script(tmp_path)
        result = tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123",
             "notes": "x" * (tools_mod._TRACKER_MAX_RECORD_BYTES + 10)},
            str(tmp_path),
        )
        assert "too large" in result
        assert not (tmp_path / "argv.json").exists()

    def test_exec_cannot_run_the_tracker(self, tmp_path):
        router = ToolRouter(default_cwd=str(tmp_path))
        for command in (
            "python3 update_tracker.py submitted '{}'",
            "/Users/mst/Downloads/job-search/job-apply/update_tracker.py error '{}'",
        ):
            out = asyncio.run(router.dispatch("exec", {"command": command}))
            assert out.startswith("Error"), command
            assert "record_submission" in out

    def test_followup_keeps_its_idempotency_key_and_body(self, tmp_path):
        """A single flat allowlist would strip eventId/messageId and message,
        which update_tracker.py uses for followUp idempotency and payload."""
        self._script(tmp_path)
        tools_mod.record_tracker_event(
            {
                "action": "followUp", "source": "nofluffjobs",
                "sourceJobId": "abc-123", "applicationId": "app-7",
                "eventId": "EMAIL-42", "message": "Wrote to the recruiter",
            },
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        assert rec["applicationId"] == "app-7"
        assert rec["eventId"] == "EMAIL-42"
        assert rec["message"] == "Wrote to the recruiter"

    def test_blocked_manual_keeps_its_block_reason(self, tmp_path):
        self._script(tmp_path)
        tools_mod.record_tracker_event(
            {"action": "blockedManual", "source": "drushim",
             "sourceJobId": "x", "blockReason": "captcha", "reason": "captcha"},
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        assert rec["blockReason"] == "captcha"
        assert rec["reason"] == "captcha"

    def test_every_confirmation_alias_the_validator_reads_is_forwarded(self, tmp_path):
        """An allowlist once dropped confirmationText, which is on 941 of the
        1938 live applications. The script then recorded `attempted` instead of
        `submitted` and the real application stopped being counted, with no
        error anywhere. These names must never be filtered again."""
        self._script(tmp_path)
        aliases = (
            "confirmationText", "confirmation", "submitConfirmation",
            "emailConfirmation", "portalConfirmationText", "confirmation_evidence",
            "confirmationUrl", "successUrl", "thankYouUrl", "confirmation_url",
            "evidence_url", "apply_url", "confirmed", "submissionConfirmed",
            "submittedConfirmed",
        )
        tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123",
             **{a: "x" for a in aliases}},
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        for alias in aliases:
            assert alias in rec, alias

    def test_forbidden_fields_stay_refused_with_the_denylist(self, tmp_path):
        """The fix for the alias bug must not reopen ledger forging."""
        self._script(tmp_path)
        tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123",
             "id": "x:FORGED", "status": "submitted", "appliedAt": "whenever",
             "at": "whenever", "stats": {"submitted": 9999}, "target": 1,
             "companyKey": "acme", "roleKey": "r"},
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        for forged in ("id", "status", "appliedAt", "at", "stats", "target",
                       "companyKey", "roleKey"):
            assert forged not in rec, forged
        assert rec["sourceJobId"] == "abc-123"

    @pytest.mark.parametrize("raw,expected", [
        # DEDUPE.md step 1's own examples.
        ("Google LLC", "google"),
        ("Cellebrite Mobile Synchronization Ltd", "cellebrite-mobile-synchronization"),
        ("LivePerson Inc.", "liveperson"),
        ("Funds-Tech Sp. z o.o.", "funds-tech"),
        ("Dector Sp. z o.o.", "dector"),
        ("Mindbox Sp. z o.o.", "mindbox"),
        ("N-iX", "n-ix"),
        ("Finanteq S.A.", "finanteq"),
        ("Mindbox", "mindbox"),
        ("  M&B  Consulting  ", "m-and-b-consulting"),
        # Hebrew keys stay in Hebrew: 12 live applications use them.
        ("קבוצת Aman", "קבוצת-aman"),
        # A spaced hyphen must not become a triple hyphen: 19 live companies
        # are already stored as "recruitment-room-global".
        ("Recruitment Room - Global", "recruitment-room-global"),
        ("JAVA Developer - Spring Framework", "java-developer-spring-framework"),
        ("Acme -", "acme"),
        # Degenerate names normalise to nothing; no empty key is written.
        ("Co", ""), ("AG", ""), ("---", ""),
    ])
    def test_normalize_dedupe_key_matches_dedupe_md(self, raw, expected):
        assert tools_mod.normalize_dedupe_key(raw) == expected

    def test_dedupe_keys_are_derived_not_supplied(self, tmp_path):
        """companyKey is the dedupe join key on 1751 of 1938 live rows, and the
        model's value overrides the correct company in the repeat-block guard.
        So the tool derives it and refuses the model's."""
        self._script(tmp_path)
        tools_mod.record_tracker_event(
            {"source": "nofluffjobs", "sourceJobId": "abc-123",
             "company": "Mindbox Sp. z o.o.", "roleTitle": "Senior Java Engineer",
             "companyKey": "attacker-controlled", "roleKey": "x"},
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        assert rec["companyKey"] == "mindbox"
        assert rec["roleKey"] == "senior-java-engineer"

    def test_no_dedupe_key_is_written_without_a_source_field(self, tmp_path):
        self._script(tmp_path)
        tools_mod.record_tracker_event(
            {"action": "skippedSalary", "source": "nofluffjobs",
             "sourceJobId": "abc-123", "notes": "too junior"},
            str(tmp_path),
        )
        rec = json.loads(json.loads((tmp_path / "argv.json").read_text())[1])
        assert "companyKey" not in rec and "roleKey" not in rec

    def test_two_simultaneous_records_both_survive(self, tmp_path):
        """update_tracker.py holds an fcntl lock; concurrent calls must not lose
        a record (PY-3)."""
        # Append, not overwrite: the real script takes an fcntl lock and both
        # records must land, so the fake must not clobber the first line.
        (tmp_path / "update_tracker.py").write_text(
            "import json, sys, pathlib\n"
            "with open('argv.json', 'a') as fh:\n"
            "    fh.write(json.dumps(sys.argv[1:]) + chr(10))\n"
            "print('submitted: x')\n",
            encoding="utf-8",
        )
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(
                lambda job_id: tools_mod.record_tracker_event(
                    {"source": "nofluffjobs", "sourceJobId": job_id}, str(tmp_path)),
                ["abc-1", "abc-2"],
            ))
        seen = [json.loads(line)[1]
                for line in (tmp_path / "argv.json").read_text().splitlines() if line]
        assert {json.loads(r)["sourceJobId"] for r in seen} == {"abc-1", "abc-2"}
        assert len(seen) == 2

    def test_schema_enum_matches_the_action_allowlist(self):
        schema = next(s for s in TOOL_SCHEMAS
                      if s["function"]["name"] == "record_submission")
        assert set(schema["function"]["parameters"]["properties"]["action"]["enum"]) \
            == set(tools_mod._TRACKER_ACTIONS)

    def test_exec_blocks_actions_outside_the_enum(self, tmp_path):
        """`attempted` is a real script action that is not a model action."""
        router = ToolRouter(default_cwd=str(tmp_path))
        out = asyncio.run(router.dispatch(
            "exec", {"command": "python3 update_tracker.py attempted '{}'"}))
        assert out.startswith("Error")

    def test_exec_still_allows_reading_the_script(self, tmp_path):
        router = ToolRouter(default_cwd=str(tmp_path))
        (tmp_path / "update_tracker.py").write_text("print('hi')\n", encoding="utf-8")
        out = asyncio.run(router.dispatch("exec", {"command": "cat update_tracker.py"}))
        assert "print('hi')" in out
