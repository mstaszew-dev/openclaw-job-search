"""
ToolRouter: maps LLM tool calls to dispatchers (exec, Playwright MCP, RAG MCP).
Provides OpenAI-format tool schemas and sync/async dispatch.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import signal
import subprocess
import sys
from pathlib import Path
from typing import Any, Protocol

from campaign_agent.cv_upload import resolve_cv, upload_cv

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
            "description": "Run a shell command (working directory defaults to the campaign directory). Use for tick_status.sh and similar. Do NOT run update_tracker.py here: use the record_submission tool.",
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
            "name": "record_submission",
            "description": (
                "Record a job-apply outcome in tracker.json by calling "
                "update_tracker.py for you. This is the ONLY supported way to "
                "record: never edit tracker.json directly and never shell out "
                "to update_tracker.py yourself."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "description": "Outcome to record",
                        "enum": ["submitted", "followUp", "skippedDuplicate",
                                 "skippedSalary", "skippedFilter",
                                 "blockedManual", "error"],
                        "default": "submitted",
                    },
                    "source": {"type": "string", "description": "Board id, e.g. nofluffjobs"},
                    "sourceJobId": {"type": "string", "description": "Board job id or URL slug"},
                    "company": {"type": "string", "description": "Company name"},
                    "roleTitle": {"type": "string", "description": "Role title"},
                    "jobUrl": {"type": "string", "description": "Listing URL"},
                    "applyUrl": {"type": "string", "description": "Apply URL used"},
                    "salarySeen": {"type": "string", "description": "Salary text seen on the listing"},
                    "notes": {"type": "string", "description": "Short outcome note"},
                    "region": {"type": "string", "description": "Region, e.g. PL"},
                    "confirmationText": {
                        "type": "string",
                        "description": "Verbatim portal confirmation text (one of "
                        "several accepted evidence fields)",
                    },
                    "confirmationUrl": {
                        "type": "string",
                        "description": "Confirmation / thank-you URL the portal returned",
                    },
                    "successUrl": {
                        "type": "string",
                        "description": "Alternative name for the confirmation URL",
                    },
                    "confirmed": {
                        "type": "boolean",
                        "description": "Set when the portal visibly confirmed the send",
                    },
                    "evidence": {
                        "type": "object",
                        "description": "Portal confirmation evidence, e.g. "
                        '{"type": "portal_confirmation", "text": "Application sent"}',
                    },
                    "applicationId": {
                        "type": "string",
                        "description": "followUp only: tracker id of the application",
                    },
                    "eventId": {
                        "type": "string",
                        "description": "followUp only: idempotency key, e.g. an email message id",
                    },
                    "messageId": {
                        "type": "string",
                        "description": "followUp only: alternative idempotency key",
                    },
                    "message": {
                        "type": "string",
                        "description": "followUp only: body of the message sent",
                    },
                    "stage": {
                        "type": "string",
                        "description": "followUp only: stage, defaults to follow-up",
                    },
                    "blockReason": {
                        "type": "string",
                        "description": "blockedManual only: e.g. captcha, login",
                    },
                    "reason": {
                        "type": "string",
                        "description": "blockedManual only: alternative reason field",
                    },
                },
                "required": ["source", "sourceJobId"],
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
            "name": "upload_cv",
            "description": (
                "Upload the campaign CV to the application form on the current "
                "page. Verifies the CV file, clicks the upload control, fills "
                "the file chooser, and falls back to CDP setFileInputFiles for "
                "drag-and-drop-only zones. Returns the uploaded filename for "
                "the tracker evidence. Prefer this over browser_file_upload: "
                "do NOT copy the CV with cp or guess paths."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "selector": {
                        "type": "string",
                        "description": (
                            "Ref/selector of the Upload CV button or dropzone "
                            "to click first (omit if a chooser is already open)"
                        ),
                    },
                    "css_input": {
                        "type": "string",
                        "description": (
                            "CSS selector of the <input type=file> for the CDP "
                            "fallback (default input[type=file])"
                        ),
                    },
                },
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
            "description": "Search campaign documentation (PORTALS.md, PL_BOARDS.md, etc.)",
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


def _cap_output(result: str, limit: int = 20000) -> str:
    """Head+tail cap: the tail keeps the `exit=N` trailer the submission gate
    keys on (2026-09-18 audit)."""
    if len(result) <= limit:
        return result
    dropped = len(result) - 19800
    return result[:17800] + f"\n[... truncated {dropped} chars ...]\n" + result[-2000:]


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
            encoding="utf-8",
            errors="replace",
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
        return _cap_output("\n".join(parts))
    parts = []
    if out:
        parts.append(out.strip())
    if err:
        parts.append(f"stderr: {err.strip()}")
    parts.append(f"exit={proc.returncode}")
    return _cap_output("\n".join(parts))


# update_tracker.py requires these; derive_id() is built from them.
_TRACKER_REQUIRED_FIELDS = ("source", "sourceJobId")
_TRACKER_CONTROL_KEYS = ("action", "timeout")
_TRACKER_ACTIONS = (
    "submitted", "followUp", "skippedDuplicate", "skippedSalary",
    "skippedFilter", "blockedManual", "error",
)
# DENYLIST, deliberately not an allowlist. submission_validator.py recognises
# confirmation evidence under many aliases (CONFIRMATION_TEXT_FIELDS_RECORD,
# CONFIRMATION_URL_FIELDS_RECORD, CONFIRMATION_FLAG_FIELDS) and adds more over
# time; confirmationText alone is on 941 of 1938 live applications. An
# allowlist silently drops names it has not heard of, and the script then
# records `attempted` instead of `submitted`, so real applications stop being
# counted with no error anywhere. Only the fields the model must never supply
# are listed here.
_TRACKER_FORBIDDEN_FIELDS = frozenset({
    # Identity: the script derives `id` from source:sourceJobId. A forged id
    # hides the listing from dedupe and is replayed by rebuild_from_events.py.
    "id",
    # Counters and lifecycle stamps the script owns.
    "status", "appliedAt", "at", "stats", "target",
    # Dedupe keys: DERIVED below, never taken from the model. The model's
    # companyKey overrides a correct company in the repeat-block guard
    # (update_tracker.py:202) and in the skipped[] join key (line 240), so a
    # wrong one marks a company blocked on its first attempt and dedupe then
    # skips it forever. Deriving removes the forgery vector entirely and keeps
    # the key canonical.
    "companyKey", "roleKey",
})
# DEDUPE.md step 1, matching normalize.py: the 7-step companyKey normalisation.
# Applied to company and roleTitle so the agent cannot get it wrong by hand.
_KEY_LEGAL_FORMS = (
    "sp z o o", "sp zoo", "s a", "sa", "ltd", "limited", "llc", "inc", "gmbh",
    "ag", "n v", "b v", "oy", "ab", "a s", "s r l", "s p a", "s c", "s r o",
    "kft", "co",
)


def normalize_dedupe_key(name: str) -> str:
    """Lowercase, drop legal forms, hyphenate: DEDUPE.md step 1.

    `Mindbox Sp. z o.o.` -> `mindbox`, `Funds-Tech Sp. z o.o.` -> `funds-tech`.
    Non-ASCII letters are kept (Hebrew and Polish company names are real).
    """
    s = name.strip().lower().replace("&", " and ")
    s = "".join(" " if ch in ".,_/" else ch for ch in s)
    s = " ".join(s.split())
    for _ in range(2):  # "up to two trailing legal forms"
        stripped = next(
            (s[: -len(form)].strip()
             for form in _KEY_LEGAL_FORMS
             if s.endswith(" " + form) or s == form),
            None,
        )
        if stripped is None:
            break
        s = stripped
    s = "".join(ch for ch in s if ch.isalnum() or ch in " -")
    # Collapse whitespace AND hyphens: a spaced hyphen ("Room - Global") would
    # otherwise become its own token and yield "room---global".
    return re.sub(r"[\s-]+", "-", s).strip("-")


_TRACKER_SCRIPT = "update_tracker.py"
# `python3 update_tracker.py <action>` / `.../update_tracker.py submitted`.
# `cat update_tracker.py` and friends stay allowed.
# Matches any action token, not an enumeration of the valid ones: the script
# has more actions than _TRACKER_ACTIONS (e.g. `attempted`), and a new one
# must not slip past this guard.
_TRACKER_EXEC_RE = re.compile(r"update_tracker\.py\s+[A-Za-z_]+")
_TRACKER_MAX_RECORD_BYTES = 100_000
RECORD_TIMEOUT_DEFAULT = 30
RECORD_TIMEOUT_MAX = 60


def record_tracker_event(args: dict[str, Any], base_dir: str | None,
                          timeout: int = RECORD_TIMEOUT_DEFAULT) -> str:
    """Record a job-apply outcome through update_tracker.py, no shell.

    The model used to hand-build the JSON inside an exec command line, kept
    getting the quoting wrong, and fell back to editing tracker.json with
    python3 -c - which skips the script's fcntl lock, submission-evidence
    check, high-watermark data-loss guard, and append-only events.jsonl. This
    passes the record as an argv list, so no quoting can corrupt it and the
    validated path is the only path.
    """
    script = Path(base_dir or ".") / _TRACKER_SCRIPT
    if not script.is_file():
        return f"Error: {_TRACKER_SCRIPT} not found in {base_dir or '.'}"

    action = str(args.get("action") or "submitted")
    if action not in _TRACKER_ACTIONS:
        return (f"Error: unknown action '{action}'. "
                f"Use one of: {', '.join(_TRACKER_ACTIONS)}")

    dropped = sorted(
        (set(args) - set(_TRACKER_CONTROL_KEYS)) & _TRACKER_FORBIDDEN_FIELDS
    )
    rec = {
        k: v for k, v in args.items()
        if k not in _TRACKER_FORBIDDEN_FIELDS
        and k not in _TRACKER_CONTROL_KEYS
        and v is not None and v != ""
    }
    if dropped:
        log.warning("record_submission: refusing to forge field(s): %s", dropped)
    # The dedupe keys are the campaign's join key (1751 of 1938 live
    # applications carry companyKey), so the tool derives them rather than
    # trusting the model's hand-built slug.
    for field, source in (("companyKey", "company"), ("roleKey", "roleTitle")):
        # A degenerate name ("Co", "SA", "---") normalises to "", and an empty
        # join key is worse than none: the script falls back to `company`.
        if rec.get(source) and (key := normalize_dedupe_key(str(rec[source]))):
            rec[field] = key
    missing = [f for f in _TRACKER_REQUIRED_FIELDS if not rec.get(f)]
    if missing:
        return f"Error: record is missing required field(s): {', '.join(missing)}"

    # ensure_ascii keeps argv byte-identical whatever locale the child decodes
    # it with.
    payload = json.dumps(rec, ensure_ascii=True)
    if len(payload) > _TRACKER_MAX_RECORD_BYTES:
        return (f"Error: record too large ({len(payload)} bytes, limit "
                f"{_TRACKER_MAX_RECORD_BYTES}); shorten notes")

    timeout = max(1, min(int(timeout), RECORD_TIMEOUT_MAX))
    try:
        # Fixed argv, no shell: the record cannot be corrupted by quoting.
        proc = subprocess.run(
            [sys.executable, str(script), action, payload],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            cwd=str(script.parent),
        )
    except subprocess.TimeoutExpired:
        return f"Error: {_TRACKER_SCRIPT} timed out after {timeout}s"
    except OSError as e:
        return f"Error: {_TRACKER_SCRIPT} could not be run: {e}"
    except Exception as e:
        return f"Error: {_TRACKER_SCRIPT} failed: {e}"

    parts = []
    if proc.stdout.strip():
        parts.append(proc.stdout.strip())
    if proc.stderr.strip():
        parts.append(f"stderr: {proc.stderr.strip()}")
    parts.append(f"exit={proc.returncode}")
    return _cap_output("\n".join(parts))


def read_file(path: str, base_dir: str | None = None, max_chars: int = 20000) -> str:
    """Read a file's contents, resolving relative paths against base_dir."""
    try:
        p = Path(path)
        if not p.is_absolute() and base_dir:
            p = Path(base_dir) / p
        text = p.read_text(encoding="utf-8", errors="replace")
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
        cv_paths: tuple[str, str] | None = None,
        cdp_url: str = "http://127.0.0.1:9222",
    ) -> None:
        self.playwright = playwright_client
        self.rag = rag_client
        self.default_cwd = default_cwd
        # (base, pl) CV paths; the PL variant wins when it verifies.
        self.cv_paths = cv_paths or (
            "/Users/mst/Downloads/job-search/job-apply/cv/michael-staszewski-cv.pdf",
            "/Users/mst/Downloads/job-search/job-apply/cv/michael-staszewski-cv-pl.pdf",
        )
        self.cdp_url = cdp_url

    @property
    def schemas(self) -> list[dict[str, Any]]:
        """Return tool schemas for the LLM."""
        return TOOL_SCHEMAS

    async def dispatch(self, name: str, args: dict[str, Any]) -> str:
        """Dispatch a tool call asynchronously."""
        if name == "exec":
            command = str(args.get("command", ""))
            if _TRACKER_EXEC_RE.search(command):
                # Stops the fallback seen in production: the model shelling
                # out to the tracker (and, when the quoting failed, editing
                # tracker.json by hand), which skips the script's lock,
                # evidence check, and event log. A guardrail, not a sandbox:
                # a python3 -c write to tracker.json is still reachable.
                return (f"Error: do not run {_TRACKER_SCRIPT} through exec. "
                        "Use the record_submission tool instead.")
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

        if name == "record_submission":
            try:
                timeout = int(args.get("timeout", RECORD_TIMEOUT_DEFAULT))
            except (TypeError, ValueError):
                timeout = RECORD_TIMEOUT_DEFAULT
            # subprocess.run blocks: keep it off the event loop.
            return await asyncio.to_thread(
                record_tracker_event, args, self.default_cwd, timeout,
            )

        if name == "upload_cv":
            if self.playwright is None:
                return "Error: Playwright MCP not available"
            cv = resolve_cv(self.cv_paths[0], self.cv_paths[1])
            try:
                return await upload_cv(
                    self.playwright,
                    self.cdp_url,
                    cv,
                    selector=args.get("selector"),
                    css_input=args.get("css_input", "input[type=file]"),
                )
            except asyncio.CancelledError:
                raise
            except Exception as e:
                return f"Error: upload_cv failed: {e}"

        if name in PLAYWRIGHT_TOOLS:
            if self.playwright is None:
                return "Error: Playwright MCP not available"
            try:
                return await self.playwright.call_tool(name, args, timeout=PLAYWRIGHT_TOOL_TIMEOUT)
            except Exception as e:
                return f"Error: Playwright tool '{name}' failed: {e}"

        if name in RAG_TOOLS:
            if self.rag is None:
                return "Error: RAG MCP not available"
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
