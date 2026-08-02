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
