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

- msrouter free chain (`mst/free`) under OpenRouter 429 walls: each remote
  provider hop hangs at most UPSTREAM_TIMEOUT_MS (60s) before the walk
  deadline (WALK_DEADLINE_MS) skips the rest, so a single chat call usually
  takes seconds-to-minutes. The LLMClient timeout is wired from
  `config.timeout_seconds` (2400s: walk deadline 300 + LM Studio first try
  300 + laptop tail 1800) so it outlasts even a full failover onto the
  30-min laptop tail. Slow is not hung - watch msrouter logs for demotions.
- A tick only counts as success when the agent actually ran
  `update_tracker.py submitted` with exit=0 (anti-gaming: content alone, e.g.
  "done" or "TICK_COMPLETE", is `no_submission` and triggers a fresh retry).
- The exec tool defaults cwd to the campaign dir
  (`/Users/mst/Downloads/job-search/job-apply`); `read` resolves relative
  paths against it. The agent never needs `/root/...` style paths.

## Laptop tail (verified 2026-10-02)

The last-resort provider is the travelmate laptop over Tailscale
(`mstro-travelmate-p215-52.taila0a683.ts.net` -> nginx :443 -> `llm-gateway`
on 127.0.0.1:11436 -> ollama on 127.0.0.1:11434). Facts read from the laptop
itself, not assumed:

- The model ollama serves under the legacy name **`qwen35-2b-64k`** is
  actually **Qwen3.5-4B Q4_K_M** (4.2B params). The "2b" in the name is
  wrong; the "64k" is right - it is the GGUF's trained window.
- **LFM2 compaction IS on**, in a Go gateway (`llm-gateway.service`,
  `/usr/local/bin/llm-gateway`, source package `llmgw/compact`) that sits in
  front of ollama. It summarises with **LFM2-350M**
  (`/opt/llm/models/LFM2-350M-Q4_K_M.gguf`). Startup line from its journal:
  `GATEWAY compaction ready model=...LFM2-350M-Q4_K_M.gguf ctx=19968
  chunk=16384 yarn=false chat=true threads=4`, and live requests show
  `compact=12117->1989tok passes=1`, `compact=25635->2361tok`. Requests of
  ~220KB have been served; everything is reduced to roughly 700-2400 tokens
  before inference.
- ollama itself is SERVED at `PARAMETER num_ctx 8192` (`/api/ps`
  context_length: 8192). That bounds what the gateway hands ollama, **not**
  what we may send, because the compaction runs first. Nothing overrides
  num_ctx to 32K, and nothing needs to.
- `max_context_tokens` is **61440** - the GGUF's trained 64K window minus
  generation headroom, and the same number as msrouter's laptop
  `maxPromptTokens`, which hard-refuses anything above it with
  `BAD_REQUEST`. It used to be 100000, justified by a comment claiming a
  131072-token laptop window that never existed. The two repos must move
  together; the pairing is pinned by a test.
- Trade-off worth knowing: the compaction target is ~2000 tokens, so the
  model rarely sees more than that regardless of what the agent sends.
  Raising `LLM_COMPACT_TARGET` on the laptop is the lever for more usable
  context, not the agent-side cap.

## Tests

```zsh
.venv/bin/python -m pytest            # 401 tests (2026-10-02)
.venv/bin/python -m pytest --cov=campaign_agent --cov-report=term-missing
```

## Trust boundary (explicit)

The agent's `exec` tool runs LLM-authored shell commands (`shell=True`, 300s
cap, process-group kill, cwd pinned to the campaign dir) and `read` resolves
arbitrary absolute paths - no allowlist. This is an accepted boundary for an
autonomous local agent on this machine; do not point it at directories you do
not want it to read. The venv is Python 3.14; CI runs 3.13.
