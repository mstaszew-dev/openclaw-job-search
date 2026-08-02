"""Tests for prompt builder — system prompt, user prompt, token budget notice."""
import pytest

from campaign_agent.prompt import build_system_prompt, build_user_prompt
from campaign_agent.config import Config


class TestSystemPrompt:
    def test_contains_campaign_rules(self):
        prompt = build_system_prompt(Config())
        assert "update_tracker.py" in prompt
        assert "autonomous" in prompt.lower()

    def test_contains_stack_info(self):
        prompt = build_system_prompt(Config())
        assert "Java" in prompt
        assert "Spring" in prompt

    def test_contains_forbidden_behaviors(self):
        prompt = build_system_prompt(Config())
        assert "Do NOT ask" in prompt
        assert "autonomous" in prompt.lower()


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

    def test_inlines_campaign_files(self):
        """AGENT_TICK.md and CONTEXT.md contents are inlined so the first
        LLM call doesn't need a tool call (free-tier models stall on tools)."""
        cfg = Config()
        prompt = build_user_prompt(cfg, session_context="", token_info="")
        assert "=== AGENT_TICK.md ===" in prompt
        assert "=== CONTEXT.md ===" in prompt
