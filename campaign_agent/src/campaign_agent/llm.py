"""
LLM client: wraps OpenAI SDK pointed at msrouter gateway.
Handles tool-call parsing, empty response detection, retry logic.
"""
from __future__ import annotations

import asyncio
import functools
import json
import logging
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any

from openai import OpenAI, APIError, APITimeoutError, RateLimitError

log = logging.getLogger(__name__)


class _AbandonedRetryError(RuntimeError):
    """Raised by chat() when reset_client() bumped the generation mid-loop:
    the call belongs to an abandoned hard-deadline attempt and its zombie
    thread must stop retrying instead of hammering the fresh client."""


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
        """Parse an OpenAI SDK response into LLMResponse.

        Handles malformed responses from free-tier models (choices=None,
        empty choices list, missing message) by returning an empty response
        instead of crashing.
        """
        choices = getattr(response, "choices", None)
        if not choices:
            return cls(content="", tool_calls=[], finish_reason="empty")
        choice = choices[0]
        msg = getattr(choice, "message", None)
        if msg is None:
            return cls(content="", tool_calls=[],
                       finish_reason=getattr(choice, "finish_reason", "empty") or "empty")

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
        hard_timeout: int | None = None,
    ) -> None:
        self.model = model
        self.max_tokens = max_tokens
        self.max_retries = max_retries
        # Generation counter: every reset_client() (hard-deadline recovery)
        # bumps it. In-flight chat() retry loops capture the generation at
        # start and abandon on mismatch - otherwise the abandoned zombie
        # thread keeps retrying against the FRESH client (duplicate upstream
        # requests, out-of-order retry logs; 2026-09-13 incident).
        self.generation = 0
        # Hard wall-clock deadline for chat_async: a half-open socket (gateway
        # restarted/vanished mid-request) leaves the sync httpx read blocked
        # indefinitely even though the SDK per-request timeout is set, so the
        # deadline is enforced at the application layer instead. Defaults to
        # one SDK attempt plus slack.
        self.hard_timeout = hard_timeout if hard_timeout is not None else timeout + 120
        self._client_kwargs = dict(
            base_url=base_url,
            api_key=api_key,
            timeout=timeout,
            # llm.py's chat() loop below is the single retry layer; disable the
            # SDK's internal retries so they do not stack (double worst-case
            # wait under msrouter 429 storms, confusing request accounting).
            max_retries=0,
        )
        self._client = OpenAI(**self._client_kwargs)
        # Dedicated pool: a hard-timed-out call abandons its thread blocked on
        # a dead socket forever, so hung threads must never starve new calls.
        self._executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="llm-chat")

    def reset_client(self) -> None:
        """Drop the current HTTP client (and its dead pooled socket) so the
        next request opens a fresh connection. Bumps the generation so any
        in-flight chat() retry loop (abandoned by chat_async's hard deadline)
        stops retrying instead of hammering the fresh client."""
        self.generation += 1
        self._client = OpenAI(**self._client_kwargs)

    def _reset_executor(self) -> None:
        """Abandon the thread pool whose workers are wedged on dead sockets.
        shutdown(cancel_futures=True) drops queued work; the already-running
        blocked threads are left behind and eventually die with their socket.
        """
        self._executor.shutdown(wait=False, cancel_futures=True)
        self._executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="llm-chat")

    async def chat_async(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        max_tokens: int | None = None,
    ) -> LLMResponse:
        """Async wrapper around chat() with a hard wall-clock deadline.

        The sync OpenAI call blocks in a bare socket read when the gateway
        connection goes half-open; the SDK timeout does not reliably fire in
        that state (observed 8h hang). wait_for gives a deterministic bound;
        on expiry the client is reset so the retry uses a fresh connection.
        """
        loop = asyncio.get_running_loop()
        fut = loop.run_in_executor(
            self._executor,
            functools.partial(self.chat, messages, tools=tools, max_tokens=max_tokens),
        )
        # A hard-deadline timeout abandons the executor future; consume its
        # eventual exception (the zombie's _AbandonedRetryError / last SDK
        # error) so asyncio never logs "Future exception was never retrieved".
        fut.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
        try:
            return await asyncio.wait_for(fut, timeout=self.hard_timeout)
        except (asyncio.TimeoutError, TimeoutError):
            log.error(
                "LLM hard deadline (%ss) exceeded; resetting HTTP client + executor",
                self.hard_timeout,
            )
            self._reset_executor()
            self.reset_client()
            raise

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        max_tokens: int | None = None,
    ) -> LLMResponse:
        """Send a chat completion request. Retries on rate limit / timeout.
        Abandons immediately when reset_client() bumped the generation
        (this loop belongs to an abandoned hard-deadline call)."""
        my_generation = self.generation
        kwargs: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_tokens or self.max_tokens,
        }
        if tools:
            kwargs["tools"] = tools

        last_error: Exception | None = None
        # Loop makes at most max_retries+1 attempts (1 + max_retries).
        for attempt in range(self.max_retries + 1):
            # Checked at loop top only: a reset landing between the check and
            # the create() call lets a zombie fire at most ONE more upstream
            # request before abandoning on the next iteration (bounded).
            if self.generation != my_generation:
                log.warning(
                    "chat() loop abandoned: client reset while in flight "
                    "(generation %d -> %d)",
                    my_generation,
                    self.generation,
                )
                raise _AbandonedRetryError(
                    f"client reset during retry loop (generation {my_generation} -> {self.generation})"
                )
            try:
                response = self._client.chat.completions.create(**kwargs)
                return LLMResponse.from_openai(response)
            except RateLimitError as e:
                last_error = e
                log.warning("Rate limit (attempt %d/%d): %s", attempt + 1, self.max_retries + 1, e)
                if attempt < self.max_retries:
                    time.sleep(5 * (attempt + 1))  # backoff
            except APITimeoutError as e:
                last_error = e
                log.warning("Timeout (attempt %d/%d): %s", attempt + 1, self.max_retries + 1, e)
                if attempt < self.max_retries:
                    time.sleep(2)
            except APIError as e:
                last_error = e
                log.error("API error: %s", e)
                break  # don't retry on generic API errors

        raise last_error or RuntimeError("Unknown LLM error")
