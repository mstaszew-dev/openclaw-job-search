"""Tests for Config dataclass — default values, env overrides, file loading."""
import os
from pathlib import Path

import pytest

from campaign_agent.config import Config


class TestConfigDefaults:
    """Config with no env vars or files should have sensible defaults."""

    def test_default_msrouter_url(self):
        cfg = Config()
        assert cfg.msrouter_url == "http://127.0.0.1:8787/v1"

    def test_default_msrouter_model(self):
        cfg = Config()
        assert cfg.msrouter_model == "mst/free"

    def test_default_msrouter_api_key(self):
        cfg = Config()
        assert cfg.msrouter_api_key == "msrouter-local"

    def test_default_tracker_path(self):
        cfg = Config()
        assert cfg.tracker_path == "/Users/mst/Downloads/job-search/job-apply/tracker.json"

    def test_default_cdp_url(self):
        cfg = Config()
        assert cfg.cdp_url == "http://127.0.0.1:9222"

    def test_default_token_budget(self):
        cfg = Config()
        assert cfg.token_budget == 128000

    def test_default_rotation_threshold(self):
        cfg = Config()
        assert cfg.rotation_threshold == 0.60  # 60% of budget

    def test_default_inner_max_fails(self):
        cfg = Config()
        assert cfg.inner_max_fails == 200

    def test_default_outer_backoff(self):
        cfg = Config()
        assert cfg.outer_backoff == 60

    def test_default_max_steps(self):
        cfg = Config()
        assert cfg.max_steps == 200


class TestConfigFromEnv:
    """Config should pick up environment variable overrides."""

    def test_env_override_msrouter_url(self, monkeypatch):
        monkeypatch.setenv("MSROUTER_URL", "http://localhost:9999/v1")
        cfg = Config.from_env()
        assert cfg.msrouter_url == "http://localhost:9999/v1"

    def test_env_override_model(self, monkeypatch):
        monkeypatch.setenv("MSROUTER_MODEL", "nemotron-3-ultra-free")
        cfg = Config.from_env()
        assert cfg.msrouter_model == "nemotron-3-ultra-free"

    def test_env_override_inner_max_fails(self, monkeypatch):
        monkeypatch.setenv("INNER_MAX_FAILS", "50")
        cfg = Config.from_env()
        assert cfg.inner_max_fails == 50

    def test_env_override_outer_backoff(self, monkeypatch):
        monkeypatch.setenv("OUTER_BACKOFF", "120")
        cfg = Config.from_env()
        assert cfg.outer_backoff == 120


class TestConfigFromOverridesFile:
    """Config should load KEY=VALUE from director-overrides.env."""

    def test_loads_overrides_file(self, tmp_path):
        overrides = tmp_path / "overrides.env"
        overrides.write_text("INNER_MAX_FAILS=10\nOUTER_BACKOFF=30\n")
        cfg = Config.from_overrides(str(overrides))
        assert cfg.inner_max_fails == 10
        assert cfg.outer_backoff == 30

    def test_missing_overrides_file_uses_defaults(self, tmp_path):
        cfg = Config.from_overrides(str(tmp_path / "nonexistent.env"))
        assert cfg.inner_max_fails == 200  # default

    def test_overrides_env_takes_precedence(self, tmp_path, monkeypatch):
        overrides = tmp_path / "overrides.env"
        overrides.write_text("INNER_MAX_FAILS=10\n")
        monkeypatch.setenv("INNER_MAX_FAILS", "5")
        cfg = Config.from_overrides(str(overrides))
        assert cfg.inner_max_fails == 5  # env wins over file


class TestConfigPlaywrightArgs:
    """Playwright MCP launch arguments."""

    def test_default_playwright_command(self):
        cfg = Config()
        assert "node" in cfg.playwright_command or "node" in cfg.playwright_args[0]

    def test_default_playwright_cdp_endpoint(self):
        cfg = Config()
        joined = " ".join(cfg.playwright_args)
        assert "http://127.0.0.1:9222" in joined


class TestDirectorOverrides:
    def test_parses_portal_skip_companies(self, tmp_path):
        env = tmp_path / "overrides.env"
        env.write_text("PORTAL_SKIP_RYBTECH=1\nPORTAL_SKIP_MINDBOX=1\nPORTAL_SKIP_ANTAL=1\n")
        cfg = Config.from_overrides(str(env))
        assert "rybtech" in cfg.skip_companies
        assert "mindbox" in cfg.skip_companies
        assert "antal" in cfg.skip_companies
        assert len(cfg.skip_companies) == 3

    def test_no_skip_companies_when_unset(self, tmp_path):
        env = tmp_path / "overrides.env"
        env.write_text("MSROUTER_MODEL=mst/free\n")
        cfg = Config.from_overrides(str(env))
        assert cfg.skip_companies == set()

    def test_skip_companies_normalized_lowercase(self, tmp_path):
        env = tmp_path / "overrides.env"
        env.write_text("PORTAL_SKIP_AcmeCorp=1\n")
        cfg = Config.from_overrides(str(env))
        assert "acmecorp" in cfg.skip_companies

    def test_director_note_loaded_from_prompt_overrides(self, tmp_path):
        prompt_md = tmp_path / "director-prompt-overrides.md"
        prompt_md.write_text("STOP applying for excluded roles.\n")
        cfg = Config()
        cfg.director_prompt_overrides_path = str(prompt_md)
        assert "excluded roles" in cfg.director_note


class TestConfigFileEdgeCases:
    def test_env_file_skips_lines_without_equals(self, tmp_path):
        from campaign_agent.config import _load_env_file

        env = tmp_path / "e.env"
        env.write_text(
            "KEY1=val1\n"
            "no-equals-here\n"
            "# comment line\n"
            "\n"
            "KEY2=two=parts\n"
        )
        d = _load_env_file(str(env))
        assert d == {"KEY1": "val1", "KEY2": "two=parts"}

    def test_director_note_missing_file_returns_empty(self, tmp_path):
        cfg = Config()
        cfg.director_prompt_overrides_path = str(tmp_path / "missing.md")
        assert cfg.director_note == ""

    def test_director_note_strips_whitespace(self, tmp_path):
        note = tmp_path / "note.md"
        note.write_text("  padded content  \n")
        cfg = Config()
        cfg.director_prompt_overrides_path = str(note)
        assert cfg.director_note == "padded content"

    def test_env_override_float_field(self, monkeypatch):
        """INNER_SLEEP is a float field; from_env must coerce it (not int())."""
        monkeypatch.setenv("INNER_SLEEP", "2.5")
        cfg = Config.from_env()
        assert cfg.inner_sleep == 2.5

    def test_rotation_token_threshold(self):
        cfg = Config()
        cfg.token_budget = 100000
        cfg.rotation_threshold = 0.6
        assert cfg.rotation_token_threshold == 60000
