"""Cross-tick summary persistence (port of campaign_agent TickContext)."""
from __future__ import annotations

import os
from pathlib import Path

from jobapps.tracker import Tracker


class TickContext:
    def __init__(self, path: str | Path, max_chars: int = 8000) -> None:
        self.path = Path(path)
        self.max_chars = max_chars

    def save(self, summary: str) -> None:
        """Atomically persist the summary (temp file + rename)."""
        if len(summary) > self.max_chars:
            summary = summary[: self.max_chars] + "\n...[truncated]"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_name(self.path.name + ".tmp")
        with tmp_path.open("w", encoding="utf-8") as fh:
            fh.write(summary)
            fh.flush()
            os.fsync(fh.fileno())
        tmp_path.replace(self.path)

    def load(self) -> str:
        try:
            return self.path.read_text(encoding="utf-8")
        except OSError:
            return ""


def build_tick_summary(tracker: Tracker, attempts: int, reason: str) -> str:
    lines: list[str] = ["Recent submissions:"]
    for record in tracker.recent_applications(3):
        company = record.get("company", "?")
        role = record.get("roleTitle", "?")
        applied = str(record.get("appliedAt", "?"))[:10]
        lines.append("  - {} / {} ({})".format(company, role, applied))
    lines.append("Attempts used this tick: {}".format(attempts))
    lines.append("Tick outcome: {}".format(reason[:300]))
    return "\n".join(lines)
