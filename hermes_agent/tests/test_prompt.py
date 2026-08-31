"""Tick prompt: IL+PL policy, interpolation, director extras."""
from __future__ import annotations

from jobhermes.config import Config
from jobhermes.prompt import build_director_extras, build_tick_prompt


def test_prompt_carries_campaign_paths() -> None:
    config = Config(campaign_dir="/tmp/camp")
    prompt = build_tick_prompt(config)
    assert "/tmp/camp" in prompt
    assert config.cv_path in prompt
    assert config.cv_path_pl in prompt
    assert config.playwright_output_dir in prompt


def test_prompt_pins_il_pl_policy() -> None:
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for marker in (
        "IL + PL",
        "alternating 50/50",
        "remote/hybrid/onsite ALL OK",
        "fully remote ONLY",
        "15 000 PLN",
        "michael-staszewski-cv-pl.pdf",
        "+48790775407",
        "Biała Parcela",
        "coverNotePl",
        "plB2bNotePl",
        "NEVER mention relocation",
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


def test_prompt_has_no_stale_il_only_or_eu_markers() -> None:
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for forbidden in (
        "Do NOT apply to Polish sites",
        "IL only",
        "15k",
        "RemotifyEurope",
        "EuroRemote",
        "4DayWeek",
        "We Work Remotely",
        "EU/GLOBAL",
        "willing to relocate",
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


def test_prompt_pins_identity_block() -> None:
    """Regression 2026-08-31: an agent session invented an email on a PL
    form because identity data lived only in applicant.json, never inlined
    into the prompt. The tick prompt must carry an explicit IDENTITY block."""
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    assert "IDENTITY" in prompt
    for marker in (
        "Michael Staszewski",
        "Michał Staszewski",
        "mst.rocking@gmail.com",
        "+972559344507",
        "+48790775407",
        "Petah Tikva",
        "Biała Parcela",
        "EXACTLY",
        "never invent",
    ):
        assert marker in prompt, marker


def test_prompt_identity_loaded_from_applicant_json(tmp_path) -> None:
    """Identity values come from applicant.json (single source of truth)."""
    import json

    (tmp_path / "applicant.json").write_text(
        json.dumps(
            {
                "fullName": "Test Person",
                "namePl": "Test Osoba",
                "email": "test@example.com",
                "phoneIl": "+972000000000",
                "phonePl": "+48000000000",
                "locationCity": "Test City IL",
                "locationPl": "Test City PL",
            }
        ),
        encoding="utf-8",
    )
    prompt = build_tick_prompt(Config(campaign_dir=str(tmp_path)))
    for marker in (
        "Test Person",
        "Test Osoba",
        "test@example.com",
        "+972000000000",
        "+48000000000",
        "Test City IL",
        "Test City PL",
    ):
        assert marker in prompt, marker


def test_prompt_identity_falls_back_when_applicant_json_missing(tmp_path) -> None:
    """Missing/unreadable applicant.json must still yield a full identity
    block (never absent - absence is how invented emails happen)."""
    prompt = build_tick_prompt(Config(campaign_dir=str(tmp_path / "nowhere")))
    assert "mst.rocking@gmail.com" in prompt
    assert "Michael Staszewski" in prompt
    assert "Michał Staszewski" in prompt
