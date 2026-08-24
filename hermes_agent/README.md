# Hermes job-search campaign agent

Hermes-native replacement for `campaign_agent/`: the agent loop, inference
(msrouter), and MCP (playwright CDP + rag) are provided by Hermes; this
package adds the campaign domain.

## Layout

- `src/jobapps/` - Hermes plugin: `campaign_status`, `record_submission`.
- `src/jobhermes/` - tick runner: config, prompt, tick context, retry loop,
  anti-gaming validation (a tick succeeds only when tracker.submitted grew).
- `skills/job-search-tick/` - tick procedure + targeting policy skill.
- `install/` - jobhunter profile installer (plugin + skill + provider
  config); cron registration is opt-in via `--enable-cron`.
- `tests/` - offline pytest suite; coverage gate 98% (line+branch) enforced on every run
  (currently 100%).

## Install (no cron, no live side effects)

```zsh
zsh install/install.sh
```

Creates the `jobhunter` Hermes profile, symlinks the plugin and skill into
it, writes the managed `config.yaml` (msrouter provider, playwright + rag
MCP servers), and validates the plugin with `hermes plugins doctor`.

## Manual tick

```zsh
PYTHONPATH=src python3 -m jobhermes --once      # one tick (runs REAL agent)
PYTHONPATH=src python3 -m jobhermes --dry-run   # print the tick prompt only
PYTHONPATH=src python3 -m jobhermes --loop      # keep ticking with backoff
```

`--once` is the default mode (flag optional). Exit codes: 0 success or
campaign complete, 1 attempts exhausted / loop failure bound / hermes exit
126-127 / tracker unreadable, 2 campaign dir missing, 3 another jobhermes
instance holds the tick lock. A corrupt tracker at tick start refuses to
launch hermes at all (no oracle, no applications). `--loop` stops after
`OUTER_MAX_FAILS` consecutive failed ticks, and a lockfile
(`state/jobhermes.lock`) prevents cron and the supervised launcher from
ticking simultaneously.

## Enable the 30-minute scheduler (starts REAL applications)

```zsh
zsh install/install.sh --enable-cron
```

The installer writes a wrapper to `~/.hermes/scripts/job-search-tick.sh`
(hermes cron `--script` takes a script path) and registers the job with
`--no-agent` so the tick runner owns validation and retries.

## Continuous mode + supervision (Director-style)

`install/job-search-agent-hermes` mirrors the Python agent's supervised
launcher contract: it stays alive as a zsh parent running
`python -m jobhermes --loop`, so a pgrep-based supervisor can watch it.
Point the Director at this script instead of `/Users/mst/bin/job-search-agent`
to cut over. Starting it begins REAL applications.

## Cutover from campaign_agent

- The runner inherits the Python agent's last tick summary on the first run
  (falls back to `campaign_agent/state/tick-context.md`; override with
  `LEGACY_TICK_CONTEXT_PATH`). Hermes-owned state then takes over at
  `hermes_agent/state/tick-context.md`.
- The managed profile config ships `plugins.enabled: [jobapps]`. Without that
  block the plugin loads but its tools never register - keep it if you edit
  the template.
- `campaign_agent/` remains untouched as fallback during the soak period;
  only one of the two agents may run at a time (they share Chrome CDP and
  tracker).

## Configuration

Defaults < `~/.campaign-agent/director-overrides.env` < environment.
Env keys: `CAMPAIGN_DIR`, `HERMES_BIN`, `HERMES_PROFILE`,
`INNER_MAX_FAILS` (5), `INNER_SLEEP` (10), `OUTER_BACKOFF` (60),
`OUTER_MAX_FAILS` (12), `RUN_BUDGET_SECONDS` (1800), `MAX_TURNS` (200),
`SUBPROCESS_TIMEOUT` (2400), `PORTAL_SKIP_<Company>=1` (skip list). The
director note is read from `~/.campaign-agent/director-prompt-overrides.md`.

The jobapps plugin honors `JOBSEARCH_CAMPAIGN_DIR` and
`JOBSEARCH_TRACKER_PATH` (separate from the runner's `CAMPAIGN_DIR`). Its
`tracker_path`/`campaign_dir` tool arguments are ignored unless
`JOBSEARCH_ALLOW_OVERRIDES=1` is set (prompt-injection guard; tests set it).

CI (GitHub Actions, `.github/workflows/hermes-agent-ci.yml`): ruff + mypy
strict + pytest with the 98% line+branch coverage gate on every push/PR touching
`hermes_agent/`.

## Tests

```zsh
python3 -m venv .venv
.venv/bin/pip install -e ".[dev]"
.venv/bin/ruff check src tests && .venv/bin/mypy && .venv/bin/python -m pytest
```

The `-m jobhermes` subprocess test requires the package installed (the pip
install above), since pytest's `pythonpath=src` does not reach subprocesses.

## Old-to-new mapping

| campaign_agent (Python loop) | hermes_agent |
|---|---|
| `llm.py` (msrouter client) | Hermes provider `msrouter` (profile config) |
| `run_agent_turn` inner loop | Hermes agent (`hermes -z` one-shot) |
| `playwright_mcp.py` / `rag_mcp.py` | profile `mcp_servers` (playwright, rag) |
| `main.py` outer loop + anti-gaming | `jobhermes.runner.run_tick` (tracker-delta rule) |
| `prompt.py` | `jobhermes.prompt` (IL-only policy pinned by tests) |
| `session.py` TickContext | `jobhermes.tick_context` (atomic writes) |
| `tracker.py` | `jobapps.tracker` |
| exec `update_tracker.py submitted` | `jobapps` tool `record_submission` |
| Director supervision | unchanged (pgrep on the launcher) or Hermes cron (`--enable-cron`); the tick lock keeps them exclusive |

Spec: `docs/superpowers/specs/2026-08-24-hermes-agent-port-design.md`.
Plan: `docs/superpowers/plans/2026-08-24-hermes-agent-port.md`.
