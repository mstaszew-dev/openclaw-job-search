"""Tests for Tracker — reads tracker.json for submitted count, target, recent apps."""
import json
from pathlib import Path

import pytest

from campaign_agent.tracker import Tracker


@pytest.fixture
def sample_tracker_data():
    return {
        "schemaVersion": "1.0",
        "targetApplications": 1200,
        "stats": {
            "submitted": 1130,
            "skippedDuplicate": 200,
            "skippedSalary": 3,
            "skippedFilter": 167,
            "blockedManual": 73,
            "errors": 0,
        },
        "applications": [
            {"id": "job1", "company": "Acme", "roleTitle": "Senior Java Dev",
             "status": "submitted", "appliedAt": "2026-07-30T10:00:00Z"},
            {"id": "job2", "company": "Foo Inc", "roleTitle": "Backend Engineer",
             "status": "submitted", "appliedAt": "2026-07-30T12:00:00Z"},
            {"id": "job3", "company": "Bar Ltd", "roleTitle": "Full Stack Dev",
             "status": "attempted", "appliedAt": "2026-07-29T08:00:00Z"},
        ],
        "applyQueue": [{"id": "q1"}, {"id": "q2"}],
    }


@pytest.fixture
def tracker_file(tmp_path, sample_tracker_data):
    p = tmp_path / "tracker.json"
    p.write_text(json.dumps(sample_tracker_data))
    return str(p)


class TestTrackerReading:
    def test_submitted_count(self, tracker_file):
        t = Tracker(tracker_file)
        assert t.submitted() == 1130

    def test_target(self, tracker_file):
        t = Tracker(tracker_file)
        assert t.target() == 1200

    def test_remaining(self, tracker_file):
        t = Tracker(tracker_file)
        assert t.remaining() == 70  # 1200 - 1130

    def test_campaign_complete_false(self, tracker_file):
        t = Tracker(tracker_file)
        assert not t.campaign_complete()

    def test_campaign_complete_true(self, tracker_file):
        t = Tracker(tracker_file)
        t._data["stats"]["submitted"] = 1200
        assert t.campaign_complete()

    def test_queue_length(self, tracker_file):
        t = Tracker(tracker_file)
        assert t.queue_length() == 2


class TestTrackerRecentApplications:
    def test_recent_applications(self, tracker_file):
        t = Tracker(tracker_file)
        recent = t.recent_applications(2)
        assert len(recent) == 2
        # Last 2 apps reversed: job3 (Bar) then job2 (Foo)
        assert recent[0]["company"] == "Bar Ltd"
        assert recent[1]["company"] == "Foo Inc"

    def test_recent_applications_fewer_than_n(self, tracker_file):
        t = Tracker(tracker_file)
        recent = t.recent_applications(10)
        assert len(recent) == 3  # only 3 in the list

    def test_recent_applications_empty(self, tmp_path):
        p = tmp_path / "empty_tracker.json"
        p.write_text(json.dumps({"stats": {"submitted": 0}, "applications": []}))
        t = Tracker(str(p))
        assert t.recent_applications(5) == []


class TestTrackerErrorHandling:
    def test_missing_file(self, tmp_path):
        t = Tracker(str(tmp_path / "nonexistent.json"))
        assert t.submitted() == 0
        assert t.target() == 1200  # default
        assert t.remaining() == 1200

    def test_malformed_json(self, tmp_path):
        p = tmp_path / "bad.json"
        p.write_text("{not valid json")
        t = Tracker(str(p))
        assert t.submitted() == 0  # graceful fallback

    def test_missing_stats_key(self, tmp_path):
        p = tmp_path / "no_stats.json"
        p.write_text(json.dumps({"applications": []}))
        t = Tracker(str(p))
        assert t.submitted() == 0


class TestTrackerContextSummary:
    def test_context_summary_includes_submitted_count(self, tracker_file):
        t = Tracker(tracker_file)
        summary = t.context_summary()
        assert "1130" in summary
        assert "1200" in summary

    def test_context_summary_includes_recent_apps(self, tracker_file):
        t = Tracker(tracker_file)
        summary = t.context_summary()
        assert "Acme" in summary or "Foo" in summary

    def test_context_summary_includes_remaining(self, tracker_file):
        t = Tracker(tracker_file)
        summary = t.context_summary()
        assert "70" in summary  # remaining
