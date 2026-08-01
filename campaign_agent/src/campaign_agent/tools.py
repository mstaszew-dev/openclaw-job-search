"""
ToolRouter: maps LLM tool calls to dispatchers (exec, Playwright MCP, RAG MCP).
Provides OpenAI-format tool schemas and sync/async dispatch.
"""
from __future__ import annotations

import asyncio
import logging
import subprocess
from pathlib import Path
from typing import Any, Protocol

log = logging.getLogger(__name__)

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
    """Execute a shell command and return stdout + stderr + exit code."""
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd,
        )
        parts = []
        if result.stdout:
            parts.append(result.stdout.strip())
        if result.stderr:
            parts.append(f"stderr: {result.stderr.strip()}")
        parts.append(f"exit={result.returncode}")
        return "\n".join(parts)
    except subprocess.TimeoutExpired:
        return f"Command timed out after {timeout}s"
    except Exception as e:
        return f"Error: {e}"


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
            return exec_tool(
                args.get("command", ""),
                timeout=args.get("timeout", 30),
                cwd=args.get("cwd") or self.default_cwd,
            )

        if name == "read":
            return read_file(args.get("path", ""), self.default_cwd)

        if name in PLAYWRIGHT_TOOLS:
            if self.playwright is None:
                return f"Error: Playwright MCP not available"
            try:
                return await self.playwright.call_tool(name, args)
            except Exception as e:
                return f"Error: Playwright tool '{name}' failed: {e}"

        if name in RAG_TOOLS:
            if self.rag is None:
                return f"Error: RAG MCP not available"
            try:
                return await self.rag.call_tool(name, args)
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
