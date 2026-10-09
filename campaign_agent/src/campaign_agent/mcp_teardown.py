"""Shared best-effort teardown for MCP client context managers.

2026-10-07 crash loop: after a wedged browser tool call, mcp's stdio_client
cleanup raised BaseExceptionGroup (anyio wraps GeneratorExit, a BaseException).
`except Exception` does not catch BaseExceptionGroup, so teardown escaped
close(), killed the campaign worker, and the Director respawned it every
5 minutes - leaking the MCP subprocesses it had spawned (four orphan
playwright-mcp processes were found from one morning of this).
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Final

log = logging.getLogger(__name__)

# Exceptions that mean "this MCP session is unusable", used by every guard on
# the tool-call path.
#
# asyncio.CancelledError is a BaseException, NOT an Exception: anyio cancels
# the session's own tasks through its cancel scopes, and that cancellation
# sailed straight past every `except Exception` guard
# (call_tool -> tools.dispatch -> run_agent_turn -> asyncio.run), killing the
# campaign worker mid-tick on 2026-10-09. A cancelled session is just a dead
# session, so it is reported like any other session failure and the wedge
# watchdog decides what to do about it.
#
# This cannot be narrowed further: the MCP session's cancel scopes are nested
# inside the campaign task itself (that is why the production message read
# "by Task-1 run_campaign"), so a session cancellation and a cancellation of
# the campaign are indistinguishable at the `except` site. Nothing is lost by
# containing both: shutdown here is signal-driven (main.py's SIGTERM/SIGINT
# handlers restore SIG_DFL and re-signal), and there is no task.cancel() in
# src/. Not calling task.uncancel() is likewise deliberate: anyio reconciles
# its own _pending_uncancellations when the scope exits, so the task stays
# schedulable and wait_for/asyncio.timeout keep raising TimeoutError.
SESSION_FAILURES: Final[tuple[type[BaseException], ...]] = (
    Exception,
    asyncio.CancelledError,
)


async def exit_ctx_quietly(ctx: Any) -> None:
    """Exit one MCP context manager, never raising (except cancellation).

    One context's fatal cleanup must not abort the rest of the stack or the
    campaign. CancelledError is deliberately re-raised: swallowing it would
    make shutdown hang instead.
    """
    try:
        await ctx.__aexit__(None, None, None)
    except asyncio.CancelledError:
        raise
    except (Exception, BaseExceptionGroup):
        log.debug("MCP context teardown error swallowed", exc_info=True)
