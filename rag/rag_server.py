#!/usr/bin/env python3
"""RAG MCP server for the job-search campaign.

Exposes two semantic-retrieval tools over the local vector index:
  - rag_search_apps(query, k=5): search past applications (semantic dedupe).
    "Have I applied to a role like this before?" Catches similar-but-not-
    identical titles/companies that exact-match dedupe misses.
  - rag_search_docs(query, k=3): search the markdown docs (PORTALS, IL_BOARDS,
    recruiter contacts, handover). Replaces re-reading the full docs each tick.

Run by OpenClaw as a stdio MCP server (see mcp.servers.rag in openclaw.json).
The index (rag/index.db) is built by index_builder.py; rebuild when the corpus
grows.

Direct test (no OpenClaw):
  echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | .venv/bin/python rag_server.py
"""
from __future__ import annotations

# Suppress the "leaked semaphore" warning from loky/joblib at shutdown
import warnings
warnings.filterwarnings("ignore", category=UserWarning, module="multiprocessing.resource_tracker")

import json
import os
import sqlite3
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

ROOT = Path(__file__).resolve().parent
# Campaign root, overridable for tests (RAG_CAMPAIGN); the tracker's app count
# is compared against the index's stamp to warn on staleness.
CAMPAIGN = Path(
    os.environ.get("RAG_CAMPAIGN", "/Users/mst/Downloads/job-search/job-apply")
)
STALE_APP_GAP = 20  # warn when the tracker gained this many apps since build
STALE_DAYS = 7  # ...or when the index is this many days old
DB = ROOT / "index.db"
MODEL = "minishlab/potion-base-8M"

# ---------------------------------------------------------------------------
# Logging: write to RAG_LOG file if set (robust - survives MCP stderr being
# dropped by the parent process), else stderr. One line per event, never raises.
# ---------------------------------------------------------------------------


def _log_sink():
    """Return (file_handle, label) for the log destination. Opens lazily."""
    path = os.environ.get("RAG_LOG")
    if path:
        try:
            # Append mode; the file may already exist from a prior server spawn.
            return open(path, "a", encoding="utf-8"), f"file:{path}"
        except OSError:
            pass  # fall through to stderr if the path isn't writable
    return sys.stderr, "stderr"


_SINK_FH = None
_SINK_LABEL = None


def _log(msg: str) -> None:
    """Write one medium-verbosity log line. Never raises (logging must not
    break the MCP server). Format: [rag] <iso-ts> <msg>."""
    global _SINK_FH, _SINK_LABEL
    try:
        if _SINK_FH is None:
            _SINK_FH, _SINK_LABEL = _log_sink()
        ts = datetime.now().isoformat(timespec="seconds")
        _SINK_FH.write(f"[rag] {ts} {msg}\n")
        _SINK_FH.flush()
    except Exception:
        # Last resort: swallow. Logging is best-effort.
        pass

# ---------------------------------------------------------------------------
# Pure search logic (no module-level state) - testable without monkeypatching.
# ---------------------------------------------------------------------------


def cosine_search(
    query_vec: np.ndarray,
    matrix: np.ndarray,
    rows: list[dict],
    collection: str,
    k: int,
) -> list[dict]:
    """Cosine-similarity search within one collection. Pure function.

    Args:
      query_vec: the query embedding (1D array).
      matrix: (N, D) array of chunk embeddings.
      rows: list of {collection, source, chunk, meta} dicts, aligned with matrix.
      collection: 'apps' | 'docs' - restricts the search.
      k: number of top hits to return.

    Returns top-k hits as [{score, meta, chunk}], descending by score.
    Returns [] if query is a zero vector or no matches in the collection.
    """
    if matrix.size == 0 or len(rows) == 0:
        return []
    qv = query_vec.astype(np.float32)
    q_norm = float(np.linalg.norm(qv))
    if q_norm == 0:
        return []
    qv = qv / q_norm
    row_norms = np.linalg.norm(matrix, axis=1)
    safe = np.where(row_norms == 0, 1.0, row_norms)
    normed = matrix / safe[:, None]
    sims = normed @ qv
    mask = np.array([r["collection"] == collection for r in rows])
    sims_masked = np.where(mask, sims, -np.inf)
    top_idx = np.argsort(sims_masked)[-k:][::-1]
    hits = []
    for i in top_idx:
        if sims_masked[i] == -np.inf:
            continue
        r = rows[int(i)]
        hits.append({"score": round(float(sims_masked[i]), 3), "meta": r["meta"], "chunk": r["chunk"]})
    return hits


