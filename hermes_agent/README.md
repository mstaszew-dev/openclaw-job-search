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
- `tests/` - offline pytest suite; coverage gate 90% enforced on every run
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

Exit codes: 0 success or campaign complete, 1 attempts exhausted,
2 campaign dir missing.

## Enable the 30-minute scheduler (starts REAL applications)

```zsh
zsh install/install.sh --enable-cron
```

## Configuration

Defaults < `~/.campaign-agent/director-overrides.env` < environment.
Env keys: `CAMPAIGN_DIR`, `HERMES_BIN`, `HERMES_PROFILE`,
`INNER_MAX_FAILS` (5), `INNER_SLEEP` (10), `OUTER_BACKOFF` (60),
`RUN_BUDGET_SECONDS` (1800), `MAX_TURNS` (200), `SUBPROCESS_TIMEOUT` (2400),
`PORTAL_SKIP_<Company>=1` (skip list). The director note is read from
`~/.campaign-agent/director-prompt-overrides.md`.

## Tests

```zsh
.venv/bin/python -m pytest
```

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
| Director supervision | Hermes cron (`--enable-cron`, script mode) |

Spec: `docs/superpowers/specs/2026-08-24-hermes-agent-port-design.md`.
Plan: `docs/superpowers/plans/2026-08-24-hermes-agent-port.md`.
