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

TASK: Apply exactly ONE job this tick. Start with the read tool on AGENT_TICK.md \
and CONTEXT.md (relative to the campaign dir), then browse for a job.

RULES:
- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Skip: ABAP, Salesforce, \
QA, C/C++, .NET, mobile-lead, ML/data, DevOps, lead/manager/architect/junior.
- IL: remote/hybrid/onsite (central only). EU/PL: full remote, B2B >= 15000 PLN.
- Record submissions ONLY via exec: update_tracker.py submitted '<json>'. Never \
edit tracker.json directly. Record immediately after browser confirmation.
- Dedupe: rag_search_apps + Gmail (60d). One company once. Do NOT call automation \
scripts (no score_candidate.py, no check_dupe.py).
- Browser: existing Chrome at http://127.0.0.1:9222. Do NOT launch/close Chrome.
- Never ask permission. No stop tokens. After recording a submission, end your turn.
- Temp scripts go in /tmp/, not the campaign dir.
- The exec tool's working directory is {campaign_dir}; use relative paths there.

Work order: IL -> EU/PL full remote -> other EU full remote. \
Stop after one confirmed submission.
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

    template = USER_PROMPT_TEMPLATE.format(
        session_context=ctx_section,
        token_info=token_section,
        campaign_dir=config.campaign_dir,
    )
    return template.strip()
