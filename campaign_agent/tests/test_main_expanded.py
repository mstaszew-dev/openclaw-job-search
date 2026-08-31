"""Expanded tests for main.py — campaign loop, LLM error paths, tool edge cases."""
import asyncio
import json
from unittest.mock import MagicMock, AsyncMock, patch

import pytest

from campaign_agent.main import classify_failure, run_agent_turn, TickResult
from campaign_agent.llm import LLMClient, LLMResponse, ToolCall
from campaign_agent.tools import ToolRouter


class TestClassifyFailureExpanded:
    def test_compaction_timeout(self):
        assert classify_failure("Compaction timed out after 30s") == "context"

    def test_token_limit(self):
        assert classify_failure("token limit too large for model") == "context"

    def test_maximum_context(self):
        assert classify_failure("maximum context length exceeded") == "context"

    def test_econnreset(self):
        assert classify_failure("ECONNRESET: connection reset") == "rate"

    def test_etimedout(self):
        assert classify_failure("ETIMEDOUT: socket timeout") == "rate"

    def test_no_provider(self):
        assert classify_failure("NO_PROVIDER_AVAILABLE") == "transient"

    def test_streaming_response_failed(self):
        assert classify_failure("Streaming response failed") == "transient"

    def test_couldnt_generate(self):
        assert classify_failure("couldn't generate a response") == "transient"

    def test_completely_empty(self):
        assert classify_failure("") == "fatal"


