"""Tests for prompt builder — system prompt, user prompt, token budget notice."""
import pytest

from campaign_agent.prompt import build_system_prompt, build_user_prompt
from campaign_agent.config import Config


class TestSystemPrompt:
    def test_contains_campaign_rules(self):
        prompt = build_system_prompt(Config())
        assert "autonomous" in prompt.lower()
        assert "tool call" in prompt.lower()

    def test_contains_stack_info(self):
        # Rules live in the USER prompt now (system prompt is minimal to avoid
        # free-tier tool-calling timeouts)
        prompt = build_user_prompt(Config(), session_context="", token_info="")
        assert "Java" in prompt
        assert "Spring" in prompt

    def test_contains_forbidden_behaviors(self):
        prompt = build_user_prompt(Config(), session_context="", token_info="")
        assert "Never ask permission" in prompt

    def test_system_prompt_stays_minimal(self):
        """System prompt must stay well under 1 KB: free-tier models time out
        on tool-calling requests when the system prompt is large."""
        prompt = build_system_prompt(Config())
        assert len(prompt) < 500


class TestUserPrompt:
    def test_contains_apply_instruction(self):
        prompt = build_user_prompt(Config(), session_context="", token_info="")
        assert "apply" in prompt.lower() or "Apply" in prompt

    def test_includes_session_context(self):
        ctx = "Previous session: 1130/1200 submitted"
        prompt = build_user_prompt(Config(), session_context=ctx, token_info="")
        assert "1130/1200" in prompt

    def test_includes_token_info(self):
        info = "Context: ~45k tokens (35% budget)"
        prompt = build_user_prompt(Config(), session_context="", token_info=info)
        assert "35%" in prompt

    def test_includes_absolute_campaign_dir(self):
        cfg = Config()
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert cfg.campaign_dir in prompt

    def test_includes_cv_path(self):
        """The model must know the exact absolute CV path for uploads (it is a
        regular file in the campaign cv/ dir, not a symlink)."""
        cfg = Config()
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert cfg.cv_path in prompt

    def test_includes_playwright_output_dir(self):
        """The model must know playwright page snapshots live under the
        absolute output dir, NOT relative to the campaign dir."""
        cfg = Config()
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert cfg.playwright_output_dir in prompt

    def test_system_prompt_is_compact(self):
        """System prompt must stay small (< 1.5 KB): free-tier models stall on
        tool-calling requests with large prompts."""
        prompt = build_system_prompt(Config())
        assert len(prompt) < 1500


class TestDirectorOverridesInPrompt:
    def test_skip_companies_included_in_user_prompt(self):
        cfg = Config()
        cfg.skip_companies = {"rybtech", "mindbox"}
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert "rybtech" in prompt.lower()
        assert "mindbox" in prompt.lower()

    def test_director_note_included_in_user_prompt(self, tmp_path):
        note = tmp_path / "director-prompt-overrides.md"
        note.write_text("STOP applying for excluded seniority roles.")
        cfg = Config()
        cfg.director_prompt_overrides_path = str(note)
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert "excluded seniority" in prompt

    def test_no_director_sections_when_empty(self):
        cfg = Config()
        cfg.skip_companies = set()
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert "SKIP COMPANIES" not in prompt.upper() or "director note" not in prompt.lower()
