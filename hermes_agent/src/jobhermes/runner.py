"""Tick runner: hermes one-shot attempts plus the retry outer loop."""
from __future__ import annotations

import logging
import os
import signal
import subprocess
import time
from typing import Callable, Optional

from jobapps.tracker import Tracker

from .config import Config
from .prompt import build_tick_prompt
from .tick_context import TickContext, build_tick_summary, load_previous_summary

logger = logging.getLogger(__name__)

OUTPUT_TAIL_CHARS = 2000
# Non-retryable hermes start failures (127: binary missing, 126: cannot start).
NON_RETRYABLE_EXIT_CODES = frozenset({126, 127})

REASON_SUCCESS = "success"
REASON_CAMPAIGN_COMPLETE = "campaign_complete"
REASON_EXHAUSTED = "attempts_exhausted"
REASON_TRACKER_UNREADABLE = "tracker_unreadable"
REASON_PYTHON_AGENT_ACTIVE = "python_agent_active"

# The standalone Python agent (campaign_agent + its supervised launcher).
# Both agents share Chrome CDP and the tracker; never tick while it runs.
LEGACY_AGENT_PGREP_PATTERN = "campaign_agent\\.main|bin/job-search-agent"

AttemptFn = Callable[[Config, str], "tuple[int, str, str]"]
SleepFn = Callable[[float], None]
LogFn = Callable[[str], None]


def build_hermes_command(config: Config, prompt: str) -> list[str]:
    # Only flags supported by hermes v0.20.5+: --run-budget/--max-turns were
    # removed upstream (passing them exits 2 with a usage error). Turn limits
    # are set in the profile config; the wall-clock budget is enforced here
    # via subprocess_timeout + process-group kill.
    return [
        config.hermes_bin,
        "-p",
        config.hermes_profile,
        "-z",
        prompt,
        "--in",
        config.campaign_dir,
    ]


def run_attempt(config: Config, prompt: str) -> tuple[int, str, str]:
    """Run one hermes one-shot; return (exit_code, stdout_tail, stderr_tail).

    The hermes process starts a new session so that, on timeout, the whole
    process group (hermes plus any MCP servers it spawned) can be killed;
    otherwise timed-out attempts would leak playwright/rag children across an
    unattended run.
    """
    try:
        proc = subprocess.Popen(
            build_hermes_command(config, prompt),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
    except FileNotFoundError:
        return 127, "", "hermes binary not found: {}".format(config.hermes_bin)
    except OSError as exc:
        return 126, "", "hermes failed to start: {}".format(exc)
    try:
        stdout, stderr = proc.communicate(timeout=config.subprocess_timeout)
    except subprocess.TimeoutExpired:
        _kill_process_group(proc)
        stdout, stderr = proc.communicate()
        return (
            124,
            (stdout or "")[-OUTPUT_TAIL_CHARS:],
            "hermes subprocess timed out after {}s".format(config.subprocess_timeout),
        )
    return proc.returncode, (stdout or "")[-OUTPUT_TAIL_CHARS:], (stderr or "")[-OUTPUT_TAIL_CHARS:]


def _kill_process_group(proc: "subprocess.Popen[str]") -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError) as exc:
        logger.warning("could not kill hermes process group %s: %s", proc.pid, exc)


def format_session_context(previous: str) -> str:
    """Fence untrusted tracker-derived text so it cannot steer the prompt."""
    if not previous:
        return ""
    safe = previous.replace("</tracker_data>", "<\\/tracker_data>")
    return "Previous tick context:\n<tracker_data>\n{}\n</tracker_data>".format(safe)