class TestRunAgentTurnExpanded:
    @pytest.mark.asyncio
    async def test_multiple_tool_calls_in_one_response(self):
        """LLM can request multiple tools in one response (parallel calls).

        Tools are dispatched, but the turn ends without a recorded submission
        → failure (anti-gaming: content alone is not a successful tick).
        """
        mock_llm = MagicMock()
        mock_llm.chat_async = AsyncMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[
                    ToolCall(id="c1", name="exec", arguments={"command": "echo a"}),
                    ToolCall(id="c2", name="exec", arguments={"command": "echo b"}),
                ],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Done with both", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason
        # Should have: assistant(tool_calls) + tool1 + tool2 + assistant(content) = 4
        assert len(messages) == 4
        # Both tool results should be present
        tool_msgs = [m for m in messages if m["role"] == "tool"]
        assert len(tool_msgs) == 2

    @pytest.mark.asyncio
    async def test_playwright_tool_dispatched(self):
        """Playwright tool calls go to the MCP client (turn ends without submission)."""
        mock_pw = AsyncMock()
        mock_pw.call_tool = AsyncMock(return_value="### Page\n- URL: https://example.com")
        tools = ToolRouter(playwright_client=mock_pw, rag_client=None)

        mock_llm = MagicMock()
        mock_llm.chat_async = AsyncMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(id="c1", name="browser_navigate", arguments={"url": "https://example.com"})],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Navigated successfully", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason
        mock_pw.call_tool.assert_called_once_with(
            "browser_navigate", {"url": "https://example.com"}, timeout=120.0
        )

    @pytest.mark.asyncio
    async def test_rag_tool_dispatched(self):
        """RAG tool calls go to the RAG MCP client (turn ends without submission)."""
        mock_rag = AsyncMock()
        mock_rag.call_tool = AsyncMock(return_value="score 0.92: Senior Java @ Foo")
        tools = ToolRouter(playwright_client=None, rag_client=mock_rag)

        mock_llm = MagicMock()
        mock_llm.chat_async = AsyncMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(id="c1", name="rag_search_apps", arguments={"query": "Java developer"})],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Duplicate found, skipping", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason
        mock_rag.call_tool.assert_called_once_with(
            "rag_search_apps", {"query": "Java developer"}, timeout=60.0
        )

    @pytest.mark.asyncio
    async def test_tool_error_continues_loop(self):
        """When a tool fails, the error result is fed back and the loop continues."""
        mock_llm = MagicMock()
        mock_llm.chat_async = AsyncMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(id="c1", name="browser_navigate", arguments={"url": "bad-url"})],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Retried with different URL", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"

        # Playwright returns error
        mock_pw = AsyncMock()
        mock_pw.call_tool = AsyncMock(return_value="Error: connection refused")
        tools = ToolRouter(playwright_client=mock_pw, rag_client=None)

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason
        # Error result should be in messages
        tool_msgs = [m for m in messages if m["role"] == "tool"]
        assert "Error" in tool_msgs[0]["content"]

    @pytest.mark.asyncio
    async def test_long_conversation_multiple_steps(self):
        """Agent makes multiple tool calls over several steps (no submission)."""
        mock_llm = MagicMock()
        mock_llm.chat_async = AsyncMock(side_effect=[
            LLMResponse(content="", tool_calls=[ToolCall(id="c1", name="exec", arguments={"command": "echo step1"})], finish_reason="tool_calls"),
            LLMResponse(content="", tool_calls=[ToolCall(id="c2", name="exec", arguments={"command": "echo step2"})], finish_reason="tool_calls"),
            LLMResponse(content="", tool_calls=[ToolCall(id="c3", name="exec", arguments={"command": "echo step3"})], finish_reason="tool_calls"),
            LLMResponse(content="All steps done", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=10)

        assert result.success is False
        assert "no_submission" in result.reason
        assert mock_llm.chat_async.call_count == 4  # 3 tool calls + 1 final

    @pytest.mark.asyncio
    async def test_update_tracker_submission_detected(self):
        """exec update_tracker.py submitted with exit=0 → successful submission tick."""
        tools = MagicMock()
        tools.schemas = []
        tools.dispatch = AsyncMock(return_value="saved 1133/1200 exit=0")

        mock_llm = MagicMock()
        mock_llm.chat_async = AsyncMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(
                    id="c1", name="exec",
                    arguments={"command": "python3 update_tracker.py submitted '{\"id\":\"test-1\",\"company\":\"TestCo\"}'"},
                )],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Submission recorded", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=5)

        assert result.success is True
        assert result.submitted == 1


class TestLLMClientExpanded:
    """Tests for LLM client retry/error paths not covered in test_llm.py."""

    def test_chat_raises_on_all_retries_exhausted(self):
        """When all retries are exhausted, the last error is raised."""
        from openai import RateLimitError
        mock_client = MagicMock()
        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.response.status_code = 429
        mock_err.body = MagicMock()
        mock_err.body.__str__ = lambda self: "rate limited"
        mock_client.chat.completions.create.side_effect = RateLimitError(
            message="rate limited", response=mock_err.response, body=mock_err.body
        )

        client = LLMClient(model="mst/free", max_retries=1)
        client._client = mock_client

        with pytest.raises(RateLimitError):
            client.chat(messages=[{"role": "user", "content": "hi"}])

    def test_chat_passes_tools_to_api(self):
        """Tools should be forwarded to the OpenAI API call."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "ok"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_resp

        client = LLMClient(model="mst/free")
        client._client = mock_client

        tools = [{"type": "function", "function": {"name": "exec", "parameters": {}}}]
        client.chat(messages=[{"role": "user", "content": "hi"}], tools=tools)

        call_kwargs = mock_client.chat.completions.create.call_args[1]
        assert "tools" in call_kwargs

    def test_chat_max_tokens_override(self):
        """max_tokens parameter should be overridable per call."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "ok"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_resp

        client = LLMClient(model="mst/free", max_tokens=2048)
        client._client = mock_client
        client.chat(messages=[{"role": "user", "content": "hi"}], max_tokens=8192)

        call_kwargs = mock_client.chat.completions.create.call_args[1]
        assert call_kwargs["max_tokens"] == 8192


class TestToolRouterSync:
    """Tests for sync dispatch paths."""

    def test_dispatch_sync_unknown(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("fake_tool", {})
        assert "Unknown" in result

    def test_dispatch_sync_exec_with_cwd(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("exec", {"command": "pwd", "cwd": "/tmp"})
        assert "exit=0" in result

    def test_dispatch_sync_playwright_no_client(self):
        router = ToolRouter(playwright_client=None, rag_client=None)
        result = router.dispatch_sync("browser_click", {"target": "btn1"})
        assert "not available" in result


class TestSessionManagerExpanded:
    """Expanded session manager tests."""

    @pytest.mark.asyncio
    async def test_estimate_tokens_with_tool_calls(self):
        from campaign_agent.session import SessionManager
        import json

        sm = SessionManager("/tmp", "/tmp/tracker.json")
        messages = [
            {"role": "assistant", "content": None, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "exec", "arguments": '{"command":"echo test"}'}}
            ]},
            {"role": "tool", "tool_call_id": "c1", "content": "test output"},
        ]
        tokens = sm.estimate_tokens_from_messages(messages)
        assert tokens > 0  # should count tool call + result

    def test_should_rotate_empty_messages(self):
        from campaign_agent.session import SessionManager
        sm = SessionManager("/tmp", "/tmp/tracker.json", token_budget=128000)
        assert not sm.should_rotate([])

    def test_rotation_context_empty_session(self):
        from campaign_agent.session import SessionManager
        sm = SessionManager("/tmp", "/tmp/tracker.json")
        ctx = sm.build_rotation_context()
        # Should still work even without a session_id
        assert "Previous session" in ctx

    def test_add_multiple_messages(self):
        from campaign_agent.session import SessionManager
        sm = SessionManager("/tmp", "/tmp/tracker.json")
        sm.add_message({"role": "user", "content": "msg1"})
        sm.add_message({"role": "assistant", "content": "reply1"})
        sm.add_message({"role": "user", "content": "msg2"})
        assert len(sm.messages) == 3
