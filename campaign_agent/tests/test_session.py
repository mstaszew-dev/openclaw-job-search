"""Tests for SessionManager — token estimation, rotation, context passing."""
import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from campaign_agent.session import SessionManager
from campaign_agent.tracker import Tracker


@pytest.fixture
def session_dir(tmp_path):
    d = tmp_path / "sessions"
    d.mkdir()
    return str(d)


@pytest.fixture
def tracker_file(tmp_path):
    p = tmp_path / "tracker.json"
    p.write_text(json.dumps({
        "target": 1200,
        "stats": {"submitted": 1130},
        "applications": [
            {"company": "TestCo", "roleTitle": "Dev", "status": "submitted",
             "appliedAt": "2026-07-30T10:00:00Z"},
        ],
    }))
    return str(p)


class TestTokenEstimation:
    def test_estimate_tokens_from_messages(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file, token_budget=128000)
        messages = [{"role": "user", "content": "a" * 400}]  # ~100 tokens
        estimate = sm.estimate_tokens_from_messages(messages)
        assert estimate > 0
        # Rough: 400 chars / 4 = ~100 tokens, plus some overhead

    def test_estimate_tokens_empty_messages(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        assert sm.estimate_tokens_from_messages([]) == 0

    def test_token_budget(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file, token_budget=128000)
        assert sm.token_budget == 128000


class TestShouldRotate:
    def test_should_rotate_when_over_threshold(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file, token_budget=128000, rotation_threshold=0.6)
        # Create messages that exceed 60% of 128k = 76800 tokens
        big_content = "x" * 400000  # ~100k tokens
        messages = [{"role": "user", "content": big_content}]
        assert sm.should_rotate(messages)

    def test_should_not_rotate_when_under_threshold(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file, token_budget=128000, rotation_threshold=0.6)
        messages = [{"role": "user", "content": "small message"}]
        assert not sm.should_rotate(messages)

    def test_rotation_threshold_property(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file, token_budget=128000, rotation_threshold=0.6)
        assert sm.rotation_token_threshold == 76800  # 60% of 128000


class TestContextPassing:
    def test_build_rotation_context_includes_tracker_summary(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        ctx = sm.build_rotation_context()
        assert "1130" in ctx  # submitted count
        assert "1200" in ctx  # target

    def test_build_rotation_context_includes_session_id(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        sm.session_id = "test-uuid-1234"
        ctx = sm.build_rotation_context()
        assert "test-uuid-1234" in ctx

    def test_rotation_clears_messages(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        sm.messages = [{"role": "user", "content": "old stuff"}]
        sm.rotate()
        assert len(sm.messages) == 0  # fresh start
        assert sm.session_id != ""  # new session assigned


class TestSessionLifecycle:
    def test_initial_state(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        assert sm.session_id == ""
        assert sm.messages == []

    def test_new_session_id_on_rotate(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        old_id = sm.session_id
        sm.rotate()
        assert sm.session_id != old_id
        assert len(sm.session_id) > 0  # UUID-like

    def test_add_message(self, session_dir, tracker_file):
        sm = SessionManager(session_dir, tracker_file)
        sm.add_message({"role": "user", "content": "hello"})
        assert len(sm.messages) == 1
        assert sm.messages[0]["content"] == "hello"


class TestTickSummaryAndTokenEstimation:
    def test_summary_without_reason_omits_outcome_line(self, tmp_path):
        from campaign_agent.session import build_tick_summary

        p = tmp_path / "t.json"
        p.write_text(json.dumps({"applications": [], "stats": {"submitted": 0}}))
        s = build_tick_summary(tracker=Tracker(str(p)), attempts=2, reason="")
        assert "Attempts used this tick: 2" in s
        assert "Tick outcome" not in s

    def test_summary_with_reason_truncates_to_300(self, tmp_path):
        from campaign_agent.session import build_tick_summary

        p = tmp_path / "t.json"
        p.write_text(json.dumps({"applications": [], "stats": {"submitted": 0}}))
        s = build_tick_summary(
            tracker=Tracker(str(p)), attempts=1, reason="x" * 500,
        )
        assert "Tick outcome:" in s
        assert len(s) < 400

    def test_estimate_tokens_handles_list_content(self):
        sm = SessionManager("/tmp/s", "/tmp/t.json")
        msgs = [
            {"role": "user", "content": [
                {"type": "text", "text": "abc"},
                {"type": "text", "text": "def"},
            ]},
            {"role": "assistant", "content": "xyz"},
        ]
        # 6 chars (list parts) + 10 overhead + 3 chars + 10 overhead = 29
        assert sm.estimate_tokens_from_messages(msgs) == 29 // 4

    def test_estimate_tokens_uses_accumulated_messages(self):
        sm = SessionManager("/tmp/s", "/tmp/t.json")
        sm.messages = [{"role": "user", "content": "hello world"}]
        # 11 chars + 10 overhead = 21 -> 5 tokens
        assert sm.estimate_tokens() == 21 // 4
