# AGENTS.md - OpenClaw workspace instructions

## Global rules (canonical, apply first)

Read and apply **~/.zcode/AGENTS.md** - the single canonical source for global
rules, skills (`~/.agents/skills/`), commands (`~/.zcode/commands/`), MCP
wiring, and conventions across all AI tools. This workspace hosts the Python
campaign agent (campaign_agent/) and the RAG server (rag/); the job-search
campaign state lives at /Users/mst/Downloads/job-search/job-apply.

## Job-search specifics

- Apply exactly ONE job per tick; verify browser confirmation before
  `update_tracker.py submitted`; dedupe via rag_search_apps + Gmail (60d).
- Target mid-to-senior Java/Kotlin/Spring, PHP/Laravel, Node/React. IL:
  remote/hybrid/onsite (central). EU/PL: full remote, B2B >= 15000 PLN.
- Drive the browser only through the existing Chrome CDP at 127.0.0.1:9222.
- Keep temp scripts in /tmp/, never in the campaign directory.
