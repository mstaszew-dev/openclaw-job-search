# openclaw-job-search

Autonomous job-application campaign agent. Python agent that applies to jobs
through a browser (Chrome CDP via Playwright MCP), using msrouter as the LLM
gateway and a RAG server for deduplication.

## Structure

```
campaign_agent/     Python campaign agent (the current agent)
campaign-agent      zsh launcher (supervised by the Director via pgrep)
rag/                RAG MCP server (semantic search for dedup)
archive/            Legacy code and intermediate artifacts (run-one-job, CDP scripts, etc.)
```

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
