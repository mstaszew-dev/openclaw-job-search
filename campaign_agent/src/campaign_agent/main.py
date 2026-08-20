"""
Main entry point: campaign agent outer loop, agent turn, failure classification.
Owns the LLM → tool_call → dispatch → repeat loop.
"""
from __future__ import annotations

# Suppress the "leaked semaphore" warning from loky/joblib at shutdown
import warnings
warnings.filterwarnings("ignore", category=UserWarning, module="multiprocessing.resource_tracker")

import asyncio
import json
import logging
import os
import re
import sys
import time
from dataclasses import dataclass
from typing import Any

from campaign_agent.config import Config
from campaign_agent.llm import LLMClient, LLMResponse
from campaign_agent.prompt import build_system_prompt, build_user_prompt
from campaign_agent.session import SessionManager, TickContext, build_tick_summary, estimate_tokens_from_messages
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
    """Classify an error output into rate/context/transient/fatal.

    Matches the exact reason tokens emitted by run_agent_turn
    ('empty_response', 'no_submission: ...') in addition to free-text phrases,
    so those retryable reasons never fall through to fatal.
    """
    text_lower = text.lower()
    # run_agent_turn reason tokens (retryable, never fatal)
    if text_lower.startswith("empty_response") or text_lower.startswith("no_submission"):
        return "transient"
    # max_steps_exceeded: the agent looped to the step cap without submitting.
    # The context at that point is huge, so treat it like context pressure:
    # rotate (compress) the session and keep going. NEVER fatal - the campaign
    # must not stop just because one tick's chain walk was long.
    if text_lower.startswith("max_steps"):
        return "max_steps"
    if any(p in text_lower for p in ["failover", "streaming response failed", "stream fail", "no_provider_available"]):
        return "transient"
    if any(p in text_lower for p in ["context overflow", "prompt too large", "compaction", "maximum context", "token"]):
        return "context"
    if any(p in text_lower for p in ["rate limit", "429", "too many requests", "timed out", "timeout", "econn"]):
        return "rate"
    if any(p in text_lower for p in ["connection error", "connection refused", "connection reset", "apiconnectionerror", "connection"]):
        return "transient"
    # Parse errors from malformed free-model responses (NoneType subscript,
    # KeyError on missing 'choices', IndexError). Transient: next model differs.
    if "nonetype" in text_lower or "llm_error:" in text_lower:
        return "transient"
    if any(p in text_lower for p in ["couldn't generate", "empty response", "no content"]):
        return "transient"
    return "fatal"


def _truncate_messages(
    messages: list[dict[str, Any]],
    token_budget: int,
    keep_last: int = 20,
) -> list[dict[str, Any]]:
    """Truncate message history to stay within token budget.

    Always preserves: system prompt (index 0), first user message (index 1),
    and the last `keep_last` messages. Drops middle messages when over budget.
    Returns a new list (does not mutate the original).
    """
    if len(messages) <= 2:
        return list(messages)

    current_tokens = estimate_tokens_from_messages(messages)
    if current_tokens <= token_budget:
        return list(messages)

    # Always keep system + first user message
    prefix = messages[:2]
    # Keep the last keep_last messages (or fewer if not enough)
    suffix = messages[-keep_last:] if len(messages) > keep_last else messages[2:]
    # Drop everything in between
    truncated = prefix + suffix

    dropped = len(messages) - len(truncated)
    new_tokens = estimate_tokens_from_messages(truncated)
    drop_start = 2  # always after system + first user
    drop_end = len(messages) - keep_last
    drop_roles = [m.get("role", "?") for m in messages[drop_start:drop_end]]
    log.info(
        "Truncated messages: %d → %d (dropped %d [%s], ~%d → ~%d tokens)",
        len(messages), len(truncated), dropped,
        ",".join(drop_roles) if drop_roles else "none",
        current_tokens, new_tokens,
    )
    return truncated


