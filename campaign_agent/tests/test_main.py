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

    def test_fatal_unknown(self):
        assert classify_failure("Something completely unexpected") == "fatal"


class TestRunAgentTurn:
    @pytest.mark.asyncio
    async def test_success_on_first_response(self):
        """LLM responds with content and no tool calls → success."""
        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(return_value=LLMResponse(
            content="Done applying", tool_calls=[], finish_reason="stop"
        ))
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is True

    @pytest.mark.asyncio
    async def test_tool_call_then_success(self):
        """LLM calls a tool, then responds with content."""
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

        assert result.success is True
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
        """LLM keeps calling tools → max_steps exceeded."""
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
    async def test_update_tracker_detected(self):
        """When exec calls update_tracker.py submitted, it's logged."""
        mock_llm = MagicMock()
        mock_llm.chat = MagicMock(side_effect=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(
                    id="c1", name="exec",
                    arguments={"command": "update_tracker.py submitted '{\"id\":\"test\"}'"},
                )],
                finish_reason="tool_calls",
            ),
            LLMResponse(content="Submitted", tool_calls=[], finish_reason="stop"),
        ])
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        result = await run_agent_turn(mock_llm, tools, [], max_steps=5)

        assert result.success is True
