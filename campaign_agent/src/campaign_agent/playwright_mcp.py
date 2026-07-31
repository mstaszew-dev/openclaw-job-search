"""
Playwright MCP client: async wrapper that spawns the Playwright MCP server
as a stdio subprocess and provides call_tool().
"""
from __future__ import annotations

import logging
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

log = logging.getLogger(__name__)


class PlaywrightMCP:
    """Manages a Playwright MCP server subprocess via stdio."""

    def __init__(self, command: str, args: list[str]) -> None:
        self.params = StdioServerParameters(command=command, args=args)
        self._session: ClientSession | None = None
        self._ctx_stack: list[Any] = []  # holds context managers

    async def connect(self) -> None:
        """Spawn the MCP server and initialize the session."""
        self._ctx_stack = []
        read_write = stdio_client(self.params)
        self._ctx_stack.append(read_write)
        read, write = await read_write.__aenter__()

        self._session = ClientSession(read, write)
        self._ctx_stack.append(self._session)
        await self._session.__aenter__()
        await self._session.initialize()
        log.info("Playwright MCP connected")

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> str:
        """Call a tool on the Playwright MCP server."""
        if self._session is None:
            return "Error: Playwright MCP not connected"
        try:
            result = await self._session.call_tool(name, arguments)
            # Extract text from result content
            texts = []
            for content in result.content:
                if hasattr(content, "text"):
                    texts.append(content.text)
                elif isinstance(content, dict) and "text" in content:
                    texts.append(content["text"])
            return "\n".join(texts) if texts else str(result)
        except Exception as e:
            log.error("Playwright MCP tool '%s' failed: %s", name, e)
            return f"Error: {e}"

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
