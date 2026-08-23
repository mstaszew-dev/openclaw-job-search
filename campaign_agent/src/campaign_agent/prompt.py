"""
Prompt builder: single source of truth for campaign policy and prompt assembly.

IMPORTANT (empirically verified): free-tier models time out on tool-calling
requests when the SYSTEM prompt exceeds roughly 0.5-1 KB. User-message size
is fine (2 KB user + tools works). So the system prompt is kept minimal and
all detailed rules live in the user prompt.
"""
from __future__ import annotations

from campaign_agent.config import Config

SYSTEM_PROMPT = """\
You are an autonomous job application agent. First action must be a TOOL CALL. \
Follow the rules in the task message. Apply exactly ONE job per tick.
"""

USER_PROMPT_TEMPLATE = """\
{session_context}

{token_info}

{director_extras}

TASK: Apply exactly ONE job this tick. Start with the read tool on AGENT_TICK.md \
and CONTEXT.md (relative to the campaign dir), then browse for a job.

RULES:
- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Include TDD, code reviews, \
test-driven development, CI/CD, and related engineering practices. \
Skip: ABAP, Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only: \
team-lead/manager/architect/director/head/VP.
- IL only: remote/hybrid/onsite ALL OK (central Israel for onsite; remote anywhere in IL). \
Do NOT apply to Polish sites, Upwork, or EU/PL portals.
- Freelance: include freelance, contract, part-time, and fixed-term B2B in IL.
- Record submissions ONLY via exec: update_tracker.py submitted '<json>'. Never \
edit tracker.json directly. Record immediately after browser confirmation.
- Dedupe: rag_search_apps + Gmail (60d). One company once. Do NOT call automation \
scripts (no score_candidate.py, no check_dupe.py).
- Browser: existing Chrome at http://127.0.0.1:9222. Do NOT launch/close Chrome.
- CV to upload: {cv_path} (absolute path; it is a regular file).
- Playwright page snapshots are saved under {playwright_output_dir} (absolute \
path, NOT relative to the campaign dir); read them from there if needed.
- Never ask permission. No stop tokens. After recording a submission, end your turn.
- Temp scripts go in /tmp/, not the campaign dir.
- The exec tool's working directory is {campaign_dir}; use relative paths there.

Work order: IL only (all modes). Stop after one confirmed submission.
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
        cv_path=config.cv_path,
        playwright_output_dir=config.playwright_output_dir,
        director_extras="\n\n".join(extras),
    )
    return template.strip()
