"""
Config: validated configuration for the campaign agent.
Loads from defaults, director-overrides.env file, and environment variables.
Env vars take precedence over file, file takes precedence over defaults.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _load_env_file(path: str) -> dict[str, str]:
    """Parse a simple KEY=VALUE env file into a dict."""
    result: dict[str, str] = {}
    try:
        for line in Path(path).read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            result[key.strip()] = value.strip()
    except FileNotFoundError:
        pass
    return result


@dataclass
class Config:
    """Campaign agent configuration. All fields have sensible defaults."""

    # msrouter (LLM gateway)
    msrouter_url: str = "http://127.0.0.1:8787/v1"
    msrouter_model: str = "mst/free"
    msrouter_api_key: str = "msrouter-local"

    # Campaign state
    tracker_path: str = "/Users/mst/Downloads/job-search/job-apply/tracker.json"
    events_path: str = "/Users/mst/Downloads/job-search/job-apply/events.jsonl"
    campaign_dir: str = "/Users/mst/Downloads/job-search/job-apply"
    workspace: str = "/Users/mst/ZCodeProject/openclaw-job-search"

    # Absolute paths the agent must know for file operations (CV uploads and
    # Playwright page snapshots live outside the campaign dir).
    cv_path: str = "/Users/mst/Downloads/job-search/job-apply/cv/michael-staszewski-cv.pdf"
    playwright_output_dir: str = "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output"

    # Chrome CDP
    cdp_url: str = "http://127.0.0.1:9222"

    # Token budget
    token_budget: int = 128000
    rotation_threshold: float = 0.60  # rotate at 60% of budget

    # Retry settings
    inner_max_fails: int = 200
    inner_sleep: float = 4.0
    outer_backoff: int = 60
    outer_max_ticks: int = 41600

    # Agent loop
    max_steps: int = 200
    timeout_seconds: int = 600

    # Session directory (OpenClaw sessions)
    session_dir: str = os.path.expanduser("~/.campaign-agent/sessions")

    # Summarized previous-tick context file
    tick_context_path: str = "/Users/mst/ZCodeProject/openclaw-job-search/campaign_agent/state/tick-context.md"

    # Director-controlled overrides (patch surface)
    director_prompt_overrides_path: str = os.path.expanduser("~/.campaign-agent/director-prompt-overrides.md")
    skip_companies: set[str] = field(default_factory=set)

    # Playwright MCP launch
    playwright_command: str = "/opt/homebrew/opt/node@24/bin/node"
    playwright_args: list[str] = field(default_factory=lambda: [
        "/Users/mst/.local/share/openclaw-tools/node_modules/@playwright/mcp/cli.js",
        "--cdp-endpoint", "http://127.0.0.1:9222",
        "--cdp-timeout", "120000",
        "--output-dir", "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output",
        "--output-mode", "file",
        "--save-session",
        "--codegen", "none",
    ])

    # RAG MCP launch
    rag_command: str = "/Users/mst/ZCodeProject/openclaw-job-search/rag/.venv/bin/python"
    rag_args: list[str] = field(default_factory=lambda: [
        "/Users/mst/ZCodeProject/openclaw-job-search/rag/rag_server.py",
    ])

    # Director overrides
    overrides_path: str = os.path.expanduser("~/.campaign-agent/director-overrides.env")

    @classmethod
    def from_env(cls) -> Config:
        """Load config from environment variables only (no file)."""
        cfg = cls()
        cfg._apply_dict(os.environ)
        return cfg

    @classmethod
    def from_overrides(cls, overrides_path: str) -> Config:
        """Load config from a director-overrides.env file, then env vars override."""
        cfg = cls()
        cfg.overrides_path = overrides_path
        file_vars = _load_env_file(overrides_path)
        cfg._apply_dict(file_vars)
        cfg._apply_dict(os.environ)  # env wins over file
        return cfg

    def _apply_dict(self, d: dict[str, str]) -> None:
        """Apply KEY=VALUE overrides to this config."""
        int_fields = {
            "INNER_MAX_FAILS": "inner_max_fails",
            "OUTER_BACKOFF": "outer_backoff",
            "OUTER_MAX_TICKS": "outer_max_ticks",
            "MAX_STEPS": "max_steps",
            "TIMEOUT_SECONDS": "timeout_seconds",
        }
        str_fields = {
            "MSROUTER_URL": "msrouter_url",
            "MSROUTER_MODEL": "msrouter_model",
            "MSROUTER_API_KEY": "msrouter_api_key",
            "CDP_URL": "cdp_url",
        }
        float_fields = {
            "INNER_SLEEP": "inner_sleep",
        }

        for key, attr in int_fields.items():
            if key in d and d[key]:
                setattr(self, attr, int(d[key]))

        for key, attr in str_fields.items():
            if key in d and d[key]:
                setattr(self, attr, d[key])

        for key, attr in float_fields.items():
            if key in d and d[key]:
                setattr(self, attr, float(d[key]))

        # PORTAL_SKIP_<Company>=1 -> skip_companies (lowercased)
        for key, value in d.items():
            if key.startswith("PORTAL_SKIP_") and value:
                company = key[len("PORTAL_SKIP_"):].strip().lower()
                if company:
                    self.skip_companies.add(company)

    @property
    def director_note(self) -> str:
        """Content of the director-prompt-overrides.md note (or '')."""
        try:
            return Path(self.director_prompt_overrides_path).read_text(
                encoding="utf-8").strip()
        except (FileNotFoundError, OSError):
            return ""

    @property
    def rotation_token_threshold(self) -> int:
        """Token count at which proactive rotation triggers."""
        return int(self.token_budget * self.rotation_threshold)
