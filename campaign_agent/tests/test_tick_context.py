"""Tests for TickContext — summarized previous-tick context persistence."""
import pytest

from campaign_agent.session import TickContext


class TestTickContext:
    def test_save_and_load_roundtrip(self, tmp_path):
        path = str(tmp_path / "tick-context.md")
        tc = TickContext(path)
        tc.save("Submitted: CompanyX/Backend Engineer. Attempts: 3. Reason: success.")
        loaded = TickContext(path).load()
        assert "CompanyX" in loaded
        assert "Attempts: 3" in loaded

    def test_load_missing_file_returns_empty(self, tmp_path):
        tc = TickContext(str(tmp_path / "does-not-exist.md"))
        assert tc.load() == ""

    def test_load_missing_file_does_not_create(self, tmp_path):
        path = str(tmp_path / "nope.md")
        TickContext(path).load()
        assert not (tmp_path / "nope.md").exists()

    def test_save_creates_parent_dir(self, tmp_path):
        path = str(tmp_path / "state" / "nested" / "tick-context.md")
        TickContext(path).save("hello")
        assert (tmp_path / "state" / "nested" / "tick-context.md").exists()

    def test_overwrite_replaces_content(self, tmp_path):
        path = str(tmp_path / "tick-context.md")
        tc = TickContext(path)
        tc.save("first summary")
        tc.save("second summary")
        assert TickContext(path).load() == "second summary"

    def test_save_truncates_long_summary(self, tmp_path):
        path = str(tmp_path / "tick-context.md")
        TickContext(path, max_chars=100).save("x" * 500)
        loaded = TickContext(path).load()
        assert len(loaded) <= 115  # 100 + "\n...[truncated]" (15 chars)
        assert "truncated" in loaded


class TestBuildTickSummaryGaps:
    """Tests for the context-passing gaps."""

    def test_summary_includes_3_recent_apps(self):
        """Gap A: build_tick_summary should list the last 3 submissions,
        not just 1, for richer cross-tick context."""
        from unittest.mock import MagicMock
        from campaign_agent.session import build_tick_summary
        tracker = MagicMock()
        tracker.recent_applications.return_value = [
            {"company": "Acme", "roleTitle": "Backend Dev", "appliedAt": "2026-08-02T10:00:00+00:00"},
            {"company": "Globex", "roleTitle": "Java Dev", "appliedAt": "2026-08-01T10:00:00+00:00"},
            {"company": "Initech", "roleTitle": "Spring Dev", "appliedAt": "2026-07-31T10:00:00+00:00"},
        ]
        summary = build_tick_summary(tracker=tracker, attempts=2, reason="success")
        assert "Acme" in summary
        assert "Globex" in summary
        assert "Initech" in summary

    def test_summary_fewer_than_3_apps(self):
        """If tracker has fewer than 3 apps, include all available."""
        from unittest.mock import MagicMock
        from campaign_agent.session import build_tick_summary
        tracker = MagicMock()
        tracker.recent_applications.return_value = [
            {"company": "Acme", "roleTitle": "Dev", "appliedAt": "2026-08-02T10:00:00+00:00"},
        ]
        summary = build_tick_summary(tracker=tracker, attempts=1, reason="success")
        assert "Acme" in summary
        assert "no submission" not in summary.lower()
