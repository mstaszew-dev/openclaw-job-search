"""Tests for tick_status.sh — target fallback consistency with tracker.py."""
import json
import subprocess
from pathlib import Path

import pytest


@pytest.fixture
def tick_status_script():
    """Path to the tick_status.sh script."""
    return Path("/Users/mst/Documents/Job-Search/job-apply/tick_status.sh")


@pytest.fixture
def tracker_file(tmp_path):
    """Create a tracker.json file with configurable target."""
    def _create(target_applications=None, submitted=0):
        data = {
            "schemaVersion": "1.0",
            "stats": {"submitted": submitted},
            "applications": [],
        }
        if target_applications is not None:
            data["target"] = target_applications
        p = tmp_path / "tracker.json"
        p.write_text(json.dumps(data))
        return str(p)
    return _create


class TestTickStatusTargetFallback:
    """Verify tick_status.sh reads target from tracker.json, not hardcoded."""

    def test_shows_target_from_tracker(self, tick_status_script, tracker_file, tmp_path):
        """When tracker.json has targetApplications, tick_status.sh uses it."""
        tracker_path = tracker_file(target_applications=1500, submitted=1200)
        # Copy tick_status.sh to tmp_path and modify ROOT to use our tracker
        script_content = tick_status_script.read_text()
        # Replace ROOT derivation to point to tmp_path
        modified = script_content.replace(
            'ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"',
            f'ROOT="{tmp_path}"',
        )
        script = tmp_path / "tick_status.sh"
        script.write_text(modified)
        script.chmod(0o755)

        result = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            timeout=5,
        )
        assert result.returncode == 0
        assert "submitted : 1200/1500" in result.stdout

    def test_fallback_is_2000_not_1000(self, tick_status_script, tmp_path):
        """When tracker.json has NO target, fallback should be 2000 (2026-08-31 target raise)."""
        data = {
            "schemaVersion": "1.0",
            "stats": {"submitted": 100},
            "applications": [],
        }
        tracker = tmp_path / "tracker.json"
        tracker.write_text(json.dumps(data))

        script_content = tick_status_script.read_text()
        modified = script_content.replace(
            'ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"',
            f'ROOT="{tmp_path}"',
        )
        script = tmp_path / "tick_status.sh"
        script.write_text(modified)
        script.chmod(0o755)

        result = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            timeout=5,
        )
        assert result.returncode == 0
        # Should show 2000 as target, NOT 1000 (2026-08-31 target raise)
        assert "submitted : 100/2000" in result.stdout
        assert "submitted : 100/1000" not in result.stdout

    def test_campaign_complete_uses_correct_target(self, tick_status_script, tmp_path):
        """CAMPAIGN COMPLETE should only appear when submitted >= actual target."""
        data = {
            "schemaVersion": "1.0",
            "target": 1500,
            "stats": {"submitted": 1200},
            "applications": [],
        }
        tracker = tmp_path / "tracker.json"
        tracker.write_text(json.dumps(data))

        script_content = tick_status_script.read_text()
        modified = script_content.replace(
            'ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"',
            f'ROOT="{tmp_path}"',
        )
        script = tmp_path / "tick_status.sh"
        script.write_text(modified)
        script.chmod(0o755)

        result = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            timeout=5,
        )
        assert result.returncode == 0
        # 1200 < 1500, so should NOT be complete
        assert "CAMPAIGN COMPLETE" not in result.stdout

    def test_campaign_complete_when_reached(self, tick_status_script, tmp_path):
        """CAMPAIGN COMPLETE appears when submitted >= target."""
        data = {
            "schemaVersion": "1.0",
            "target": 1500,
            "stats": {"submitted": 1500},
            "applications": [],
        }
        tracker = tmp_path / "tracker.json"
        tracker.write_text(json.dumps(data))

        script_content = tick_status_script.read_text()
        modified = script_content.replace(
            'ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"',
            f'ROOT="{tmp_path}"',
        )
        script = tmp_path / "tick_status.sh"
        script.write_text(modified)
        script.chmod(0o755)

        result = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            timeout=5,
        )
        assert result.returncode == 0
        assert "CAMPAIGN COMPLETE" in result.stdout
