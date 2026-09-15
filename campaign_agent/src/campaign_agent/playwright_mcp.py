"""
Playwright MCP client: async wrapper that spawns the Playwright MCP server
as a stdio subprocess and provides call_tool().
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

log = logging.getLogger(__name__)

# MCP startup deadline: initialize() has no internal timeout in mcp 2.x
CONNECT_TIMEOUT_S = 60.0


def _extract_texts(result: Any) -> str:
    """Pull text out of an MCP CallToolResult (objects or dicts)."""
    texts = []
    for content in result.content:
        if hasattr(content, "text"):
            texts.append(content.text)
        elif isinstance(content, dict) and "text" in content:
            texts.append(content["text"])
    return "\n".join(texts) if texts else str(result)

# Consecutive browser-tool failures that indicate a WEDGED MCP CDP session
# (2026-09-15: every browser tool timed out for 6h while Chrome's own CDP
# HTTP layer answered instantly; respawning the MCP server fixed it in one
# second). After this many consecutive failures, call_tool kills + respawns
# the MCP server so the turn recovers in place instead of grinding
# timeout x steps until the Director kills the worker for staleness.
WEDGE_RESTART_STRIKES = 3


class PlaywrightMCP:
    """Manages a Playwright MCP server subprocess via stdio."""

    def __init__(self, command: str, args: list[str],
                 wedge_restart_strikes: int = WEDGE_RESTART_STRIKES,
                 dialog_clear_timeout: float = 5.0) -> None:
        self.params = StdioServerParameters(command=command, args=args)
        self._session: ClientSession | None = None
        self._ctx_stack: list[Any] = []  # holds context managers
        self.wedge_restart_strikes = wedge_restart_strikes
        self.dialog_clear_timeout = dialog_clear_timeout
        self._consecutive_failures = 0

    async def connect(self) -> None:
        """Spawn the MCP server and initialize the session.

        initialize() is bounded by CONNECT_TIMEOUT_S: a wedged MCP startup
        (e.g. Chrome CDP endpoint dead) must not block connect() forever.
        On any failure the half-initialized session and spawned context
        managers are unwound so no broken session object survives.
        """
        self._ctx_stack = []
        try:
            read_write = stdio_client(self.params)
            self._ctx_stack.append(read_write)
            read, write = await read_write.__aenter__()

            self._session = ClientSession(read, write)
            self._ctx_stack.append(self._session)
            await self._session.__aenter__()
            await asyncio.wait_for(self._session.initialize(), timeout=CONNECT_TIMEOUT_S)
        except BaseException:
            self._session = None
            for ctx in reversed(self._ctx_stack):
                try:
                    await ctx.__aexit__(None, None, None)
                except Exception:
                    pass
            self._ctx_stack = []
            raise
        log.info("Playwright MCP connected")

    async def call_tool(self, name: str, arguments: dict[str, Any], timeout: float = 120.0) -> str:
        """Call a tool on the Playwright MCP server with a timeout."""
        if self._session is None:
            return "Error: Playwright MCP not connected"
        try:
            # Use asyncio.wait_for to enforce a timeout on the tool call
            result = await asyncio.wait_for(
                self._session.call_tool(name, arguments),
                timeout=timeout,
            )
            self._consecutive_failures = 0
            return _extract_texts(result)
        except asyncio.TimeoutError:
            # A pending native dialog ("Leave site?" beforeunload fired by
            # form pages - ATS portals, registration forms) blocks every
            # page-level operation and all actions queued behind it: the
            # next tool hangs its full timeout and the session wedges
            # (2026-09-15: 6h of 120s timeouts). Dismiss the dialog
            # (accept = proceed with leaving) and retry the original tool
            # once; only a repeat failure counts toward the wedge watchdog.
            note = await self._clear_pending_dialog()
            if note is not None:
                try:
                    retry = await asyncio.wait_for(
                        self._session.call_tool(name, arguments),
                        timeout=timeout,
                    )
                    if not (isinstance(retry, str) and retry.startswith("Error")):
                        self._consecutive_failures = 0
                        return _extract_texts(retry) + note
                except Exception:
                    pass
            return await self._handle_failure(
                f"Error: Playwright tool '{name}' timed out after {timeout}s",
                "tool '%s' timed out after %.1fs", name, timeout,
            )
        except Exception as e:
            return await self._handle_failure(
                f"Error: {e}", "tool '%s' failed: %s", name, e,
            )

    async def _clear_pending_dialog(self) -> str | None:
        """Dismiss a pending native dialog (accept = leave the page) via the
        MCP's browser_handle_dialog tool. Returns a caller-facing note when
        the dismiss call ANSWERED (even 'no dialog open' proves the session
        is alive), or None when the dismiss itself hung (session dead)."""
        try:
            await asyncio.wait_for(
                self._session.call_tool(
                    "browser_handle_dialog", {"accept": True},
                ),
                timeout=self.dialog_clear_timeout,
            )
            return " (a pending browser dialog was auto-accepted)"
        except Exception:
            return None

    async def _handle_failure(self, err_msg: str, log_fmt: str, *log_args: Any) -> str:
        """Count consecutive failures; at the wedge threshold, kill + respawn
        the MCP server so the session recovers without a worker restart."""
        self._consecutive_failures += 1
        log.error("Playwright MCP " + log_fmt, *log_args)
        if self._consecutive_failures < self.wedge_restart_strikes:
            return err_msg
        log.warning(
            "%d consecutive Playwright MCP failures; respawning MCP server",
            self._consecutive_failures,
        )
        await self.close()
        try:
            await self.connect()
            self._consecutive_failures = 0
            return err_msg + " (Playwright MCP respawned; retry the browser tool)"
        except Exception as e:
            log.error("Playwright MCP respawn failed: %s", e)
            # Counter deliberately NOT reset: a later failure retries the respawn.
            return f"{err_msg} (Playwright MCP respawn failed: {e})"

    async def close(self) -> None:
        """Close the MCP session and subprocess."""
        # Exit in reverse order
        for ctx in reversed(self._ctx_stack):
            try:
                await ctx.__aexit__(None, None, None)
            except Exception:
                pass
        self._ctx_stack = []
        self._session = None
        log.info("Playwright MCP disconnected")
