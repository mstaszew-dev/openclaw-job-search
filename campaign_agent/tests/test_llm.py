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
