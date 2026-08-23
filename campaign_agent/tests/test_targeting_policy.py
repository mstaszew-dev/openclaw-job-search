"""IL-only targeting policy regression tests (plan 2026-08-23).

The implementation plan
docs/superpowers/plans/2026-08-23-il-only-targeting.md is the spec;
these tests encode every planned assertion against the live prompt
template and runtime docs, so targeting drift fails CI instead of
silently re-widening the campaign.

Machine-specific absolute paths are deliberate: the campaign config
(config.py) already pins these locations.
"""
from __future__ import annotations

from pathlib import Path

from campaign_agent.config import Config
from campaign_agent.prompt import build_user_prompt

CAMPAIGN_DIR = Path("/Users/mst/Downloads/job-search/job-apply")
AGENT_TICK = CAMPAIGN_DIR / "AGENT_TICK.md"
PORTALS = CAMPAIGN_DIR / "PORTALS.md"
CONTEXT = CAMPAIGN_DIR / "CONTEXT.md"
DIRECTOR_OVERRIDES = Path("~/.campaign-agent/director-prompt-overrides.md").expanduser()
WORKSPACE_AGENTS = Path("/Users/mst/ZCodeProject/openclaw-job-search/AGENTS.md")
HUB_AGENTS = Path("/Users/mst/ZCodeProject/AGENTS.md")

EU_PORTAL_MARKERS = (
    "Nofluffjobs",
    "No Fluff Jobs",
    "Just Join IT",
    "theProtocol",
    "Bulldogjob",
    "Pracuj",
    "We Work Remotely",
    "Working Nomads",
    "EuroRemote",
    "Remotify",
    "4DayWeek",
    "Remote.ok",
)
SALARY_FLOOR_MARKERS = ("15000 PLN", "15k PLN", "B2B >=")


def _prompt() -> str:
    """Render the user prompt from the pure template (director extras isolated)."""
    cfg = Config()
    cfg.director_prompt_overrides_path = "/nonexistent/director-note.md"
    return build_user_prompt(cfg, session_context="", token_info="")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class TestPromptPolicy:
    """campaign_agent/src/campaign_agent/prompt.py (single source of truth)."""

    def test_targets_il_only_with_explicit_prohibition(self):
        p = _prompt()
        assert "IL only:" in p
        assert "Do NOT apply to Polish sites, Upwork, or EU/PL portals" in p

    def test_upwork_application_flow_removed(self):
        p = _prompt()
        assert "upwork.com/nx/search" not in p
        assert 'Filter by "Contract" type' not in p

    def test_all_seniority_levels_accepted(self):
        p = _prompt()
        assert "ALL levels accepted" in p
        assert "architect/junior" not in p  # old skip list bundled junior

    def test_no_salary_floor(self):
        p = _prompt()
        for marker in SALARY_FLOOR_MARKERS:
            assert marker not in p

    def test_work_order_is_il_only(self):
        assert "Work order: IL only (all modes)" in _prompt()

    def test_skip_list_is_domain_only(self):
        p = _prompt()
        assert "Skip: ABAP, Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only." in p
        assert ", QA," not in p
        assert "mobile-lead" not in p

    def test_expertise_scope_names_concrete_ci_tooling(self):
        p = _prompt()
        assert "TDD" in p
        assert "code reviews" in p
        assert "CI/CD" in p
        assert "Jenkins" in p
        assert "GitHub Actions" in p


