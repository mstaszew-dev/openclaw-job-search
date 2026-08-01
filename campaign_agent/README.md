# campaign_agent

Python rewrite of the job-search campaign agent (replaces `run-one-job`).
Owns the full loop: LLM inference via msrouter, tool dispatch (Playwright MCP,
RAG MCP, exec), tick/rotation/state management, retry logic.

## Run

```zsh
cd campaign_agent && PYTHONPATH=src .venv/bin/python -m campaign_agent.main
```

or via the supervised launcher (what the Director uses):

```zsh
/Users/mst/bin/job-search-agent    # symlink -> ../campaign-agent
```

## Supervision wiring (important)

- The Director (msrouter `src/director/`) supervises the campaign every
  `DIRECTOR_INTERVAL_MINUTES` (5): pgrep for `job-search-agent`, and if absent,
  starts `cd <workspace> && job-search-agent` in a new iTerm tab.
- `/Users/mst/bin/job-search-agent` -> `openclaw-job-search/campaign-agent`
  (zsh launcher). The launcher stays alive as the zsh parent (cmdline
  `/bin/zsh .../job-search-agent`) so pgrep supervision matches, and runs the
  python agent as its child. `run-one-job` remains as fallback (unused).
- `exec -a` argv0 tricks do NOT survive on macOS for python binaries (ps shows
  the resolved framework path), so do not rely on them for detection; the
  parent-watchdog form above is what works.
- One campaign runner only: if the Director ever spawns a second instance,
  kill the extra zsh + its python/MCP children immediately (double agents
  fight over the same Chrome CDP and tracker).

## Runtime behavior notes

- msrouter free chain (`mst/free`) under OpenRouter 429 walls: each provider
  hop can hang up to 120s (UPSTREAM_TIMEOUT_MS), so a single chat call can
  take 5-10 min. The LLMClient timeout is wired from `config.timeout_seconds`
  (600) so it outlasts the chain walk; SDK retries + llm.py retries stack on
  top. Slow is not hung - watch msrouter logs for chain demotions.
- A tick only counts as success when the agent actually ran
  `update_tracker.py submitted` with exit=0 (anti-gaming: content alone, e.g.
  "done" or "TICK_COMPLETE", is `no_submission` and triggers a fresh retry).
- The exec tool defaults cwd to the campaign dir
  (`/Users/mst/Downloads/job-search/job-apply`); `read` resolves relative
  paths against it. The agent never needs `/root/...` style paths.

## Tests

```zsh
.venv/bin/python -m pytest            # 158 tests
.venv/bin/python -m pytest --cov=campaign_agent --cov-report=term-missing
```
