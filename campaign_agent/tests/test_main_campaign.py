"""Tests for the run_campaign outer loop — retry kinds, fresh attempts, completion."""
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from campaign_agent.config import Config
from campaign_agent.main import run_campaign


class _PatchHarness:
    """Patches all run_campaign dependencies and exposes the mocks as attributes."""

    def __init__(self):
        self.Tracker = MagicMock()
        self.SessionManager = MagicMock()
        self.LLMClient = MagicMock()
        self.TickContext = MagicMock()
        self.TickContext.return_value.load.return_value = ""
        self.PlaywrightMCP = MagicMock(return_value=AsyncMock())
        self.RAGMCP = MagicMock(return_value=AsyncMock())

        self._patches = [
            patch.multiple(
                "campaign_agent.main",
                Tracker=self.Tracker,
                SessionManager=self.SessionManager,
                LLMClient=self.LLMClient,
                TickContext=self.TickContext,
            ),
            # PlaywrightMCP/RAGMCP are imported lazily inside run_campaign,
            # so they are patched at their source modules.
            patch.multiple(
                "campaign_agent.playwright_mcp",
                PlaywrightMCP=self.PlaywrightMCP,
            ),
            patch.multiple(
                "campaign_agent.rag_mcp",
                RAGMCP=self.RAGMCP,
            ),
        ]
        for patch_ in self._patches:
            patch_.start()

    def stop(self):
        for patch_ in self._patches:
            patch_.stop()


def _cfg(tmp_path, **overrides) -> Config:
    cfg = Config()
    cfg.tracker_path = str(tmp_path / "tracker.json")
    cfg.session_dir = str(tmp_path / "sessions")
    cfg.inner_sleep = 0
    cfg.outer_backoff = 0
    cfg.outer_max_ticks = 5
    for k, v in overrides.items():
        setattr(cfg, k, v)
    return cfg


@pytest.mark.asyncio
async def test_completes_when_target_reached(tmp_path):
    h = _PatchHarness()
    try:
        tracker = h.Tracker.return_value
        tracker.campaign_complete.return_value = True
        tracker.submitted.return_value = 10
        tracker.target.return_value = 10

        await run_campaign(_cfg(tmp_path))

        h.Tracker.assert_called_once()
        tracker.reload.assert_called_once()
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_no_submission_retries_with_fresh_messages(tmp_path):
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        cfg.inner_max_fails = 5

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]  # first tick, then done
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        captured: list[list[dict]] = []

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            captured.append(list(messages))
            if len(captured) == 1:
                return MagicMock(success=False, reason="no_submission: files missing")
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        # Two attempts: first no_submission (retried), second success
        assert len(captured) == 2
        # Every attempt starts with a fresh system + user message list
        for msgs in captured:
            assert len(msgs) == 2
            assert msgs[0]["role"] == "system"
            assert msgs[1]["role"] == "user"
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_no_submission_exhausts_retries_then_backs_off(tmp_path):
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        cfg.inner_max_fails = 2
        cfg.outer_max_ticks = 1

        tracker = h.Tracker.return_value
        tracker.campaign_complete.return_value = False
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        calls = {"n": 0}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            calls["n"] += 1
            return MagicMock(success=False, reason="no_submission: nothing to apply")

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        # inner_max_fails=2 attempts within the single tick, then outer loop exits
        assert calls["n"] == 2
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_context_overflow_rotates_session(tmp_path):
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        cfg.inner_max_fails = 3

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = "sess-abc"
        session.should_rotate.return_value = False
        session.build_rotation_context.return_value = "previous context"

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            return MagicMock(success=False, reason="context overflow")

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        session.rotate.assert_called()
        session.build_rotation_context.assert_called()
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_max_steps_exceeded_does_not_stop_campaign(tmp_path):
    """When the agent exceeds max steps without submitting, the campaign must
    NOT stop: the session is rotated (compressed context) and it keeps going."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        cfg.inner_max_fails = 3

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = "sess-abc"
        session.should_rotate.return_value = False
        session.build_rotation_context.return_value = "compressed previous context"

        calls = {"n": 0}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            calls["n"] += 1
            return MagicMock(success=False, reason="max_steps_exceeded")

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        # Not fatal: inner retries ran (3 attempts), then the campaign
        # continued to a second tick (campaign_complete polled again) instead
        # of stopping. 2 ticks x 3 attempts = 6 turns.
        assert calls["n"] == 6
        session.rotate.assert_called()
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_fatal_error_stops_campaign(tmp_path):
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)

        tracker = h.Tracker.return_value
        tracker.campaign_complete.return_value = False
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        calls = {"n": 0}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            calls["n"] += 1
            return MagicMock(success=False, reason="auth failure")

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        # Fatal stops immediately after one attempt, no retries
        assert calls["n"] == 1
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_router_wired_with_campaign_dir(tmp_path):
    """ToolRouter gets the campaign dir as default exec cwd."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        seen = {}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            seen["router"] = tools
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        assert seen["router"].default_cwd == cfg.campaign_dir
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_mcp_connect_failure_continues_exec_only(tmp_path):
    """If Playwright/RAG MCP fail to connect, the campaign still runs exec-only."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        h.PlaywrightMCP.return_value.connect.side_effect = RuntimeError("cdp down")

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        seen = {"turns": 0}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            seen["turns"] += 1
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        assert seen["turns"] == 1  # exec-only still worked
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_proactive_rotation_when_near_budget(tmp_path):
    """should_rotate=True at tick start rotates before the agent turn."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = "sess-abc"
        session.should_rotate.return_value = True
        session.build_rotation_context.return_value = "rotated context"

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        session.rotate.assert_called_once()
        session.build_rotation_context.assert_called()
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_rate_limit_backs_off_then_succeeds(tmp_path):
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        calls = {"n": 0}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            calls["n"] += 1
            if calls["n"] == 1:
                return MagicMock(success=False, reason="Rate limit reached (429)")
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        assert calls["n"] == 2  # rate-limited once, then succeeded
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_transient_error_retries_then_succeeds(tmp_path):
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        calls = {"n": 0}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            calls["n"] += 1
            if calls["n"] == 1:
                return MagicMock(success=False, reason="Streaming response failed")
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        assert calls["n"] == 2
    finally:
        h.stop()


