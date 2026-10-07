"""Tests for the upload_cv tool: chooser path, CDP fallback, verification.

The agent previously improvised CV uploads: the model picked a path (sometimes
wrong), staged copies with `cp`, and called browser_file_upload hoping a file
chooser was open. Seven stray CV copies accumulated in campaign_agent/ and one
log shows a wrong-path guess two seconds after the right one. The tool pins one
canonical path, verifies the file, drives the chooser, and falls back to CDP
DOM.setFileInputFiles for drag-and-drop-only zones.
"""
from __future__ import annotations

import asyncio
from typing import ClassVar
from unittest.mock import AsyncMock, MagicMock

import pytest

from campaign_agent.cv_upload import (
    resolve_cv,
    upload_cv,
    verify_cv,
)


def _make_cv(tmp_path, name="cv.pdf", size=100):
    p = tmp_path / name
    p.write_bytes(b"%PDF-" + b"x" * (size - 5))
    return p


class TestVerifyCv:
    def test_ok_for_existing_nonempty_file(self, tmp_path):
        cv = _make_cv(tmp_path)
        assert verify_cv(str(cv)) is None

    def test_missing_file_reports_error(self, tmp_path):
        err = verify_cv(str(tmp_path / "nope.pdf"))
        assert err is not None and "not found" in err

    def test_empty_file_reports_error(self, tmp_path):
        cv = tmp_path / "empty.pdf"
        cv.write_bytes(b"")
        err = verify_cv(str(cv))
        assert err is not None and "empty" in err

    def test_broken_symlink_reports_error(self, tmp_path):
        link = tmp_path / "link.pdf"
        link.symlink_to(tmp_path / "gone.pdf")
        err = verify_cv(str(link))
        assert err is not None and "dangling" in err


class TestResolveCv:
    def test_prefers_pl_variant(self, tmp_path):
        base = str(_make_cv(tmp_path, "cv.pdf"))
        pl = str(_make_cv(tmp_path, "cv-pl.pdf"))
        assert resolve_cv(base, pl) == pl

    def test_falls_back_to_base_when_pl_missing(self, tmp_path):
        base = str(_make_cv(tmp_path, "cv.pdf"))
        assert resolve_cv(base, str(tmp_path / "missing-pl.pdf")) == base


class TestUploadCv:
    async def test_rejects_when_cv_missing(self, tmp_path):
        client = MagicMock()
        client.call_tool = AsyncMock()
        result = await upload_cv(
            client, "http://127.0.0.1:9222", str(tmp_path / "nope.pdf")
        )
        assert result.startswith("Error")
        assert "not found" in result
        client.call_tool.assert_not_awaited()

    async def test_uploads_via_file_chooser_and_verifies(self, tmp_path):
        cv = _make_cv(tmp_path)
        client = MagicMock()
        client.call_tool = AsyncMock(
            side_effect=[
                "ok (clicked upload button)",              # browser_click
                "uploaded 1 file",                          # browser_file_upload
            ]
        )
        result = await upload_cv(
            client, "http://127.0.0.1:9222", str(cv), selector="#upload-btn"
        )
        assert not result.startswith("Error")
        assert cv.name in result
        calls = [c.args for c in client.call_tool.await_args_list]
        assert calls[0][0] == "browser_click"  # opens the chooser
        assert calls[1][0] == "browser_file_upload"
        assert calls[1][1]["paths"] == [str(cv)]

    async def test_click_failure_aborts_with_error(self, tmp_path):
        cv = _make_cv(tmp_path)
        client = MagicMock()
        client.call_tool = AsyncMock(return_value="Error: element not found")
        result = await upload_cv(
            client, "http://127.0.0.1:9222", str(cv), selector="#gone"
        )
        assert result.startswith("Error")
        # Never attempts the upload when the chooser could not be opened.
        names = [c.args[0] for c in client.call_tool.await_args_list]
        assert "browser_file_upload" not in names

    async def test_chooser_upload_error_triggers_cdp_fallback(self, tmp_path, monkeypatch):
        cv = _make_cv(tmp_path)
        client = MagicMock()
        client.call_tool = AsyncMock(
            side_effect=[
                "ok (clicked)",                              # click
                "Error: no file chooser is open",             # chooser upload failed
                "https://example.com/apply",                  # active tab URL
            ]
        )
        called = {}

        async def fake_cdp(cdp_url, path, css, page_url=None):
            called["args"] = (cdp_url, path, css)
            return "CV set on input[type=file] via CDP (1 file)"

        monkeypatch.setattr("campaign_agent.cv_upload.set_file_input_via_cdp", fake_cdp)
        result = await upload_cv(
            client, "http://127.0.0.1:9222", str(cv), selector="#dropzone"
        )
        assert "CDP" in result
        assert called["args"][1] == str(cv)

    async def test_everything_failing_reports_actionable_error(self, tmp_path, monkeypatch):
        cv = _make_cv(tmp_path)
        client = MagicMock()
        client.call_tool = AsyncMock(
            side_effect=[
                "ok (clicked)",
                "Error: no file chooser is open",
                "https://example.com/apply",
            ]
        )

        async def failing_cdp(cdp_url, path, css, page_url=None):
            return "Error: CDP fallback failed (no input[type=file] found)"

        monkeypatch.setattr("campaign_agent.cv_upload.set_file_input_via_cdp", failing_cdp)
        result = await upload_cv(
            client, "http://127.0.0.1:9222", str(cv), selector="#dz"
        )
        assert result.startswith("Error")
        assert "manual" in result.lower()


