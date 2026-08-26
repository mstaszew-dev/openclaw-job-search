"""OpenAI-style tool schemas for the jobapps plugin."""
from __future__ import annotations

from typing import Any

CAMPAIGN_STATUS: dict[str, Any] = {
    "name": "campaign_status",
    "description": (
        "Read job-search campaign progress from tracker.json: submitted/target, "
        "remaining, and recent applications."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "tracker_path": {
                "type": "string",
                "description": "Optional tracker.json path override (tests/admin).",
            },
        },
        "required": [],
    },
}

RECORD_SUBMISSION: dict[str, Any] = {
    "name": "record_submission",
    "description": (
        "Record one job application via the campaign's update_tracker.py "
        "(action=submitted). The ONLY sanctioned recording path; never edit "
        "tracker.json directly. Call immediately after browser confirmation."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "record": {
                "type": "object",
                "description": (
                    "Application record: source, sourceJobId, company, roleTitle, "
                    "and evidence of the portal confirmation."
                ),
            },
            "campaign_dir": {
                "type": "string",
                "description": "Optional campaign directory override (tests/admin).",
            },
        },
        "required": ["record"],
    },
}
