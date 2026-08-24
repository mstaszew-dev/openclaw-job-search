"""Tick runner: hermes one-shot attempts plus the retry outer loop."""
from __future__ import annotations

import logging
import subprocess
import time
from typing import Callable, Optional

from jobapps.tracker import Tracker

from .config import Config
from .prompt import build_tick_prompt
from .tick_context import TickContext, build_tick_summary

logger = logging.getLogger(__name__)

OUTPUT_TAIL_CHARS = 2000

REASON_SUCCESS = "success"
REASON_CAMPAIGN_COMPLETE = "campaign_complete"
REASON_EXHAUSTED = "attempts_exhausted"

AttemptFn = Callable[[Config, str], "tuple[int, str, str]"]
SleepFn = Callable[[float], None]
LogFn = Callable[[str], None]


def build_hermes_command(config: Config, prompt: str) -> list[str]:
    return [
        config.hermes_bin,
        "-p",
        config.hermes_profile,
        "-z",
        prompt,
        "--in",
        config.campaign_dir,
        "--run-budget",
        str(config.run_budget_seconds),
        "--max-turns",
        str(config.max_turns),
    ]


def run_attempt(config: Config, prompt: str) -> tuple[int, str, str]:
    """Run one hermes one-shot; return (exit_code, stdout_tail, stderr_tail)."""
    try:
        proc = subprocess.run(
            build_hermes_command(config, prompt),
            capture_output=True,
            text=True,
            timeout=config.subprocess_timeout,
        )
        return (
            proc.returncode,
            proc.stdout[-OUTPUT_TAIL_CHARS:],
            proc.stderr[-OUTPUT_TAIL_CHARS:],
        )
    except subprocess.TimeoutExpired:
        return (
            124,
            "",
            "hermes subprocess timed out after {}s".format(config.subprocess_timeout),
        )
    except FileNotFoundError:
        return 127, "", "hermes binary not found: {}".format(config.hermes_bin)
    except OSError as exc:
        return 126, "", "hermes failed to start: {}".format(exc)


def run_tick(
    config: Config,
    run_attempt_fn: Optional[AttemptFn] = None,
    sleep_fn: Optional[SleepFn] = None,
    log: Optional[LogFn] = None,
) -> str:
    """Run one tick: fresh hermes attempts until tracker.submitted increases.

    Anti-gaming rule (ported from campaign_agent.main): an attempt only counts
    when the campaign tracker actually advanced. Plain "done" text without a
    recorded submission is a failed attempt and triggers a retry.
    """
    run_attempt_fn = run_attempt_fn or run_attempt
    sleep_fn = sleep_fn or time.sleep
    log = log or (lambda message: print(message, flush=True))

    tracker = Tracker(config.tracker_path)
    if tracker.campaign_complete():
        log("Campaign complete: {}/{}".format(tracker.submitted(), tracker.target()))
        return REASON_CAMPAIGN_COMPLETE

    context = TickContext(config.tick_context_path)
    previous = context.load()
    session_context = "Previous tick context:\n{}".format(previous) if previous else ""
    prompt = build_tick_prompt(config, session_context=session_context)

    attempts = 0
    while attempts < config.inner_max_fails:
        attempts += 1
        before = tracker.submitted()
        exit_code, _stdout_tail, stderr_tail = run_attempt_fn(config, prompt)
        tracker.reload()
        if tracker.submitted() > before:
            log(
                "Attempt {}: submission recorded (submitted {} -> {})".format(
                    attempts, before, tracker.submitted()
                )
            )
            return _finish_tick(config, tracker, attempts, REASON_SUCCESS, context, log)
        reason = "no_submission" if exit_code == 0 else "hermes_exit_{}".format(exit_code)
        log(
            "Attempt {} failed ({}); stderr tail: {!r}".format(
                attempts, reason, stderr_tail[-500:]
            )
        )
        if attempts < config.inner_max_fails:
            sleep_fn(config.inner_sleep)
    return _finish_tick(config, tracker, attempts, REASON_EXHAUSTED, context, log)


def _finish_tick(
    config: Config,
    tracker: Tracker,
    attempts: int,
    outcome: str,
    context: TickContext,
    log: LogFn,
) -> str:
    tracker.reload()
    try:
        context.save(build_tick_summary(tracker=tracker, attempts=attempts, reason=outcome))
    except OSError as exc:
        log("Could not save tick context: {}".format(exc))
    return outcome


def build_session_context(config: Config) -> str:
    context = TickContext(config.tick_context_path)
    previous = context.load()
    return "Previous tick context:\n{}".format(previous) if previous else ""


def main(argv: Optional[list[str]] = None) -> int:
    import argparse
    import sys
    from pathlib import Path

    parser = argparse.ArgumentParser(
        prog="jobhermes", description="Hermes job-search campaign tick runner"
    )
    parser.add_argument(
        "--loop", action="store_true", help="keep ticking with outer backoff (default: one tick)"
    )
    parser.add_argument("--dry-run", action="store_true", help="print the tick prompt and exit")
    parser.add_argument("--config", default=None, help="director overrides .env path")
    args = parser.parse_args(argv)

    config = (
        Config.from_env(overrides_path=args.config) if args.config else Config.from_env()
    )
    if not Path(config.campaign_dir).is_dir():
        print("campaign dir does not exist: {}".format(config.campaign_dir), file=sys.stderr)
        return 2
    if args.dry_run:
        print(build_tick_prompt(config, session_context=build_session_context(config)), end="")
        return 0
    while True:
        outcome = run_tick(config)
        if not args.loop:
            return 0 if outcome in (REASON_SUCCESS, REASON_CAMPAIGN_COMPLETE) else 1
        if outcome == REASON_CAMPAIGN_COMPLETE:
            return 0
        time.sleep(config.outer_backoff)
