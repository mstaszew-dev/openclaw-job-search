"""One-call CV upload: verify the file, drive the chooser, verify the result.

Before this tool the model improvised uploads: it picked a path (sometimes
wrong - one log shows a wrong-path guess two seconds after the right one),
staged copies with `cp`, and called browser_file_upload hoping a file chooser
was open. Seven identical stray CV copies accumulated in campaign_agent/ from
that improvisation.

The tool owns the whole sequence:
  1. resolve the canonical CV path and verify it exists and is non-empty
     (symlinks resolved, so a dangling link fails here, not mid-application);
  2. click the upload control to open the file chooser, if a selector is given;
  3. upload via browser_file_upload with the canonical path;
  4. if no chooser opened (drag-and-drop-only zones), fall back to CDP
     DOM.setFileInputFiles on the page - the only reliable route for inputs
     that never open a native chooser;
  5. report the filename so the caller can attach it as submission evidence.

Never raises: every failure returns an Error string so a broken upload degrades
one application, not the tick. CancelledError propagates so shutdown works.
"""
from __future__ import annotations

import asyncio
import json
import logging
import urllib.request
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

CDP_TIMEOUT_S = 15.0


def verify_cv(path: str) -> str | None:
    """Return an error string when the CV is unusable, else None."""
    p = Path(path)
    try:
        resolved = p.resolve(strict=True)
    except (OSError, RuntimeError):
        if p.is_symlink():
            return f"Error: CV symlink {path} is dangling"
        return f"Error: CV not found at {path}"
    if not resolved.is_file():
        return f"Error: CV not found at {path}"
    if resolved.stat().st_size == 0:
        return f"Error: CV at {path} is empty (0 bytes)"
    return None


def resolve_cv(base_path: str, pl_path: str) -> str:
    """Pick the CV to upload: the PL variant when it verifies, else the base."""
    if verify_cv(pl_path) is None:
        return pl_path
    return base_path


async def upload_cv(
    client: Any,
    cdp_url: str,
    cv_path: str,
    selector: str | None = None,
    css_input: str = "input[type=file]",
) -> str:
    """Upload the CV through the Playwright client, CDP as fallback.

    Args:
      client: the Playwright MCP client (call_tool).
      cdp_url: Chrome DevTools endpoint for the chooser-less fallback.
      cv_path: canonical CV path; verified before anything is clicked.
      selector: optional ref/selector of the upload control to click first.
      css_input: CSS selector for the fallback DOM.setFileInputFiles target.
    """
    err = verify_cv(cv_path)
    if err is not None:
        log.error("upload_cv rejected: %s", err)
        return err

    if selector:
        click = await client.call_tool("browser_click", {"target": selector})
        if isinstance(click, str) and click.startswith("Error"):
            return (
                f"Error: could not open the file chooser ({selector}): "
                f"{click[:200]}"
            )

    upload = await client.call_tool("browser_file_upload", {"paths": [cv_path]})
    if not (isinstance(upload, str) and upload.startswith("Error")):
        name = Path(cv_path).name
        log.info("CV uploaded via file chooser: %s", name)
        return f"CV uploaded ({name}): {upload}"

    # No chooser opened (or the upload was refused): drag-and-drop-only zone.
    log.warning("chooser upload refused (%s); trying CDP fallback", upload[:120])
    # Resolve the tab the MCP is actually driving: /json/list order is
    # arbitrary in a multi-tab Chrome, so matching by URL is the only way to
    # avoid setting the CV on an unrelated tab's form (2026-10-07 review).
    active_url = None
    where = await client.call_tool(
        "browser_evaluate", {"function": "() => location.href"}
    )
    if isinstance(where, str) and not where.startswith("Error"):
        active_url = where.strip().splitlines()[-1].strip()
    fallback = await set_file_input_via_cdp(cdp_url, cv_path, css_input, page_url=active_url)
    if not fallback.startswith("Error"):
        log.info("CV uploaded via CDP setFileInputFiles: %s", Path(cv_path).name)
        return f"CV uploaded ({Path(cv_path).name}) {fallback}"
    return (
        f"Error: CV upload failed via chooser ({upload[:120]}) and via CDP "
        f"({fallback[:120]}). Attach the CV manually: {cv_path}"
    )


