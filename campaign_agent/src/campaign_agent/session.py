"""
SessionManager: tracks message history, estimates tokens, handles rotation.
On rotation: clears messages, generates new session ID, builds context from tracker.
"""
from __future__ import annotations

import json
import logging
import uuid
from pathlib import Path
from typing import Any

from campaign_agent.tracker import Tracker

log = logging.getLogger(__name__)


class TickContext:
    """Persists a summarized previous-tick context between ticks.

    The summary is loaded at the start of each tick and injected into the user
    prompt so the model knows what happened in prior ticks (submitted jobs,
    blockers) even though each attempt starts with fresh messages.
    """

    def __init__(self, path: str, max_chars: int = 8000) -> None:
        self.path = path
        self.max_chars = max_chars

    def save(self, summary: str) -> None:
        """Write the summary, truncating if needed. Creates parent dirs."""
        text = summary.strip()
        if len(text) > self.max_chars:
            text = text[: self.max_chars] + "\n...[truncated]"
        p = Path(self.path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def load(self) -> str:
        """Return the previous tick summary, or '' if none exists."""
        try:
            return Path(self.path).read_text(encoding="utf-8").strip()
        except (FileNotFoundError, OSError):
            return ""


def build_tick_summary(
    *,
    tracker: Tracker,
    attempts: int,
    reason: str,
) -> str:
    """Build a structured summary of the tick that just completed.

    Includes the last 3 submissions (not just 1) for richer cross-tick context.
    """
    lines: list[str] = []
    recent = tracker.recent_applications(3)
    if recent:
        lines.append("Recent submissions:")
        for app in recent:
            lines.append(
                f"  - {app.get('company', '?')} / "
                f"{app.get('roleTitle', '?')} ({app.get('appliedAt', '?')[:10]})"
            )
    else:
        lines.append("Last tick result: no submission recorded.")
    lines.append(f"Attempts used this tick: {attempts}")
    if reason:
        lines.append(f"Tick outcome: {reason[:300]}")
    return "\n".join(lines)


class SessionManager:
    """Manages agent session: message history, token tracking, rotation."""

    def __init__(
        self,
        session_dir: str,
        tracker_path: str,
        token_budget: int = 128000,
        rotation_threshold: float = 0.60,
    ) -> None:
        self.session_dir = session_dir
        self.tracker = Tracker(tracker_path)
        self.token_budget = token_budget
        self.rotation_threshold = rotation_threshold
        self.session_id: str = ""
        self.messages: list[dict[str, Any]] = []

    @property
    def rotation_token_threshold(self) -> int:
        """Token count at which proactive rotation triggers."""
        return int(self.token_budget * self.rotation_threshold)

    def estimate_tokens_from_messages(self, messages: list[dict[str, Any]]) -> int:
        """Estimate token count from message list (~4 chars per token)."""
        total_chars = 0
        for msg in messages:
            content = msg.get("content", "")
            if isinstance(content, str):
                total_chars += len(content)
            elif isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and "text" in part:
                        total_chars += len(str(part["text"]))
            # Add overhead per message (role, structure)
            total_chars += 10
        return max(0, total_chars // 4)

    def estimate_tokens(self) -> int:
        """Current token estimate from accumulated messages."""
        return self.estimate_tokens_from_messages(self.messages)

    def should_rotate(self, messages: list[dict[str, Any]] | None = None) -> bool:
        """Check if proactive rotation should occur based on token estimate."""
        msgs = messages if messages is not None else self.messages
        return self.estimate_tokens_from_messages(msgs) >= self.rotation_token_threshold

    def add_message(self, message: dict[str, Any]) -> None:
        """Append a message to the session history."""
        self.messages.append(message)

    def rotate(self) -> str:
        """Rotate to a fresh session. Returns rotation context for next session."""
        # Build context from tracker before clearing
        context = self.build_rotation_context()

        # Generate new session ID
        self.session_id = str(uuid.uuid4())
        self.messages = []

        log.info("Rotated to new session %s", self.session_id)
        return context

    def build_rotation_context(self) -> str:
        """Build context summary from tracker for passing to new session."""
        lines = ["Previous session summary:"]
        lines.append(self.tracker.context_summary())
        if self.session_id:
            lines.append(f"Previous session ID: {self.session_id}")
        return "\n".join(lines)
