"""Tests for update_tracker.py — target fallback consistency with tracker.py."""
import json
import subprocess
import sys
from pathlib import Path

import pytest


@pytest.fixture
def update_tracker_script():
    """Path to the update_tracker.py script."""
    return Path("/Users/mst/Documents/Job-Search/job-apply/update_tracker.py")


@pytest.fixture
def campaign_dir(tmp_path):
    """Create a temporary campaign directory with tracker.json and required files."""
    def _create(target_applications=None, submitted=0):
        data = {
            "schemaVersion": "1.0",
            "stats": {"submitted": submitted},
            "applications": [],
            "applyQueue": [],
        }
        if target_applications is not None:
            data["targetApplications"] = target_applications
        tracker = tmp_path / "tracker.json"
        tracker.write_text(json.dumps(data))
        # Create events.jsonl
        events = tmp_path / "events.jsonl"
        events.write_text("")
        # Create submission_validator.py (needed by update_tracker.py)
        validator = tmp_path / "submission_validator.py"
        validator.write_text('''#!/usr/bin/env python3
def has_valid_submission_evidence(rec):
    return bool(rec.get("evidence"))
''')
        # Copy update_tracker.py to tmp_path so ROOT resolves correctly
        import shutil
        src = Path("/Users/mst/Documents/Job-Search/job-apply/update_tracker.py")
        dst = tmp_path / "update_tracker.py"
        shutil.copy2(src, dst)
        return str(tmp_path)
    return _create


class TestUpdateTrackerTargetFallback:
    """Verify update_tracker.py uses correct target fallback."""

    def test_shows_correct_target_when_present(self, campaign_dir):
        """When tracker.json has targetApplications, update_tracker.py uses it."""
        campaign = campaign_dir(target_applications=1500, submitted=1200)
        update_tracker = Path(campaign) / "update_tracker.py"
        record = json.dumps({
            "source": "upwork",
            "sourceJobId": "test123",
            "company": "TestCo",
            "roleTitle": "Developer",
            "evidence": {"type": "portal_confirmation", "url": "https://example.com"},
        })
        result = subprocess.run(
            [sys.executable, str(update_tracker), "submitted", record],
            capture_output=True,
            text=True,
            timeout=10,
            cwd=campaign,
        )
        assert result.returncode == 0
        assert "submitted 1201/1500" in result.stdout

    def test_fallback_is_1500_not_1000(self, campaign_dir):
        """When tracker.json has NO targetApplications, fallback should be 1500."""
        campaign = campaign_dir(submitted=100)
        update_tracker = Path(campaign) / "update_tracker.py"
        record = json.dumps({
            "source": "upwork",
            "sourceJobId": "test456",
            "company": "AnotherCo",
            "roleTitle": "Engineer",
            "evidence": {"type": "portal_confirmation", "url": "https://example.com"},
        })
        result = subprocess.run(
            [sys.executable, str(update_tracker), "submitted", record],
            capture_output=True,
            text=True,
            timeout=10,
            cwd=campaign,
        )
        assert result.returncode == 0
        # Should show 1500 as target, NOT 1000
        assert "submitted 101/1500" in result.stdout
        assert "submitted 101/1000" not in result.stdout

    def test_campaign_complete_message_uses_correct_target(self, campaign_dir):
        """CAMPAIGN COMPLETE message should use correct target."""
        campaign = campaign_dir(target_applications=1500, submitted=1499)
        update_tracker = Path(campaign) / "update_tracker.py"
        record = json.dumps({
            "source": "upwork",
            "sourceJobId": "test789",
            "company": "FinalCo",
            "roleTitle": "Lead",
            "evidence": {"type": "portal_confirmation", "url": "https://example.com"},
        })
        result = subprocess.run(
            [sys.executable, str(update_tracker), "submitted", record],
            capture_output=True,
            text=True,
            timeout=10,
            cwd=campaign,
        )
        assert result.returncode == 0
        assert "CAMPAIGN COMPLETE" in result.stdout
        assert "submitted 1500/1500" in result.stdout
