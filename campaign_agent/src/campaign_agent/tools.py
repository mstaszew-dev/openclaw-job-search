"""
ToolRouter: maps LLM tool calls to dispatchers (exec, Playwright MCP, RAG MCP).
Provides OpenAI-format tool schemas and sync/async dispatch.
"""
from __future__ import annotations

import asyncio
import logging
import os
import signal
import subprocess
from pathlib import Path
from typing import Any, Protocol

log = logging.getLogger(__name__)

# Default timeout for Playwright MCP tool calls (seconds)
PLAYWRIGHT_TOOL_TIMEOUT = 120.0

# Default timeout for RAG MCP tool calls (seconds)
RAG_TOOL_TIMEOUT = 60.0

# exec tool: hard ceiling on model-supplied timeouts. A free-tier model
# passing timeout=86400 must never be able to wedge the event loop for a day.
EXEC_DEFAULT_TIMEOUT = 30
EXEC_MAX_TIMEOUT = 300

# OpenAI function-calling tool schemas
TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "exec",
            "description": "Run a shell command (working directory defaults to the campaign directory). Use for update_tracker.py, tick_status.sh, etc.",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Shell command to execute"},
                    "timeout": {"type": "integer", "description": "Timeout in seconds", "default": 30},
                    "cwd": {"type": "string", "description": "Working directory override (defaults to campaign directory)", "default": None},
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read",
            "description": "Read the contents of a file. Relative paths resolve against the campaign directory. Use for AGENT_TICK.md, CONTEXT.md, PORTALS.md, tracker.json, etc.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Absolute path, or path relative to the campaign directory"},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_navigate",
            "description": "Navigate the browser to a URL",
            "parameters": {
                "type": "object",
                "properties": {"url": {"type": "string"}},
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_snapshot",
            "description": "Take an accessibility snapshot of the current page",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_click",
            "description": "Click an element on the page",
            "parameters": {
                "type": "object",
                "properties": {
                    "element": {"type": "string", "description": "Human-readable element description"},
                    "target": {"type": "string", "description": "Element reference from snapshot"},
                },
                "required": ["target"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_fill_form",
            "description": "Fill multiple form fields",
            "parameters": {
                "type": "object",
                "properties": {
                    "fields": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "target": {"type": "string"},
                                "name": {"type": "string"},
                                "type": {"type": "string"},
                                "value": {"type": "string"},
                            },
                        },
                    },
                },
                "required": ["fields"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_file_upload",
            "description": "Upload files to a file input element",
            "parameters": {
                "type": "object",
                "properties": {"paths": {"type": "array", "items": {"type": "string"}}},
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_type",
            "description": "Type text into an editable element",
            "parameters": {
                "type": "object",
                "properties": {
                    "target": {"type": "string"},
                    "text": {"type": "string"},
                    "submit": {"type": "boolean", "default": False},
                },
                "required": ["target", "text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_evaluate",
            "description": "Evaluate JavaScript on the page",
            "parameters": {
                "type": "object",
                "properties": {"function": {"type": "string"}},
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_find",
            "description": "Search the page accessibility snapshot for text",
            "parameters": {
                "type": "object",
                "properties": {"text": {"type": "string"}},
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_wait_for",
            "description": "Wait for text to appear/disappear or time to pass",
            "parameters": {
                "type": "object",
                "properties": {
                    "text": {"type": "string"},
                    "time": {"type": "number"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "browser_tabs",
            "description": "List, create, close, or select browser tabs",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {"type": "string"},
                    "index": {"type": "integer"},
                    "url": {"type": "string"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "rag_search_apps",
            "description": "Semantic search over past applications for deduplication",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "rag_search_docs",
            "description": "Search campaign documentation (PORTALS.md, IL_BOARDS.md, etc.)",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        },
    },
]

# Tools that go to Playwright MCP
PLAYWRIGHT_TOOLS = {
    "browser_navigate", "browser_snapshot", "browser_click", "browser_fill_form",
    "browser_file_upload", "browser_type", "browser_evaluate", "browser_find",
    "browser_wait_for", "browser_tabs",
}

# Tools that go to RAG MCP
RAG_TOOLS = {"rag_search_apps", "rag_search_docs"}


class MCPClient(Protocol):
    """Protocol for MCP client (Playwright or RAG)."""
    async def call_tool(self, name: str, arguments: dict[str, Any]) -> str: ...


def exec_tool(command: str, timeout: int = 30, cwd: str | None = None) -> str:
    """Execute a shell command and return stdout + stderr + exit code.

    The command runs in its own session (start_new_session) so that on
    timeout the WHOLE process group can be SIGKILLed - subprocess.run's
    default kill only reaps the /bin/sh, leaking backgrounded grandchildren.
    """
    try:
        proc = subprocess.Popen(
            command,
            shell=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
            cwd=cwd,
        )
    except Exception as e:
        return f"Error: {e}"
    try:
        out, err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            proc.kill()
        out, err = proc.communicate()
        parts = [f"Command timed out after {timeout}s"]
        if out and out.strip():
            parts.append(out.strip())
        if err and err.strip():
            parts.append(f"stderr: {err.strip()}")
        return "\n".join(parts)
    parts = []
    if out:
        parts.append(out.strip())
    if err:
        parts.append(f"stderr: {err.strip()}")
    parts.append(f"exit={proc.returncode}")
    return "\n".join(parts)


def read_file(path: str, base_dir: str | None = None, max_chars: int = 20000) -> str:
    """Read a file's contents, resolving relative paths against base_dir."""
    try:
        p = Path(path)
        if not p.is_absolute() and base_dir:
            p = Path(base_dir) / p
        text = p.read_text()
        if len(text) > max_chars:
            text = text[:max_chars] + f"\n...[truncated: file exceeds {max_chars} chars]"
        return text
    except FileNotFoundError:
        return f"Error: file not found: {path}"
    except IsADirectoryError:
        return f"Error: path is a directory: {path}"
    except Exception as e:
        return f"Error reading {path}: {e}"


class ToolRouter:
    """Routes tool calls to the appropriate dispatcher."""

    def __init__(
        self,
        playwright_client: MCPClient | None = None,
        rag_client: MCPClient | None = None,
        default_cwd: str | None = None,
    ) -> None:
        self.playwright = playwright_client
        self.rag = rag_client
        self.default_cwd = default_cwd

    @property
    def schemas(self) -> list[dict[str, Any]]:
        """Return tool schemas for the LLM."""
        return TOOL_SCHEMAS

    async def dispatch(self, name: str, args: dict[str, Any]) -> str:
        """Dispatch a tool call asynchronously."""
        if name == "exec":
            try:
                timeout = min(
                    max(int(args.get("timeout", EXEC_DEFAULT_TIMEOUT)), 1),
                    EXEC_MAX_TIMEOUT,
                )
            except (TypeError, ValueError):
                timeout = EXEC_DEFAULT_TIMEOUT
            # subprocess.run blocks: run it in a worker thread with a hard
            # ceiling so the event loop stays responsive no matter what the
            # command does.
            return await asyncio.wait_for(
                asyncio.to_thread(
                    exec_tool,
                    args.get("command", ""),
                    timeout,
                    args.get("cwd") or self.default_cwd,
                ),
                timeout=timeout + 10,
            )

        if name == "read":
            return read_file(args.get("path", ""), self.default_cwd)

        if name in PLAYWRIGHT_TOOLS:
            if self.playwright is None:
                return f"Error: Playwright MCP not available"
            try:
                return await self.playwright.call_tool(name, args, timeout=PLAYWRIGHT_TOOL_TIMEOUT)
            except Exception as e:
                return f"Error: Playwright tool '{name}' failed: {e}"

        if name in RAG_TOOLS:
            if self.rag is None:
                return f"Error: RAG MCP not available"
            try:
                return await self.rag.call_tool(name, args, timeout=RAG_TOOL_TIMEOUT)
            except Exception as e:
                return f"Error: RAG tool '{name}' failed: {e}"

        return f"Error: Unknown tool '{name}'"

    def dispatch_sync(self, name: str, args: dict[str, Any]) -> str:
        """Synchronous dispatch (for exec and error cases)."""
        if name == "exec":
            return exec_tool(
                args.get("command", ""),
                timeout=args.get("timeout", 30),
                cwd=args.get("cwd") or self.default_cwd,
            )

        if name == "read":
            return read_file(args.get("path", ""), self.default_cwd)

        if name in PLAYWRIGHT_TOOLS and self.playwright is None:
            return "Error: Playwright MCP not available"

        if name in RAG_TOOLS and self.rag is None:
            return "Error: RAG MCP not available"

        return f"Error: Unknown tool '{name}'"
