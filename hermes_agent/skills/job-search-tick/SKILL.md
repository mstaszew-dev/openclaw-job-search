---
name: job-search-tick
description: Apply to one IL job per tick - pick, dedupe, apply, record
version: 1.0.0
platforms: [macos]
metadata:
  hermes:
    tags: [job-search, career, automation]
---

# Job-Search Tick

## When to Use

Run once per campaign tick (cron or manual) to apply to exactly ONE job and
record it. Never run twice in a row without a recorded outcome.

## Procedure

1. `campaign_status` tool: read progress and recent applications (dedupe
   context).
2. Read `AGENT_TICK.md` and `CONTEXT.md` in the campaign dir
   (`/Users/mst/Downloads/job-search/job-apply`).
3. Pick ONE matching job using the targeting rules below. Honor the
   DIRECTOR SKIP LIST if the tick prompt contains one.
4. Dedupe: rag search over past applications (rag MCP tools) plus Gmail
   (in:sent OR in:inbox, newer_than:60d, company name). One company once.
5. Apply with the playwright MCP tools (existing Chrome CDP at
   http://127.0.0.1:9222; never launch or close Chrome). Verify the portal
   confirmation (thank-you page or text) before recording.
6. Record via the `record_submission` tool immediately after confirmation,
   with evidence of the confirmation in the record.
7. End the turn. ONE job per tick, no more.

## Pitfalls

- Never edit tracker.json directly; only `record_submission` may write it
  (via update_tracker.py).
- Never call score_candidate.py or check_dupe.py (retired automation).
- Do not apply to skip-listed companies or to companies applied to in the
  last 60 days.
- Temp scripts go in /tmp/, never the campaign dir.
- A submission without portal confirmation evidence does not count.

## Targeting rules

- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Roles involving TDD,
  code reviews, CI/CD (Jenkins, GitHub Actions) are in scope. Skip: ABAP,
  Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only:
  team-lead/manager/architect/director/head/VP.
- IL only: remote/hybrid/onsite ALL OK (central Israel for onsite; remote
  anywhere in IL). Do NOT apply to Polish sites, Upwork, or EU/PL portals.
- Freelance: include freelance, contract, part-time, and fixed-term B2B in
  IL.
