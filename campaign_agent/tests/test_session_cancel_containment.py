"""Regression tests for the session-cancellation re-delivery crash (2026-10-11).

b142d5e (2026-10-09) contained the CancelledError raised by the MCP session,
and the wedge watchdog then logged "Playwright MCP respawn failed: Cancelled
via cancel scope ... by Task-1 run_campaign". The worker still died one step
later, inside llm.chat_async's asyncio.wait_for (traceback: llm.py:184) - the
log showed step 41 completing, then the process dying.

Root cause: catching a CancelledError is not enough to survive it. anyio
re-delivers a cancelled scope's cancellation at EVERY subsequent await until
that scope exits. The MCP session's scope (an anyio task group inside
ClientSession/stdio_client) encloses the campaign and is abandoned when a
respawn half-fails, so it never exits and the worker dies at the next await.

contain_session_cancellation() clears both the asyncio cancel counter and
anyio's re-delivery flag for scopes anyio no longer owns (see the predicate in
mcp_teardown), leaving anyio-owned deadline scopes alone.

The existing TestRealAnyioCancelScopeContained mocked pw.close, so it never
exercised real scope teardown and could not catch this. These tests use real
anyio task groups, the shape mcp actually runs.
"""
import asyncio
from unittest.mock import AsyncMock

import anyio
import pytest

from campaign_agent.playwright_mcp import PlaywrightMCP
from campaign_agent.rag_mcp import RAGMCP


class _SessionCtx:
    """A real anyio TASK GROUP, shaped like mcp's ClientSession.

    The attribute name matters as much as the shape: mcp 2.0 stores the task
    group as `_task_group` (verified against the installed SDK), so a fake
    exposing `.tg` would let a wrong predicate pass these tests while doing
    nothing in production. test_session_scopes_match_the_real_mcp_attribute
    pins that.
    """

    def __init__(self):
        self._task_group = None
        self.exited = False

    async def __aenter__(self):
        self._task_group = anyio.create_task_group()
        await self._task_group.__aenter__()
        return self

    async def __aexit__(self, *exc):
        self.exited = True
        return await self._task_group.__aexit__(*exc)

    @property
    def scope(self):
        return self._task_group.cancel_scope

    def cancel(self):
        self._task_group.cancel_scope.cancel()


class TestPredicateMatchesTheRealMcpSession:
    """A predicate that finds no scope silently disables the whole fix, and no
    behavioural test would notice (the fakes would just be shaped to match it).
    So assert the predicate against the REAL installed mcp class."""

    def test_real_client_session_exposes_the_task_group_attribute_we_probe(self):
        mcp_session = pytest.importorskip("mcp.client.session")
        import inspect

        source = inspect.getsource(mcp_session.ClientSession.__aenter__)
        assigned = [
            line.strip().split("=")[0].strip().removeprefix("self.")
            for line in source.splitlines()
            if "task_group" in line and "self." in line and "=" in line
        ]
        assert assigned, "ClientSession no longer creates a task group here"
        found = [name for name in assigned if name in ("_task_group", "tg", "task_group")]
        assert found, (
            f"ClientSession stores its task group as {assigned!r}; "
            "session_cancel_scopes() must probe that name"
        )

    def test_predicate_finds_a_session_shaped_like_real_mcp(self):
        from campaign_agent.mcp_teardown import session_cancel_scopes

        client = PlaywrightMCP("node", [])
        client._ctx_stack.append(_SessionCtx())
        # Before entering, there is no task group to find - the predicate must
        # not raise, because that is what a stdio transport looks like.
        assert session_cancel_scopes(client) == []

    @pytest.mark.asyncio
    async def test_predicate_finds_the_entered_session_scope(self):
        from campaign_agent.mcp_teardown import session_cancel_scopes

        ctx = await _enter_session()
        client = PlaywrightMCP("node", [])
        client._ctx_stack.append(ctx)

        assert session_cancel_scopes(client) == [ctx.scope], (
            "the containment would find no scope and the crash would recur"
        )

    @pytest.mark.asyncio
    async def test_containment_survives_an_anyio_rename(self):
        """The scope mutation runs inside an `except SESSION_FAILURES` handler;
        an AttributeError there would escape and kill the worker - the exact
        failure being fixed. It must stay best-effort."""
        from campaign_agent.mcp_teardown import contain_session_cancellation

        class Renamed:
            def __init__(self):
                self._task_group = None

        class BrokenScope:
            @property
            def cancel_scope(self):  # the getter itself is fine...
                return self

            # ...but the private attributes anyio would set no longer exist
            def __setattr__(self, name, value):
                if name == "_cancel_called":
                    raise AttributeError(name)
                object.__setattr__(self, name, value)

        holder = Renamed()
        holder._task_group = BrokenScope()
        client = PlaywrightMCP("node", [])
        client._ctx_stack.append(holder)

        # Must not raise, and must still leave the task schedulable.
        contain_session_cancellation(client)