async def run_agent_turn(
    llm: LLMClient,
    tools: ToolRouter,
    messages: list[dict[str, Any]],
    max_steps: int = 200,
    context_token_budget: int = 102400,
) -> TickResult:
    """
    Run one agent turn: LLM call → tool dispatch → repeat until done or max_steps.
    The turn ends when the LLM responds with content and no tool calls.
    Messages are truncated in-place when they exceed context_token_budget.
    """
    recorded_submission = False
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
            # Anti-gaming: content alone is NOT a successful tick. A tick only
            # succeeds when update_tracker.py submitted was invoked and exit=0.
            if recorded_submission:
                return TickResult(success=True, reason=response.content[:200], submitted=1)
            return TickResult(success=False, reason=f"no_submission: {response.content[:200]}")

        # Dispatch each tool call
        for tc in response.tool_calls:
            log.info("Tool call: %s(%s)", tc.name, tc.arguments)
            result = await tools.dispatch(tc.name, tc.arguments)
            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": str(result),
            })

            # A submission is recorded only when update_tracker.py submitted
            # actually succeeded (exit=0). Failed commands do not count.
            if tc.name == "exec":
                command = str(tc.arguments.get("command", ""))
                if "update_tracker.py submitted" in command and "exit=0" in str(result):
                    recorded_submission = True
                    log.info("Submission recorded: %s", command[:120])

        # Truncate if context is growing too large (prevents malformed JSON
        # from under-trained models choking on huge prompts)
        if estimate_tokens_from_messages(messages) > context_token_budget:
            truncated = _truncate_messages(messages, token_budget=context_token_budget)
            messages.clear()
            messages.extend(truncated)

    log.warning("Max steps (%d) exceeded", max_steps)
    if recorded_submission:
        return TickResult(success=True, reason="max_steps_after_submission", submitted=1)
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
    tick_context = TickContext(config.tick_context_path)

    llm = LLMClient(
        base_url=config.msrouter_url,
        api_key=config.msrouter_api_key,
        model=config.msrouter_model,
        max_retries=3,
        # Timeout is managed entirely by msrouter per-provider (e.g.
        # LMSTUDIO_TIMEOUT_MS=1200s for local, UPSTREAM_TIMEOUT_MS=120s
        # for remote). The client timeout is a safety ceiling only; msrouter
        # will abort the attempt before the client does in normal operation.
        timeout=config.timeout_seconds,
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

    tools = ToolRouter(playwright_client=pw, rag_client=rag, default_cwd=config.campaign_dir)

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

            # Build context for this tick: combine the summarized previous tick
            # (if any) with the session rotation context (5 recent apps from
            # tracker). Both are useful; neither should shadow the other.
            prev_summary = tick_context.load()
            rot_ctx = session.build_rotation_context() if session.session_id else ""
            parts = [p for p in [prev_summary, rot_ctx] if p]
            session_context = "\n\n".join(parts)
            budget = max(config.token_budget, 1)
            token_info = f"~{session.estimate_tokens()} tokens ({session.estimate_tokens() * 100 // budget}% of budget)"

            user_prompt = build_user_prompt(config, session_context, token_info)

            # Proactive rotation check
            if session.should_rotate([{"role": "system", "content": system_prompt},
                                      {"role": "user", "content": user_prompt}]):
                log.warning("Context near budget, rotating...")
                session.rotate()
                session_context = session.build_rotation_context() if session.session_id else ""
                budget = max(config.token_budget, 1)
                token_info = f"~{session.estimate_tokens()} tokens ({session.estimate_tokens() * 100 // budget}% of budget)"
                user_prompt = build_user_prompt(config, session_context, token_info)

            # Run the agent turn
            fail_count = 0
            inner_result = "exhausted"

            while fail_count < config.inner_max_fails:
                log.info("Attempt %d/%d", fail_count + 1, config.inner_max_fails)

                # Fresh messages per attempt (mirrors run-one-job: each attempt
                # is a clean turn with the same tick prompt)
                messages: list[dict[str, Any]] = [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ]

                t0 = time.time()
                # Truncate at 80% of token budget to leave room for response.
                # Include tool schemas in the budget (they add 2-5K tokens that
                # the message-only estimator doesn't count).
                tool_chars = sum(len(json.dumps(s)) for s in tools.schemas)
                tool_tokens = tool_chars // 4
                ctx_budget = max(1000, int(config.token_budget * 0.80) - tool_tokens)
                result = await run_agent_turn(
                    llm, tools, messages, config.max_steps,
                    context_token_budget=ctx_budget,
                )
                elapsed = time.time() - t0

                log.info("Turn finished in %.0fs: success=%s reason=%s",
                         elapsed, result.success, result.reason[:100])

                if result.success:
                    inner_result = "success"
                    break

                fail_count += 1
                kind = classify_failure(result.reason)
                if result.reason.startswith("no_submission"):
                    kind = "no_submission"

                if kind == "context":
                    log.warning("Context overflow, rotating session")
                    session.rotate()
                    session_context = session.build_rotation_context() if session.session_id else ""
                    user_prompt = build_user_prompt(config, session_context, "")
                elif kind == "max_steps":
                    # The agent burned the full step budget without submitting.
                    # Rotate (compress) the session context so the next attempt
                    # starts lean, then retry. If retries exhaust, the tick ends
                    # and the next tick opens with the compressed tick summary.
                    log.warning("Max steps exceeded, rotating session for compressed context")
                    session.rotate()
                    session_context = session.build_rotation_context() if session.session_id else ""
                    user_prompt = build_user_prompt(config, session_context, "")
                    await asyncio.sleep(config.inner_sleep)
                elif kind == "no_submission":
                    log.warning("Agent finished without recording a submission; retrying fresh in %ss",
                                config.inner_sleep)
                    await asyncio.sleep(config.inner_sleep)
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

            # Persist a summarized context for the next tick.
            # Reload the tracker first so a late submission (recorded via
            # update_tracker.py during the tick) is reflected in the summary.
            try:
                tracker.reload()
                summary = build_tick_summary(
                    tracker=tracker, attempts=fail_count, reason=result.reason,
                )
                tick_context.save(summary)
                log.info("Saved tick context: %s", summary.replace("\n", " | ")[:120])
            except Exception as e:
                log.warning("Could not save tick context: %s", e)

            await asyncio.sleep(2)  # brief pause between ticks

    finally:
        await pw.close()
        await rag.close()


def assert_in_iterm() -> None:
    """Refuse to start unless running inside an iTerm2 session.

    Checks the TERM_PROGRAM environment variable that iTerm2 sets to
    'iTerm.app' on every session. Prevents stealth background runs where
    the agent can't be supervised (no visible terminal tab).
    """
    if os.environ.get("TERM_PROGRAM") != "iTerm.app":
        term = os.environ.get("TERM_PROGRAM", "(unset)")
        print(
            f"[campaign-agent] FATAL: must be launched from iTerm2 "
            f"(detected TERM_PROGRAM={term}).\n"
            f"Open iTerm2 and run: python -m campaign_agent.main",
            file=sys.stderr,
        )
        sys.exit(1)


def main() -> None:
    """CLI entry point."""
    assert_in_iterm()
    import argparse

    parser = argparse.ArgumentParser(description="Campaign agent")
    parser.add_argument("--config", default="~/.campaign-agent/director-overrides.env",
                        help="Path to overrides file")
    parser.add_argument("--model", default=None, help="Override model")
    args = parser.parse_args()

    config = Config.from_overrides(args.config)
    if args.model:
        config.msrouter_model = args.model

    asyncio.run(run_campaign(config))


if __name__ == "__main__":
    main()
