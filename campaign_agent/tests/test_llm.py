"""Tests for LLM client — msrouter wrapper, tool-call parsing, retry logic."""
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
