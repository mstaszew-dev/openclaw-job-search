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


class RAGMCP:
    """Manages a RAG MCP server subprocess via stdio."""

    def __init__(self, command: str, args: list[str]) -> None:
        self.params = StdioServerParameters(command=command, args=args)
        self._session: ClientSession | None = None
        self._ctx_stack: list[Any] = []

    async def connect(self) -> None:
        """Spawn the RAG MCP server and initialize the session."""
        self._ctx_stack = []
        read_write = stdio_client(self.params)
        self._ctx_stack.append(read_write)
        read, write = await read_write.__aenter__()

        self._session = ClientSession(read, write)
        self._ctx_stack.append(self._session)
        await self._session.__aenter__()
        await self._session.initialize()
        log.info("RAG MCP connected")

    async def call_tool(self, name: str, arguments: dict[str, Any], timeout: float = 60.0) -> str:
        """Call a tool on the RAG MCP server with a timeout."""
        if self._session is None:
            return "Error: RAG MCP not connected"
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
        except asyncio.TimeoutError:
            log.error("RAG MCP tool '%s' timed out after %.1fs", name, timeout)
            return f"Error: RAG tool '{name}' timed out after {timeout}s"
        except Exception as e:
            log.error("RAG MCP tool '%s' failed: %s", name, e)
            return f"Error: {e}"

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
