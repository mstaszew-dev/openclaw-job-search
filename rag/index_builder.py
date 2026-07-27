#!/usr/bin/env python3
"""Build the RAG vector index from the campaign corpus.

Two collections are indexed into a single SQLite db (rag/index.db):
  - 'apps': one row per past application (tracker.json -> applications[]).
            Text = company + roleTitle + source + salary. Powers semantic
            dedupe ("have I applied to a role like this before?").
  - 'docs': markdown docs chunked by '##' header. Replaces per-tick
            re-reading of PORTALS/IL_BOARDS/recruiter-contacts/etc.

Idempotent: drops + recreates the chunks table on every run. Re-run whenever
the corpus grows (new applications, edited docs).

Usage:
  .venv/bin/python index_builder.py            # build into rag/index.db
  .venv/bin/python index_builder.py --check    # report counts without writing
"""
from __future__ import annotations

import json
import re
import sqlite3
import sys
from pathlib import Path

from model2vec import StaticModel

ROOT = Path(__file__).resolve().parent
CAMPAIGN = Path("/Users/mst/Downloads/job-search/job-apply")
TRACKER = CAMPAIGN / "tracker.json"
DB = ROOT / "index.db"
MODEL = "minishlab/potion-base-8M"

# The docs to index (relative to CAMPAIGN). Chunked by '##' header.
# NOTE: recruiter-contacts-* are intentionally NOT here - those live in .txt
# files and recruiter contacts are kept out of RAG by design.
DOCS = [
    "CONTEXT.md",
    "AGENT_TICK.md",
    "PORTALS.md",
    "IL_BOARDS.md",
    "SCHEMA.md",
    "HANDOVER_SUMMARY.md",
]


def app_text(a: dict) -> str:
    """Build the retrievable text for one application row.

    The text emphasizes role-relevant tokens (company, roleTitle, stack) and
    repeats them to upweight their influence on the embedding, while including
    source/salary only once (low signal-to-noise). This matters because small
    static embedding models weight by token frequency; salary numbers and portal
    names otherwise dominate.
    """
    company = str(a.get("company", "")).strip()
    role = str(a.get("roleTitle", "")).strip()
    # Skip the framing when both are empty (avoids a junk " at " chunk that
    # would pollute the index with meaningless tokens).
    head = f"{role} at {company}" if (role or company) else ""
    parts = [head, role, company] if (role or company) else []
    stack = a.get("stack")
    if isinstance(stack, list) and stack:
        parts.append(" ".join(str(s) for s in stack))
        parts.append(" ".join(str(s) for s in stack))  # upweight stack
    elif stack:
        parts.append(str(stack))
    # Include source + salary once (low signal, but useful for "did I apply via X?").
    parts.append(str(a.get("source", "")))
    sal = a.get("salarySeen")
    if isinstance(sal, dict):
        parts.append(f"{sal.get('currency', '')}")
    return " ".join(p for p in parts if p).strip()


def app_meta(a: dict) -> dict:
    """Lightweight metadata returned with each app hit (not embedded)."""
    return {
        "id": a.get("id"),
        "company": a.get("company"),
        "roleTitle": a.get("roleTitle"),
        "source": a.get("source"),
        "appliedAt": a.get("appliedAt"),
        "status": a.get("status"),
    }


def chunk_doc(text: str) -> list[tuple[str, str]]:
    """Split a markdown doc into (header, body) chunks by '##' headers.

    Returns a list of (title, chunk_text). The chunk_text includes the header
    line so the embedding captures the topic. Content before the first '##'
    becomes an '(intro)' chunk if non-empty.
    """
    lines = text.splitlines()
    chunks: list[tuple[str, str]] = []
    current_title = "(intro)"
    current_body: list[str] = []
    for line in lines:
        if line.startswith("## "):
            if current_body:
                chunks.append((current_title, "\n".join(current_body).strip()))
            current_title = line[3:].strip()
            current_body = [line]
        else:
            current_body.append(line)
    if current_body:
        chunks.append((current_title, "\n".join(current_body).strip()))
    # Drop chunks that have no body beyond their own header line (or are empty).
    # A section like "## A\n\n## B\nContent" leaves A with only the "## A" line.
    def _has_content(title: str, body: str) -> bool:
        if not body.strip():
            return False
        # For named sections, the body always starts with the "## <title>" line;
        # a real section has at least one more non-empty line.
        lines_body = [ln for ln in body.splitlines() if ln.strip()]
        if title == "(intro)":
            return len(lines_body) >= 1
        # Named section: need more than just the header line.
        return len(lines_body) >= 2

    return [(t, b) for t, b in chunks if _has_content(t, b)]