def _llm_await():
    """One production-style LLM call: an executor future under wait_for
    (llm.chat_async). This is the await point that raised in production."""
    fut = asyncio.get_running_loop().run_in_executor(None, lambda: "LLM")
    return asyncio.wait_for(fut, timeout=5)


async def _enter_session() -> _SessionCtx:
    """Enter a session task group and return the wrapper. The task group is
    entered in the CURRENT task (as ClientSession's is, inside stdio_client),
    so its cancel scope is live for the rest of the test."""
    ctx = _SessionCtx()
    await ctx.__aenter__()
    return ctx


class TestToolCallContainsTheCancellation:
    """Wiring: the production catch site must actually call the containment,
    otherwise the worker still dies on the next await."""

    @pytest.mark.asyncio
    async def test_call_tool_contains_a_session_cancellation(self):
        ctx = await _enter_session()
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        pw._ctx_stack.append(ctx)  # as connect() does

        async def cancelling_call(name, args):
            ctx.cancel()  # the session's task group cancels, as mcp's does
            await asyncio.sleep(60)

        pw._session = AsyncMock()
        pw._session.call_tool = cancelling_call

        result = await asyncio.wait_for(
            pw.call_tool("browser_evaluate", {"function": "() => 1"}, timeout=5),
            timeout=20,
        )
        assert result.startswith("Error"), "a cancelled session is a session failure"

        # The campaign's next LLM call must not be poisoned.
        for _ in range(3):
            try:
                assert await _llm_await() == "LLM"
            except asyncio.CancelledError:
                pytest.fail(
                    "call_tool returned but left the task cancelled: the worker "
                    "would die on its next await (the 2026-10-11 crash)"
                )

    @pytest.mark.asyncio
    async def test_rag_call_tool_contains_a_session_cancellation(self):
        ctx = await _enter_session()
        rag = RAGMCP("node", [])
        rag._ctx_stack.append(ctx)  # as connect() does

        async def cancelling_call(name, args):
            ctx.cancel()
            await asyncio.sleep(60)

        rag._session = AsyncMock()
        rag._session.call_tool = cancelling_call

        result = await asyncio.wait_for(
            rag.call_tool("rag_search_apps", {"query": "x"}, timeout=5), timeout=20
        )
        assert result.startswith("Error")

        # Several awaits: a live cancelled scope re-delivers on the second one,
        # so a single follow-up call would pass even without the containment.
        for _ in range(4):
            try:
                assert await _llm_await() == "LLM"
            except asyncio.CancelledError:
                pytest.fail(
                    "RAG call_tool left the task cancelled: the worker would "
                    "die on a later await (the 2026-10-11 crash)"
                )


