"""The per-turn step cap moved 200 -> 250 (owner, 2026-10-05).

Discovery-heavy ticks were hitting the 200-step cap and rotating without
applying; the owner asked for more headroom. Pinned behaviourally: a model
that always answers with a tool call must be dispatched exactly 250 times
before the turn ends with max_steps_exceeded.
"""
from unittest.mock import AsyncMock, MagicMock

import pytest

from campaign_agent.llm import LLMResponse, ToolCall
from campaign_agent.main import run_agent_turn


def _tool_response(i: int) -> LLMResponse:
    return LLMResponse(
        content=f"step {i}",
        tool_calls=[ToolCall(id=f"c{i}", name="browser_navigate",
                             arguments={"url": "https://x.test"})],
        finish_reason="tool_calls",
    )


@pytest.mark.asyncio
async def test_step_cap_is_250_not_200():
    llm = MagicMock()
    llm.chat_async = AsyncMock(
        side_effect=[_tool_response(i) for i in range(300)])
    tools = MagicMock()
    tools.schemas = []
    tools.dispatch = AsyncMock(side_effect=lambda name, args: "ok")

    messages = [{"role": "system", "content": "s"},
                {"role": "user", "content": "go"}]
    result = await run_agent_turn(llm, tools, messages, max_steps=250)

    assert result.success is False
    assert "max_steps" in result.reason
    # The cap itself: exactly 250 dispatched steps, not 200.
    assert llm.chat_async.call_count == 250
    assert tools.dispatch.call_count == 250


@pytest.mark.asyncio
async def test_config_default_max_steps_is_250():
    from campaign_agent.config import Config
    assert Config().max_steps == 250
