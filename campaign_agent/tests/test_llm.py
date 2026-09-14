"""Tests for LLM client — msrouter wrapper, tool-call parsing, retry logic."""
import asyncio
import json
import time
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from openai import APIError, APITimeoutError, RateLimitError

from campaign_agent.llm import LLMClient, LLMResponse


@pytest.fixture
def mock_openai_client():
    """Mock OpenAI client for testing."""
    client = MagicMock()
    return client


class TestLLMResponse:
    def test_from_openai_response_with_content(self):
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "Hello world"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"

        r = LLMResponse.from_openai(mock_resp)
        assert r.content == "Hello world"
        assert r.tool_calls == []
        assert r.finish_reason == "stop"

    def test_from_openai_response_with_tool_calls(self):
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = None
        mock_tc = MagicMock()
        mock_tc.id = "call_123"
        mock_tc.function.name = "exec"
        mock_tc.function.arguments = '{"command": "echo hi"}'
        mock_resp.choices[0].message.tool_calls = [mock_tc]
        mock_resp.choices[0].finish_reason = "tool_calls"

        r = LLMResponse.from_openai(mock_resp)
        assert r.content == ""
        assert len(r.tool_calls) == 1
        assert r.tool_calls[0].name == "exec"
        assert r.tool_calls[0].arguments == {"command": "echo hi"}
        assert r.finish_reason == "tool_calls"

    def test_from_openai_choices_none(self):
        """Free models sometimes return responses with choices=None.
        Must not crash; should yield an empty (is_empty) response."""
        mock_resp = MagicMock()
        mock_resp.choices = None
        r = LLMResponse.from_openai(mock_resp)
        assert r.content == ""
        assert r.tool_calls == []
        assert r.is_empty()

    def test_from_openai_choices_empty_list(self):
        """Empty choices list must not crash."""
        mock_resp = MagicMock()
        mock_resp.choices = []
        r = LLMResponse.from_openai(mock_resp)
        assert r.is_empty()

    def test_from_openai_choice_missing_message(self):
        """A choice with no message must not crash."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message = None
        r = LLMResponse.from_openai(mock_resp)
        assert r.is_empty()

    def test_from_openai_malformed_tool_arguments(self):
        """Malformed JSON in function arguments must not crash - fall back to
        the raw string so the caller can still see what the model sent."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = None
        mock_tc = MagicMock()
        mock_tc.id = "call_bad"
        mock_tc.function.name = "exec"
        mock_tc.function.arguments = "{not valid json"
        mock_resp.choices[0].message.tool_calls = [mock_tc]
        mock_resp.choices[0].finish_reason = "tool_calls"

        r = LLMResponse.from_openai(mock_resp)
        assert r.tool_calls[0].arguments == {"raw": "{not valid json"}

    def test_assistant_message_dict_without_tools(self):
        r = LLMResponse(content="Done", tool_calls=[], finish_reason="stop")
        d = r.assistant_message_dict()
        assert d["role"] == "assistant"
        assert d["content"] == "Done"
        assert "tool_calls" not in d

    def test_assistant_message_dict_with_tools(self):
        from campaign_agent.llm import ToolCall
        r = LLMResponse(
            content="",
            tool_calls=[ToolCall(id="c1", name="exec", arguments={"cmd": "ls"})],
            finish_reason="tool_calls",
        )
        d = r.assistant_message_dict()
        assert d["role"] == "assistant"
        assert "tool_calls" in d
        assert d["tool_calls"][0]["function"]["name"] == "exec"