def collect_corpus(campaign: Path = CAMPAIGN) -> list[dict]:
    """Return all rows to index: [{collection, source, chunk, meta}, ...].

    Args:
      campaign: root dir containing tracker.json + the DOCS. Defaults to the
        module constant so the production path works unchanged; tests pass a
        tmp corpus dir.
    """
    rows: list[dict] = []

    # Applications.
    tracker = json.loads((campaign / "tracker.json").read_text())
    for a in tracker.get("applications", []):
        text = app_text(a)
        if not text:
            continue
        rows.append(
            {
                "collection": "apps",
                "source": "tracker.json",
                "chunk": text,
                "meta": app_meta(a),
            }
        )

    # Docs.
    for name in DOCS:
        path = campaign / name
        if not path.exists():
            print(f"  (skip, missing: {name})", file=sys.stderr)
            continue
        text = path.read_text()
        for title, body in chunk_doc(text):
            rows.append(
                {
                    "collection": "docs",
                    "source": name,
                    "chunk": body,
                    "meta": {"header": title, "file": name},
                }
            )

    return rows


def write_index(
    rows: list[dict],
    vectors: "np.ndarray",
    db_path: Path = DB,
) -> None:
    """Write rows + their vectors into a SQLite index. Idempotent (drops existing)."""
    if db_path.exists():
        db_path.unlink()
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(
            """
            CREATE TABLE chunks (
              id INTEGER PRIMARY KEY,
              collection TEXT NOT NULL,
              source TEXT NOT NULL,
              chunk TEXT NOT NULL,
              meta_json TEXT NOT NULL,
              vector_json TEXT NOT NULL
            )
            """
        )
        conn.execute("CREATE INDEX idx_chunks_collection ON chunks(collection)")
        for r, vec in zip(rows, vectors, strict=True):
            conn.execute(
                "INSERT INTO chunks (collection, source, chunk, meta_json, vector_json) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    r["collection"],
                    r["source"],
                    r["chunk"],
                    json.dumps(r["meta"], ensure_ascii=False),
                    json.dumps(vec.tolist()),
                ),
            )
        conn.commit()
    finally:
        conn.close()


def build(
    check_only: bool = False,
    model: "StaticModel | None" = None,
    campaign: Path = CAMPAIGN,
    db_path: Path = DB,
) -> None:
    """Build the index. Injects model + paths for testability.

    Args:
      check_only: report corpus counts without writing.
      model: embedding model (must have .encode(list[str])->ndarray). If None,
        loads the default minishlab model (network + model2vec dep required).
      campaign: corpus root (tracker.json + DOCS).
      db_path: where to write index.db.
    """
    if model is None:
        print(f"Loading embedding model: {MODEL}")
        model = StaticModel.from_pretrained(MODEL)

    rows = collect_corpus(campaign)
    apps_n = sum(1 for r in rows if r["collection"] == "apps")
    docs_n = sum(1 for r in rows if r["collection"] == "docs")
    print(f"Corpus: {apps_n} applications, {docs_n} doc chunks ({len(rows)} total)")

    if check_only:
        return

    texts = [r["chunk"] for r in rows]
    print("Embedding...")
    vectors = model.encode(texts)
    print(f"  embedded {len(vectors)} vectors, dim {vectors.shape[1]}")

    write_index(rows, vectors, db_path)
    size_kb = db_path.stat().st_size / 1024
    print(f"Wrote {db_path} ({size_kb:.0f} KB, {len(rows)} rows)")


if __name__ == "__main__":
    check = "--check" in sys.argv
    build(check_only=check)
