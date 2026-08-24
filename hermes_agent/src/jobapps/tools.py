"""jobapps plugin handlers. JSON in, JSON out, never raise."""
from __future__ import annotations

import json
import logging
import os
import subprocess
from pathlib import Path
from typing import Any

from .tracker import Tracker

logger = logging.getLogger(__name__)

UPDATE_TRACKER_TIMEOUT = 60
OUTPUT_TAIL_CHARS = 2000
DEFAULT_CAMPAIGN_DIR = "/Users/mst/Downloads/job-search/job-apply"


def _default_tracker_path() -> str:
    return os.environ.get("JOBSEARCH_TRACKER_PATH") or str(
        Path(DEFAULT_CAMPAIGN_DIR) / "tracker.json"
    )


def _default_campaign_dir() -> str:
    return os.environ.get("JOBSEARCH_CAMPAIGN_DIR") or DEFAULT_CAMPAIGN_DIR


def campaign_status(args: dict[str, Any], **kwargs: Any) -> str:
    """Read tracker.json and return campaign progress as JSON."""
    tracker = Tracker(args.get("tracker_path") or _default_tracker_path())
    payload = {
        "tracker_path": str(tracker.path),
        "submitted": tracker.submitted(),
        "target": tracker.target(),
        "remaining": tracker.remaining(),
        "campaign_complete": tracker.campaign_complete(),
        "queue_length": tracker.queue_length(),
        "recent_applications": tracker.recent_applications(5),
    }
    return json.dumps(payload)


def record_submission(args: dict[str, Any], **kwargs: Any) -> str:
    """Run update_tracker.py submitted with the given record; JSON result."""
    record = args.get("record")
    if not isinstance(record, dict):
        return json.dumps({"ok": False, "error": "record must be an object"})
    campaign_dir = args.get("campaign_dir") or _default_campaign_dir()
    command = [
        "python3",
        "update_tracker.py",
        "submitted",
        json.dumps(record, ensure_ascii=False),
    ]
    try:
        proc = subprocess.run(
            command,
            cwd=campaign_dir,
            capture_output=True,
            text=True,
            timeout=UPDATE_TRACKER_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        return json.dumps(
            {
                "ok": False,
                "error": "update_tracker.py timed out after {}s".format(
                    UPDATE_TRACKER_TIMEOUT
                ),
            }
        )
    except (FileNotFoundError, OSError) as exc:
        logger.warning("record_submission could not run update_tracker.py: %s", exc)
        return json.dumps(
            {"ok": False, "error": "could not run update_tracker.py: {}".format(exc)}
        )
    return json.dumps(
        {
            "ok": proc.returncode == 0,
            "exit": proc.returncode,
            "stdout": proc.stdout[-OUTPUT_TAIL_CHARS:],
            "stderr": proc.stderr[-OUTPUT_TAIL_CHARS:],
        }
    )
