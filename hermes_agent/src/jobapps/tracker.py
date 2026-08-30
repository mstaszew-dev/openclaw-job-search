"""Crash-proof read access to the campaign tracker.json."""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

DEFAULT_TARGET = 2000


class Tracker:
    """Read-only accessor; any read failure degrades to empty state."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._data: dict[str, Any] = {}
        self.reload()

    def reload(self) -> bool:
        """Re-read tracker.json; return False when the file is unusable."""
        try:
            with self.path.open("r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError) as exc:
            logger.warning("tracker unreadable (%s): %s", self.path, exc)
            self._data = {}
            return False
        self._data = data if isinstance(data, dict) else {}
        return True

    @property
    def _stats(self) -> dict[str, Any]:
        stats = self._data.get("stats")
        return stats if isinstance(stats, dict) else {}

    def submitted(self) -> int:
        value = self._stats.get("submitted", 0)
        return value if isinstance(value, int) and not isinstance(value, bool) else 0

    def target(self) -> int:
        for key in ("target", "targetApplications"):
            value = self._data.get(key)
            if isinstance(value, int) and not isinstance(value, bool) and value > 0:
                return value
        return DEFAULT_TARGET

    def remaining(self) -> int:
        return max(self.target() - self.submitted(), 0)

    def campaign_complete(self) -> bool:
        return self.submitted() >= self.target()

    def recent_applications(self, n: int = 5) -> list[dict[str, Any]]:
        applications = self._data.get("applications")
        if not isinstance(applications, list):
            return []
        recent: list[dict[str, Any]] = []
        for record in reversed(applications):
            if isinstance(record, dict):
                recent.append(record)
                if len(recent) >= n:
                    break
        return recent

    def context_summary(self) -> str:
        lines = [
            "Submitted: {}/{}".format(self.submitted(), self.target()),
            "Remaining: {}".format(self.remaining()),
        ]
        for record in self.recent_applications(5):
            company = record.get("company", "?")
            role = record.get("roleTitle", "?")
            status = record.get("status", "?")
            applied = str(record.get("appliedAt", "?"))[:10]
            lines.append("  - {} / {} ({}, {})".format(company, role, status, applied))
        return "\n".join(lines)