class TestSessionCancellationDoesNotKillWorker:
    @pytest.mark.asyncio
    async def test_live_cancelled_scope_re_delivers_to_later_awaits(self):
        """Pins the mechanism: a live cancelled scope re-delivers
        CancelledError at every later await. This is what killed the worker,
        and it is why catching the first one is not enough."""
        ctx = await _enter_session()
        ctx.cancel()
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            pass  # the guard, exactly as SESSION_FAILURES does

        hits = 0
        for _ in range(4):
            try:
                await _llm_await()
            except asyncio.CancelledError:
                hits += 1
        assert hits >= 1, (
            "expected a live cancelled scope to re-deliver CancelledError at "
            "later awaits; if this stops happening the test no longer pins the "
            "real mechanism"
        )

    @pytest.mark.asyncio
    async def test_worker_survives_awaits_after_handling_session_cancellation(self):
        """The end-to-end regression: after the guard catches a session
        cancellation, later awaits must keep working (production survived step
        41, then died inside llm.chat_async's wait_for)."""
        from campaign_agent.mcp_teardown import contain_session_cancellation

        ctx = await _enter_session()
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        pw._ctx_stack.append(ctx)

        ctx.cancel()
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            pass  # the guard

        contain_session_cancellation(pw)

        results = []
        for _ in range(5):
            try:
                results.append(await _llm_await())
            except asyncio.CancelledError:
                pytest.fail(
                    "worker died after handling a session cancellation: a live "
                    "cancelled scope kept re-delivering to later awaits"
                )
        assert results == ["LLM"] * 5

    @pytest.mark.asyncio
    async def test_contain_session_cancellation_uncancels_the_anyio_scope_chain(self):
        """anyio reschedules _deliver_cancellation each loop iteration while the
        scope's cancel_called flag is set. Clearing it (and cancelling the
        scheduled handle) is what stops the re-delivery loop."""
        from campaign_agent.mcp_teardown import contain_session_cancellation

        ctx = await _enter_session()
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        pw._ctx_stack.append(ctx)

        ctx.cancel()
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            pass

        scope = ctx.scope
        assert scope._cancel_called is True, "precondition: the scope is cancelled"

        contain_session_cancellation(pw)

        assert scope._cancel_called is False, (
            "the scope still looks cancelled, so anyio keeps re-delivering"
        )
        assert scope._cancel_handle is None, (
            "a scheduled _deliver_cancellation would still fire once"
        )

    @pytest.mark.asyncio
    async def test_contain_session_cancellation_clears_the_task_cancel_counter(self):
        """The task must be left schedulable: asyncio re-raises CancelledError
        at the next await while cancelling() > 0."""
        from campaign_agent.mcp_teardown import contain_session_cancellation

        ctx = await _enter_session()
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        pw._ctx_stack.append(ctx)

        ctx.cancel()
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            pass

        task = asyncio.current_task()
        assert task is not None and task.cancelling() > 0

        contain_session_cancellation(pw)

        assert task.cancelling() == 0, (
            "the task is still marked cancelled, so the next await raises"
        )

    @pytest.mark.asyncio
    async def test_contain_session_cancellation_is_a_noop_without_cancellation(self):
        """A normal (uncancelled) path must not be disturbed."""
        from campaign_agent.mcp_teardown import contain_session_cancellation

        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        contain_session_cancellation(pw)  # nothing pending, no session

        await asyncio.sleep(0)  # still schedulable
        task = asyncio.current_task()
        assert task is not None and task.cancelling() == 0

    @pytest.mark.asyncio
    async def test_contain_leaves_a_foreign_scope_to_anyio(self):
        """Only the session's OWN scopes may be cleared. A scope anyio owns (a
        deadline/timeout scope) must be left to exit normally, or anyio's
        __exit__ asserts on _pending_uncancellations (pinned by test_mcp's
        deadline-scope test)."""
        from campaign_agent.mcp_teardown import contain_session_cancellation

        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        # No session context in the stack, so nothing may be touched.
        contain_session_cancellation(pw)

        with anyio.CancelScope() as deadline_scope:
            deadline_scope.deadline = anyio.current_time() + 0.05
            try:
                await asyncio.sleep(1)
            except asyncio.CancelledError:
                contain_session_cancellation(pw)
        # A clean exit here is the assertion: the flag was left alone.

    @pytest.mark.asyncio
    async def test_close_unwinds_a_cancelled_session_scope(self):
        """close() must still unwind a CANCELLED session cleanly. This is the
        containment's safety net: contain_session_cancellation only stops the
        re-delivery, the real unwind still happens here (mcp's task group
        swallows its own cancellation and exits, so exit_ctx_quietly's
        re-raise does not fire)."""
        ctx = await _enter_session()
        pw = PlaywrightMCP("node", [], wedge_restart_strikes=3)
        pw._ctx_stack.append(ctx)
        pw._session = AsyncMock()

        ctx.cancel()
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            pass

        await asyncio.wait_for(pw.close(), timeout=10)

        assert ctx.exited, "close() must complete the context exit"
        assert pw._session is None