def test_main_cli_entry(tmp_path):
    """main() parses CLI args, applies model override, and runs the campaign."""
    import sys
    from campaign_agent.main import main

    overrides_path = str(tmp_path / "overrides.env")
    with patch("campaign_agent.main.Config") as mock_cfg_cls, \
         patch("campaign_agent.main.run_campaign", new_callable=AsyncMock) as mock_run:
        mock_cfg = MagicMock()
        mock_cfg_cls.from_overrides.return_value = mock_cfg

        with patch.object(sys, "argv", ["campaign_agent", "--config", overrides_path, "--model", "mst/free"]):
            main()

        mock_cfg_cls.from_overrides.assert_called_once_with(overrides_path)
        assert mock_cfg.msrouter_model == "mst/free"
        mock_run.assert_awaited_once()


@pytest.mark.asyncio
async def test_llm_client_wired_with_timeout_seconds(tmp_path):
    """LLMClient timeout must allow local's slow prefills to complete.

    msrouter gives local (llama-server) attempts up to 300s (LOCAL_TIMEOUT_MS)
    so big 128K prompts can be served, so the client cap moves to 300s.
    Stalled remote providers are still bounded by msrouter's own per-attempt
    timeout (UPSTREAM_TIMEOUT_MS=120s), so the client ceiling only extends the
    window local may use. The config's timeout_seconds is the upper bound, but
    the client caps it.
    """
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        cfg.timeout_seconds = 600

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        call_kwargs = h.LLMClient.call_args.kwargs
        assert call_kwargs["timeout"] <= 300
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_previous_tick_summary_injected_into_user_prompt(tmp_path):
    """The summarized previous tick context must be passed to the next tick's
    user prompt so the model knows what happened before."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        h.TickContext.return_value.load.return_value = "Last tick result: submitted Acme / Backend Engineer"

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        seen = {}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            seen["user_prompt"] = messages[1]["content"]
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        assert "Acme" in seen["user_prompt"]
        h.TickContext.assert_called_once_with(cfg.tick_context_path)
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_tick_summary_saved_after_tick(tmp_path):
    """After a tick completes, a summarized context must be persisted for the
    next tick."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        h.TickContext.return_value.load.return_value = ""

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10
        tracker.recent_applications.return_value = [
            {"company": "Acme", "roleTitle": "Backend Engineer", "appliedAt": "2026-08-02T10:00:00+00:00"}
        ]

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        h.TickContext.return_value.save.assert_called_once()
        saved = h.TickContext.return_value.save.call_args.args[0]
        assert "Acme" in saved
        assert "Backend Engineer" in saved
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_combines_tick_summary_and_rotation_context(tmp_path):
    """Gap B: when both a tick summary and a rotation context exist, both are
    injected into the user prompt (not short-circuited by `or`)."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        h.TickContext.return_value.load.return_value = "Tick summary: Acme"
        session = h.SessionManager.return_value
        session.session_id = "sess-1"
        session.build_rotation_context.return_value = "Rotation: submitted 5/10"
        session.should_rotate.return_value = False

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        seen = {}

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            seen["user_prompt"] = messages[1]["content"]
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        assert "Tick summary: Acme" in seen["user_prompt"]
        assert "Rotation: submitted 5/10" in seen["user_prompt"]
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_token_budget_zero_does_not_crash(tmp_path):
    """Gap D: token_budget=0 must not cause a zero-division error."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        cfg.token_budget = 0

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)
    finally:
        h.stop()


@pytest.mark.asyncio
async def test_tracker_reloaded_before_saving_summary(tmp_path):
    """Gap C: tracker.reload() is called before building the tick summary, so
    a late submission is reflected."""
    h = _PatchHarness()
    try:
        cfg = _cfg(tmp_path)
        h.TickContext.return_value.load.return_value = ""

        tracker = h.Tracker.return_value
        tracker.campaign_complete.side_effect = [False, True]
        tracker.submitted.return_value = 5
        tracker.target.return_value = 10
        tracker.recent_applications.return_value = [
            {"company": "LateCo", "roleTitle": "Dev", "appliedAt": "2026-08-02T10:00:00+00:00"}
        ]

        session = h.SessionManager.return_value
        session.session_id = None
        session.should_rotate.return_value = False

        async def fake_turn(llm, tools, messages, max_steps, **kwargs):
            return MagicMock(success=True, reason="recorded", submitted=1)

        with patch("campaign_agent.main.run_agent_turn", side_effect=fake_turn):
            await run_campaign(cfg)

        # reload called (at least the top-of-tick one + the pre-save one)
        assert tracker.reload.call_count >= 2
        saved = h.TickContext.return_value.save.call_args.args[0]
        assert "LateCo" in saved
    finally:
        h.stop()
