"""Tests for main agent loop — tick flow, failure classification, agent turn."""
import asyncio
import json
import os
import runpy
import sys
from unittest.mock import MagicMock, AsyncMock, patch

import pytest

from campaign_agent.config import Config
from campaign_agent.main import classify_failure, run_agent_turn, TickResult, assert_in_iterm, _truncate_messages
from campaign_agent.session import estimate_tokens_from_messages
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

    def test_nonetype_parse_error_transient(self):
        """Malformed free-model responses cause parse errors (NoneType, KeyError,
        IndexError). These are transient: the next provider/model will likely
        return a valid response."""
        assert classify_failure("llm_error: 'NoneType' object is not subscriptable") == "transient"

    def test_key_error_transient(self):
        assert classify_failure("llm_error: 'choices'") == "transient"

    def test_fatal_unknown(self):
        assert classify_failure("Something completely unexpected") == "fatal"

    def test_max_steps_exceeded_not_fatal(self):
        """max_steps_exceeded must never stop the campaign: it is classified
        as its own retryable kind (rotate + retry), not 'fatal'."""
        assert classify_failure("max_steps_exceeded") == "max_steps"

    def test_max_steps_after_submission_is_success_token(self):
        """The success path after max steps (submission recorded) must not be
        misclassified as fatal either."""
        kind = classify_failure("max_steps_after_submission: done")
        assert kind == "max_steps"


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


class TestAssertInIterm:
    def test_raises_when_not_in_iterm(self):
        """Agent must refuse to start outside iTerm2."""
        with patch.dict(os.environ, {"TERM_PROGRAM": "Apple_Terminal"}, clear=False):
            with pytest.raises(SystemExit):
                assert_in_iterm()

    def test_raises_when_term_program_unset(self):
        """Agent must refuse to start when TERM_PROGRAM is not set."""
        env = {k: v for k, v in os.environ.items() if k != "TERM_PROGRAM"}
        with patch.dict(os.environ, env, clear=True):
            with pytest.raises(SystemExit):
                assert_in_iterm()

    def test_passes_when_in_iterm(self):
        """Agent should start normally when TERM_PROGRAM is iTerm.app."""
        with patch.dict(os.environ, {"TERM_PROGRAM": "iTerm.app"}, clear=False):
            assert_in_iterm()  # should not raise


class TestMainEntryPoint:
    def test_dunder_main_wires_config_and_starts_campaign(self, monkeypatch, tmp_path):
        """Executing the module as a script (python -m campaign_agent.main) must
        wire the overrides file + --model override into a Config and hand
        control to the campaign loop. Everything runs real (iTerm guard, argv
        parsing, Config.from_overrides); only the event-loop runner is stubbed
        - the genuine seam that would start the full Playwright/RAG runtime."""
        overrides = tmp_path / "overrides.env"
        overrides.write_text("OUTER_MAX_TICKS=3\n")

        seen = {}
        real_from_overrides = Config.from_overrides

        def spy_from_overrides(path):
            cfg = real_from_overrides(path)
            seen["path"] = path
            seen["config"] = cfg
            return cfg

        monkeypatch.setattr(Config, "from_overrides", staticmethod(spy_from_overrides))

        started = []

        def fake_asyncio_run(coro):
            started.append(True)
            coro.close()

        monkeypatch.setattr(asyncio, "run", fake_asyncio_run)

        monkeypatch.setenv("TERM_PROGRAM", "iTerm.app")
        monkeypatch.setattr(
            sys, "argv",
            ["campaign_agent.main", "--config", str(overrides), "--model", "test-model"],
        )

        import warnings

        with warnings.catch_warnings():
            # runpy warns that the module is already imported; re-execution
            # under __main__ is exactly the entry-point behavior under test.
            warnings.simplefilter("ignore", RuntimeWarning)
            runpy.run_module("campaign_agent.main", run_name="__main__")

        assert started == [True]
        assert seen["path"] == str(overrides)
        # Overrides file was really parsed and --model really applied.
        assert seen["config"].outer_max_ticks == 3
        assert seen["config"].msrouter_model == "test-model"


class TestEstimateTokensFromMessages:
    def test_empty_list(self):
        assert estimate_tokens_from_messages([]) == 0

    def test_string_content(self):
        msgs = [{"role": "user", "content": "hello world"}]  # 11 chars
        # 11 chars + 10 overhead = 21 // 4 = 5
        assert estimate_tokens_from_messages(msgs) == 5

    def test_list_content(self):
        msgs = [{"role": "user", "content": [
            {"type": "text", "text": "hello"},  # 5 chars
        ]}]
        # 5 chars + 10 overhead = 15 // 4 = 3
        assert estimate_tokens_from_messages(msgs) == 3

    def test_missing_content_key(self):
        msgs = [{"role": "user"}]
        # 0 chars + 10 overhead = 10 // 4 = 2
        assert estimate_tokens_from_messages(msgs) == 2

    def test_multiple_messages(self):
        msgs = [
            {"role": "system", "content": "ab"},  # 2 + 10 = 12
            {"role": "user", "content": "cd"},    # 2 + 10 = 12
        ]
        # total 24 // 4 = 6
        assert estimate_tokens_from_messages(msgs) == 6


