"""
Main entry point: campaign agent outer loop, agent turn, failure classification.
Owns the LLM → tool_call → dispatch → repeat loop.
"""
from __future__ import annotations

import asyncio
import logging
import re
import sys
import time
from dataclasses import dataclass
from typing import Any

from campaign_agent.config import Config
from campaign_agent.llm import LLMClient, LLMResponse
from campaign_agent.prompt import build_system_prompt, build_user_prompt
from campaign_agent.session import SessionManager
from campaign_agent.tools import ToolRouter
from campaign_agent.tracker import Tracker

log = logging.getLogger(__name__)


@dataclass
class TickResult:
    """Result of a single tick."""
    success: bool
    reason: str = ""
    submitted: int = 0


def classify_failure(text: str) -> str:
    """Classify an error output into rate/context/transient/fatal."""
    text_lower = text.lower()
    if any(p in text_lower for p in ["failover", "streaming response failed", "stream fail", "no_provider_available"]):
        return "transient"
    if any(p in text_lower for p in ["context overflow", "prompt too large", "compaction", "maximum context", "token"]):
        return "context"
    if any(p in text_lower for p in ["rate limit", "429", "too many requests", "timed out", "timeout", "econn"]):
        return "rate"
    if any(p in text_lower for p in ["couldn't generate", "empty response", "no content"]):
        return "transient"
    return "fatal"


async def run_agent_turn(
    llm: LLMClient,
    tools: ToolRouter,
    messages: list[dict[str, Any]],
    max_steps: int = 30,
) -> TickResult:
    """
    Run one agent turn: LLM call → tool dispatch → repeat until done or max_steps.
    The turn ends when the LLM responds with content and no tool calls.
    """
    for step in range(max_steps):
        log.info("Agent step %d/%d", step + 1, max_steps)

        try:
            response = llm.chat(messages, tools=tools.schemas)
        except Exception as e:
            log.error("LLM call failed: %s", e)
            return TickResult(success=False, reason=f"llm_error: {e}")

        messages.append(response.assistant_message_dict())

        if response.is_empty():
            log.warning("Empty LLM response (no content, no tool calls)")
            return TickResult(success=False, reason="empty_response")

        if not response.tool_calls:
            log.info("Agent turn complete: %s", response.content[:200])
            return TickResult(success=True, reason=response.content[:200])

        # Dispatch each tool call
        for tc in response.tool_calls:
            log.info("Tool call: %s(%s)", tc.name, tc.arguments)
            result = await tools.dispatch(tc.name, tc.arguments)
            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": str(result),
            })

            # Check if update_tracker.py was called (potential submission)
            if tc.name == "exec" and "update_tracker.py submitted" in str(tc.arguments.get("command", "")):
                log.info("update_tracker.py submitted called")

    log.warning("Max steps (%d) exceeded", max_steps)
    return TickResult(success=False, reason="max_steps_exceeded")


async def run_campaign(config: Config) -> None:
    """Main campaign loop: ticks until target reached."""
    logging.basicConfig(
        level=logging.INFO,
        format="[%(asctime)s] %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )

    tracker = Tracker(config.tracker_path)
    session = SessionManager(
        config.session_dir,
        config.tracker_path,
        token_budget=config.token_budget,
        rotation_threshold=config.rotation_threshold,
    )

    llm = LLMClient(
        base_url=config.msrouter_url,
        api_key=config.msrouter_api_key,
        model=config.msrouter_model,
        max_retries=3,
    )

    # MCP clients will be initialized when tools are set up
    from campaign_agent.playwright_mcp import PlaywrightMCP
    from campaign_agent.rag_mcp import RAGMCP

    pw = PlaywrightMCP(config.playwright_command, config.playwright_args)
    rag = RAGMCP(config.rag_command, config.rag_args)

    try:
        await pw.connect()
        await rag.connect()
    except Exception as e:
        log.error("MCP connection failed: %s", e)
        log.info("Continuing without MCP (exec-only mode)")

    tools = ToolRouter(playwright_client=pw, rag_client=rag)

    system_prompt = build_system_prompt(config)
    tick = 0

    try:
        while tick < config.outer_max_ticks:
            tracker.reload()
            if tracker.campaign_complete():
                log.info("Campaign complete: %d/%d", tracker.submitted(), tracker.target())
                return

            tick += 1
            log.info("=== Tick %d: %d/%d (%d to go) ===",
                     tick, tracker.submitted(), tracker.target(), tracker.remaining())

            # Build messages for this tick
            session_context = session.build_rotation_context() if session.session_id else ""
            token_info = f"~{session.estimate_tokens()} tokens ({session.estimate_tokens() * 100 // config.token_budget}% of budget)"

            user_prompt = build_user_prompt(config, session_context, token_info)

            # Fresh message list each tick (system + user)
            messages: list[dict[str, Any]] = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ]

            # Proactive rotation check
            if session.should_rotate(messages):
                log.warning("Context near budget, rotating...")
                session.rotate()

            # Run the agent turn
            fail_count = 0
            inner_result = "exhausted"

            while fail_count < config.inner_max_fails:
                log.info("Attempt %d/%d", fail_count + 1, config.inner_max_fails)

                t0 = time.time()
                result = await run_agent_turn(llm, tools, messages, config.max_steps)
                elapsed = time.time() - t0

                log.info("Turn finished in %.0fs: success=%s reason=%s",
                         elapsed, result.success, result.reason[:100])

                if result.success:
                    inner_result = "success"
                    break

                fail_count += 1
                kind = classify_failure(result.reason)

                if kind == "context":
                    log.warning("Context overflow, rotating session")
                    session.rotate()
                    # Rebuild messages for fresh session
                    session_context = session.build_rotation_context()
                    user_prompt = build_user_prompt(config, session_context, "")
                    messages = [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ]
                elif kind == "rate":
                    log.warning("Rate limit, backing off %ss", config.inner_sleep)
                    await asyncio.sleep(config.inner_sleep)
                elif kind == "transient":
                    log.warning("Transient error, retrying in %ss", config.inner_sleep)
                    await asyncio.sleep(config.inner_sleep)
                elif kind == "fatal":
                    log.error("Fatal error: %s", result.reason)
                    inner_result = "fatal"
                    break

            if inner_result == "fatal":
                log.error("Fatal error, stopping campaign")
                break

            if inner_result != "success":
                log.warning("Retries exhausted, backing off %ss", config.outer_backoff)
                await asyncio.sleep(config.outer_backoff)

            await asyncio.sleep(2)  # brief pause between ticks

    finally:
        await pw.close()
        await rag.close()


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(description="Campaign agent")
    parser.add_argument("--config", default="~/.openclaw/director-overrides.env",
                        help="Path to overrides file")
    parser.add_argument("--model", default=None, help="Override model")
    args = parser.parse_args()

    config = Config.from_overrides(args.config)
    if args.model:
        config.msrouter_model = args.model

    asyncio.run(run_campaign(config))


if __name__ == "__main__":
    main()
