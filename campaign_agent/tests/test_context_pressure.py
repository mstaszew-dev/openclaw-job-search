"""Regression tests for the discovery-spiral fix (2026-10-04).

THE BUG
-------
A tick burned 200 steps and produced zero applications, five hours running.
Root cause chain:

  * applyQueue is empty, so every tick rediscovers a candidate by browsing.
  * Discovery tool results are huge: the agent's own browser_evaluate calls
    return 20-60 items x ~200 chars, several per step.
  * Truncation kept only `keep_last` = 20 messages. At the tool-heavy loop's
    ~2 messages/step that is ~10 steps of history, so each truncation threw
    away ~45 steps of findings - including WHICH COMPANIES WERE ALREADY CHECKED.
  * The agent woke with no memory of its own progress, re-ran the same dedupe
    checks, rebuilt the same context, and hit the step cap again.

FIXES
-----
  1. Truncation is TOKEN-budgeted, not message-counted: keep as many recent
     messages as fit the allowance (with a floor), so findings survive.
  2. Tool results are capped head+tail before entering the context, so one
     giant listing dump cannot eat the window. The tail is preserved because
     shell exit codes live at the end and a submission is detected from
     "exit=0" in the result.
"""
import pytest

from campaign_agent.main import _cap_tool_result, _truncate_messages
from campaign_agent.session import estimate_tokens_from_messages


def _msg(role, content, content_len=None):
    if content_len is not None:
        content = "x" * content_len
    return {"role": role, "content": content}


def _long_history(n_pairs=60, content_len=800):
    """A tool-heavy history like the real one: assistant(tool_calls)+tool pairs."""
    msgs = [_msg("system", "you are the campaign agent"), _msg("user", "pick a job")]
    for i in range(n_pairs):
        msgs.append({"role": "assistant", "content": None,
                     "tool_calls": [{"id": f"c{i}", "type": "function",
                                     "function": {"name": "browser_evaluate",
                                                  "arguments": "{}"}}]})
        msgs.append({"role": "tool", "tool_call_id": f"c{i}",
                     "content": _msg("tool", None, content_len)["content"]})
    return msgs


class TestTruncationIsTokenBudgeted:
    def test_keeps_far_more_than_twenty_messages_when_budget_allows(self):
        """The core fix: keep_last=20 dropped ~45 steps of findings."""
        msgs = _long_history(n_pairs=60, content_len=100)
        budget = estimate_tokens_from_messages(msgs) // 2  # room to keep plenty
        kept = _truncate_messages(msgs, token_budget=budget)
        # Should retain far more than 20 messages when the budget allows it.
        assert len(kept) > 20, f"only kept {len(kept)} messages"

    def test_never_exceeds_the_token_budget(self):
        msgs = _long_history(n_pairs=80, content_len=2000)
        budget = 8000
        kept = _truncate_messages(msgs, token_budget=budget)
        assert estimate_tokens_from_messages(kept) <= budget

    def test_still_preserves_system_and_first_user(self):
        msgs = _long_history(n_pairs=40, content_len=2000)
        kept = _truncate_messages(msgs, token_budget=3000)
        assert kept[0]["role"] == "system"
        assert kept[1]["role"] == "user"

    def test_never_starts_the_suffix_with_a_tool_message(self):
        """A kept suffix starting with `tool` has a dropped parent -> HTTP 400."""
        msgs = _long_history(n_pairs=40, content_len=2000)
        for budget in (1000, 3000, 8000, 20000):
            kept = _truncate_messages(msgs, token_budget=budget)
            tail = [m for m in kept[2:] if m["role"] != "system"]
            assert not tail or tail[0]["role"] != "tool", f"budget={budget}"

    def test_explicit_keep_last_is_still_honoured_as_a_minimum(self):
        msgs = _long_history(n_pairs=40, content_len=500)
        kept = _truncate_messages(msgs, token_budget=100000, keep_last=5)
        # Under budget: everything preserved, keep_last is a floor not a cap.
        assert len(kept) == len(msgs)


class TestToolResultCap:
    def test_short_result_is_untouched(self):
        assert _cap_tool_result("applied ok", limit=1000) == "applied ok"

    def test_long_result_is_capped(self):
        big = "a" * 50_000
        out = _cap_tool_result(big, limit=1000)
        assert len(out) < len(big)
        assert "truncated" in out.lower()

    def test_cap_preserves_the_tail_where_shell_exit_codes_live(self):
        """A submission is detected via 'exit=0' in the result; the tail must survive."""
        result = "x" * 40_000 + "\nexit=0\n"
        out = _cap_tool_result(result, limit=1000)
        assert "exit=0" in out

    def test_cap_preserves_the_head(self):
        out = _cap_tool_result("HEADMARKER" + "y" * 40_000, limit=1000)
        assert "HEADMARKER" in out

    def test_cap_never_grows_the_result(self):
        for n in (0, 1, 999, 1000, 1001, 100_000):
            out = _cap_tool_result("z" * n, limit=1000)
            assert len(out) <= max(n, 1000) + 200


class TestEndToEndPressure:
    def test_a_discovery_heavy_tick_keeps_its_findings(self):
        """A realistic history must not lose company checks to truncation."""
        msgs = _long_history(n_pairs=120, content_len=1500)
        # A budget the real policy would hand this loop.
        kept = _truncate_messages(msgs, token_budget=12000)
        assert len(kept) >= 40, "findings from the last ~20 steps must survive"
