"""Tick prompt: IL-only policy, interpolation, director extras."""
from __future__ import annotations

from jobhermes.config import Config
from jobhermes.prompt import build_director_extras, build_tick_prompt


def test_prompt_carries_campaign_paths() -> None:
    config = Config(campaign_dir="/tmp/camp")
    prompt = build_tick_prompt(config)
    assert "/tmp/camp" in prompt
    assert config.cv_path in prompt
    assert config.playwright_output_dir in prompt


def test_prompt_pins_il_only_policy() -> None:
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for marker in (
        "IL only",
        "remote/hybrid/onsite ALL OK",
        "Do NOT apply to Polish sites, Upwork, or EU/PL portals",
        "ALL levels accepted (junior through senior)",
        "Skip only: team-lead/manager/architect/director/head/VP",
        "exactly ONE job",
        "record_submission",
        "Never edit tracker.json directly",
        "One company once",
        "127.0.0.1:9222",
        "end your turn",
        "AGENT_TICK.md",
        "campaign_status",
    ):
        assert marker in prompt, marker


def test_prompt_has_no_eu_or_salary_floor_markers() -> None:
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for forbidden in (
        "PLN",
        "15k",
        "RemotifyEurope",
        "EuroRemote",
        "4DayWeek",
        "We Work Remotely",
        "EU/GLOBAL",
    ):
        assert forbidden not in prompt, forbidden


def test_director_extras_empty_when_nothing_configured() -> None:
    assert build_director_extras(set(), "") == ""


def test_director_extras_skip_list_sorted() -> None:
    extras = build_director_extras({"Mindbox", "antal"}, "")
    # plain sorted() like the original campaign_agent (ASCII order)
    assert extras == (
        "DIRECTOR SKIP LIST: do NOT apply to any of these companies: Mindbox, antal."
    )


def test_director_extras_note_and_both() -> None:
    extras = build_director_extras({"acme"}, "focus backend")
    assert "DIRECTOR SKIP LIST" in extras and "acme" in extras
    assert "DIRECTOR NOTE: focus backend" in extras


def test_director_extras_note_only() -> None:
    extras = build_director_extras(set(), "focus backend")
    assert extras == "DIRECTOR NOTE: focus backend"


def test_prompt_includes_director_extras_and_session_context(tmp_path) -> None:
    config = Config(
        campaign_dir="/tmp/camp",
        skip_companies={"antal"},
        director_note_path=str(tmp_path / "none.md"),
    )
    prompt = build_tick_prompt(config, session_context="Previous tick context:\napplied to Acme")
    assert "DIRECTOR SKIP LIST" in prompt and "antal" in prompt
    assert "Previous tick context:\napplied to Acme" in prompt


def test_prompt_includes_director_note_from_file(tmp_path) -> None:
    note = tmp_path / "note.md"
    note.write_text("no fintech this week", encoding="utf-8")
    config = Config(campaign_dir="/tmp/camp", director_note_path=str(note))
    prompt = build_tick_prompt(config)
    assert "DIRECTOR NOTE: no fintech this week" in prompt


def test_prompt_collapses_leading_blank_sections() -> None:
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    assert not prompt.startswith("\n")
    assert "\n\n\n" not in prompt
