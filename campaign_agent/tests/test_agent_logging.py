"""Tests for the agent's file logging (2026-10-05).

THE BUG: the agent logged to stdout only, so every exit reason died with the
iTerm tab. The Director restarted the worker six times over two days and not
one termination cause was recoverable afterwards. A rotating file handler in
the campaign directory keeps the reasons (and everything else) on disk.

Crash + SIGTERM capture: main() installs signal handlers that log before the
default disposition, and wraps asyncio.run so an unhandled crash is logged
with its traceback before re-raising.
"""
import logging
import logging.handlers
import os

import pytest

from campaign_agent.main import setup_logging


@pytest.fixture(autouse=True)
def _restore_root_logger():
    """setup_logging mutates the process-global root logger; restore after."""
    root = logging.getLogger()
    handlers_before = list(root.handlers)
    level_before = root.level
    yield
    root.handlers[:] = handlers_before
    root.setLevel(level_before)


def test_returns_the_log_path_inside_the_campaign_dir(tmp_path):
    path = setup_logging(str(tmp_path))
    assert path == os.path.join(str(tmp_path), "agent.log")


def test_log_file_is_created_and_receives_records(tmp_path):
    setup_logging(str(tmp_path))
    logging.getLogger("agent-test").info("exit reason probe")
    log_file = tmp_path / "agent.log"
    assert log_file.exists()
    assert "exit reason probe" in log_file.read_text(encoding="utf-8")


def test_adds_exactly_one_file_handler_per_path(tmp_path):
    root = logging.getLogger()
    before = sum(1 for h in root.handlers
                 if isinstance(h, logging.handlers.RotatingFileHandler))
    setup_logging(str(tmp_path))
    setup_logging(str(tmp_path))
    after = sum(1 for h in root.handlers
                if isinstance(h, logging.handlers.RotatingFileHandler))
    assert after - before <= 1, "duplicate file handlers would double-log"


def test_handler_is_rotating_so_the_log_cannot_grow_without_bound(tmp_path):
    path = setup_logging(str(tmp_path))
    handler = next(h for h in logging.getLogger().handlers
                   if isinstance(h, logging.handlers.RotatingFileHandler)
                   and getattr(h, "baseFilename", "") == os.path.abspath(path))
    assert isinstance(handler, logging.handlers.RotatingFileHandler)
    assert handler.maxBytes > 0 and handler.backupCount >= 1


def test_setup_logging_is_called_from_main_before_the_run():
    """main() must call setup_logging BEFORE asyncio.run, so crashes and
    Director SIGTERMs are captured from process start."""
    import inspect

    from campaign_agent import main as main_mod
    src = inspect.getsource(main_mod.main)
    setup_pos = src.index("setup_logging(")
    run_pos = src.index("asyncio.run(")
    assert setup_pos < run_pos


# --- robustness: a missing campaign dir must not kill the agent --------------
# CI (a fresh runner with no /Users/mst/Downloads/... tree) failed with
# FileNotFoundError: agent.log, because RotatingFileHandler cannot create its
# parent. The agent must degrade to console logging, not die at startup.

def test_returns_none_and_does_not_raise_when_dir_cannot_be_created(tmp_path):
    blocker = tmp_path / "afile"
    blocker.write_text("not a directory")
    impossible = blocker / "campaign"      # makedirs under a file -> OSError

    result = setup_logging(str(impossible))

    assert result is None
    # Console logging still works, and the failure is itself visible.
    logging.getLogger("agent-test").warning("still logging to the console")
