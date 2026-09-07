# openclaw-job-search

Autonomous job-application campaign agent targeting the **Polish market only**
(PL-only since 2026-09-07; the IL and dual-region campaigns are retired).
Python agent that applies to jobs through a browser (Chrome CDP via Playwright
MCP), using msrouter as the LLM gateway and a RAG server for deduplication.

## Structure

```
campaign_agent/     Python campaign agent (the current and only agent)
campaign-agent      zsh launcher (supervised by the Director via pgrep)
rag/                RAG MCP server (semantic search for dedup)
archive/            Legacy code + tar.gz archives:
                    hermes-agent-20260907.tar.gz      (hermes agent, retired)
                    openclaw-legacy-20260907.tar.gz   (legacy agent + director snapshot)
                    agent-artifacts-2026-07-31.zip    (older artifacts)
```

The Hermes port was retired on 2026-09-07: its source lives in
`archive/hermes-agent-20260907.tar.gz` and its history in the
`hermes-agent-port` branch on origin. The Python agent is the only agent
kept for the future.

## Run

```zsh
# Start the agent (Director-supervised, auto-restarts in iTerm)
/Users/mst/bin/job-search-agent

# Or directly
cd campaign_agent && PYTHONPATH=src .venv/bin/python -m campaign_agent.main
```

See `campaign_agent/README.md` for supervision wiring and runtime notes.

## Tests

```zsh
cd campaign_agent && .venv/bin/python -m pytest --cov=campaign_agent
```
