"""
Tracker: reads tracker.json for campaign state.
Provides submitted count, target, recent applications, and context summary.
Never crashes — returns defaults on any read error.
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

DEFAULT_TARGET = 1500


class Tracker:
    """Read-only tracker.json accessor. Safe on missing/malformed files."""

    def __init__(self, tracker_path: str) -> None:
        self.path = tracker_path
        self._data: dict[str, Any] = self._load()

    def _load(self) -> dict[str, Any]:
        """Load and parse tracker.json. Returns empty dict on any error."""
        try:
            return json.loads(Path(self.path).read_text(encoding="utf-8"))
        except FileNotFoundError:
            log.debug("tracker.json not found: %s", self.path)
            return {}
        except json.JSONDecodeError as e:
            log.warning("tracker.json malformed: %s", e)
            return {}
        except Exception as e:
            log.warning("tracker.json read error: %s", e)
            return {}

    def reload(self) -> None:
        """Re-read tracker.json from disk (use between ticks)."""
        self._data = self._load()

    def submitted(self) -> int:
        """Current submitted application count."""
        return self._data.get("stats", {}).get("submitted", 0)

    def target(self) -> int:
        """Target application count."""
        return self._data.get("target", self._data.get("targetApplications", DEFAULT_TARGET))

    def remaining(self) -> int:
        """Applications remaining to reach target."""
        return max(0, self.target() - self.submitted())

    def campaign_complete(self) -> bool:
        """True when submitted >= target."""
        return self.submitted() >= self.target()

    def recent_applications(self, n: int = 5) -> list[dict[str, Any]]:
        """Return last n applications, most recent first."""
        apps = self._data.get("applications", [])
        if not isinstance(apps, list):
            return []
        return list(reversed(apps[-n:])) if apps else []

    def context_summary(self) -> str:
        """Build a compact text summary for session rotation context."""
        lines = [
            f"Submitted: {self.submitted()}/{self.target()}",
            f"Remaining: {self.remaining()}",
        ]
        recent = self.recent_applications(5)
        if recent:
            lines.append("Recent applications:")
            for app in recent:
                company = app.get("company", "?")
                role = app.get("roleTitle", "?")
                status = app.get("status", "?")
                at = str(app.get("appliedAt", "?"))[:10]
                lines.append(f"  - {company}: {role} ({status}) at {at}")
        return "\n".join(lines)
