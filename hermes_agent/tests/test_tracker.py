"""Tracker: crash-proof reads of campaign tracker.json."""
import json
from pathlib import Path

from jobapps.tracker import DEFAULT_TARGET, Tracker


def test_missing_file_degrades_to_empty_state(tmp_path: Path) -> None:
    tracker = Tracker(tmp_path / "nope.json")
    assert tracker.submitted() == 0
    assert tracker.target() == DEFAULT_TARGET
    assert tracker.remaining() == DEFAULT_TARGET
    assert tracker.campaign_complete() is False
    assert tracker.queue_length() == 0
    assert tracker.recent_applications() == []


def test_invalid_json_degrades_to_empty_state(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text("{not json", encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.submitted() == 0


def test_non_dict_root_degrades_to_empty_state(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text("[]", encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.submitted() == 0


def test_non_int_submitted_is_zero(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": "many"}}), encoding="utf-8")
    assert Tracker(path).submitted() == 0


def test_counts_and_completion(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": 7}, "target": 10}), encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.submitted() == 7
    assert tracker.target() == 10
    assert tracker.remaining() == 3
    assert tracker.campaign_complete() is False


def test_target_fallback_chain(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": 1}, "targetApplications": 900}), encoding="utf-8")
    assert Tracker(path).target() == 900
    path.write_text(json.dumps({"stats": {"submitted": 1}}), encoding="utf-8")
    assert Tracker(path).target() == DEFAULT_TARGET
    path.write_text(json.dumps({"stats": {"submitted": 1}, "target": 0}), encoding="utf-8")
    assert Tracker(path).target() == DEFAULT_TARGET


def test_queue_length_ignores_non_list(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"applyQueue": "nope"}), encoding="utf-8")
    assert Tracker(path).queue_length() == 0


def test_recent_applications_most_recent_first(tmp_path: Path) -> None:
    records = [
        {"company": f"c{i}", "roleTitle": f"r{i}", "appliedAt": f"2026-08-0{i}"}
        for i in range(1, 6)
    ]
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"applications": records}), encoding="utf-8")
    tracker = Tracker(path)
    recent = tracker.recent_applications(3)
    assert [r["company"] for r in recent] == ["c5", "c4", "c3"]


def test_reload_picks_up_external_change(tmp_path: Path) -> None:
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": 1}, "target": 2}), encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.campaign_complete() is False
    path.write_text(json.dumps({"stats": {"submitted": 2}, "target": 2}), encoding="utf-8")
    tracker.reload()
    assert tracker.campaign_complete() is True


def test_context_summary_format(tmp_path: Path) -> None:
    records = [
        {
            "company": "Acme",
            "roleTitle": "Backend",
            "status": "submitted",
            "appliedAt": "2026-08-20T10:00:00Z",
        }
    ]
    path = tmp_path / "tracker.json"
    path.write_text(
        json.dumps({"stats": {"submitted": 3}, "target": 1500, "applications": records}),
        encoding="utf-8",
    )
    summary = Tracker(path).context_summary()
    assert "Submitted: 3/1500" in summary
    assert "Remaining: 1497" in summary
    assert "- Acme / Backend (submitted, 2026-08-20)" in summary
