"""Tests for LLM client — msrouter wrapper, tool-call parsing, retry logic."""
import json
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
        
        raw_response = MagicMock()
        raw_response.http_response.headers = {"x-served-by-provider": "opencode"}
        raw_response.parse.return_value = mock_resp
        mock_openai_client.chat.completions.with_raw_response.create.return_value = raw_response

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
        
        raw_response = MagicMock()
        raw_response.http_response.headers = {"x-served-by-provider": "opencode"}
        raw_response.parse.return_value = mock_resp
        mock_openai_client.chat.completions.with_raw_response.create.return_value = raw_response

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

        raw_response = MagicMock()
        raw_response.http_response.headers = {"x-served-by-provider": "opencode"}
        raw_response.parse.return_value = mock_resp

        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.response.status_code = 429
        mock_err.body = MagicMock()
        mock_err.body.__str__ = lambda self: "rate limited"
        mock_openai_client.chat.completions.with_raw_response.create.side_effect = [
            RateLimitError(message="rate limited", response=mock_err.response, body=mock_err.body),
            raw_response,
        ]

        client = LLMClient(model="mst/free", max_retries=3)
        client._client = mock_openai_client
        r = client.chat(messages=[{"role": "user", "content": "hi"}])

        assert r.content == "ok"
        assert mock_openai_client.chat.completions.with_raw_response.create.call_count == 2

    def test_chat_retries_on_timeout_then_succeeds(self, mock_openai_client):
        """APITimeoutError is retryable: after the timeout, the next attempt
        succeeds (msrouter stalls but recovers)."""
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = "recovered"
        mock_resp.choices[0].message.tool_calls = None
        mock_resp.choices[0].finish_reason = "stop"

        raw_response = MagicMock()
        raw_response.http_response.headers = {"x-served-by-provider": "opencode"}
        raw_response.parse.return_value = mock_resp

        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.request = MagicMock()
        mock_openai_client.chat.completions.with_raw_response.create.side_effect = [
            APITimeoutError(request=mock_err.request),
            raw_response,
        ]

        client = LLMClient(model="mst/free", max_retries=2)
        client._client = mock_openai_client
        with patch("campaign_agent.llm.time.sleep"):
            r = client.chat(messages=[{"role": "user", "content": "hi"}])

        assert r.content == "recovered"
        assert mock_openai_client.chat.completions.with_raw_response.create.call_count == 2

    def test_chat_timeout_exhausts_retries_and_raises(self, mock_openai_client):
        """Persistent APITimeoutError raises the last error after retries."""
        mock_err = MagicMock()
        mock_err.response = MagicMock()
        mock_err.request = MagicMock()
        mock_openai_client.chat.completions.with_raw_response.create.side_effect = [
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
        mock_openai_client.chat.completions.with_raw_response.create.side_effect = [
            APIError(message="401 unauthorized", request=mock_err.request, body=None),
        ]

        client = LLMClient(model="mst/free", max_retries=3)
        client._client = mock_openai_client
        with pytest.raises(APIError):
            client.chat(messages=[{"role": "user", "content": "hi"}])
        assert mock_openai_client.chat.completions.with_raw_response.create.call_count == 1


class TestModelRotation:
    def test_consecutive_local_increments(self):
        """Consecutive local use counter increments on local provider."""
        client = LLMClient(
            model="mst/free",
            local_provider="lmstudio",
            local_consecutive_limit=5,
            models=["mst/free", "big-pickle"],
        )
        client._track_provider("lmstudio")
        assert client._consecutive_local == 1
        client._track_provider("lmstudio")
        assert client._consecutive_local == 2

    def test_consecutive_local_resets_on_remote(self):
        """Counter resets when remote provider is used."""
        client = LLMClient(
            model="mst/free",
            local_provider="lmstudio",
            local_consecutive_limit=5,
            models=["mst/free", "big-pickle"],
        )
        client._consecutive_local = 4
        client._track_provider("opencode")
        assert client._consecutive_local == 0

    def test_rotate_model_after_limit(self):
        """Model rotates after reaching consecutive local limit."""
        client = LLMClient(
            model="mst/free",
            local_provider="lmstudio",
            local_consecutive_limit=3,
            models=["mst/free", "big-pickle", "qwen3.6-plus"],
        )
        for _ in range(3):
            client._track_provider("lmstudio")
        assert client.model == "big-pickle"
        assert client._consecutive_local == 0

    def test_rotate_wraps_around(self):
        """Rotation wraps around to first model after reaching end."""
        client = LLMClient(
            model="mst/free",
            local_provider="lmstudio",
            local_consecutive_limit=2,
            models=["mst/free", "big-pickle"],
        )
        client._track_provider("lmstudio")
        client._track_provider("lmstudio")
        assert client.model == "big-pickle"
        client._track_provider("lmstudio")
        client._track_provider("lmstudio")
        assert client.model == "mst/free"
