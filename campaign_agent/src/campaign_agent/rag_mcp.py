"""
RAG MCP client: async wrapper that spawns the RAG server as a stdio subprocess.
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


class RAGMCP:
    """Manages a RAG MCP server subprocess via stdio."""

    def __init__(self, command: str, args: list[str], wedge_restart_strikes: int = 3) -> None:
        self.params = StdioServerParameters(command=command, args=args)
        self._session: ClientSession | None = None
        self._ctx_stack: list[Any] = []
        self.wedge_restart_strikes = wedge_restart_strikes
        self._consecutive_failures = 0

    async def connect(self) -> None:
        """Spawn the RAG MCP server and initialize the session.

        initialize() is bounded by CONNECT_TIMEOUT_S: a wedged MCP startup
        must not block connect() forever. On any failure the
        half-initialized session and spawned context managers are unwound
        so no broken session object survives.
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
        log.info("RAG MCP connected")

    async def _handle_failure(self, err_msg: str, log_fmt: str, *log_args: Any) -> str:
        """Wedge watchdog (ported from PlaywrightMCP, 2026-09-18 review S-3):
        count consecutive failures; at the threshold kill + respawn the MCP
        server so a dead dedupe path recovers without a worker restart."""
        self._consecutive_failures += 1
        log.error("RAG MCP " + log_fmt, *log_args)
        if self._consecutive_failures < self.wedge_restart_strikes:
            return err_msg
        log.warning(
            "%d consecutive RAG MCP failures; respawning MCP server",
            self._consecutive_failures,
        )
        await self.close()
        try:
            await self.connect()
            self._consecutive_failures = 0
            return err_msg + " (RAG MCP respawned; retry the tool)"
        except Exception as e:
            log.error("RAG MCP respawn failed: %s", e)
            # Counter deliberately NOT reset: a later failure retries the respawn.
            return f"{err_msg} (RAG MCP respawn failed: {e})"

    async def call_tool(self, name: str, arguments: dict[str, Any], timeout: float = 60.0) -> str:
        """Call a tool on the RAG MCP server with a timeout."""
        if self._session is None:
            # Disconnected (e.g. a failed respawn): count a strike so the
            # respawn is retried at the threshold instead of erroring forever.
            return await self._handle_failure(
                "Error: RAG MCP not connected", "tool call while disconnected"
            )
        try:
            result = await asyncio.wait_for(
                self._session.call_tool(name, arguments),
                timeout=timeout,
            )
            texts = []
            for content in result.content:
                if hasattr(content, "text"):
                    texts.append(content.text)
                elif isinstance(content, dict) and "text" in content:
                    texts.append(content["text"])
            return "\n".join(texts) if texts else str(result)
        except TimeoutError:
            # Mirror PlaywrightMCP: timeout/exception paths must count strikes
            # so a wedged-but-connected server respawns (2026-10-01 bug hunt).
            return await self._handle_failure(
                f"Error: RAG tool '{name}' timed out after {timeout}s",
                "tool '%s' timed out after %.1fs", name, timeout,
            )
        except Exception as e:
            return await self._handle_failure(
                f"Error: {e}", "tool '%s' failed: %s", name, e,
            )

    async def close(self) -> None:
        """Close the MCP session and subprocess."""
        for ctx in reversed(self._ctx_stack):
            try:
                await ctx.__aexit__(None, None, None)
            except Exception:
                pass
        self._ctx_stack = []
        self._session = None
        log.info("RAG MCP disconnected")