class TestLLMClient:
    def test_init_with_config(self):
        client = LLMClient(
            base_url="http://127.0.0.1:8787/v1",
            api_key="msrouter-local",
            model="mst/free",
        )
        assert client.model == "mst/free"

    def test_sdk_client_created_without_internal_retries(self):
        """The SDK's own retries must be disabled (max_retries=0).

        llm.py's chat() loop is the single retry layer. SDK-internal retries
        (default 2) stack on top of it, doubling worst-case wait during
        msrouter chain 429 storms and making request accounting confusing.
        """
        with patch("campaign_agent.llm.OpenAI") as mock_openai:
            LLMClient(base_url="http://127.0.0.1:8787/v1", api_key="k", model="mst/free")
        call_kwargs = mock_openai.call_args.kwargs
        assert call_kwargs["max_retries"] == 0

    def test_chat_returns_response(self, mock_openai_client):
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "test response"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"
        mock_openai_client.chat.completions.create.return_value = mock_resp

        client = LLMClient(model="mst/free")
        client._client = mock_openai_client
        r = client.chat(messages=[{"role": "user", "content": "hi"}])

        assert isinstance(r, LLMResponse)
        assert r.content == "test response"

    def test_chat_empty_content_detected(self, mock_openai_client):
        """Empty content should be flagged as a potential issue."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = ""
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"
        mock_openai_client.chat.completions.create.return_value = mock_resp

        client = LLMClient(model="mst/free")
        client._client = mock_openai_client
        r = client.chat(messages=[{"role": "user", "content": "hi"}])

        assert r.is_empty()

    def test_chat_retries_on_rate_limit(self, mock_openai_client):
        """Should retry on RateLimitError."""
        from openai import RateLimitError
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "ok"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"

        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.response.status_code = 429
        mock_err.body = MagicMock()
        mock_err.body.__str__ = lambda self: "rate limited"
        mock_openai_client.chat.completions.create.side_effect = [
            RateLimitError(message="rate limited", response=mock_err.response, body=mock_err.body),
            mock_resp,
        ]

        client = LLMClient(model="mst/free", max_retries=3)
        client._client = mock_openai_client
        r = client.chat(messages=[{"role": "user", "content": "hi"}])

        assert r.content == "ok"
        assert mock_openai_client.chat.completions.create.call_count == 2

    def test_chat_retries_on_timeout_then_succeeds(self, mock_openai_client):
        """APITimeoutError is retryable: after the timeout, the next attempt
        succeeds (msrouter stalls but recovers)."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "recovered"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"

        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.request = MagicMock()
        mock_openai_client.chat.completions.create.side_effect = [
            APITimeoutError(request=mock_err.request),
            mock_resp,
        ]

        client = LLMClient(model="mst/free", max_retries=2)
        client._client = mock_openai_client
        with patch("campaign_agent.llm.time.sleep"):
            r = client.chat(messages=[{"role": "user", "content": "hi"}])

        assert r.content == "recovered"
        assert mock_openai_client.chat.completions.create.call_count == 2

    def test_chat_timeout_exhausts_retries_and_raises(self, mock_openai_client):
        """Persistent APITimeoutError raises the last error after retries."""
        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.request = MagicMock()
        mock_openai_client.chat.completions.create.side_effect = [
            APITimeoutError(request=mock_err.request),
            APITimeoutError(request=mock_err.request),
        ]

        client = LLMClient(model="mst/free", max_retries=1)
        client._client = mock_openai_client
        with patch("campaign_agent.llm.time.sleep"):
            with pytest.raises(APITimeoutError):
                client.chat(messages=[{"role": "user", "content": "hi"}])

    def test_chat_generic_api_error_does_not_retry(self, mock_openai_client):
        """A generic APIError (auth, bad request) is NOT retried - it raises
        immediately after one attempt (no point hammering a 4xx)."""
        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.request = MagicMock()
        mock_openai_client.chat.completions.create.side_effect = [
            APIError(message="401 unauthorized", request=mock_err.request, body=None),
        ]

        client = LLMClient(model="mst/free", max_retries=3)
        client._client = mock_openai_client
        with pytest.raises(APIError):
            client.chat(messages=[{"role": "user", "content": "hi"}])
        assert mock_openai_client.chat.completions.create.call_count == 1


class TestChatAsyncHardDeadline:
    """chat_async must enforce a hard wall-clock deadline: a half-open socket
    (peer vanished mid-request) makes the sync httpx read block forever, and
    the SDK per-request timeout has proven unreliable in that state - so the
    agent resets the HTTP client and retries on a fresh connection."""

    def _client(self, hard_timeout=0.2, hang=2):
        llm = LLMClient(base_url="http://127.0.0.1:9", api_key="x", hard_timeout=hard_timeout)

        def hang_forever(**kwargs):
            time.sleep(hang)
            return SimpleNamespace(choices=[], model="m")

        llm._client.chat.completions.create = hang_forever
        return llm

    async def test_chat_async_returns_parsed_response(self):
        llm = LLMClient(base_url="http://127.0.0.1:9", api_key="x", hard_timeout=5)
        fake = SimpleNamespace(
            choices=[SimpleNamespace(
                message=SimpleNamespace(content="ok", tool_calls=None),
                finish_reason="stop")],
            model="m",
        )
        llm._client.chat.completions.create = lambda **kwargs: fake
        res = await llm.chat_async([{"role": "user", "content": "hi"}])
        assert res.content == "ok"

    async def test_chat_async_hard_timeout_resets_client(self):
        llm = self._client(hard_timeout=0.2, hang=30)
        old_client = llm._client
        with pytest.raises(TimeoutError):
            await llm.chat_async([{"role": "user", "content": "hi"}])
        assert llm._client is not old_client

    async def test_chat_async_no_timeout_when_healthy(self):
        llm = self._client(hard_timeout=5, hang=0)
        old_client = llm._client
        await llm.chat_async([{"role": "user", "content": "hi"}])
        assert llm._client is old_client


