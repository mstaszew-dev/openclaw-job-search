"""
Seed the Pinecone agent-memory index from ~/ZCodeProject/context/ files.

Embeds durable knowledge (KEYWORDS.md, memories/, rules/global/, mcp-config.md,
workspace-projects.md) with the local potion-base-8M embedder (256-dim, no API
key) and upserts via the `pc` CLI. Run with the rag venv (has model2vec):

    rag/.venv/bin/python -m campaign_agent.seed_memory   (PYTHONPATH=src)

PINECONE_API_KEY must be in the environment for the pc CLI.
"""
from __future__ import annotations

import json
import logging
import subprocess
import sys
import tempfile
from pathlib import Path

log = logging.getLogger(__name__)

CONTEXT_DIR = Path.home() / "ZCodeProject" / "context"
SEED_FILES = [
    "KEYWORDS.md",
    "mcp-config.md",
    "workspace-projects.md",
]
SEED_GLOBS = ["memories/*.md", "rules/global/*.md"]
DIMENSION = 256
MODEL = "minishlab/potion-base-8M"


def collect_seed_files(context_dir: str) -> list[Path]:
    """Return the seed files under context_dir (explicit + globbed)."""
    base = Path(context_dir)
    found: list[Path] = []
    for name in SEED_FILES:
        p = base / name
        if p.is_file():
            found.append(p)
    for pattern in SEED_GLOBS:
        found.extend(sorted(base.glob(pattern)))
    # De-dup preserving order
    seen: set[Path] = set()
    out: list[Path] = []
    for p in found:
        if p not in seen:
            seen.add(p)
            out.append(p)
    return out


def chunk_markdown(
    text: str,
    source: str,
    kind: str,
    max_chars: int = 4000,
) -> list[dict]:
    """Split markdown by '## ' headings into chunk dicts with metadata."""
    if not text.strip():
        return []
    chunks: list[dict] = []
    current: list[str] = []
    for line in text.splitlines():
        if line.startswith("## "):
            if current:
                body = "\n".join(current)
                if len(body) > max_chars:
                    body = body[:max_chars] + "\n...[truncated]"
                chunks.append({
                    "id": f"{source}::{len(chunks)}",
                    "text": body,
                    "metadata": {"source": source, "kind": kind},
                })
            current = [line]
        else:
            current.append(line)
    if current:
        body = "\n".join(current)
        if len(body) > max_chars:
            body = body[:max_chars] + "\n...[truncated]"
        chunks.append({
            "id": f"{source}::{len(chunks)}",
            "text": body,
            "metadata": {"source": source, "kind": kind},
        })
    return chunks


def build_vectors(chunks: list[dict], embed_fn) -> list[dict]:
    """Embed chunk text into Pinecone upsert vectors."""
    vectors = []
    for c in chunks:
        values = embed_fn(c["text"])
        vectors.append({
            "id": c["id"],
            "values": values,
            "metadata": c["metadata"],
        })
    return vectors


def build_upsert_payload(vectors: list[dict]) -> str:
    """Serialize vectors as a JSON array for `pc index vector upsert --file`."""
    return json.dumps(vectors)


def upsert_to_pinecone(index: str, vectors_path: str) -> subprocess.CompletedProcess:
    """Upsert the vectors file into the index via the pc CLI."""
    return subprocess.run(
        ["pc", "index", "vector", "upsert", "--index-name", index, "--file", vectors_path],
        capture_output=True,
        text=True,
        check=False,
    )


def _embed_fn():
    """Lazy model2vec embedder (potion-base-8M, 256-dim)."""
    from model2vec import StaticModel  # type: ignore

    model = StaticModel.from_pretrained(MODEL)
    return lambda text: [float(x) for x in model.encode(text)]


def main(argv: list[str] | None = None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    index = argv[0] if argv else "agent-memory"
    context_dir = argv[1] if len(argv) > 1 else str(CONTEXT_DIR)

    files = collect_seed_files(context_dir)
    if not files:
        log.error("No seed files found under %s", context_dir)
        return 1
    log.info("Seeding %d files from %s", len(files), context_dir)

    embed_fn = _embed_fn()
    all_vectors: list[dict] = []
    for p in files:
        kind = "keyword" if p.name == "KEYWORDS.md" else (
            "memory" if "memories" in str(p) else "rules"
        )
        text = p.read_text(encoding="utf-8")
        chunks = chunk_markdown(text, str(p), kind)
        all_vectors.extend(build_vectors(chunks, embed_fn))
        log.info("  %s: %d chunks", p.name, len(chunks))

    payload = build_upsert_payload(all_vectors)
    with tempfile.NamedTemporaryFile(
        "w", suffix=".json", prefix="seed-vectors-", delete=False
    ) as f:
        f.write(payload)
        vectors_path = f.name
    log.info("Wrote %d vectors to %s", len(all_vectors), vectors_path)

    result = upsert_to_pinecone(index, vectors_path)
    if result.returncode != 0:
        log.error("pc upsert failed: %s", result.stderr[-500:])
        return 1
    log.info("Upserted %d vectors into %s", len(all_vectors), index)
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    sys.exit(main())