class TestTruncateMessages:
    def _make_msgs(self, n: int, content_len: int = 500) -> list[dict]:
        """Create n messages with ~content_len chars each."""
        msgs = [{"role": "system", "content": "x" * 100}]
        for i in range(n - 1):
            msgs.append({"role": "user" if i % 2 == 0 else "assistant",
                         "content": "y" * content_len})
        return msgs

    def test_under_budget_returns_copy(self):
        msgs = self._make_msgs(5, content_len=100)
        result = _truncate_messages(msgs, token_budget=50000)
        assert len(result) == 5
        assert result is not msgs  # copy, not mutate

    def test_system_and_first_user_preserved(self):
        msgs = self._make_msgs(50, content_len=1000)
        result = _truncate_messages(msgs, token_budget=2000, keep_last=10)
        assert result[0]["role"] == "system"
        assert result[1]["role"] == "user"
        # Middle messages are dropped
        assert len(result) < 50

    def test_keep_last_messages_preserved(self):
        msgs = self._make_msgs(50, content_len=1000)
        result = _truncate_messages(msgs, token_budget=2000, keep_last=10)
        # Last 10 messages should be present
        for msg in msgs[-10:]:
            assert msg in result

    def test_empty_list(self):
        result = _truncate_messages([], token_budget=1000)
        assert result == []

    def test_two_messages_no_truncation(self):
        msgs = [{"role": "system", "content": "x"}, {"role": "user", "content": "y"}]
        result = _truncate_messages(msgs, token_budget=1000)
        assert len(result) == 2

    def test_drops_middle_not_head_or_tail(self):
        """Truncation drops messages between prefix (system+user) and suffix (keep_last)."""
        msgs = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "u0"},
            {"role": "assistant", "content": "a1 " + "z" * 500},
            {"role": "user", "content": "u2 " + "z" * 500},
            {"role": "assistant", "content": "a3 " + "z" * 500},
            {"role": "user", "content": "u4 " + "z" * 500},
        ]
        result = _truncate_messages(msgs, token_budget=500, keep_last=2)
        assert result[0]["content"] == "sys"
        assert result[1]["content"] == "u0"
        assert result[-2]["content"].startswith("a3")
        assert result[-1]["content"].startswith("u4")

    def test_overlap_keeps_all_when_few_messages(self):
        """When messages fit within keep_last, nothing is dropped."""
        msgs = self._make_msgs(3, content_len=100)
        result = _truncate_messages(msgs, token_budget=50, keep_last=50)
        assert len(result) == 3


class TestRunAgentTurnTruncation:
    @pytest.mark.asyncio
    async def test_context_truncation_kicks_in(self):
        """Agent turn truncates messages when context_token_budget is small."""
        call_count = 0

        def fake_chat(messages, tools=None):
            nonlocal call_count
            call_count += 1
            if call_count <= 3:
                return LLMResponse(
                    content="",
                    tool_calls=[ToolCall(id=f"c{call_count}", name="exec",
                                         arguments={"command": "echo " + "x" * 2000})],
                    finish_reason="tool_calls",
                )
            return LLMResponse(content="done", tool_calls=[], finish_reason="stop")

        mock_llm = MagicMock()
        mock_llm.chat = fake_chat
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        messages = [
            {"role": "system", "content": "You are a test agent."},
            {"role": "user", "content": "Do something."},
        ]
        # Set very low budget so truncation triggers quickly
        result = await run_agent_turn(mock_llm, tools, messages, max_steps=10,
                                       context_token_budget=1000)

        # No submission recorded (no update_tracker.py submitted call)
        assert result.success is False
        assert "no_submission" in result.reason
        # Messages should have been truncated (not grown unbounded)
        assert len(messages) < 20  # would be ~8 without truncation

    @pytest.mark.asyncio
    async def test_big_picture_survives_truncation(self):
        """System prompt and user prompt (containing tick summary) always survive."""
        call_count = 0

        def fake_chat(messages, tools=None):
            nonlocal call_count
            call_count += 1
            if call_count <= 5:
                return LLMResponse(
                    content="",
                    tool_calls=[ToolCall(id=f"c{call_count}", name="exec",
                                         arguments={"command": "echo " + "x" * 3000})],
                    finish_reason="tool_calls",
                )
            return LLMResponse(content="done", tool_calls=[], finish_reason="stop")

        mock_llm = MagicMock()
        mock_llm.chat = fake_chat
        mock_llm.model = "test"
        tools = ToolRouter(playwright_client=None, rag_client=None)

        system_content = "You are a job agent. Target: Java/Kotlin/Spring."
        user_content = "Recent: acme/Senior Java (2026-08-17). TASK: Apply one job."
        messages = [
            {"role": "system", "content": system_content},
            {"role": "user", "content": user_content},
        ]
        # Very low budget forces truncation after a few tool calls
        await run_agent_turn(mock_llm, tools, messages, max_steps=10,
                              context_token_budget=2000)

        # System prompt and first user message must survive truncation
        assert messages[0]["role"] == "system"
        assert messages[0]["content"] == system_content
        assert messages[1]["role"] == "user"
        assert messages[1]["content"] == user_content
