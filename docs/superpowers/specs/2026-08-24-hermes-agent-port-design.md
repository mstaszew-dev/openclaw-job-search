# Job-Search Campaign Agent, Hermes Port - Design

Date: 2026-08-24
Status: approved (autonomous run per user instruction: investigate, plan, auto-execute, review)
Source: port of `campaign_agent/` (Python, 301 tests) to Hermes Agent v0.20.5.

## Problem

The campaign agent is a bespoke Python program that owns everything: the LLM loop
(msrouter), tool dispatch (exec, read, Playwright MCP, RAG MCP), tick/rotation
state, retries, and anti-gaming validation. Hermes already provides the agent
loop, inference (custom OpenAI-compatible providers), MCP attachment, sessions,
memory, and scheduling. Maintaining a custom loop duplicates the framework.

## Goal

Run the same campaign behavior (one job application per tick, IL-only targeting,
dedupe, tracker-only recording, anti-gaming submission validation) with Hermes
as the runtime. Delete nothing from `campaign_agent/` during the port; the new
implementation lives beside it and can replace it after a soak period.

## Non-goals

- No changes to the live campaign state (`tracker.json`, `events.jsonl`,
  `update_tracker.py`, campaign docs).
- No autonomous start of the real loop: the cron job is registered only with an
  explicit `--enable-cron` flag. Default install is inert until the user runs a
  tick manually or enables cron.
- No port of `seed_memory.py` (one-off Pinecone admin tool, still runnable from
  `campaign_agent/`).
- No port of token-budget rotation/truncation (Hermes owns context management
  and compression; the old 128k/60% math was a workaround for a hand-rolled
  loop).

## Approaches considered

1. **Pure Hermes cron prompt-mode**: a cron job runs the task prompt every 30
   minutes, no custom code. Rejected: cannot express the anti-gaming rule
   (a tick succeeds only when `stats.submitted` actually increased), per-attempt
   retry classification, or tick-context persistence deterministically.
2. **Profile + plugin tools + skill + script-mode cron driving a thin Python
   tick runner** (chosen): the runner keeps the deterministic outer loop from
   `main.py` (retries, tick context, tracker-delta validation) and delegates each
   attempt to `hermes -p jobhunter -z "<prompt>"`. Hermes owns the inner loop.
   Everything custom is small, offline-testable, and framework-free.
3. **Keep the Python agent, use Hermes only as an LLM gateway**: pointless;
   msrouter already is that gateway.

## Architecture

```
Hermes cron (script mode, no agent, every 30m, opt-in)
  └─ python -m jobhermes (tick runner, campaign workdir)
       ├─ Tracker before/after delta          ← anti-gaming validation
       ├─ TickContext load/save               ← cross-tick summary
       ├─ Prompt builder                      ← ported USER_PROMPT_TEMPLATE
       ├─ retry loop (classify, sleep, backoff)
       └─ subprocess: hermes -p jobhunter -z "<tick prompt>" --in <campaign_dir>
            └─ Hermes agent (profile jobhunter)
                 ├─ provider msrouter → http://127.0.0.1:8787/v1, model mst/free
                 ├─ MCP: playwright (CDP 127.0.0.1:9222), rag (rag_server.py)
                 ├─ plugin jobapps:
                 │    campaign_status     (read tracker.json → JSON)
                 │    record_submission   (exec update_tracker.py → JSON)
                 └─ skill /job-search-tick (procedure + campaign rules)
```

Hermes replaces: `llm.py` (inference), the inner `run_agent_turn` loop,
`playwright_mcp.py`/`rag_mcp.py` (MCP clients), session rotation. Ported:
`config.py` (subset), `prompt.py`, `tracker.py`, `session.py` (TickContext +
tick summary only), the outer-loop retry/validation policy from `main.py`.

## Components (new directory `hermes_agent/`)

| Path | Responsibility |
|---|---|
| `src/jobapps/plugin.yaml` | Plugin manifest: `name: jobapps`, provides `campaign_status`, `record_submission` |
| `src/jobapps/__init__.py` | `register(ctx)` wiring schemas to handlers |
| `src/jobapps/schemas.py` | OpenAI tool schemas for both tools |
| `src/jobapps/tools.py` | Handlers: JSON-in/JSON-out, never raise |
| `src/jobapps/tracker.py` | Crash-proof `Tracker` port (read side only) |
| `src/jobhermes/config.py` | Runner config from env + director-overrides.env |
| `src/jobhermes/prompt.py` | Tick prompt builder (ported template) |
| `src/jobhermes/tick_context.py` | `TickContext` + `build_tick_summary` port |
| `src/jobhermes/runner.py` | Outer loop; invokes hermes one-shot; validates delta |
| `src/jobhermes/__main__.py` | CLI: `--once` (default) / `--loop`, `--dry-run` |
| `skills/job-search-tick/SKILL.md` | Skill: When to Use / Procedure / Pitfalls / Verification |
| `install/profile-soul.md` | Persona for the jobhunter profile |
| `install/config.template.yaml` | Provider (msrouter), model, MCP servers, max_turns |
| `install/install.sh` | Create profile, symlink plugin+skill, verify, optional cron |
| `tests/` | Offline pytest suite (fake HERMES_HOME, fake hermes binary) |
| `pyproject.toml` | pytest config, `--cov-fail-under=90` |
| `README.md` | Run/install/enable instructions |

