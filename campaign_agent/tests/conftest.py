"""Suite-wide test isolation.

The agent attaches a RotatingFileHandler to the REAL campaign log
(<campaign_dir>/agent.log) in main(). Without isolation, any test that runs
main() - or that emits a log record after such a test - writes into the live
agent log. Observed on 2026-10-05: pytest temp paths
(T/pytest-of-mst/pytest-NN/...) appearing inside the campaign agent's log,
which made the live log unreadable and hid real agent events.

This autouse fixture snapshots the process-global root logger's handlers and
level around every test and restores them afterwards, so no test can leak a
handler (or a log level) into another test or into the live agent log.
"""
import logging

import pytest


@pytest.fixture(autouse=True)
def _isolate_root_logger():
    root = logging.getLogger()
    handlers_before = list(root.handlers)
    level_before = root.level
    try:
        yield
    finally:
        root.handlers[:] = handlers_before
        root.setLevel(level_before)