class TestExecutorSwap:
    """B3: hard-timed-out calls abandon their wedged thread forever; a
    dedicated small executor must be swapped so hung threads can never
    starve new chat calls (the aggregate wedge class)."""

    async def test_healthy_call_survives_repeated_timeouts(self):
        llm = LLMClient(base_url="http://127.0.0.1:9", api_key="x", hard_timeout=0.1)

        def hanging(**kwargs):
            time.sleep(30)
            return SimpleNamespace(choices=[], model="m")

        for _ in range(3):  # more than max_workers=2
            llm._client.chat.completions.create = hanging  # re-stub after each reset
            with pytest.raises(TimeoutError):
                await llm.chat_async([{"role": "user", "content": "hi"}])

        import time as _t
        fake = SimpleNamespace(
            choices=[SimpleNamespace(
                message=SimpleNamespace(content="recovered", tool_calls=None),
                finish_reason="stop")],
            model="m",
        )
        llm._client.chat.completions.create = lambda **kwargs: fake
        start = _t.monotonic()
        res = await llm.chat_async([{"role": "user", "content": "hi"}])
        assert res.content == "recovered"
        assert _t.monotonic() - start < 1.0  # not queued behind wedged threads


class TestZombieRetryGuard:
    """After chat_async's hard deadline resets the client, the ABANDONED
    thread's chat() retry loop must stop retrying. Pre-fix behavior
    (2026-09-13 incident): the zombie kept its retry loop alive against the
    FRESH client, sending duplicate 1200s requests upstream and logging
    out-of-order 'Timeout (attempt 4/3)' lines. A generation counter makes
    abandoned loops exit at their next check."""

    def test_reset_client_stops_inflight_retry_loop(self, caplog):
        """chat_async's hard-deadline reset must stop the abandoned chat()
        retry loop: the zombie wakes from its (real, unpatched) backoff
        sleep, hits the loop-top generation check, and abandons without
        calling upstream again (pre-fix: duplicate 1200s requests from
        zombie threads).

        Deterministic outcome: the guard fires on the zombie's next loop
        iteration no matter the thread schedule (the generation check is
        race-free here - the reset happens while create() is wedged), so we
        poll for the guard's log record with a generous bound instead of
        asserting wall-clock margins."""
        import logging as _logging
        llm = LLMClient(base_url="http://127.0.0.1:9", api_key="x",
                        max_retries=5, hard_timeout=0.3)
        calls = []
        real_sleep = time.sleep

        def wedged_create(**kwargs):
            calls.append(1)
            # Wedged socket: hang until the hard deadline resets (bumping the
            # generation), then surface the SDK timeout.
            while llm.generation == 0:
                real_sleep(0.01)
            raise APITimeoutError(request=MagicMock())

        llm._client.chat.completions.create = wedged_create
        assert llm.generation == 0

        with caplog.at_level(_logging.WARNING, logger="campaign_agent.llm"):
            with pytest.raises(TimeoutError):
                asyncio.run(llm.chat_async([{"role": "user", "content": "hi"}]))
            assert llm.generation == 1
            # The zombie now: raises APITimeoutError -> real 2s backoff ->
            # loop-top guard -> "chat() loop abandoned". Poll for the guard.
            deadline = time.monotonic() + 15.0
            while time.monotonic() < deadline:
                if any("chat() loop abandoned" in r.message for r in caplog.records):
                    break
                real_sleep(0.05)

        assert any("chat() loop abandoned" in r.message for r in caplog.records), \
            "generation guard never fired; zombie would have retried"
        # At most one call ever hit the (wedged) client - no duplicates.
        assert len(calls) == 1, f"zombie retried after reset ({len(calls)} calls)"

    def test_chat_attempt_log_denominator(self, caplog):
        """The retry loop makes max_retries+1 attempts total; the timeout log
        must never print attempt N+1/N (the 'attempt 4/3' cosmetic bug)."""
        import logging as _logging
        llm = LLMClient(base_url="http://127.0.0.1:9", api_key="x", max_retries=3)

        def always_timeout(**kwargs):
            raise APITimeoutError(request=MagicMock())

        llm._client.chat.completions.create = always_timeout
        with caplog.at_level(_logging.WARNING, logger="campaign_agent.llm"), \
             patch("campaign_agent.llm.time.sleep", lambda s: None):
            with pytest.raises(APITimeoutError):
                llm.chat(messages=[{"role": "user", "content": "hi"}])

        timeout_logs = [r.message for r in caplog.records if "Timeout (attempt" in r.message]
        assert len(timeout_logs) == 4  # 1 + max_retries attempts, each logged
        import re as _re
        pairs = [_re.search(r"attempt (\d+)/(\d+)", m) for m in timeout_logs]
        assert all(m for m in pairs)
        # Numerators within 1..N, denominators always N = max_retries+1.
        assert [int(m.group(1)) for m in pairs] == [1, 2, 3, 4]
        assert {int(m.group(2)) for m in pairs} == {4}
        assert llm.generation == 0  # healthy loop never bumps the generation