Runtime code is stdlib-only. Dev deps: pytest, pytest-asyncio (not needed if no
async), pytest-cov, pyyaml (config template assertions only).

## Key decisions

1. **Anti-gaming port**: the runner snapshots `Tracker.submitted` before each
   attempt and reloads after; an attempt succeeds iff the count increased. This
   is the observable equivalent of the old rule (exec `update_tracker.py
   submitted` with `exit=0`), because `stats.submitted` only increments when
   `submission_validator` accepts the evidence; evidence-less records become
   `attempted` and do not count.
2. **record_submission tool**: wraps
   `python3 update_tracker.py submitted '<json>'` with cwd = campaign dir and
   returns `{"ok", "exit", "stdout", "stderr"}`. The recorder keeps its own
   validation; the tool stays thin and never raises.
3. **Prompt port**: `USER_PROMPT_TEMPLATE` is carried over verbatim except:
   drop the `TOKEN BUDGET` section (Hermes owns context); replace the "Record
   submissions ONLY via exec: update_tracker.py" rule with the
   `record_submission` tool as the only recording path; keep IL-only targeting,
   dedupe, browser discipline, one-job-per-tick, stop conditions verbatim.
   Policy regression tests pin the IL-only markers and forbid EU/PL/salary-floor
   markers in both the prompt and the skill.
4. **Retry defaults differ from campaign_agent** (rationale: Hermes retries
   inference internally, so runner-level attempts are full sessions, not single
   API calls): `INNER_MAX_FAILS=5` (was 200), `INNER_SLEEP=10` (was 4),
   `OUTER_BACKOFF=60`, `RUN_BUDGET_SECONDS=1800`, `MAX_TURNS=200`. All
   env-overridable, documented.
5. **Attempt failure classification** (port of `classify_failure`, simplified to
   the hermes surface): exit 0 with no tracker delta → `no_submission` (retry);
   exit 1/2 or missing/empty final response → `transient` (retry); runner-level
   subprocess timeout → `timeout` (retry). All kinds count against
   `INNER_MAX_FAILS`; no fatal class (bad model config surfaces as repeated
   transient and stops after max fails, logged loudly).
6. **Isolation via profile `jobhunter`**: plugin and skill are installed only
   into `~/.hermes/profiles/jobhunter/` (symlinked to this repo, single source
   of truth). The default `~/.hermes` home is untouched.
7. **Sessions**: each attempt is a fresh `hermes -z` one-shot (exit codes 0/1/2,
   final text only, auto-bypassed approvals - mirrors the old clean-turn
   behavior). Cross-attempt continuity is carried by TickContext + tracker, not
   by Hermes session resume.

## Data flow per tick

1. Load config; `Tracker.reload()`; if `submitted >= target` → exit 0 (done).
2. Load previous tick summary; build prompt (skip list + director note included).
3. For up to `INNER_MAX_FAILS` attempts: snapshot submitted → run hermes
   one-shot (run-budget ceiling) → reload tracker → success iff delta ≥ 1.
   Sleep `INNER_SLEEP` between attempts.
4. Save tick summary (last 3 submissions, attempts used, outcome) to
   `state/tick-context.md` (same file the Python agent used, so a mixed
   rollout keeps continuity).
5. `--loop`: sleep `OUTER_BACKOFF` and repeat; `--once` (default): exit
   (exit code 0 on success or campaign complete, 1 otherwise).

## Error handling

- `Tracker` read failures degrade to empty state (never crash the tick), same
  as the Python port.
- TickContext save failures are logged, not fatal.
- hermes subprocess: non-zero exit, empty stdout, and timeout all produce
  classified attempt failures with captured stdout/stderr tails (last 2000
  chars) in logs.
- Plugin handlers catch everything and return `{"error": ...}` JSON.

## Testing strategy

- TDD throughout (red-green-refactor, small commits on branch
  `hermes-agent-port`).
- Offline only: fake `hermes` shell stub (records argv, scripted exit codes and
  stdout), tmp-path trackers, monkeypatched env; no network, no Chrome, no
  writes to the live campaign dir.
- Coverage gate: `--cov-fail-under=90` on `src/` in pyproject addopts.
- Suites: tracker, plugin registration + handlers (incl. error paths), config
  precedence, prompt content, tick context round-trip, runner loop (success,
  no-submission retry, exhaustion, campaign-complete short-circuit, subprocess
  timeout/exit codes, loop mode), policy regression (IL-only pins, forbidden
  EU/PL/salary-floor markers, prompt+skill), skill frontmatter/structure
  validation, config template sanity (yaml parse, required keys).
- Smoke (manual, not in pytest): `install.sh`, `hermes plugins doctor`,
  `jobhunter-hermes --dry-run` prompt preview.

## Safety

- Install never mutates the default Hermes home, the campaign dir, or tracker
  state.
- Cron registration is opt-in (`--enable-cron`), and the tick runner refuses to
  start when `campaign_dir` does not exist.
- No `.only`/skip in tests; coverage gate enforced on every pytest run.
