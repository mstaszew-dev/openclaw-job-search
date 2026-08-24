"""TickContext: cross-tick summary persistence and legacy cutover fallback."""
from __future__ import annotations

import json
from pathlib import Path

from jobapps.tracker import Tracker
from jobhermes.tick_context import TickContext, build_tick_summary, load_previous_summary


def test_load_missing_returns_empty(tmp_path: Path) -> None:
    assert TickContext(tmp_path / "none.md").load() == ""


def test_save_creates_parents_and_round_trips(tmp_path: Path) -> None:
    ctx = TickContext(tmp_path / "nested" / "dir" / "tick.md")
    ctx.save("summary text")
    assert ctx.load() == "summary text"


def test_save_truncates_long_summaries(tmp_path: Path) -> None:
    ctx = TickContext(tmp_path / "tick.md", max_chars=10)
    ctx.save("x" * 50)
    loaded = ctx.load()
    assert loaded.startswith("x" * 10)
    assert "...[truncated]" in loaded


def test_save_is_atomic_no_temp_left_behind(tmp_path: Path) -> None:
    ctx = TickContext(tmp_path / "tick.md")
    ctx.save("first")
    ctx.save("second")
    assert ctx.load() == "second"
    leftovers = [p.name for p in tmp_path.iterdir() if p.name != "tick.md"]
    assert leftovers == []


def test_build_tick_summary_lists_three_recent(tmp_path: Path) -> None:
    records = [
        {"company": "A", "roleTitle": "ra", "appliedAt": "2026-08-01T00:00:00Z"},
        {"company": "B", "roleTitle": "rb", "appliedAt": "2026-08-02T00:00:00Z"},
        {"company": "C", "roleTitle": "rc", "appliedAt": "2026-08-03T00:00:00Z"},
        {"company": "D", "roleTitle": "rd", "appliedAt": "2026-08-04T00:00:00Z"},
    ]
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"applications": records}), encoding="utf-8")
    summary = build_tick_summary(Tracker(path), attempts=2, reason="success")
    assert "- D / rd (2026-08-04)" in summary
    assert "- C / rc (2026-08-03)" in summary
    assert "- B / rb (2026-08-02)" in summary
    assert "- A / ra" not in summary
    assert "Attempts used this tick: 2" in summary
    assert "Tick outcome: success" in summary


def test_reason_is_clamped(tmp_path: Path) -> None:
    summary = build_tick_summary(Tracker(tmp_path / "none.json"), attempts=1, reason="r" * 500)
    outcome_line = next(
        line for line in summary.splitlines() if line.startswith("Tick outcome:")
    )
    assert len(outcome_line) <= len("Tick outcome: ") + 300


def test_prefers_hermes_state_when_present(tmp_path: Path) -> None:
    hermes_state = tmp_path / "state" / "tick-context.md"
    hermes_state.parent.mkdir(parents=True)
    hermes_state.write_text("hermes summary", encoding="utf-8")
    legacy = tmp_path / "legacy" / "tick-context.md"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("legacy summary", encoding="utf-8")
    assert load_previous_summary(hermes_state, legacy) == "hermes summary"


def test_falls_back_to_legacy_when_hermes_state_missing(tmp_path: Path) -> None:
    hermes_state = tmp_path / "state" / "tick-context.md"
    legacy = tmp_path / "legacy" / "tick-context.md"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("legacy summary", encoding="utf-8")
    assert load_previous_summary(hermes_state, legacy) == "legacy summary"


def test_empty_when_both_missing(tmp_path: Path) -> None:
    assert load_previous_summary(tmp_path / "a.md", tmp_path / "b.md") == ""


def test_empty_legacy_file_is_treated_as_missing(tmp_path: Path) -> None:
    hermes_state = tmp_path / "state" / "tick-context.md"
    legacy = tmp_path / "legacy" / "tick-context.md"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("", encoding="utf-8")
    assert load_previous_summary(hermes_state, legacy) == ""
