"""Tests for main agent loop — tick flow, failure classification, agent turn."""
import asyncio
import json
from unittest.mock import MagicMock, AsyncMock, patch

import pytest

from campaign_agent.main import classify_failure, run_agent_turn, TickResult
from campaign_agent.llm import LLMClient, LLMResponse, ToolCall
from campaign_agent.tools import ToolRouter


class TestClassifyFailure:
    def test_context_overflow(self):
        assert classify_failure("Context overflow: prompt too large") == "context"

    def test_compaction(self):
        assert classify_failure("compaction failed") == "context"

    def test_rate_limit(self):
        assert classify_failure("Rate limit reached (429)") == "rate"

    def test_timeout(self):
        assert classify_failure("Request timed out") == "rate"

    def test_transient_failover(self):
        assert classify_failure("FailoverError: all providers failed") == "transient"

    def test_empty_response(self):
        assert classify_failure("empty response from model") == "transient"

    def test_empty_response_reason_token(self):
        """The exact reason string emitted by run_agent_turn ('empty_response',
        no space) must classify as transient, not fall through to fatal."""
        assert classify_failure("empty_response") == "transient"

    def test_no_submission_reason_token(self):
        """The no_submission reason prefix must classify so the outer loop
        retries with fresh messages (transient or its own kind), never fatal."""
        kind = classify_failure("no_submission: the files are missing")
        assert kind != "fatal"

    def test_connection_error_transient(self):
        """Connection errors (msrouter restart, network blip) must retry, not
        fatally stop the campaign. Covers the SDK's 'Connection error.' text."""
        assert classify_failure("llm_error: Connection error.") == "transient"

    def test_connection_error_plain_transient(self):
        assert classify_failure("connection error") == "transient"

    def test_connection_refused_transient(self):
        assert classify_failure("connection refused") == "transient"

    def test_api_connection_error_transient(self):
        assert classify_failure("APIConnectionError: failed to connect") == "transient"

    def test_fatal_unknown(self):
        assert classify_failure("Something completely unexpected") == "fatal"


class TestRunAgentTurn:
    @pytest.mark.asyncio
    async def test_content_without_submission_is_failure(self):
        """LLM ends turn with content but no submission recorded → not success.

        This is the anti-gaming rule: content alone (e.g. 'done', 'TICK_COMPLETE',
        'the files are missing') must NOT count as a successful tick.
        """
        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(return_value=LLMResponse(
            content="The required files are missing", tool_calls=[], finish_reason="stop"
        ))
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason

    @pytest.mark.asyncio
    async def test_tool_call_then_content_without_submission_is_failure(self):
        """Tool call then content, but no update_tracker.py submitted → failure."""
        mock_llm = MagicMock()
        # First call: tool call
        # Second call: content
        mock_llm.chat = MagicMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(id="c1", name="exec", arguments={"command": "echo hi"})],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Applied successfully", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        messages: list[dict] = []
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason
        assert len(messages) == 3  # assistant(tool_call) + tool(result) + assistant(content)

    @pytest.mark.asyncio
    async def test_empty_response_returns_failure(self):
        """Empty response (no content, no tools) → failure."""
        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(return_value=LLMResponse(
            content="", tool_calls=[], finish_reason="stop"
        ))
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is False
        assert "empty" in result.reason

    @pytest.mark.asyncio
    async def test_max_steps_exceeded(self):
        """LLM keeps calling tools → max_steps exceeded (no submission)."""
        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(return_value=LLMResponse(
            content="",
            tool_calls=[ToolCall(id="c1", name="exec", arguments={"command": "echo loop"})],
            finish_reason="tool_calls",
        ))
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        result = await run_agent_turn(mock_llm, tools, [], max_steps=3)

        assert result.success is False
        assert "max_steps" in result.reason

    @pytest.mark.asyncio
    async def test_llm_error_returns_failure(self):
        """LLM raises exception → failure."""
        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(side_effect=RuntimeError("msrouter down"))
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is False
        assert "llm_error" in result.reason

    @pytest.mark.asyncio
    async def test_update_tracker_submitted_exit0_is_success(self):
        """exec update_tracker.py submitted returning exit=0 → success, submitted=1."""
        tools = MagicMock()
        tools.schemas = []
        tools.dispatch = AsyncMock(return_value="saved 1133/1200 exit=0")

        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(
                    id="c1", name="exec",
                    arguments={"command": "python3 update_tracker.py submitted '{\"id\":\"j1\"}'"},
                )],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Submitted", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is True
        assert result.submitted == 1

    @pytest.mark.asyncio
    async def test_update_tracker_failure_is_not_submission(self):
        """exec update_tracker.py submitted returning exit!=0 → not a submission."""
        tools = MagicMock()
        tools.schemas = []
        tools.dispatch = AsyncMock(return_value="Error: bad json exit=2")

        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(
                    id="c1", name="exec",
                    arguments={"command": "python3 update_tracker.py submitted 'bad'"},
                )],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Trying again", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is False
        assert "no_submission" in result.reason

    @pytest.mark.asyncio
    async def test_submission_then_max_steps_is_success(self):
        """A recorded submission is a successful tick even if steps run out after."""
        tools = MagicMock()
        tools.schemas = []
        tools.dispatch = AsyncMock(return_value="saved exit=0")

        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(return_value=LLMResponse(
            content="",
            tool_calls=[ToolCall(
                id="c1", name="exec",
                arguments={"command": "update_tracker.py submitted '{\"id\":\"x\"}'"},
            )],
            finish_reason="tool_calls",
        ))
        mock_llm.model = "test"

        result = await run_agent_turn(mock_llm, tools, [], max_steps=3)

        assert result.success is True
        assert result.submitted == 1
