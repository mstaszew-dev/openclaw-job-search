"""Post-submission refresh of the RAG dedupe index.

`rag_search_apps` answers dedupe questions from a SQLite vector index built by
`rag/index_builder.py`. Nothing rebuilt it automatically: the index was a manual
step, so every application submitted after the last build was invisible to
dedupe search. That is exactly the case dedupe exists to catch, since the agent
re-reads boards and reconsiders the same company within a tick.

A full rebuild is cheap enough to run inline. The embedder (model2vec
`potion-base-8M`) is a static embedding table, not a neural forward pass, so a
full rebuild over ~1800 applications measures around one second. Incremental
append would save that second at the cost of a second code path that can drift;
not worth it here.

The rebuild must run under the rag venv (that is where model2vec lives), hence
the subprocess rather than an import.
"""
from __future__ import annotations

import asyncio
import logging

log = logging.getLogger(__name__)

# Tail of the child's stderr to log when it fails. The full output can be long
# (model download progress bars) and is not useful in the agent log.
_ERR_TAIL_CHARS = 400


async def rebuild_index(
    python: str,
    *args: str,
    timeout_s: float = 120.0,
) -> bool:
    """Rebuild the RAG index. Returns True on success.

    Never raises: the caller is the campaign turn loop, and a failed index
    refresh must degrade dedupe quality, not abort a submission. A missing
    interpreter, a crashing builder, and a timeout all return False.

    Cancellation is re-raised after killing the child, so a shutdown signal
    still propagates.
    """
    if timeout_s <= 0:
        log.warning("RAG rebuild skipped: non-positive timeout %s", timeout_s)
        return False

    try:
        proc = await asyncio.create_subprocess_exec(
            python,
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except (OSError, ValueError) as exc:
        log.warning("RAG rebuild could not start (%s): %s", python, exc)
        return False

    try:
        async with asyncio.timeout(timeout_s):
            _, err = await proc.communicate()
    except TimeoutError:
        log.warning("RAG rebuild timed out after %ss; killing it", timeout_s)
        await _kill(proc)
        return False
    except asyncio.CancelledError:
        await _kill(proc)
        raise

    if proc.returncode != 0:
        tail = err.decode("utf-8", "replace").strip()[-_ERR_TAIL_CHARS:]
        log.warning("RAG rebuild failed (exit=%s): %s", proc.returncode, tail)
        return False

    log.info("RAG index rebuilt after submission")
    return True


async def _kill(proc: asyncio.subprocess.Process) -> None:
    """Kill a child and reap it, ignoring a race where it already exited."""
    try:
        proc.kill()
    except ProcessLookupError:
        return
    try:
        await proc.wait()
    except asyncio.CancelledError:
        raise
