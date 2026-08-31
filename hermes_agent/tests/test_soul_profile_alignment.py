"""Hermes SOUL.md + profile.yaml must stay aligned with the python agent's
targeting and identity. The python agent is the canonical source (its
prompt.py carries the IDENTITY block and the IL+PL rules); hermes is a
profile-driven fork of the same campaign, so its persona files must
reference the same regions, boards, and identity fields.

Regression: the hermes SOUL was IL-only and predated the PL expansion and
the IDENTITY block, so a profile that looks current can silently drift.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Import the python agent's prompt builder as the canonical source of truth.
# Test file lives at <repo>/hermes_agent/tests/test_soul_profile_alignment.py,
# so parents[2] is the openclaw-job-search repo root and the python agent's
# src sits at <root>/campaign_agent/src.
_PY_AGENT_SRC = (
    Path(__file__).resolve().parents[2] / "campaign_agent" / "src"
)
if str(_PY_AGENT_SRC) not in sys.path:
    sys.path.insert(0, str(_PY_AGENT_SRC))

from jobhermes.config import Config  # noqa: E402
from jobhermes.prompt import build_tick_prompt  # noqa: E402
from campaign_agent.prompt import build_identity_block, build_user_prompt  # type: ignore  # noqa: E402

import pytest  # noqa: E402

SOUL = Path.home() / ".hermes/profiles/jobhunter/SOUL.md"
PROFILE = Path.home() / ".hermes/profiles/jobhunter/profile.yaml"

CAMPAIGN_DIR = "/Users/mst/Downloads/job-search/job-apply"


@pytest.fixture(autouse=True)
def _skip_without_install():
    """The persona files live in the user's hermes profile, not the repo, so
    skip on fresh checkouts rather than crash."""
    if not SOUL.exists() or not PROFILE.exists():
        pytest.skip("hermes profile not installed at ~/.hermes/profiles/jobhunter")


def _soul() -> str:
    return SOUL.read_text(encoding="utf-8")


def _profile() -> str:
    return PROFILE.read_text(encoding="utf-8")


def test_soul_is_dual_region() -> None:
    soul = _soul()
    assert "IL + PL" in soul or "IL and PL" in soul
    assert "NoFluffJobs" in soul
    assert "JustJoin.it" in soul
    assert "theProtocol.it" in soul


def test_soul_references_identity_block_and_applicant_json() -> None:
    soul = _soul()
    assert "IDENTITY" in soul
    assert "applicant.json" in soul


def test_soul_references_record_submission_and_campaign_state() -> None:
    soul = _soul()
    assert "record_submission" in soul
    assert "tracker.json" in soul
    assert "job-search/job-apply" in soul


def test_profile_describes_dual_region_and_boards() -> None:
    profile = _profile()
    assert "IL + PL" in profile or "IL and PL" in profile
    assert "NoFluffJobs" in profile
    assert "JustJoin.it" in profile
    assert "theProtocol.it" in profile
    assert "15k PLN" in profile or "15 000 PLN" in profile


def test_profile_advertises_identity_source() -> None:
    profile = _profile()
    assert "applicant.json" in profile
    assert "IDENTITY" in profile


def test_hermes_identity_block_matches_python_agent() -> None:
    """The IDENTITY block in hermes must carry the same values the python
    agent inlines - both derive from applicant.json."""
    py = build_identity_block(CAMPAIGN_DIR)
    hermes = build_tick_prompt(Config(campaign_dir=CAMPAIGN_DIR))
    for marker in (
        "Michael Staszewski",
        "Michał Staszewski",
        "mst.rocking@gmail.com",
        "+972559344507",
        "+48790775407",
        "Petah Tikva",
        "Biała Parcela",
    ):
        assert marker in py, f"python identity missing: {marker}"
        assert marker in hermes, f"hermes identity missing: {marker}"


def test_hermes_prompt_targets_match_python_agent() -> None:
    """Both agents must carry the same targeting markers that live in the
    prompt itself. Board lists (NoFluffJobs etc.) live in PORTALS.md and
    PL_BOARDS.md, referenced by both agents' prompts, not inlined."""
    py = build_user_prompt(Config(), session_context="", token_info="")
    hermes = build_tick_prompt(Config(campaign_dir=CAMPAIGN_DIR))
    for marker in (
        "IL + PL",
        "alternating 50/50",
        "PL forms",
        "+48790775407",
        "Biała Parcela",
        "michael-staszewski-cv-pl.pdf",
        "applicant.json",
    ):
        assert marker in py, f"python targeting missing: {marker}"
        assert marker in hermes, f"hermes targeting missing: {marker}"


def test_live_memory_is_dual_region() -> None:
    """The live profile memory is what the bot actually recites about itself
    (the user-facing self-description). It must carry the current IL+PL
    targeting and the identity email, and must not use the retired
    IL-only campaign phrasing."""
    memory = (Path.home() / ".hermes/profiles/jobhunter/memories/MEMORY.md").read_text(
        encoding="utf-8"
    )
    assert "IL + PL" in memory
    assert "NoFluffJobs" in memory
    assert "mst.rocking@gmail.com" in memory
    assert "IL job-search campaign" not in memory
