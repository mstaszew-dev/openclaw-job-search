"""run_tick: outer loop with tracker-delta anti-gaming validation."""
from __future__ import annotations

import json
from pathlib import Path

from jobhermes.config import Config
from jobhermes.runner import (
    REASON_CAMPAIGN_COMPLETE,
    REASON_EXHAUSTED,
    REASON_SUCCESS,
    run_tick,
)


def make_config(
    tmp_path: Path, inner_max_fails: int = 3, inner_sleep: float = 0
) -> Config:
    campaign = tmp_path / "campaign"
    campaign.mkdir(exist_ok=True)
    return Config(
        campaign_dir=str(campaign),
        tick_context_path=str(tmp_path / "state" / "tick-context.md"),
        inner_max_fails=inner_max_fails,
        inner_sleep=inner_sleep,
    )


def test_campaign_complete_short_circuits_without_hermes(
    tmp_path: Path, tracker_factory
) -> None:
    tracker_path = tracker_factory(submitted=1500, target=1500)
    config = make_config(tmp_path)
    config.campaign_dir = str(tracker_path.parent)  # tracker lives beside campaign dir
    calls: list[str] = []

    def attempt(config: Config, prompt: str):
        calls.append(prompt)
        return 0, "", ""

    assert run_tick(config, run_attempt_fn=attempt) == REASON_CAMPAIGN_COMPLETE
    assert calls == []


def _tracker_bumper(tracker_path: Path):
    def attempt(config: Config, prompt: str):
        data = json.loads(tracker_path.read_text(encoding="utf-8"))
        data["stats"]["submitted"] += 1
        tracker_path.write_text(json.dumps(data), encoding="utf-8")
        return 0, "applied and recorded", ""

    return attempt


def test_success_when_tracker_increases(tmp_path: Path, tracker_factory) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path)
    config.campaign_dir = str(tracker_path.parent)
    outcome = run_tick(config, run_attempt_fn=_tracker_bumper(tracker_path))
    assert outcome == REASON_SUCCESS


def test_exit_zero_without_delta_is_no_submission_retry(
    tmp_path: Path, tracker_factory
) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, inner_max_fails=2, inner_sleep=0.0)
    config.campaign_dir = str(tracker_path.parent)
    sleeps: list[float] = []
    attempts: list[str] = []

    def attempt(config: Config, prompt: str):
        attempts.append(prompt)
        return 0, "done", ""  # claims success without recording

    outcome = run_tick(config, run_attempt_fn=attempt, sleep_fn=sleeps.append)
    assert outcome == REASON_EXHAUSTED
    assert len(attempts) == 2
    assert sleeps == [config.inner_sleep]


def test_nonzero_exit_also_retries_and_is_classified(
    tmp_path: Path, tracker_factory
) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, inner_max_fails=2, inner_sleep=0.0)
    config.campaign_dir = str(tracker_path.parent)
    logs: list[str] = []

    def attempt(config: Config, prompt: str):
        return 2, "", "usage error"

    outcome = run_tick(
        config, run_attempt_fn=attempt, sleep_fn=lambda s: None, log=logs.append
    )
    assert outcome == REASON_EXHAUSTED
    assert any("hermes_exit_2" in line for line in logs)


def test_retries_then_succeeds(tmp_path: Path, tracker_factory) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, inner_max_fails=5, inner_sleep=0.0)
    config.campaign_dir = str(tracker_path.parent)
    attempt = _tracker_bumper(tracker_path)
    real_calls: list[int] = []

    def flaky(config: Config, prompt: str):
        real_calls.append(1)
        if len(real_calls) == 1:
            return 1, "", "hermes blew up"
        return attempt(config, prompt)

    assert run_tick(config, run_attempt_fn=flaky, sleep_fn=lambda s: None) == REASON_SUCCESS
    assert len(real_calls) == 2


def test_tick_summary_saved(tmp_path: Path, tracker_factory) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, inner_max_fails=1, inner_sleep=0.0)
    config.campaign_dir = str(tracker_path.parent)
    run_tick(config, run_attempt_fn=_tracker_bumper(tracker_path), sleep_fn=lambda s: None)
    saved = (tmp_path / "state" / "tick-context.md").read_text(encoding="utf-8")
    assert "Attempts used this tick: 1" in saved
    assert "Tick outcome: success" in saved


def test_prompt_contains_previous_tick_context(tmp_path: Path, tracker_factory) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, inner_max_fails=1, inner_sleep=0.0)
    config.campaign_dir = str(tracker_path.parent)
    context_path = tmp_path / "state" / "tick-context.md"
    context_path.parent.mkdir(parents=True, exist_ok=True)
    context_path.write_text("Previous tick: applied to Acme", encoding="utf-8")
    prompts: list[str] = []

    def attempt(config: Config, prompt: str):
        prompts.append(prompt)
        return 0, "", ""

    run_tick(config, run_attempt_fn=attempt, sleep_fn=lambda s: None)
    assert "Previous tick: applied to Acme" in prompts[0]


def test_skip_list_reaches_prompt(tmp_path: Path, tracker_factory) -> None:
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, inner_max_fails=1, inner_sleep=0.0)
    config.campaign_dir = str(tracker_path.parent)
    config.skip_companies = {"antal"}
    prompts: list[str] = []

    def attempt(config: Config, prompt: str):
        prompts.append(prompt)
        return 0, "", ""

    run_tick(config, run_attempt_fn=attempt, sleep_fn=lambda s: None)
    assert "DIRECTOR SKIP LIST" in prompts[0]
    assert "antal" in prompts[0]
