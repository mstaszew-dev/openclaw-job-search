"""Tick prompt builder, ported from campaign_agent.prompt (IL+PL policy).

The system prompt is owned by Hermes (profile SOUL.md); this module builds the
task prompt that carries the campaign rules.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from .config import Config

# Identity fallbacks: used only when applicant.json is missing/unreadable, so
# the prompt NEVER goes out identity-less (an identity-less prompt is how an
# agent invents form values - 2026-08-31 incident).
_IDENTITY_FALLBACK: dict[str, str] = {
    "full_name": "Michael Staszewski",
    "name_pl": "Michał Staszewski",
    "email": "mst.rocking@gmail.com",
    "phone_il": "+972559344507",
    "phone_pl": "+48790775407",
    "city_il": "Petah Tikva, Israel",
    "city_pl": "Biała Parcela, woj. łódzkie",
}


def load_identity(campaign_dir: str) -> dict[str, str]:
    """Identity fields inlined into the tick prompt. applicant.json is the
    single source of truth; the fallback keeps the block complete when the
    file is missing or unparseable."""
    try:
        data = json.loads(
            (Path(campaign_dir) / "applicant.json").read_text(encoding="utf-8")
        )
    except (OSError, ValueError):
        data = {}

    def pick(json_key: str, fallback_key: str) -> str:
        value = data.get(json_key)
        return str(value) if value else _IDENTITY_FALLBACK[fallback_key]

    return {
        "full_name": pick("fullName", "full_name"),
        "name_pl": pick("namePl", "name_pl"),
        "email": pick("email", "email"),
        "phone_il": pick("phoneIl", "phone_il"),
        "phone_pl": pick("phonePl", "phone_pl"),
        "city_il": pick("locationCity", "city_il"),
        "city_pl": pick("locationPl", "city_pl"),
    }


def build_identity_block(campaign_dir: str) -> str:
    """Explicit identity block for application forms. Inlined (not a file
    pointer) so the values are in context even if the model never opens
    applicant.json."""
    ident = load_identity(campaign_dir)
    return (
        "IDENTITY (use EXACTLY these values on every application form; "
        "never invent, guess, or vary any of them):\n"
        "- Name: {full_name} (PL forms: {name_pl})\n"
        "- Email: {email} - the ONLY email you may ever type into a form field\n"
        "- Phone: IL {phone_il} / PL {phone_pl}\n"
        "- Location: IL {city_il} / PL {city_pl}".format(**ident)
    )

TASK_TEMPLATE = """{session_context}

{director_extras}

{identity_block}

TASK: Apply exactly ONE job this tick. Start by reading AGENT_TICK.md and CONTEXT.md in the campaign dir ({campaign_dir}), then browse for a job. Check progress with the campaign_status tool.

RULES:
- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Roles involving TDD, code reviews, CI/CD (Jenkins, GitHub Actions) are in scope - deep hands-on experience. Skip: ABAP, Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only: team-lead/manager/architect/director/head/VP.
- Regions: IL + PL, alternating 50/50 (the latest tracker submission's region decides the next tick: IL -> PL, PL -> IL; none/ambiguous -> IL).
- IL: remote/hybrid/onsite ALL OK (central Israel for onsite; remote anywhere in IL), no salary floor. PL: fully remote ONLY (NoFluffJobs/JustJoin.it/theProtocol.it), B2B >= 15 000 PLN net+VAT/month (skip when the listing shows a lower B2B rate).
- Freelance: include freelance, contract, part-time, and fixed-term B2B in both regions.
- Record submissions ONLY via the record_submission tool. Never edit tracker.json directly. Record immediately after browser confirmation.
- Dedupe: rag search over past applications (rag MCP tools) + Gmail (60d). One company once. Do NOT call automation scripts (no score_candidate.py, no check_dupe.py).
- Browser: use the playwright MCP tools attached to the existing Chrome at http://127.0.0.1:9222. Do NOT launch/close Chrome.
- CV to upload: IL listing -> {cv_path}; PL listing -> {cv_path_pl}. PL forms: phone +48790775407, location Biała Parcela, woj. łódzkie, coverNotePl / plB2bNotePl from applicant.json; NEVER mention relocation or Israel on PL forms.
- Playwright page snapshots are saved under {playwright_output_dir} (absolute path); read them from there if needed.
- Never ask permission. No stop tokens. After recording a submission, end your turn.
- Temp scripts go in /tmp/, not the campaign dir.

Work order: IL + PL alternate per tick. Stop after one confirmed submission."""


def build_director_extras(skip_companies: set[str], director_note: str) -> str:
    parts: list[str] = []
    if skip_companies:
        listing = ", ".join(sorted(skip_companies))
        parts.append(
            "DIRECTOR SKIP LIST: do NOT apply to any of these companies: {}.".format(
                listing
            )
        )
    if director_note:
        parts.append("DIRECTOR NOTE: {}".format(director_note))
    return "\n\n".join(parts)


def build_tick_prompt(config: Config, session_context: str = "") -> str:
    filled = TASK_TEMPLATE.format(
        session_context=session_context,
        director_extras=build_director_extras(config.skip_companies, config.director_note),
        identity_block=build_identity_block(config.campaign_dir),
        campaign_dir=config.campaign_dir,
        cv_path=config.cv_path,
        cv_path_pl=config.cv_path_pl,
        playwright_output_dir=config.playwright_output_dir,
    )
    return re.sub(r"\n{3,}", "\n\n", filled).strip() + "\n"