def load_index(db_path: Path) -> tuple[np.ndarray, list[dict]]:
    """Load the matrix + rows from a built index.db. Returns (matrix, rows)."""
    conn = sqlite3.connect(db_path)
    try:
        cur = conn.execute(
            "SELECT collection, source, chunk, meta_json, vector_json FROM chunks ORDER BY id"
        )
        cols = cur.fetchall()
    finally:
        conn.close()
    rows = [
        {"collection": c, "source": s, "chunk": ch, "meta": json.loads(mj)}
        for (c, s, ch, mj, _) in cols
    ]
    if not rows:
        return np.array([], dtype=np.float32).reshape(0, 0), []
    matrix = np.array([json.loads(vj) for (_, _, _, _, vj) in cols], dtype=np.float32)
    return matrix, rows


# ---------------------------------------------------------------------------
# Module-level state for the MCP server (loaded lazily on first query).
# ---------------------------------------------------------------------------

_model = None  # StaticModel | None
_matrix: np.ndarray | None = None
_rows: list[dict] | None = None


def _ensure_loaded() -> None:
    """Load the model + index into memory on first use (warm thereafter)."""
    global _model, _matrix, _rows
    if _model is not None:
        return
    if not DB.exists():
        raise RuntimeError(
            f"index not found at {DB}; run index_builder.py first "
            f"(.venv/bin/python {ROOT}/index_builder.py)"
        )
    from model2vec import StaticModel  # imported lazily so tests can skip it

    _model = StaticModel.from_pretrained(MODEL)
    _matrix, _rows = load_index(DB)
    # Log load (the sink label is reported implicitly by where the line lands).
    _log(f"loaded {len(_rows)} chunks, dim {_matrix.shape[1]}")
    _warn_if_stale()


def _warn_if_stale() -> None:
    """Warn (never block) when the index predates the tracker: applications
    submitted after the last rebuild are invisible to dedupe search."""
    try:
        conn = sqlite3.connect(DB)
        try:
            meta = dict(
                conn.execute("SELECT key, value FROM _meta").fetchall()
            )
        finally:
            conn.close()
        built_at = datetime.fromisoformat(meta["built_at"])
        indexed_apps = int(meta["n_apps"])
    except (FileNotFoundError, sqlite3.OperationalError, KeyError, ValueError):
        return  # old index without a stamp; nothing to compare
    try:
        tracker = json.loads((CAMPAIGN / "tracker.json").read_text())
        live_apps = len(tracker.get("applications", []))
    except (OSError, json.JSONDecodeError):
        return

    age_days = (datetime.now(timezone.utc) - built_at).total_seconds() / 86400
    gap = live_apps - indexed_apps
    if gap >= STALE_APP_GAP or age_days >= STALE_DAYS:
        _log(
            f"STALE index: built {built_at:%Y-%m-%d} with {indexed_apps} apps, "
            f"tracker now has {live_apps} (+{gap}); rebuild soon: "
            f".venv/bin/python {ROOT}/index_builder.py"
        )


def _search(query: str, collection: str, k: int) -> list[dict]:
    """Module-level wrapper: loads state if needed, then calls cosine_search."""
    _ensure_loaded()
    assert _model is not None and _matrix is not None and _rows is not None
    qv = _model.encode([query])[0]
    return cosine_search(qv, _matrix, _rows, collection, k)


def _format_apps(hits: list[dict]) -> str:
    if not hits:
        return "No similar past applications found."
    lines = [f"Top {len(hits)} similar past applications:"]
    for h in hits:
        m = h["meta"]
        lines.append(
            f"  - score {h['score']}: {m.get('roleTitle')} @ {m.get('company')} "
            f"({m.get('source')}, {m.get('appliedAt', '?')[:10]}, {m.get('status')})"
        )
    return "\n".join(lines)


def _format_docs(hits: list[dict]) -> str:
    if not hits:
        return "No matching doc chunks found."
    lines = [f"Top {len(hits)} matching doc chunks:"]
    for h in hits:
        m = h["meta"]
        lines.append(f"  - score {h['score']} [{m.get('file')} > {m.get('header')}]:")
        # Truncate long chunks so the agent context stays bounded.
        body = h["chunk"][:500]
        lines.append("    " + body.replace("\n", "\n    "))
    return "\n".join(lines)


server = Server("rag")


