# Distributed Kotlin pipeline (kotlin/)

Kotlin + Spring Boot 3 port of the Python campaign agent as a small
distributed system, deployed on the lubuntu k3s single node.

## Services

| Module | Runs as | Role |
|---|---|---|
| `common` | lib | schema (Flyway V1), entities, queue (SKIP LOCKED claim + lease + reaper), dedupe port (DEDUPE.md), LLM client, MCP stdio client |
| `finder-service` | native (GraalVM) | collects listings from NoFluffJobs/JustJoin/theProtocol through the shared Chrome (CDP via Playwright MCP), dedups, applies eligibility policy, LLM CV scoring, enqueues |
| `apply-worker` | JVM (GraalVM JDK) | claims queue tasks; `APPLY_MODE=shadow` re-checks dedup and releases; `APPLY_MODE=live` drives the browser agent (one tab, verifier-guarded recording) |
| `campaign-api` | native (GraalVM) | `/api/v1/stats`, `/api/v1/queue`, `/api/v1/applications` |
| `import-tracker` | CLI | idempotent tracker.json/events.jsonl -> Postgres import |

Postgres carries the tracker model: `job_listings`, `apply_tasks` (queue),
`applications`, `skips`, `blockers`, `events` (append-only ledger).

## Run tests

```
cd kotlin && ./gradlew build        # unit tests always; ITs need Docker
```

Integration tests run on GitHub Actions (kotlin-ci) where Docker exists.

## Deploy

See `deploy/docs/KOTLIN_DEPLOY.md` (lubuntu k3s runbook: images, manifests,
import, shadow-mode verification, gated cutover).

## Rules

- Targeting policy lives in `finder.policy`/`finder` config (PL only, fully
  remote, 15000 PLN B2B floor when listed, lead/manager exclusions).
- Dedupe must stay byte-compatible with the campaign spec (DEDUPE.md); the
  golden tests in `common` pin `CompanyKeyNormalizer`/`UrlNormalizer`.
- A submission counts only with valid confirmation evidence
  (`SubmissionVerifier`, port of submission_validator.py).
