"""IL+PL targeting policy regression tests (plan 2026-08-30).

Supersedes the 2026-08-23 IL-only plan: Poland is a second target region
(NoFluffJobs + JustJoin.it + theProtocol.it, fully remote only, B2B >=
15 000 PLN net+VAT/month when listed) applied on a 50/50 alternating-tick
rotation, with a dedicated Polish CV variant for PL applications.

These tests encode the policy against the live prompt template and
runtime docs, so targeting drift fails CI instead of silently re-widening
or re-narrowing the campaign.

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
PL_BOARDS = CAMPAIGN_DIR / "PL_BOARDS.md"
IL_BOARDS = CAMPAIGN_DIR / "IL_BOARDS.md"
CONTEXT = CAMPAIGN_DIR / "CONTEXT.md"
DIRECTOR_OVERRIDES = Path("~/.campaign-agent/director-prompt-overrides.md").expanduser()
WORKSPACE_AGENTS = Path("/Users/mst/ZCodeProject/openclaw-job-search/AGENTS.md")
HUB_AGENTS = Path("/Users/mst/ZCodeProject/AGENTS.md")

PL_PORTAL_MARKERS = ("NoFluffJobs", "JustJoin.it", "theProtocol.it")
STILL_FORBIDDEN_EU_MARKERS = (
    "Bulldogjob",
    "Pracuj",
    "We Work Remotely",
    "Working Nomads",
    "EuroRemote",
    "Remotify",
    "4DayWeek",
    "Remote.ok",
)
OLD_IL_ONLY_PROHIBITION = "Do NOT apply to Polish sites"
PL_SALARY_FLOOR_MARKERS = ("15 000 PLN", "B2B >=")


def _prompt() -> str:
    """Render the user prompt from the pure template (director extras isolated)."""
    cfg = Config()
    cfg.director_prompt_overrides_path = "/nonexistent/director-note.md"
    return build_user_prompt(cfg, session_context="", token_info="")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class TestPromptPolicy:
    """campaign_agent/src/campaign_agent/prompt.py (single source of truth)."""

    def test_targets_il_and_pl_with_alternating_rotation(self):
        p = _prompt()
        assert "IL + PL" in p
        assert "alternating 50/50" in p

    def test_old_il_only_prohibition_removed(self):
        p = _prompt()
        assert OLD_IL_ONLY_PROHIBITION not in p
        assert "IL only:" not in p

    def test_pl_is_remote_only_with_b2b_floor(self):
        p = _prompt()
        assert "fully remote ONLY" in p
        for marker in PL_SALARY_FLOOR_MARKERS:
            assert marker in p

    def test_pl_uses_polish_cv_and_pl_form_data(self):
        p = _prompt()
        assert "michael-staszewski-cv-pl.pdf" in p
        assert "+48790775407" in p
        assert "Biała Parcela" in p
        assert "coverNotePl" in p
        assert "plB2bNotePl" in p
        assert "relocation" in p.lower()  # the rule: never mention it

    def test_upwork_application_flow_removed(self):
        p = _prompt()
        assert "upwork.com/nx/search" not in p
        assert 'Filter by "Contract" type' not in p
        assert "Upwork" not in p

    def test_all_seniority_levels_accepted(self):
        p = _prompt()
        assert "ALL levels accepted" in p
        assert "architect/junior" not in p  # old skip list bundled junior

    def test_il_has_no_salary_floor(self):
        p = _prompt()
        assert "no salary floor" in p

    def test_work_order_covers_both_regions(self):
        assert "Work order: IL + PL" in _prompt()
        assert "Work order: IL only" not in _prompt()

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

    def test_work_order_alternates_regions(self):
        assert "IL and PL, alternating 50/50" in _read(AGENT_TICK)
        assert "Order: **IL only**" not in _read(AGENT_TICK)

    def test_pl_portals_listed(self):
        c = _read(AGENT_TICK)
        for marker in PL_PORTAL_MARKERS:
            assert marker in c, f"missing PL portal: {marker}"
        assert "PL_BOARDS.md" in c

    def test_stale_eu_portals_absent(self):
        c = _read(AGENT_TICK)
        for marker in STILL_FORBIDDEN_EU_MARKERS:
            assert marker not in c, f"stale portal reference: {marker}"

    def test_all_seniority_accepted(self):
        assert "ALL seniority levels accepted" in _read(AGENT_TICK)

    def test_region_salary_rules(self):
        c = _read(AGENT_TICK)
        assert "market rate for IL" in c
        for marker in PL_SALARY_FLOOR_MARKERS:
            assert marker in c

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

    def test_dual_cv_and_pl_form_data(self):
        c = _read(AGENT_TICK)
        assert "michael-staszewski-cv-pl.pdf" in c
        assert "michael-staszewski-cv.pdf" in c
        assert "+48790775407" in c
        assert "coverNotePl" in c
        assert "plB2bNotePl" in c
        assert "Biała Parcela" in c


class TestPortalsPolicy:
    """/Users/mst/Downloads/job-search/job-apply/PORTALS.md (portal catalog)."""

    def test_header_declares_il_plus_pl(self):
        c = _read(PORTALS)
        assert "**IL + PL**" in c
        assert "Israel (all work modes) + Poland (fully remote only" in c
        assert OLD_IL_ONLY_PROHIBITION not in c
        assert "Israel ONLY" not in c

    def test_pl_section_present(self):
        c = _read(PORTALS)
        assert "## Poland (PL)" in c
        for marker in PL_PORTAL_MARKERS:
            assert marker in c, f"missing PL portal: {marker}"
        assert "PL_BOARDS.md" in c
        for marker in PL_SALARY_FLOOR_MARKERS:
            assert marker in c

    def test_rotation_tiebreaker_pinned(self):
        assert "none/ambiguous" in _read(PORTALS).lower()
        assert "none/ambiguous" in _read(AGENT_TICK).lower()


class TestIlBoardsPolicy:
    """/Users/mst/Downloads/job-search/job-apply/IL_BOARDS.md (IL runbook)."""

    def test_no_stale_il_only_claim(self):
        c = _read(IL_BOARDS)
        assert "Israeli jobs only" not in c
        assert "IL + PL" in c
        assert "PL_BOARDS.md" in c

    def test_stale_eu_portals_absent(self):
        c = _read(PORTALS)
        assert "## Europe" not in c
        assert "FULL REMOTE ONLY" not in c
        for marker in STILL_FORBIDDEN_EU_MARKERS:
            assert marker not in c, f"stale EU portal: {marker}"

    def test_seniority_and_expertise_line(self):
        c = _read(PORTALS)
        assert "ALL levels" in c
        assert "TDD" in c
        assert "code reviews" in c
        assert "CI/CD" in c
        assert "Jenkins" in c
        assert "GitHub Actions" in c


class TestPlBoardsPolicy:
    """/Users/mst/Downloads/job-search/job-apply/PL_BOARDS.md (PL runbook)."""

    def test_remote_only_and_floor(self):
        c = _read(PL_BOARDS)
        assert "Fully remote ONLY" in c
        for marker in PL_SALARY_FLOOR_MARKERS:
            assert marker in c

    def test_pl_presentation_table(self):
        c = _read(PL_BOARDS)
        assert "michael-staszewski-cv-pl.pdf" in c
        assert "+48790775407" in c
        assert "Biała Parcela" in c
        assert "coverNotePl" in c
        assert "plB2bNotePl" in c

    def test_no_relocation_talk(self):
        c = _read(PL_BOARDS)
        assert "relocation" in c.lower()  # the 'never mention' rule is stated
        assert "willing to relocate" not in c.lower()


class TestContextPolicy:
    """/Users/mst/Downloads/job-search/job-apply/CONTEXT.md (campaign context)."""

    def test_goal_line(self):
        c = _read(CONTEXT)
        assert "Regions: IL + PL" in c
        assert "ALL seniority levels. IL only." not in c

    def test_score_against_line(self):
        c = _read(CONTEXT)
        assert "TDD/reviews/CI/CD" in c
        assert "PL: fully remote" in c
        assert "IL only" not in c
        for marker in PL_SALARY_FLOOR_MARKERS:
            assert marker in c


class TestDirectorOverridesPolicy:
    """~/.campaign-agent/director-prompt-overrides.md (injected into every prompt)."""

    def test_regions_directive(self):
        c = _read(DIRECTOR_OVERRIDES)
        assert c.startswith("REGIONS IL + PL:")
        assert "fully remote" in c
        for marker in PL_SALARY_FLOOR_MARKERS:
            assert marker in c
        assert "All seniority levels" in c
        assert "TDD" in c
        assert "CI/CD" in c
        assert "Jenkins" in c
        assert "GitHub Actions" in c

    def test_directive_survives_appended_director_log(self):
        """The director appends timestamped entries below the directive; the
        durable invariant is that the REGIONS block stays at the top."""
        c = _read(DIRECTOR_OVERRIDES)
        assert c.splitlines()[0].startswith("REGIONS IL + PL:")
        for required in (
            "fully remote",
            "15 000 PLN",
            "All seniority levels",
            "Jenkins",
            "GitHub Actions",
        ):
            assert required in c


class TestWorkspaceAgentsPolicy:
    """openclaw-job-search/AGENTS.md (workspace instructions)."""

    def test_targeting_updated(self):
        c = _read(WORKSPACE_AGENTS)
        assert "IL + PL" in c
        for marker in PL_PORTAL_MARKERS:
            assert marker in c
        assert OLD_IL_ONLY_PROHIBITION not in c
        assert "mid-to-senior" not in c
        assert "All seniority" in c


class TestHubAgentsPolicy:
    """/Users/mst/ZCodeProject/AGENTS.md (global hub rules, applied first)."""

    def test_targeting_updated(self):
        c = _read(HUB_AGENTS)
        assert "IL + PL" in c
        for marker in PL_PORTAL_MARKERS:
            assert marker in c
        assert OLD_IL_ONLY_PROHIBITION not in c
        assert "All seniority levels" in c
        assert "mid-to-senior only" not in c
