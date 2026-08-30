"""Runner configuration: defaults < overrides file < environment."""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Optional

logger = logging.getLogger(__name__)

CAMPAIGN_DIR_DEFAULT = "/Users/mst/Downloads/job-search/job-apply"
PLAYWRIGHT_OUTPUT_DIR_DEFAULT = (
    "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output"
)
DEFAULT_OVERRIDES_PATH = "~/.campaign-agent/director-overrides.env"
DEFAULT_NOTE_PATH = "~/.campaign-agent/director-prompt-overrides.md"

_INT_FIELDS = {
    "INNER_MAX_FAILS": "inner_max_fails",
    "OUTER_BACKOFF": "outer_backoff",
    "OUTER_MAX_FAILS": "outer_max_fails",
    "RUN_BUDGET_SECONDS": "run_budget_seconds",
    "MAX_TURNS": "max_turns",
    "SUBPROCESS_TIMEOUT": "subprocess_timeout",
}
_FLOAT_FIELDS = {"INNER_SLEEP": "inner_sleep"}
_STR_FIELDS = {
    "HERMES_BIN": "hermes_bin",
    "HERMES_PROFILE": "hermes_profile",
    "CAMPAIGN_DIR": "campaign_dir",
    "LEGACY_TICK_CONTEXT_PATH": "legacy_tick_context_path",
    "TICK_CONTEXT_PATH": "tick_context_path",
}
_RECOGNIZED_KEYS = frozenset(_INT_FIELDS) | frozenset(_FLOAT_FIELDS) | frozenset(_STR_FIELDS)


def load_env_file(path: str | Path) -> dict[str, str]:
    """Parse KEY=VALUE lines; blanks and # comments skipped; missing file is empty."""
    values: dict[str, str] = {}
    try:
        lines = Path(path).expanduser().read_text(encoding="utf-8").splitlines()
    except OSError:
        return values
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


@dataclass
class Config:
    campaign_dir: str = CAMPAIGN_DIR_DEFAULT
    cv_path: str = ""
    cv_path_pl: str = ""
    playwright_output_dir: str = PLAYWRIGHT_OUTPUT_DIR_DEFAULT
    tick_context_path: str = ""
    hermes_bin: str = "hermes"
    hermes_profile: str = "jobhunter"
    run_budget_seconds: int = 1800
    max_turns: int = 200
    inner_max_fails: int = 5
    inner_sleep: float = 10.0
    outer_backoff: int = 60
    outer_max_fails: int = 12
    subprocess_timeout: int = 2400
    director_note_path: str = ""
    legacy_tick_context_path: str = (
        "/Users/mst/ZCodeProject/openclaw-job-search/campaign_agent/state/tick-context.md"
    )
    skip_companies: set[str] = field(default_factory=set)

    def __post_init__(self) -> None:
        if not self.cv_path:
            self.cv_path = str(Path(self.campaign_dir) / "cv" / "michael-staszewski-cv.pdf")
        if not self.cv_path_pl:
            self.cv_path_pl = str(
                Path(self.campaign_dir) / "cv" / "michael-staszewski-cv-pl.pdf"
            )
        if not self.tick_context_path:
            self.tick_context_path = str(
                Path(__file__).resolve().parents[2] / "state" / "tick-context.md"
            )
        if not self.director_note_path:
            # resolved at runtime so tests can repoint the default hermetically
            self.director_note_path = DEFAULT_NOTE_PATH

    @property
    def tracker_path(self) -> str:
        return str(Path(self.campaign_dir) / "tracker.json")

    @property
    def director_note(self) -> str:
        try:
            return (
                Path(self.director_note_path)
                .expanduser()
                .read_text(encoding="utf-8")
                .strip()
            )
        except OSError:
            return ""

    @classmethod
    def from_env(
        cls,
        env: Optional[Mapping[str, str]] = None,
        overrides_path: str = DEFAULT_OVERRIDES_PATH,
    ) -> "Config":
        source: Mapping[str, str] = os.environ if env is None else env
        merged = load_env_file(overrides_path)
        for key, value in source.items():
            if key in _RECOGNIZED_KEYS or key.startswith("PORTAL_SKIP_"):
                merged[key] = value
        kwargs: dict[str, Any] = {}
        for fields, cast in ((_INT_FIELDS, int), (_FLOAT_FIELDS, float)):
            for key, attr in fields.items():
                if key not in merged:
                    continue
                try:
                    kwargs[attr] = cast(merged[key])
                except ValueError:
                    logger.warning(
                        "ignoring invalid value for %s: %r", key, merged[key]
                    )
                    continue
        for key, attr in _STR_FIELDS.items():
            if key in merged:
                kwargs[attr] = merged[key]
        skip = {
            key[len("PORTAL_SKIP_") :].lower()
            for key, value in merged.items()
            if key.startswith("PORTAL_SKIP_") and value == "1"
        }
        if skip:
            kwargs["skip_companies"] = skip
        return cls(**kwargs)