@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="rag_search_apps",
            description=(
                "Semantic search over past job applications (the campaign tracker). "
                "Use this for SEMANTIC DEDUPE: 'have I applied to a role/company like "
                "this before?' Catches similar-but-not-identical titles (e.g. 'Senior "
                "Java Engineer' ~ 'Lead JVM Developer') that exact-match dedupe misses. "
                "Run before applying to any candidate."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The candidate text to check: role title + company + stack.",
                    },
                    "k": {
                        "type": "integer",
                        "default": 5,
                        "minimum": 1,
                        "maximum": 20,
                        "description": "Number of similar past applications to return.",
                    },
                },
                "required": ["query"],
            },
        ),
        Tool(
            name="rag_search_docs",
            description=(
                "Semantic search over the campaign markdown docs (PORTALS, IL_BOARDS, "
                "recruiter contacts, HANDOVER_SUMMARY, CONTEXT, SCHEMA). Use this INSTEAD "
                "of re-reading the full docs each tick: ask for the relevant portal, "
                "recruiter, or convention and get just the matching section."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "What you need: e.g. 'IL hybrid portal', 'Poland B2B note', 'dedupe rules'.",
                    },
                    "k": {
                        "type": "integer",
                        "default": 3,
                        "minimum": 1,
                        "maximum": 10,
                        "description": "Number of matching doc chunks to return.",
                    },
                },
                "required": ["query"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:
    query = arguments.get("query", "")
    k = int(arguments.get("k", 5))
    if not query.strip():
        _log(f"call {name} rejected: empty query")
        return [TextContent(type="text", text="Error: query is required.")]
    t0 = time.monotonic()
    try:
        if name == "rag_search_apps":
            hits = _search(query, "apps", k)
            _log_call(name, query, k, hits, t0)
            return [TextContent(type="text", text=_format_apps(hits))]
        if name == "rag_search_docs":
            hits = _search(query, "docs", k)
            _log_call(name, query, k, hits, t0)
            return [TextContent(type="text", text=_format_docs(hits))]
        _log(f"call {name} rejected: unknown tool")
        return [TextContent(type="text", text=f"Error: unknown tool {name}")]
    except Exception as e:  # noqa: BLE001 - surface any error to the agent
        _log(f"error {name} query={query[:60]!r}: {e}")
        return [TextContent(type="text", text=f"Error: {e}")]


def _log_call(name: str, query: str, k: int, hits: list[dict], t0: float) -> None:
    """Emit one medium-verbosity line per tool call: query (truncated), hit
    count, top score, and elapsed ms. Enough to see the agent using RAG and
    whether retrievals are relevant, without per-chunk noise."""
    elapsed_ms = int((time.monotonic() - t0) * 1000)
    top = hits[0]["score"] if hits else "n/a"
    q = query.replace("\n", " ")[:60]
    _log(f"call {name} query={q!r} k={k} -> {len(hits)} hits (top_score={top}, t={elapsed_ms}ms)")


async def main() -> None:
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())


def _hit_to_text(h: dict, collection: str) -> str:
    """One-line human-readable rendering of a single hit, shared by the MCP
    formatters and the one-shot CLI so rendering stays in one place."""
    m = h.get("meta", {})
    if collection == "apps":
        return (
            f"score {h.get('score')}: {m.get('roleTitle')} @ {m.get('company')} "
            f"({m.get('source')}, {str(m.get('appliedAt', '?'))[:10]}, {m.get('status')})"
        )
    return f"score {h.get('score')} [{m.get('file')} > {m.get('header')}]: {h.get('chunk', '')[:500]}"


if __name__ == "__main__":
    import argparse
    import asyncio

    parser = argparse.ArgumentParser(
        description="Campaign RAG server (MCP stdio, or one-shot CLI)."
    )
    parser.add_argument(
        "--query",
        type=str,
        help="One-shot query mode: process this query and exit. "
        "Requires --tool to specify the tool to call.",
    )
    parser.add_argument(
        "--tool",
        type=str,
        choices=["rag_search_apps", "rag_search_docs"],
        help="Tool to call in one-shot mode.",
    )
    parser.add_argument("--k", type=int, default=5, help="Number of results (default: 5).")
    parser.add_argument(
        "--db",
        type=Path,
        default=DB,
        help=f"SQLite index db path (default: {DB}).",
    )
    parser.add_argument(
        "--campaign",
        type=Path,
        default=Path("/Users/mst/Downloads/job-search/job-apply"),
        help="Campaign directory (informational; the db is already built).",
    )
    args = parser.parse_args()
    if args.query:
        # One-shot CLI mode (Director RagClient contract): load --db (module-
        # level assignment rebinds the global the loader reads), search, print
        # one JSON line, exit.
        DB = args.db
        _ensure_loaded()
        if not args.tool:
            print(json.dumps({"error": "--tool is required when using --query"}))
            sys.exit(1)
        collection = "apps" if args.tool == "rag_search_apps" else "docs"
        hits = _search(args.query, collection, args.k)
        result = [
            {"score": float(h["score"]), "text": _hit_to_text(h, collection)}
            for h in hits
        ]
        print(json.dumps({"result": result}))
    else:
        asyncio.run(main())
