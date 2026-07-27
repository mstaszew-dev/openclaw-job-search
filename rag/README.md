# RAG server for the job-search campaign

Local semantic retrieval over the campaign corpus, exposed to the OpenClaw agent
as two MCP tools. No torch, no external vector DB, no API calls at query time.

## What it does

- **`rag_search_apps(query, k=5)`** - semantic search over the 1060 past
  applications in `tracker.json`. Use for **semantic dedupe**: "have I applied
  to a role/company like this before?" Catches similar-but-not-identical titles
  ("Senior Java Engineer" ~ "Lead JVM Developer") that exact-match dedupe misses.
- **`rag_search_docs(query, k=3)`** - semantic search over the markdown docs
  (PORTALS, IL_BOARDS, recruiter contacts, HANDOVER). Replaces re-reading the
  full docs each tick; returns just the matching section.

## How it works

- **Embeddings**: `model2vec` (`minishlab/potion-base-8M`, 256-dim, ~30MB, pure
  Python + numpy, NO torch). Static embedding lookup - fast, local, no GPU.
- **Storage**: SQLite (`index.db`) with vectors as JSON blobs; cosine similarity
  computed in numpy at query time (1116 rows = sub-ms).
- **Integration**: stdio MCP server registered in `~/.openclaw/openclaw.json`
  alongside playwright. The agent calls it like any other tool.

## Setup (one-time)

```bash
cd ~/ZCodeProject/openclaw-job-search/rag
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python index_builder.py     # builds index.db from tracker.json + docs
```

## Rebuild the index (when the corpus grows)

Run this after new applications land or docs are edited:

```bash
cd ~/ZCodeProject/openclaw-job-search/rag
.venv/bin/python index_builder.py     # idempotent: drops + recreates index.db
```

The index is NOT auto-updated per application (by design - keeps it simple).
Re-run the builder periodically, or after a batch of submissions.

## Files

- `index_builder.py` - reads `tracker.json` (apps) + 9 MD docs (chunked by `##`
  header), embeds, writes `index.db`. Idempotent.
- `rag_server.py` - the MCP server (stdio). Loads model + index lazily on first
  query, then serves from memory.
- `index.db` - the built index (SQLite, ~7MB, 1116 rows). Gitignore this; it's
  rebuildable from the corpus.
- `requirements.txt` - `model2vec`, `mcp`, `numpy` (pinned).
- `.venv/` - the isolated Python env. Gitignore this.

## Quality notes

- The `app_text()` builder upweights role/company/stack tokens (repeats them)
  so they dominate the embedding over low-signal tokens (portal name, salary
  numbers). This meaningfully improved retrieval relevance.
- Score > 0.85 + `appliedAt` within 60d = strong duplicate signal. The agent is
  instructed to skip in that case. Below 0.85, treat as "similar but distinct".
- The model is small (8M params). It distinguishes stacks well (Java vs Node vs
  PHP) but won't catch every paraphrase. Exact-match dedupe (`check_dupe.py`)
  still runs as the primary gate; RAG is the semantic backstop.

## Rollback

Remove the `rag` entry from `mcp.servers` in `~/.openclaw/openclaw.json` and
restart the daemon (`openclaw daemon restart`). The campaign reverts to its
prior doc-re-reading + exact-match-dedupe behavior. `rm -rf rag/` to remove
everything.
