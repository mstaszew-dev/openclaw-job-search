"""
LLM client: wraps OpenAI SDK pointed at msrouter gateway.
Handles tool-call parsing, empty response detection, retry logic.
"""
from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

from openai import OpenAI, APIError, APITimeoutError, RateLimitError

log = logging.getLogger(__name__)


@dataclass
class ToolCall:
    """A single tool call from the LLM."""
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class LLMResponse:
    """Parsed LLM response with content and/or tool calls."""
    content: str
    tool_calls: list[ToolCall] = field(default_factory=list)
    finish_reason: str = "stop"

    @classmethod
    def from_openai(cls, response: Any) -> LLMResponse:
        """Parse an OpenAI SDK response into LLMResponse."""
        choice = response.choices[0]
        msg = choice.message

        content = msg.content or ""

        tool_calls: list[ToolCall] = []
        if msg.tool_calls:
            for tc in msg.tool_calls:
                try:
                    args = json.loads(tc.function.arguments) if tc.function.arguments else {}
                except json.JSONDecodeError:
                    args = {"raw": tc.function.arguments}
                tool_calls.append(ToolCall(
                    id=tc.id,
                    name=tc.function.name,
                    arguments=args,
                ))

        return cls(
            content=content,
            tool_calls=tool_calls,
            finish_reason=choice.finish_reason or "stop",
        )

    def is_empty(self) -> bool:
        """True if no content and no tool calls."""
        return not self.content.strip() and not self.tool_calls

    def assistant_message_dict(self) -> dict[str, Any]:
        """Convert to OpenAI message format for appending to history."""
        msg: dict[str, Any] = {"role": "assistant", "content": self.content or ""}
        if self.tool_calls:
            msg["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {
                        "name": tc.name,
                        "arguments": json.dumps(tc.arguments),
                    },
                }
                for tc in self.tool_calls
            ]
            msg["content"] = None
        return msg


class LLMClient:
    """Wraps OpenAI SDK for msrouter gateway with retry logic."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8787/v1",
        api_key: str = "msrouter-local",
        model: str = "mst/free",
        max_tokens: int = 4096,
        max_retries: int = 3,
        timeout: int = 120,
    ) -> None:
        self.model = model
        self.max_tokens = max_tokens
        self.max_retries = max_retries
        self._client = OpenAI(
            base_url=base_url,
            api_key=api_key,
            timeout=timeout,
        )

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        max_tokens: int | None = None,
    ) -> LLMResponse:
        """Send a chat completion request. Retries on rate limit / timeout."""
        kwargs: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_tokens or self.max_tokens,
        }
        if tools:
            kwargs["tools"] = tools

        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            try:
                response = self._client.chat.completions.create(**kwargs)
                return LLMResponse.from_openai(response)
            except RateLimitError as e:
                last_error = e
                log.warning("Rate limit (attempt %d/%d): %s", attempt + 1, self.max_retries, e)
                if attempt < self.max_retries:
                    time.sleep(5 * (attempt + 1))  # backoff
            except APITimeoutError as e:
                last_error = e
                log.warning("Timeout (attempt %d/%d): %s", attempt + 1, self.max_retries, e)
                if attempt < self.max_retries:
                    time.sleep(2)
            except APIError as e:
                last_error = e
                log.error("API error: %s", e)
                break  # don't retry on generic API errors

        raise last_error or RuntimeError("Unknown LLM error")