@pytest.mark.parametrize("run", [asyncio.run])
def test_upload_cv_is_cancellable_cleanly(run, tmp_path):
    """A cancelled upload must not swallow CancelledError (shutdown safety)."""
    cv = _make_cv(tmp_path)

    async def scenario():
        client = MagicMock()
        client.call_tool = AsyncMock(side_effect=asyncio.CancelledError)
        try:
            await upload_cv(client, "http://127.0.0.1:9222", str(cv))
        except asyncio.CancelledError:
            raise
        return "no-cancel"

    with pytest.raises(asyncio.CancelledError):
        run(scenario())


class TestMatchPageTarget:
    """The CDP fallback must hit the tab the MCP is driving, not tab #1."""

    TARGETS: ClassVar[list[dict]] = [
        {"type": "browser_ui", "url": "chrome://omnibox/"},
        {"type": "page", "url": "https://mail.google.com/mail/u/0"},
        {"type": "page", "url": "https://justjoin.it/job/x"},
        {"type": "page", "url": "https://justjoin.it/job/x/apply"},
        {"type": "iframe", "url": "https://ads.example/"},
    ]

    def test_exact_match_wins(self):
        from campaign_agent.cv_upload import _match_page_target
        t = _match_page_target(self.TARGETS, "https://justjoin.it/job/x/apply")
        assert t is not None and t["url"].endswith("/apply")

    def test_prefix_match_when_no_exact(self):
        from campaign_agent.cv_upload import _match_page_target
        t = _match_page_target(self.TARGETS, "https://justjoin.it/job/x#form")
        assert t is not None and t["url"] == "https://justjoin.it/job/x"

    def test_ambiguous_match_returns_none_not_first(self):
        from campaign_agent.cv_upload import _match_page_target
        # Two tabs share the prefix: guessing the first would upload into a
        # random tab's form.
        assert _match_page_target(self.TARGETS, "https://justjoin.it") is None

    def test_no_page_url_returns_none_never_first_page(self):
        from campaign_agent.cv_upload import _match_page_target
        # Before the active-tab fix this picked targets[1] (Gmail).
        assert _match_page_target(self.TARGETS, None) is None


class TestUploadCvFallbackWiring:
    async def test_fallback_receives_the_active_tab_url(self, tmp_path, monkeypatch):
        cv = _make_cv(tmp_path)
        client = MagicMock()
        client.call_tool = AsyncMock(
            side_effect=[
                "Error: no file chooser is open",               # chooser refused
                "https://justjoin.it/job/x/apply",              # browser_evaluate
            ]
        )
        seen = {}

        async def fake_cdp(cdp_url, path, css, page_url=None):
            seen["page_url"] = page_url
            return "via CDP setFileInputFiles on input[type=file]"

        monkeypatch.setattr("campaign_agent.cv_upload.set_file_input_via_cdp", fake_cdp)
        result = await upload_cv(client, "http://127.0.0.1:9222", str(cv))
        assert "CDP" in result
        assert seen["page_url"] == "https://justjoin.it/job/x/apply"
