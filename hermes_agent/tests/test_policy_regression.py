"""Policy pins: IL-only, all seniority, no EU/PL portals, no salary floor.

These tests deliberately hard-code the campaign policy so accidental edits to
the prompt or skill fail CI.
"""
from __future__ import annotations

from pathlib import Path

from jobhermes.config import Config
from jobhermes.prompt import build_tick_prompt

SKILL_PATH = Path(__file__).resolve().parents[1] / "skills" / "job-search-tick" / "SKILL.md"

REQUIRED_MARKERS = (
    "IL only",
    "remote/hybrid/onsite",
    "ALL levels accepted (junior through senior)",
    "team-lead/manager/architect/director/head/VP",
)

FORBIDDEN_MARKERS = (
    "PLN",
    "15k",
    "RemotifyEurope",
    "EuroRemote",
    "4DayWeek",
    "We Work Remotely",
    "prag",
    "theprotocol",
    "nofluffjobs",
)


def test_prompt_policy_pins() -> None:
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for marker in REQUIRED_MARKERS:
        assert marker in prompt, marker
    for marker in FORBIDDEN_MARKERS:
        assert marker not in prompt, marker


def test_skill_policy_pins() -> None:
    skill = SKILL_PATH.read_text(encoding="utf-8").lower()
    for marker in REQUIRED_MARKERS:
        assert marker.lower() in skill, marker
    for marker in FORBIDDEN_MARKERS:
        assert marker.lower() not in skill, marker


def test_skill_pins_one_job_one_record_rules() -> None:
    skill = SKILL_PATH.read_text(encoding="utf-8")
    assert "ONE job" in skill
    assert "record_submission" in skill
    assert "never edit tracker.json" in skill.lower()
    assert "one company once" in skill.lower()
