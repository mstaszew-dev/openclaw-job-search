"""Shared best-effort teardown for MCP client context managers.

2026-10-07 crash loop: after a wedged browser tool call, mcp's stdio_client
cleanup raised BaseExceptionGroup (anyio wraps GeneratorExit, a BaseException).
`except Exception` does not catch BaseExceptionGroup, so teardown escaped
close(), killed the campaign worker, and the Director respawned it every
5 minutes - leaking the MCP subprocesses it had spawned (four orphan
playwright-mcp processes were found from one morning of this).

2026-10-11: the follow-on crash this module now also owns. Catching a session
cancellation was not enough to survive it. anyio re-delivers a cancelled
scope's cancellation at EVERY subsequent await until that scope exits, so an
MCP session left live killed the worker one step after the guard caught the
first CancelledError: the log showed step 41 completing, then the process died
inside llm.chat_async's asyncio.wait_for (llm.py:184) with "Cancelled via
cancel scope ... by Task-1 run_campaign".

The fix is to contain that cancellation where it is caught, in call_tool:
that path only counts a strike and returns, leaving the session's scope live
around the campaign. contain_session_cancellation() clears both asyncio's
counter and anyio's re-delivery flag, so the scope stops cancelling a task
that is busy being a campaign. The scope is cleared, not exited: the real
unwind still happens later through close().

exit_ctx_quietly is deliberately left as it was. A session scope (mcp's anyio
task group) swallows its own cancellation and exits cleanly, so its re-raise
never fires for the session case; close() already re-reports a cancellation to
its caller.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Final

log = logging.getLogger(__name__)


def absorb_session_failure(exc: BaseException, client: Any) -> None:
    """Contain a session failure if it was a cancellation; otherwise nothing.

    Used at every `except SESSION_FAILURES` site so no path can return with a
    live cancelled scope still wrapped around the campaign task (the dialog
    dismiss and post-dismiss retry paths included - those return without
    closing, so nothing downstream would ever stop the re-delivery).
    """
    if isinstance(exc, asyncio.CancelledError):
        contain_session_cancellation(client)

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
# src/.
SESSION_FAILURES: Final[tuple[type[BaseException], ...]] = (
    Exception,
    asyncio.CancelledError,
)


def session_cancel_scopes(client: Any) -> list[Any]:
    """The anyio cancel scopes owned by a live MCP session.

    mcp's ClientSession runs its dispatcher on an anyio task group and cancels
    the session through that group's cancel scope. The attribute is private and
    has changed name across mcp versions (2.0 uses `_task_group`), so every
    known spelling is probed rather than assumed - getting this wrong silently
    disables the whole containment (an earlier revision matched only `.tg`,
    which mcp 2.0 does not have, so the fix would never have run in production).

    Contexts that expose no task group (the stdio transport, a bare session
    mock) simply contribute nothing.
    """
    scopes: list[Any] = []
    for ctx in getattr(client, "_ctx_stack", ()) or ():
        for attr in ("_task_group", "tg", "task_group"):
            task_group = getattr(ctx, attr, None)
            if task_group is None:
                continue
            scope = getattr(task_group, "cancel_scope", None)
            if scope is not None and scope not in scopes:
                scopes.append(scope)
    return scopes


def contain_session_cancellation(client: Any) -> None:
    """Stop anyio from re-delivering a dead session's cancellation.

    Call this at the `except SESSION_FAILURES` site in call_tool, right after a
    CancelledError from a session scope. Catching the error is not enough: a
    cancelled anyio scope re-delivers CancelledError at EVERY subsequent await
    until it exits, and the session scope does not exit there - call_tool only
    counts a strike and returns, leaving the scope live around the campaign.
    That is what killed the worker on 2026-10-11 one step after the guard
    caught the first one (log: step 41 completes, then the process dies in
    llm.chat_async's asyncio.wait_for, llm.py:184).

    Uncancelling the task alone is not enough either: the task can be
    schedulable while the scope keeps cancelling it, so anyio's side is cleared
    too. Only the SESSION's own scopes are touched - a deadline/timeout scope
    that anyio owns must be left to exit normally, or anyio's __exit__ asserts
    on _pending_uncancellations (pinned by test_mcp's deadline-scope test).
    Scopes are cleared, never exited; the real unwind still happens in close().

    Narrow, idempotent, and a no-op when no cancellation is pending. It does
    contain a cancellation of the campaign task as well, which is safe here:
    shutdown is signal-driven (main.py's SIGTERM/SIGINT handlers restore SIG_DFL
    and re-signal), and grep confirms no task.cancel() on an asyncio Task
    anywhere in src/ (the only .cancel() calls are anyio scope cancels and
    asyncio.Handle.cancel()). A future graceful-shutdown task.cancel() would be
    silently contained, so re-check that invariant before adding one.
    """
    task = asyncio.current_task()
    if task is not None:
        while task.cancelling() > 0:
            task.uncancel()
    for scope in session_cancel_scopes(client):
        # Best-effort: this runs inside an `except SESSION_FAILURES` handler, so
        # an AttributeError from an anyio rename would escape and kill the
        # worker - the exact failure being fixed. anyio is a transitive
        # dependency, so its internals are not ours to guarantee.
        try:
            scope._cancel_called = False
            if scope._cancel_handle is not None:
                scope._cancel_handle.cancel()
                scope._cancel_handle = None
        except Exception:
            log.warning("could not clear a session cancel scope", exc_info=True)


async def exit_ctx_quietly(ctx: Any) -> None:
    """Exit one MCP context manager, never raising (except cancellation).

    One context's fatal cleanup must not abort the rest of the stack or the
    campaign. CancelledError is deliberately re-raised: swallowing it would
    make shutdown hang instead. (Note the session scopes reached from here -
    mcp's anyio task groups - swallow their own cancellation and exit
    cleanly, so this re-raise does not fire for them.)
    """
    try:
        await ctx.__aexit__(None, None, None)
    except asyncio.CancelledError:
        raise
    except (Exception, BaseExceptionGroup):
        log.debug("MCP context teardown error swallowed", exc_info=True)
