"""Shared fixtures: tracker files, fake hermes binary, isolated env."""
from __future__ import annotations

import json
import stat
from pathlib import Path
from typing import Callable, Optional

import pytest

# Tests exercise the path-override knobs (tracker_path/campaign_dir args);
# production only honors them when JOBSEARCH_ALLOW_OVERRIDES=1 is set
# (prompt-injection guard).
@pytest.fixture(autouse=True)
def _allow_overrides(monkeypatch) -> None:
    monkeypatch.setenv("JOBSEARCH_ALLOW_OVERRIDES", "1")


@pytest.fixture()
def tracker_factory(tmp_path: Path) -> Callable[..., Path]:
    """Return a factory writing tracker.json under tmp_path."""

    def _make(
        records: Optional[list] = None,
        submitted: int = 0,
        target: int = 1500,
        name: str = "tracker.json",
    ) -> Path:
        data = {
            "stats": {"submitted": submitted},
            "target": target,
            "applications": records or [],
            "applyQueue": [],
        }
        path = tmp_path / name
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    return _make


@pytest.fixture()
def fake_hermes(tmp_path: Path) -> tuple[Path, Path]:
    """Create a fake ``hermes`` executable that logs argv and obeys env knobs.

    Knobs (read by the stub at run time):
      FAKE_HERMES_EXIT    exit code (default 0)
      FAKE_HERMES_STDOUT  text printed to stdout (default empty)
    The stub appends one line per invocation (``"$*"``) to ``$FAKE_HERMES_LOG``.
    Returns ``(bin_dir, log_path)``.
    """
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    stub = bin_dir / "hermes"
    stub.write_text(
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$*" >> "$FAKE_HERMES_LOG"\n'
        'if [[ -n "${FAKE_HERMES_STDOUT:-}" ]]; then printf "%s" "$FAKE_HERMES_STDOUT"; fi\n'
        'exit "${FAKE_HERMES_EXIT:-0}"\n',
        encoding="utf-8",
    )
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    log_path = tmp_path / "hermes.log"
    return bin_dir, log_path
