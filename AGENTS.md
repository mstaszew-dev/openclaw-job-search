# AGENTS.md - OpenClaw workspace instructions

## Global rules (canonical, apply first)

Read and apply **~/.zcode/AGENTS.md** - the single canonical source for global
rules, skills (`~/.agents/skills/`), commands (`~/.zcode/commands/`), MCP
wiring, and conventions across all AI tools. This workspace hosts the Python
campaign agent (`campaign_agent/`), its Hermes port (`hermes_agent/`, branch
`hermes-agent-port`), and the RAG server (`rag/`); the job-search campaign
state lives at /Users/mst/Downloads/job-search/job-apply.

## Job-search specifics

- Apply exactly ONE job per tick; verify browser confirmation before
  `update_tracker.py submitted`; dedupe via rag_search_apps + Gmail (60d).
- Target Java/Kotlin/Spring, PHP/Laravel, Node/React + TDD, code reviews, CI/CD. \
Regions IL + PL, alternating 50/50 per tick. IL: remote/hybrid/onsite, no salary floor. \
PL: NoFluffJobs/JustJoin.it/theProtocol.it, fully remote only, B2B >= 15 000 PLN \
net+VAT/month when listed. All seniority. Include freelance. No Upwork. \
PL applications use the Polish CV (michael-staszewski-cv-pl.pdf), phonePl, \
Biała Parcela location, coverNotePl/plB2bNotePl; never mention relocation or Israel.
- Drive the browser only through the existing Chrome CDP at 127.0.0.1:9222.
- Keep temp scripts in /tmp/, never in the campaign directory.

## Python code review

Before writing or approving any Python in this workspace (campaign_agent/,
rag/), read the canonical checklist first and apply it:
`~/ZCodeProject/PYTHON_CODE_REVIEW.md`. Treat BLOCKER items as merge gates;
SHOULD as "fix or justify"; NICE as suggestions. The campaign-agent annex
(PY-1..PY-4) applies here (LLM gateway calls, MCP lifecycle, atomic state).
Never approve with a BLOCKER open; three or more SHOULD findings escalates.
