"""
Prompt builder: single source of truth for campaign policy and prompt assembly.

IMPORTANT (empirically verified): free-tier models time out on tool-calling
requests when the SYSTEM prompt exceeds roughly 0.5-1 KB. User-message size
is fine (2 KB user + tools works). So the system prompt is kept minimal and
all detailed rules live in the user prompt.

PL-only campaign (2026-09-07): the Polish market is the sole target region;
IL targeting was retired (superseded plans: 2026-08-23 IL-only, 2026-08-30
IL+PL). IL board runbooks and CV variants are no longer referenced.
"""
from __future__ import annotations

import json
from pathlib import Path

from campaign_agent.config import Config

SYSTEM_PROMPT = """\
You are an autonomous job application agent. First action must be a TOOL CALL. \
Follow the rules in the task message. Apply exactly ONE job per tick.
"""

# Identity fallbacks (Polish presentation): used only when applicant.json is
# missing/unreadable, so the prompt NEVER goes out identity-less (an
# identity-less prompt is how an agent invents form values - 2026-08-31
# incident).
_IDENTITY_FALLBACK: dict[str, str] = {
    "name_pl": "Michał Staszewski",
    "email": "mst.rocking@gmail.com",
    "phone_pl": "+48790775407",
    "city_pl": "Biała Parcela, woj. łódzkie",
}


def load_identity(campaign_dir: str) -> dict[str, str]:
    """Polish identity fields inlined into the tick prompt. applicant.json is
    the single source of truth; the fallback keeps the block complete when
    the file is missing or unparseable. IL identity fields are deliberately
    not returned (PL-only campaign)."""
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
        "name_pl": pick("namePl", "name_pl"),
        "email": pick("email", "email"),
        "phone_pl": pick("phonePl", "phone_pl"),
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
        "- Name: {name_pl}\n"
        "- Email: {email} - the ONLY email you may ever type into a form field\n"
        "- Phone: {phone_pl}\n"
        "- Location: {city_pl}, Poland".format(**ident)
    )

USER_PROMPT_TEMPLATE = """\
{session_context}

{token_info}

{director_extras}

{identity_block}

TASK: Apply exactly ONE job this tick. Start with the read tool on AGENT_TICK.md \
and CONTEXT.md (relative to the campaign dir), then browse for a job.

RULES:
- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Roles involving TDD, code \
reviews, CI/CD (Jenkins, GitHub Actions) are in scope - deep hands-on experience. \
Skip: ABAP, Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only: \
team-lead/manager/architect/director/head/VP.
- Region: PL only (Polish market). Do not apply to roles outside Poland.
- PL: fully remote ONLY, B2B >= 15 000 PLN net+VAT/month (skip when the \
listing shows a lower B2B rate). Boards: PORTALS.md / PL_BOARDS.md.
- Freelance: include freelance, contract, part-time, and fixed-term B2B in the \
Polish market.
- Record submissions ONLY via exec: update_tracker.py submitted '<json>'. Never \
edit tracker.json directly. Record immediately after browser confirmation.
- Dedupe: rag_search_apps + Gmail (60d). One company once. Do NOT call automation \
scripts (no score_candidate.py, no check_dupe.py).
- Browser: existing Chrome at http://127.0.0.1:9222. Do NOT launch/close Chrome.
- CV to upload: {cv_path_pl}. PL forms: \
phone +48790775407, location Biała Parcela, woj. łódzkie, coverNotePl / plB2bNotePl \
from applicant.json; NEVER mention relocation or Israel on PL forms.
- Playwright page snapshots are saved under {playwright_output_dir} (absolute \
path, NOT relative to the campaign dir); read them from there if needed.
- Never ask permission. No stop tokens. After recording a submission, end your turn.
- Temp scripts go in /tmp/, not the campaign dir.
- The exec tool's working directory is {campaign_dir}; use relative paths there.

Work order: one confirmed submission per tick, Polish market only. Stop after one \
confirmed submission.
"""


def build_system_prompt(config: Config) -> str:
    """Build the system prompt with campaign rules."""
    return SYSTEM_PROMPT


def build_user_prompt(
    config: Config,
    session_context: str = "",
    token_info: str = "",
) -> str:
    """Build the user prompt for a single tick."""
    ctx_section = ""
    if session_context:
        ctx_section = f"Previous session context:\n{session_context}"

    token_section = ""
    if token_info:
        token_section = f"TOKEN BUDGET: {token_info}"

    extras: list[str] = []
    if config.skip_companies:
        extras.append(
            "DIRECTOR SKIP LIST: do NOT apply to any of these companies: "
            + ", ".join(sorted(config.skip_companies)) + "."
        )
    director_note = config.director_note
    if director_note:
        extras.append(f"DIRECTOR NOTE: {director_note}")

    template = USER_PROMPT_TEMPLATE.format(
        session_context=ctx_section,
        token_info=token_section,
        campaign_dir=config.campaign_dir,
        cv_path_pl=config.cv_path_pl,
        playwright_output_dir=config.playwright_output_dir,
        director_extras="\n\n".join(extras),
        identity_block=build_identity_block(config.campaign_dir),
    )
    return template.strip()
