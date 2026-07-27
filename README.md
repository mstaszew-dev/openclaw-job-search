# OpenClaw Job Search Agent

This workspace runs OpenClaw against the live job-search campaign without copying campaign state.

## Run one application

Keep the campaign Chrome running with CDP on port `9222`, then run:

```bash
job-search-agent
```

You may add an instruction for the same single-job run:

```bash
job-search-agent "Prefer an Israeli remote Java/Spring role this tick."
```

The launcher refuses to start if Chrome CDP is unavailable. The agent must follow `AGENTS.md`, the canonical campaign runbook, dedupe and scoring gates, and browser confirmation before updating the tracker.

## Operator commands

```bash
openclaw gateway status
openclaw models status
openclaw mcp probe playwright
openclaw dashboard
openclaw tui
```

## Live paths

- `campaign/` -> `/Users/mst/Downloads/job-search/job-apply`
- `joblooper/` -> `/Users/mst/ZCodeProject/joblooper`
- Playwright MCP -> existing Chrome at `http://127.0.0.1:9222`
- Model -> OpenRouter free router
- OpenRouter credential -> SecretRef into OpenCode's credential store; no duplicate API-key copy
