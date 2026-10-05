"""
Config: validated configuration for the campaign agent.
Loads from defaults, director-overrides.env file, and environment variables.
Env vars take precedence over file, file takes precedence over defaults.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Final

# Largest context we will ever hand the laptop tail, in tokens.
#
# The binding constraint is NOT ollama's window. On the travelmate, a Go
# gateway (llm-gateway.service, 127.0.0.1:11436, fronting ollama on 11434)
# runs an LFM2-350M summarizer that compacts oversized requests before they
# reach the model - verified 2026-10-02 in its journal:
#   GATEWAY compaction ready model=/opt/llm/models/LFM2-350M-Q4_K_M.gguf
#     ctx=19968 chunk=16384 yarn=false chat=true threads=4
#   compact=12117->1989tok passes=1 ... compact=25635->2361tok
# Requests of ~220KB have been served, and everything is reduced to roughly
# 700-2400 tokens before inference. So the agent may send a large context
# even though the model is SERVED at `PARAMETER num_ctx 8192` (ollama /api/ps
# context_length: 8192) - that window bounds what the gateway hands ollama,
# not what we may send.
#
# The real ceiling is the OTHER repo: msrouter's laptop provider hard-rejects
# anything above its `maxPromptTokens` with BAD_REQUEST (see
# ~/ZCodeProject/msrouter/src/providers/instances.ts, ~61440, and the guard in
# src/providers/local.ts). That is the GGUF's trained 64K window minus
# generation headroom. Staying under it matters twice over: above it the
# request is refused, and the refusal text matches no classify_failure
# context phrase, so it lands in the transient bucket and burns retries
# instead of rotating away. The two repos drift silently, hence the pairing
# is pinned by a test in tests/test_config.py.
LAPTOP_MAX_CONTEXT_TOKENS: Final[int] = 61_440


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
    campaign_dir: str = "/Users/mst/Downloads/job-search/job-apply"

    # Absolute paths the agent must know for file operations (CV uploads and
    # Playwright page snapshots live outside the campaign dir).
    cv_path: str = "/Users/mst/Downloads/job-search/job-apply/cv/michael-staszewski-cv.pdf"
    cv_path_pl: str = "/Users/mst/Downloads/job-search/job-apply/cv/michael-staszewski-cv-pl.pdf"
    playwright_output_dir: str = "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output"

    # Chrome CDP endpoint; wired into playwright_args in __post_init__ so a
    # CDP_URL override actually takes effect (2026-09-18 audit: it was read
    # but the arg was hardcoded).
    cdp_url: str = "http://127.0.0.1:9222"

    # Token budget
    token_budget: int = 128000
    rotation_threshold: float = 0.60  # rotate at 60% of budget
    # Per-request context cap (tokens, INCLUDING tool schemas). Policy
    # (2026-09-27): large contexts and slow responses are preferable to no
    # response at all when every remote provider is down.
    #
# Corrected 2026-10-02: the old 100_000 was justified by a comment
    # claiming the laptop served a 131072-token window. No such window ever
    # existed - ollama serves the model at num_ctx 8192 - but the number was
    # also not the real constraint, because the Go gateway in front of ollama
    # compacts oversized requests with LFM2-350M before inference. The cap is
    # the GGUF's trained window minus generation headroom,
    # LAPTOP_MAX_CONTEXT_TOKENS (61440): large enough that the agent keeps
    # real working history, and small enough that msrouter does not refuse
    # the request outright when the walk fails over to the tail.
    max_context_tokens: int = LAPTOP_MAX_CONTEXT_TOKENS

    # Retry settings
    inner_max_fails: int = 200
    inner_sleep: float = 4.0
    outer_backoff: int = 60
    outer_max_ticks: int = 41600

    # Agent loop
    max_steps: int = 250
    # Output token cap per LLM call: 1500 tokens is ~190s at the laptop
    # tail's ~8 tok/s decode, so a runaway generation cannot sit for many
    # minutes. Config-driven so the Director can retune without a code change.
    llm_max_output_tokens: int = 1500
    # SDK per-request timeout. Must cover the gateway's full slow-walk budget:
    # walk deadline 300s + LM Studio first try 300s + laptop tail 1800s
    # (LAPTOP_TIMEOUT_MS=1800000 in msrouter) = 2400s, so a 30-min laptop
    # response survives even when the remote phase burns its deadline first.
    timeout_seconds: int = 2400
    # Hard wall-clock deadline per LLM call (chat_async): a half-open socket
    # must never wedge the agent beyond this, regardless of SDK timeouts.
    llm_hard_timeout: int = 2520

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

    def __post_init__(self) -> None:
        """Wire cdp_url into the Playwright MCP launch args (kept in sync with
        any CDP_URL override applied after construction via _apply_dict) and
        check the laptop window invariant."""
        self._sync_cdp_arg()
        self._sync_playwright_output_dir()
        self._validate_context_window()

    def _validate_context_window(self) -> None:
        """Keep max_context_tokens inside the laptop model's usable window.

        The Go gateway compacts anything oversized, so this is NOT about
        ollama's num_ctx - it is about staying under msrouter's laptop
        `maxPromptTokens`, which refuses the request outright above it.

        A bad override CLAMPS rather than raises. This config is loaded at
        startup under a pgrep-based Director that restarts the worker every
        few minutes, so a ValueError here would become a permanent restart
        loop (the codebase's convention elsewhere is degrade, don't crash -
        see _probe_browser and the MCP connect failure path).
        """
        ceiling = LAPTOP_MAX_CONTEXT_TOKENS
        if self.max_context_tokens <= 0:
            logging.getLogger(__name__).error(
                "max_context_tokens=%d is not a usable context size; using %d",
                self.max_context_tokens, ceiling,
            )
            self.max_context_tokens = ceiling
        elif self.max_context_tokens > ceiling:
            logging.getLogger(__name__).error(
                "max_context_tokens=%d exceeds the laptop model's usable window "
                "(%d); msrouter would refuse the request when the walk fails "
                "over to the tail. Clamping.",
                self.max_context_tokens, ceiling,
            )
            self.max_context_tokens = ceiling

    def _sync_cdp_arg(self) -> None:
        for i, a in enumerate(self.playwright_args):
            if a == "--cdp-endpoint" and i + 1 < len(self.playwright_args):
                if self.playwright_args[i + 1] != self.cdp_url:
                    logging.getLogger(__name__).debug(
                        "cdp-endpoint rewired to %s", self.cdp_url
                    )
                self.playwright_args[i + 1] = self.cdp_url
                return

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
            "TOKEN_BUDGET": "token_budget",
            "TIMEOUT_SECONDS": "timeout_seconds",
            "LLM_HARD_TIMEOUT": "llm_hard_timeout",
            "LLM_MAX_OUTPUT_TOKENS": "llm_max_output_tokens",
            "MAX_CONTEXT_TOKENS": "max_context_tokens",
        }
        str_fields = {
            "MSROUTER_URL": "msrouter_url",
            "MSROUTER_MODEL": "msrouter_model",
            "MSROUTER_API_KEY": "msrouter_api_key",
            "CDP_URL": "cdp_url",
            # Path overrides (container relocation, 2026-10-04): every
            # hardcoded absolute path is settable so the app tree can live
            # anywhere (e.g. /home/agent/... in the k3s pod). HOME-derived
            # paths (session_dir, overrides_path) keep following $HOME.
            # Defaults are unchanged, so the Mac deployment is unaffected.
            "TRACKER_PATH": "tracker_path",
            "CAMPAIGN_DIR": "campaign_dir",
            "CV_PATH": "cv_path",
            "CV_PATH_PL": "cv_path_pl",
            "PLAYWRIGHT_OUTPUT_DIR": "playwright_output_dir",
            "TICK_CONTEXT_PATH": "tick_context_path",
            "PLAYWRIGHT_COMMAND": "playwright_command",
            "RAG_COMMAND": "rag_command",
        }
        float_fields = {
            "INNER_SLEEP": "inner_sleep",
            "ROTATION_THRESHOLD": "rotation_threshold",
        }

        for key, attr in int_fields.items():
            if d.get(key):
                setattr(self, attr, int(d[key]))

        for key, attr in str_fields.items():
            if d.get(key):
                setattr(self, attr, d[key])

        # Single-element launch-arg overrides (vectors stay otherwise intact;
        # guarded for empty vectors - index 0 is the entry script by contract).
        if d.get("PLAYWRIGHT_MCP_ENTRY") and self.playwright_args:
            self.playwright_args[0] = d["PLAYWRIGHT_MCP_ENTRY"]
        if d.get("RAG_SCRIPT") and self.rag_args:
            self.rag_args[0] = d["RAG_SCRIPT"]

        for key, attr in float_fields.items():
            if d.get(key):
                setattr(self, attr, float(d[key]))

        # PORTAL_SKIP_<Company>=1 -> skip_companies (lowercased)
        for key, value in d.items():
            if key.startswith("PORTAL_SKIP_") and value:
                company = key[len("PORTAL_SKIP_"):].strip().lower()
                if company:
                    self.skip_companies.add(company)
        self._sync_cdp_arg()
        self._sync_playwright_output_dir()
        # Overrides land after __post_init__, so re-check the ceiling:
        # MAX_CONTEXT_TOKENS is overridable and must stay within the laptop
        # model's trained window.
        self._validate_context_window()

    def _sync_playwright_output_dir(self) -> None:
        """Keep the --output-dir value inside playwright_args equal to
        playwright_output_dir. The arg was a separate literal, so overriding
        the field alone left a stale path in the MCP launch args."""
        for i, a in enumerate(self.playwright_args):
            if a == "--output-dir" and i + 1 < len(self.playwright_args):
                if self.playwright_args[i + 1] != self.playwright_output_dir:
                    logging.getLogger(__name__).debug(
                        "playwright --output-dir rewired to %s",
                        self.playwright_output_dir,
                    )
                self.playwright_args[i + 1] = self.playwright_output_dir
                return

    @property
    def director_note(self) -> str:
        """Content of the director-prompt-overrides.md note (or '')."""
        try:
            return Path(self.director_prompt_overrides_path).read_text(
                encoding="utf-8").strip()
        except (FileNotFoundError, OSError):
            return ""

