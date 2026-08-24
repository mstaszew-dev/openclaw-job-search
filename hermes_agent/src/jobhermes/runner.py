"""Tick runner: hermes one-shot attempts plus the retry outer loop."""
from __future__ import annotations

import logging
import subprocess
from typing import Callable

from .config import Config
from .prompt import build_tick_prompt

logger = logging.getLogger(__name__)

OUTPUT_TAIL_CHARS = 2000


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