def python_agent_active() -> bool:
    """True when the standalone campaign_agent process is running."""
    try:
        proc = subprocess.run(
            ["pgrep", "-f", LEGACY_AGENT_PGREP_PATTERN],
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as exc:
        logger.warning("could not check for standalone agent (%s); assuming absent", exc)
        return False
    return proc.returncode == 0


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
    if not tracker.reload():
        # No ledger means no anti-gaming oracle: launching hermes would risk
        # real applications that can never be recorded or deduped.
        log("tracker.json unreadable at tick start; not launching hermes")
        return REASON_TRACKER_UNREADABLE
    if tracker.campaign_complete():
        log("Campaign complete: {}/{}".format(tracker.submitted(), tracker.target()))
        return REASON_CAMPAIGN_COMPLETE
    if python_agent_active():
        # Cutover guard: the standalone Python agent owns ticking while it runs.
        log(
            "standalone campaign_agent is running; skipping this tick "
            "(no double agents on shared Chrome/tracker)"
        )
        return REASON_PYTHON_AGENT_ACTIVE

    context = TickContext(config.tick_context_path)
    prompt = build_tick_prompt(
        config,
        session_context=format_session_context(
            load_previous_summary(context.path, config.legacy_tick_context_path)
        ),
    )

    attempts = 0
    while attempts < config.inner_max_fails:
        attempts += 1
        before = tracker.submitted()
        exit_code, _stdout_tail, stderr_tail = run_attempt_fn(config, prompt)
        if not tracker.reload():
            log("tracker.json unreadable after attempt; ending tick without retry")
            return _finish_tick(
                config, tracker, attempts, REASON_TRACKER_UNREADABLE, context, log
            )
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
        if exit_code in NON_RETRYABLE_EXIT_CODES:
            log("exit code {} is not retryable; stopping tick".format(exit_code))
            return _finish_tick(
                config, tracker, attempts, "hermes_exit_{}".format(exit_code), context, log
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
    return format_session_context(
        load_previous_summary(
            TickContext(config.tick_context_path).path, config.legacy_tick_context_path
        )
    )


def main(argv: Optional[list[str]] = None) -> int:
    import argparse
    import fcntl
    import sys
    from pathlib import Path

    parser = argparse.ArgumentParser(
        prog="jobhermes", description="Hermes job-search campaign tick runner"
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--loop", action="store_true", help="keep ticking with outer backoff (default: one tick)"
    )
    mode.add_argument(
        "--once", action="store_true", help="run a single tick (same as the default)"
    )
    parser.add_argument("--dry-run", action="store_true", help="print the tick prompt and exit")
    parser.add_argument("--config", default=None, help="director overrides .env path")
    args = parser.parse_args(argv)

    config = (
        Config.from_env(overrides_path=args.config) if args.config else Config.from_env()
    )
    lock_dir = Path(config.tick_context_path).parent
    lock_file = None
    try:
        lock_dir.mkdir(parents=True, exist_ok=True)
        lock_file = (lock_dir / "jobhermes.lock").open("w")
    except OSError as exc:
        # Degraded mode: no lock, but do not crash before reporting config errors.
        logger.warning("could not create tick lock (%s); continuing without it", exc)
    if lock_file is not None:
        try:
            # Single instance across cron and supervised-launcher activation
            # paths: concurrent ticks share Chrome CDP and race the tracker.
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(
                "another jobhermes instance holds the tick lock; exiting",
                file=sys.stderr,
            )
            return 3
    if not Path(config.campaign_dir).is_dir():
        print("campaign dir does not exist: {}".format(config.campaign_dir), file=sys.stderr)
        return 2
    if args.dry_run:
        print(build_tick_prompt(config, session_context=build_session_context(config)), end="")
        return 0
    consecutive_failures = 0
    while True:
        outcome = run_tick(config)
        benign = (REASON_SUCCESS, REASON_CAMPAIGN_COMPLETE, REASON_PYTHON_AGENT_ACTIVE)
        if not args.loop:
            return 0 if outcome in benign else 1
        if outcome == REASON_CAMPAIGN_COMPLETE:
            return 0
        if outcome in (REASON_SUCCESS, REASON_PYTHON_AGENT_ACTIVE):
            # a guarded skip is not a failure; do not burn the failure budget
            consecutive_failures = 0
        else:
            consecutive_failures += 1
            if consecutive_failures >= config.outer_max_fails:
                logger.error(
                    "outer failure bound reached (%d consecutive failed ticks); stopping",
                    config.outer_max_fails,
                )
                print(
                    "stopping: {} consecutive failed ticks".format(config.outer_max_fails),
                    file=sys.stderr,
                )
                return 1
        time.sleep(config.outer_backoff)
