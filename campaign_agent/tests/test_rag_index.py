"""Tests for the post-submission RAG index refresh.

The dedupe search (rag_search_apps) reads a SQLite vector index built by
rag/index_builder.py. Nothing rebuilt it automatically, so every application
submitted after the last manual build was invisible to dedupe search.
"""
from __future__ import annotations

import asyncio
import sys

import pytest

from campaign_agent.rag_index import rebuild_index


def test_rebuild_reports_success_on_zero_exit() -> None:
    ok = asyncio.run(rebuild_index(sys.executable, "-c", "", timeout_s=30))
    assert ok is True


def test_rebuild_reports_failure_on_nonzero_exit() -> None:
    ok = asyncio.run(rebuild_index(sys.executable, "-c", "raise SystemExit(3)", timeout_s=30))
    assert ok is False


def test_rebuild_reports_failure_when_the_binary_is_missing() -> None:
    # A bad interpreter path must degrade to False, never raise into the tick.
    ok = asyncio.run(rebuild_index("/nonexistent/python", "-c", timeout_s=30))
    assert ok is False


def test_rebuild_times_out_and_kills_the_child() -> None:
    # A hung rebuild must not stall the campaign turn indefinitely.
    ok = asyncio.run(
        rebuild_index(sys.executable, "-c", "import time; time.sleep(30)", timeout_s=1.0)
    )
    assert ok is False


def test_rebuild_passes_the_script_as_an_argument() -> None:
    ok = asyncio.run(
        rebuild_index(sys.executable, "-c", "import sys; assert sys.argv[1:]==['a','b']", "a", "b", timeout_s=30)
    )
    assert ok is True


def test_rebuild_does_not_raise_on_a_crashing_child() -> None:
    ok = asyncio.run(rebuild_index(sys.executable, "-c", "raise ValueError('boom')", timeout_s=30))
    assert ok is False


@pytest.mark.parametrize("kwargs", [{"timeout_s": 0.0}])
def test_rebuild_handles_a_nonpositive_timeout(kwargs: dict[str, float]) -> None:
    ok = asyncio.run(rebuild_index(sys.executable, "-c", "", **kwargs))
    assert ok is False