class TestAgentTickPolicy:
    """/Users/mst/Downloads/job-search/job-apply/AGENT_TICK.md (per-tick runbook)."""

    def test_work_order_il_only(self):
        assert "Order: **IL only**" in _read(AGENT_TICK)

    def test_no_eu_or_polish_portals(self):
        content = _read(AGENT_TICK)
        for marker in EU_PORTAL_MARKERS:
            assert marker not in content, f"stale portal reference: {marker}"

    def test_all_seniority_accepted(self):
        assert "ALL seniority levels accepted" in _read(AGENT_TICK)

    def test_no_salary_floor_and_market_rate_guidance(self):
        c = _read(AGENT_TICK)
        for marker in SALARY_FLOOR_MARKERS:
            assert marker not in c
        assert "market rate for IL" in c

    def test_skip_list_aligned_with_prompt(self):
        c = _read(AGENT_TICK)
        assert "pure QA" not in c
        assert "mobile-lead" not in c
        assert "DevOps/SRE-only" in c

    def test_expertise_scope_present_with_ci_tooling(self):
        c = _read(AGENT_TICK)
        assert "Expertise scope" in c
        for tool in ("TDD", "code reviews", "CI/CD"):
            assert tool in c
        assert "Jenkins" in c
        assert "GitHub Actions" in c

    def test_polish_phone_and_b2b_note_removed(self):
        c = _read(AGENT_TICK)
        assert "+48790775407" not in c
        assert "plB2bNote" not in c


class TestPortalsPolicy:
    """/Users/mst/Downloads/job-search/job-apply/PORTALS.md (portal catalog)."""

    def test_header_declares_il_only(self):
        c = _read(PORTALS)
        assert "**IL only**" in c
        assert "Israel ONLY" in c
        assert "Do NOT apply to Polish sites, Upwork, or EU/PL portals" in c

    def test_eu_section_removed(self):
        c = _read(PORTALS)
        assert "## Europe" not in c
        assert "FULL REMOTE ONLY" not in c
        for marker in EU_PORTAL_MARKERS:
            assert marker not in c, f"stale EU portal: {marker}"

    def test_seniority_and_expertise_line(self):
        c = _read(PORTALS)
        assert "ALL levels" in c
        assert "TDD" in c
        assert "code reviews" in c
        assert "CI/CD" in c
        assert "Jenkins" in c
        assert "GitHub Actions" in c


class TestContextPolicy:
    """/Users/mst/Downloads/job-search/job-apply/CONTEXT.md (campaign context)."""

    def test_goal_line(self):
        assert "ALL seniority levels. IL only." in _read(CONTEXT)

    def test_score_against_line(self):
        c = _read(CONTEXT)
        assert "TDD/reviews/CI/CD" in c
        assert "No salary floor" in c

    def test_salary_line_has_no_floor(self):
        c = _read(CONTEXT)
        assert "market rate for IL" in c
        for marker in ("15000 PLN", "15k PLN"):
            assert marker not in c


class TestDirectorOverridesPolicy:
    """~/.campaign-agent/director-prompt-overrides.md (injected into every prompt)."""

    def test_il_only_directive(self):
        c = _read(DIRECTOR_OVERRIDES)
        assert c.startswith("IL ONLY:")
        assert "Polish sites, Upwork, or EU/PL portals" in c
        assert "No salary floor" in c
        assert "All seniority levels" in c
        assert "TDD" in c
        assert "CI/CD" in c
        assert "Jenkins" in c
        assert "GitHub Actions" in c

    def test_stale_test_entries_purged(self):
        c = _read(DIRECTOR_OVERRIDES)
        assert "\ntest\n" not in f"\n{c}\n"


class TestWorkspaceAgentsPolicy:
    """openclaw-job-search/AGENTS.md (workspace instructions)."""

    def test_targeting_updated(self):
        c = _read(WORKSPACE_AGENTS)
        assert "IL only" in c
        assert "No salary floor" in c
        assert "mid-to-senior" not in c
        for marker in ("15000 PLN", "15k PLN"):
            assert marker not in c


class TestHubAgentsPolicy:
    """/Users/mst/ZCodeProject/AGENTS.md (global hub rules, applied first)."""

    def test_targeting_updated(self):
        c = _read(HUB_AGENTS)
        assert "IL only" in c
        assert "All seniority levels" in c
        assert "mid-to-senior only" not in c
        assert "15k PLN" not in c