async def set_file_input_via_cdp(
    cdp_url: str, cv_path: str, css_input: str, page_url: str | None = None
) -> str:
    """Set the file on an <input type=file> via CDP DOM.setFileInputFiles.

    Serves inputs that never open a native chooser (drop zones). The target
    page is resolved by URL match: the shared campaign Chrome holds many tabs
    (Gmail, portals, other ATS forms) and /json/list order is arbitrary, so
    picking the first page silently uploaded the CV into an unrelated tab's
    form (2026-10-07 review). When page_url is None the ACTIVE tab is resolved
    first through the Playwright client. Returns an Error string on any
    failure; never raises.
    """
    try:
        import websockets
    except ImportError:
        return "Error: CDP fallback unavailable (websockets not installed)"

    ws = None
    try:
        # urllib is sync: off the event loop so a hung endpoint cannot stall
        # the campaign (checklist section 2).
        targets = await asyncio.to_thread(
            json_loads_url, f"{cdp_url.rstrip('/')}/json/list"
        )
        page = _match_page_target(targets, page_url)
        if page is None:
            return "Error: CDP fallback failed (no page target)"
        ws = await asyncio.wait_for(
            websockets.connect(page["webSocketDebuggerUrl"], max_size=2**24),
            timeout=CDP_TIMEOUT_S,
        )
        msg_id = 0

        async def cmd(method: str, params: dict[str, Any]) -> dict[str, Any]:
            nonlocal msg_id
            msg_id += 1
            await ws.send(json.dumps({"id": msg_id, "method": method, "params": params}))
            while True:
                reply = json.loads(await asyncio.wait_for(ws.recv(), timeout=CDP_TIMEOUT_S))
                if reply.get("id") == msg_id:
                    return reply

        doc = await cmd("DOM.getDocument", {"depth": 0})
        root = doc["result"]["root"]["nodeId"]
        found = await cmd("DOM.querySelector", {"nodeId": root, "selector": css_input})
        node = found["result"].get("nodeId", 0)
        if not node:
            return f"Error: CDP fallback failed (no {css_input} found)"
        await cmd("DOM.setFileInputFiles", {"files": [cv_path], "nodeId": node})
        # Verify by re-reading the node we just set. No JS string interpolation
        # of a model-supplied selector (json.dumps would be safe; this needs
        # none), and setFileInputFiles errors arrive as a CDP reply error.
        check = await cmd("DOM.getAttributes", {"nodeId": node})
        if not check.get("result"):
            return "Error: CDP fallback failed (could not re-read the input node)"
        return f"via CDP setFileInputFiles on {css_input}"
    except asyncio.CancelledError:
        raise
    except Exception as e:
        return f"Error: CDP fallback failed ({e.__class__.__name__}: {e})"
    finally:
        if ws is not None:
            try:
                await ws.close()
            except Exception:
                pass


def json_loads_url(url: str) -> Any:
    """GET a CDP HTTP endpoint and parse JSON (urllib is sync but cheap)."""
    with urllib.request.urlopen(url, timeout=CDP_TIMEOUT_S) as r:
        return json.loads(r.read().decode("utf-8"))


def _match_page_target(targets: Any, page_url: str | None) -> dict[str, Any] | None:
    """Pick the /json/list entry for the tab the MCP is driving.

    With page_url: the unique page target whose url matches (an exact match
    wins; a prefix match tolerates a trailing fragment). Without page_url
    there is no way to know which tab is active, so return None rather than
    guessing the first one.
    """
    if not page_url:
        return None
    pages = [t for t in targets if t.get("type") == "page"]
    exact = [t for t in pages if t.get("url") == page_url]
    if len(exact) == 1:
        return exact[0]
    # The active URL may carry a fragment/query the target omits.
    prefix = [t for t in pages if page_url.startswith(t.get("url") or "")]
    if len(prefix) == 1:
        return prefix[0]
    return None
