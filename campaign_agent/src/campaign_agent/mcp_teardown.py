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
from typing import Any

log = logging.getLogger(__name__)


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
