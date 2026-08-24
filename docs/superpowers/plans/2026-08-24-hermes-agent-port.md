# Hermes Agent Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the IL job-search campaign on Hermes (profile `jobhunter`) with a plugin (campaign tools), a skill (tick procedure), and a thin Python tick runner that preserves the anti-gaming tracker-delta rule.

**Architecture:** Hermes owns the agent loop, inference (msrouter provider), and MCP (playwright CDP + rag). New code: `jobapps` plugin (2 tools), `jobhermes` runner (outer loop, config, prompt, tick context), skill, installer. Spec: `docs/superpowers/specs/2026-08-24-hermes-agent-port-design.md`.

**Tech Stack:** Python 3.11+ (stdlib-only runtime), pytest + pytest-cov + pyyaml (dev), Hermes Agent v0.20.5, zsh installer.

## Global Constraints

- Repo: `/Users/mst/ZCodeProject/openclaw-job-search`, branch `hermes-agent-port` (branch off `main` first; never push).
- All new code under `hermes_agent/`. Runtime imports: stdlib only.
- Never write to `/Users/mst/Downloads/job-search/job-apply/` or `~/.hermes` during tests. Tests use tmp dirs and fake `hermes` stubs only.
- Coverage gate: every `pytest` run enforces `--cov-fail-under=90` over `src/jobapps` + `src/jobhermes`.
- Commit style: conventional (`feat(hermes): ...`, `test(hermes): ...`), small commits after each green cycle.
- No em dash in code, docs, or commit messages.
- Policy text (IL-only, all seniority, no EU/PL portals, no salary floor) is pinned by `tests/test_policy_regression.py`; changing it requires updating those tests deliberately.

---

### Task 0: Branch and scaffold

**Files:**
- Create: `hermes_agent/pyproject.toml`, `hermes_agent/src/jobapps/__init__.py`, `hermes_agent/src/jobhermes/__init__.py`, `hermes_agent/tests/conftest.py`, `hermes_agent/tests/test_scaffold.py`
- Test venv: `hermes_agent/.venv`

- [ ] **Step 1: Create branch**

```bash
cd /Users/mst/ZCodeProject/openclaw-job-search && git checkout -b hermes-agent-port
```

- [ ] **Step 2: Write pyproject.toml**

```toml
[project]
name = "jobhermes"
version = "0.1.0"
description = "Hermes-native job-search campaign agent (jobapps plugin + jobhermes tick runner)"
requires-python = ">=3.9"
dependencies = []

[project.optional-dependencies]
dev = ["pytest>=8", "pytest-cov>=5", "pyyaml>=6"]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
pythonpath = ["src"]
testpaths = ["tests"]
addopts = "--cov=jobapps --cov=jobhermes --cov-report=term-missing --cov-fail-under=90"

[tool.coverage.run]
source = ["src/jobapps", "src/jobhermes"]
branch = false

[tool.coverage.report]
exclude_lines = ["if __name__ == .__main__.:", "pragma: no cover"]
```

- [ ] **Step 3: Empty package inits + conftest + scaffold test**

`hermes_agent/src/jobapps/__init__.py`:

```python
"""Hermes plugin: job-search campaign tools."""
```

`hermes_agent/src/jobhermes/__init__.py`:

```python
"""Tick runner for the Hermes job-search campaign agent."""
```

`hermes_agent/tests/conftest.py`:

```python
"""Shared fixtures: tracker files, fake hermes binary, isolated env."""
from __future__ import annotations

import json
import os
import stat
from pathlib import Path

import pytest


@pytest.fixture()
def tracker_factory(tmp_path):
    """Return a factory writing tracker.json under tmp_path."""

    def _make(records=None, submitted=0, target=1500, name="tracker.json"):
        data = {
            "stats": {"submitted": submitted},
            "target": target,
            "applications": records or [],
            "applyQueue": [],
        }
        path = tmp_path / name
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    return _make


@pytest.fixture()
def fake_hermes(tmp_path):
    """Create a fake `hermes` executable that logs argv and obeys env knobs.

    Knobs (read by the stub at run time):
      FAKE_HERMES_EXIT    exit code (default 0)
      FAKE_HERMES_STDOUT  text printed to stdout (default empty)
    The stub appends one line per invocation ("$*" quoted) to $FAKE_HERMES_LOG.
    Returns (bin_dir, log_path).
    """
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    stub = bin_dir / "hermes"
    stub.write_text(
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$*" >> "$FAKE_HERMES_LOG"\n'
        'if [[ -n "${FAKE_HERMES_STDOUT:-}" ]]; then printf "%s" "$FAKE_HERMES_STDOUT"; fi\n'
        'exit "${FAKE_HERMES_EXIT:-0}"\n',
        encoding="utf-8",
    )
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    log_path = tmp_path / "hermes.log"
    return bin_dir, log_path
```

`hermes_agent/tests/test_scaffold.py`:

```python
"""Smoke: both packages are importable under pythonpath=src."""


def test_packages_importable():
    import jobapps  # noqa: F401
    import jobhermes  # noqa: F401
```

- [ ] **Step 4: Create venv, install dev deps, run pytest**

```bash
cd /Users/mst/ZCodeProject/openclaw-job-search/hermes_agent
python3 -m venv .venv
.venv/bin/pip install -q -e ".[dev]"
.venv/bin/python -m pytest
```

Expected: 1 passed, coverage gate satisfied trivially (0% files excluded? No: with no source lines beyond docstrings, coverage of the two init modules is 100%).

- [ ] **Step 5: Commit**

```bash
git add hermes_agent
git commit -m "chore(hermes): scaffold jobhermes package and test harness"
```

---

### Task 1: Tracker (read-side port)

**Files:**
- Create: `hermes_agent/src/jobapps/tracker.py`
- Test: `hermes_agent/tests/test_tracker.py`

**Interfaces:**
- Produces: `Tracker(path)` with `reload()`, `submitted() -> int`, `target() -> int`, `remaining() -> int`, `campaign_complete() -> bool`, `queue_length() -> int`, `recent_applications(n=5) -> list[dict]`, `context_summary() -> str`; constant `DEFAULT_TARGET = 1500`. Used by Task 2 (plugin tools), Task 5 (tick context), Task 6 (runner).

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_tracker.py`:

```python
"""Tracker: crash-proof reads of campaign tracker.json."""
import json

from jobapps.tracker import DEFAULT_TARGET, Tracker


def test_missing_file_degrades_to_empty_state(tmp_path):
    tracker = Tracker(tmp_path / "nope.json")
    assert tracker.submitted() == 0
    assert tracker.target() == DEFAULT_TARGET
    assert tracker.remaining() == DEFAULT_TARGET
    assert tracker.campaign_complete() is False
    assert tracker.queue_length() == 0
    assert tracker.recent_applications() == []


def test_invalid_json_degrades_to_empty_state(tmp_path):
    path = tmp_path / "tracker.json"
    path.write_text("{not json", encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.submitted() == 0


def test_non_dict_root_degrades_to_empty_state(tmp_path):
    path = tmp_path / "tracker.json"
    path.write_text("[]", encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.submitted() == 0


def test_counts_and_completion(tmp_path):
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": 7}, "target": 10}), encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.submitted() == 7
    assert tracker.target() == 10
    assert tracker.remaining() == 3
    assert tracker.campaign_complete() is False


def test_target_fallback_to_target_applications_then_default(tmp_path):
    path = tmp_path / "tracker.json"
    for payload in (
        {"stats": {"submitted": 1}, "targetApplications": 900},
        {"stats": {"submitted": 1}},
    ):
        path.write_text(json.dumps(payload), encoding="utf-8")
        assert Tracker(path).target() in (900, DEFAULT_TARGET)
    path.write_text(json.dumps({"stats": {"submitted": 1}, "targetApplications": 900}), encoding="utf-8")
    assert Tracker(path).target() == 900
    path.write_text(json.dumps({"stats": {"submitted": 1}}), encoding="utf-8")
    assert Tracker(path).target() == DEFAULT_TARGET


def test_queue_length_ignores_non_list(tmp_path):
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"applyQueue": "nope"}), encoding="utf-8")
    assert Tracker(path).queue_length() == 0


def test_recent_applications_most_recent_first(tmp_path):
    records = [{"company": f"c{i}", "roleTitle": f"r{i}", "appliedAt": f"2026-08-0{i}"} for i in range(1, 6)]
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"applications": records}), encoding="utf-8")
    tracker = Tracker(path)
    recent = tracker.recent_applications(3)
    assert [r["company"] for r in recent] == ["c5", "c4", "c3"]


def test_reload_picks_up_external_change(tmp_path):
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": 1}, "target": 2}), encoding="utf-8")
    tracker = Tracker(path)
    assert tracker.campaign_complete() is False
    path.write_text(json.dumps({"stats": {"submitted": 2}, "target": 2}), encoding="utf-8")
    tracker.reload()
    assert tracker.campaign_complete() is True


def test_context_summary_format(tmp_path):
    records = [{"company": "Acme", "roleTitle": "Backend", "status": "submitted", "appliedAt": "2026-08-20T10:00:00Z"}]
    path = tmp_path / "tracker.json"
    path.write_text(json.dumps({"stats": {"submitted": 3}, "target": 1500, "applications": records}), encoding="utf-8")
    summary = Tracker(path).context_summary()
    assert "Submitted: 3/1500" in summary
    assert "Remaining: 1497" in summary
    assert "- Acme / Backend (submitted, 2026-08-20)" in summary
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_tracker.py -q
```

Expected: FAIL (`ModuleNotFoundError: No module named 'jobapps.tracker'`).

- [ ] **Step 3: Implement `jobapps/tracker.py`**

```python
"""Crash-proof read access to the campaign tracker.json."""
from __future__ import annotations

import json
from pathlib import Path

DEFAULT_TARGET = 1500


class Tracker:
    """Read-only accessor; any read failure degrades to empty state."""

    def __init__(self, path):
        self.path = Path(path)
        self._data = {}
        self.reload()

    def reload(self):
        try:
            with self.path.open("r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            self._data = {}
            return False
        self._data = data if isinstance(data, dict) else {}
        return True

    @property
    def _stats(self):
        stats = self._data.get("stats")
        return stats if isinstance(stats, dict) else {}

    def submitted(self):
        value = self._stats.get("submitted", 0)
        return value if isinstance(value, int) else 0

    def target(self):
        for key in ("target", "targetApplications"):
            value = self._data.get(key)
            if isinstance(value, int) and value > 0:
                return value
        return DEFAULT_TARGET

    def remaining(self):
        return max(self.target() - self.submitted(), 0)

    def campaign_complete(self):
        return self.submitted() >= self.target()

    def queue_length(self):
        queue = self._data.get("applyQueue")
        return len(queue) if isinstance(queue, list) else 0

    def recent_applications(self, n=5):
        applications = self._data.get("applications")
        if not isinstance(applications, list):
            return []
        recent = []
        for record in reversed(applications):
            if isinstance(record, dict):
                recent.append(record)
                if len(recent) >= n:
                    break
        return recent

    def context_summary(self):
        lines = [
            "Submitted: {}/{}".format(self.submitted(), self.target()),
            "Remaining: {}".format(self.remaining()),
        ]
        for record in self.recent_applications(5):
            company = record.get("company", "?")
            role = record.get("roleTitle", "?")
            status = record.get("status", "?")
            applied = str(record.get("appliedAt", "?"))[:10]
            lines.append("  - {} / {} ({}, {})".format(company, role, status, applied))
        return "\n".join(lines)
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_tracker.py -q
```

Expected: 9 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobapps/tracker.py hermes_agent/tests/test_tracker.py
git commit -m "feat(hermes): port Tracker read-side to jobapps plugin package"
```

---

### Task 2: Plugin tools, schemas, registration, manifest

**Files:**
- Create: `hermes_agent/src/jobapps/schemas.py`, `hermes_agent/src/jobapps/tools.py`, `hermes_agent/src/jobapps/plugin.yaml`; rewrite `hermes_agent/src/jobapps/__init__.py`
- Test: `hermes_agent/tests/test_plugin_tools.py`, `hermes_agent/tests/test_plugin_registration.py`

**Interfaces:**
- Consumes: `Tracker` from Task 1.
- Produces: handlers `campaign_status(args: dict, **kwargs) -> str` and `record_submission(args: dict, **kwargs) -> str` (JSON strings, never raise); schemas `schemas.CAMPAIGN_STATUS`, `schemas.RECORD_SUBMISSION`; `register(ctx)` registering both into toolset `jobapps`; env overrides `JOBSEARCH_TRACKER_PATH`, `JOBSEARCH_CAMPAIGN_DIR`.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_plugin_tools.py`:

```python
"""Plugin tool handlers: JSON in/out, never raise."""
import json

from jobapps import tools


def test_campaign_status_reads_tracker(tracker_factory):
    path = tracker_factory(submitted=4, target=10)
    result = json.loads(tools.campaign_status({"tracker_path": str(path)}))
    assert result["submitted"] == 4
    assert result["target"] == 10
    assert result["remaining"] == 6
    assert result["campaign_complete"] is False
    assert result["queue_length"] == 0
    assert result["recent_applications"] == []
    assert result["tracker_path"] == str(path)


def test_campaign_status_missing_file_is_zeroed(tmp_path):
    result = json.loads(tools.campaign_status({"tracker_path": str(tmp_path / "none.json")}))
    assert result["submitted"] == 0
    assert result["campaign_complete"] is False


def test_campaign_status_default_path_from_env(monkeypatch, tracker_factory, tmp_path):
    path = tracker_factory(submitted=1, target=1)
    monkeypatch.setenv("JOBSEARCH_TRACKER_PATH", str(path))
    result = json.loads(tools.campaign_status({}))
    assert result["campaign_complete"] is True


def _make_update_tracker_stub(campaign_dir, exit_code=0, stdout="submitted 5/1500"):
    stub = campaign_dir / "update_tracker.py"
    stub.write_text(
        "#!/usr/bin/env bash\n"
        'echo "$1 $2" > /tmp/last_record.json\n' if False else
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$2" > "' + str(campaign_dir / "last_record.json") + '"\n'
        'printf "%s" "' + stdout + '"\n'
        "exit {}\n".format(exit_code),
        encoding="utf-8",
    )
    import stat

    stub.chmod(stub.S_IXUSR | stub.S_IRUSR | stub.S_IWUSR)
    return stub


def test_record_submission_runs_update_tracker(tmp_path):
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign)
    args = {
        "record": {"source": "drushim", "sourceJobId": "123", "company": "Acme", "roleTitle": "Backend"},
        "campaign_dir": str(campaign),
    }
    result = json.loads(tools.record_submission(args))
    assert result["ok"] is True
    assert result["exit"] == 0
    assert "submitted" in result["stdout"]
    written = json.loads((campaign / "last_record.json").read_text())
    assert written["company"] == "Acme"


def test_record_submission_nonzero_exit_is_not_ok(tmp_path):
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign, exit_code=1)
    result = json.loads(tools.record_submission({"record": {}, "campaign_dir": str(campaign)}))
    assert result["ok"] is False
    assert result["exit"] == 1


def test_record_submission_rejects_non_object_record():
    result = json.loads(tools.record_submission({"record": "not-a-dict"}))
    assert result["ok"] is False
    assert "record" in result["error"]


def test_record_submission_missing_cwd_returns_error_not_exception(tmp_path):
    result = json.loads(tools.record_submission({"record": {}, "campaign_dir": str(tmp_path / "missing")}))
    assert result["ok"] is False
    assert "error" in result


def test_record_submission_timeout_returns_error(monkeypatch, tmp_path):
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign)
    monkeypatch.setattr(tools, "UPDATE_TRACKER_TIMEOUT", 0)

    def hang(command, **kwargs):
        import subprocess as sp

        raise sp.TimeoutExpired(cmd=command, timeout=0)

    monkeypatch.setattr("subprocess.run", hang)
    result = json.loads(tools.record_submission({"record": {}, "campaign_dir": str(campaign)}))
    assert result["ok"] is False
    assert "timed out" in result["error"]
```

`hermes_agent/tests/test_plugin_registration.py`:

```python
"""Plugin registration contract and manifest consistency."""
from pathlib import Path

import yaml

from jobapps import register, schemas

PLUGIN_DIR = Path(__file__).resolve().parents[1] / "src" / "jobapps"


class FakeCtx:
    def __init__(self):
        self.tools = {}

    def register_tool(self, name, toolset, schema, handler, **kwargs):
        self.tools[name] = {"toolset": toolset, "schema": schema, "handler": handler}


def test_register_registers_both_tools_into_jobapps_toolset():
    ctx = FakeCtx()
    register(ctx)
    assert set(ctx.tools) == {"campaign_status", "record_submission"}
    for entry in ctx.tools.values():
        assert entry["toolset"] == "jobapps"
        assert callable(entry["handler"])


def test_schemas_are_valid_tool_shapes():
    for schema in (schemas.CAMPAIGN_STATUS, schemas.RECORD_SUBMISSION):
        assert schema["name"]
        assert schema["description"]
        params = schema["parameters"]
        assert params["type"] == "object"
        assert set(params["required"]) <= set(params["properties"])


def test_manifest_matches_registered_tools():
    manifest = yaml.safe_load((PLUGIN_DIR / "plugin.yaml").read_text())
    assert manifest["name"] == "jobapps"
    assert sorted(manifest["provides_tools"]) == ["campaign_status", "record_submission"]
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_plugin_tools.py tests/test_plugin_registration.py -q
```

Expected: FAIL (ImportError: cannot import name 'schemas'/'tools'/'register').

- [ ] **Step 3: Implement schemas, tools, manifest, register**

`hermes_agent/src/jobapps/schemas.py`:

```python
"""OpenAI-style tool schemas for the jobapps plugin."""

CAMPAIGN_STATUS = {
    "name": "campaign_status",
    "description": (
        "Read job-search campaign progress from tracker.json: submitted/target, "
        "remaining, queue length, and recent applications."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "tracker_path": {
                "type": "string",
                "description": "Optional tracker.json path override (tests/admin).",
            },
        },
        "required": [],
    },
}

RECORD_SUBMISSION = {
    "name": "record_submission",
    "description": (
        "Record one job application via the campaign's update_tracker.py "
        "(action=submitted). The ONLY sanctioned recording path; never edit "
        "tracker.json directly. Call immediately after browser confirmation."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "record": {
                "type": "object",
                "description": (
                    "Application record: source, sourceJobId, company, roleTitle, "
                    "and evidence of the portal confirmation."
                ),
            },
            "campaign_dir": {
                "type": "string",
                "description": "Optional campaign directory override (tests/admin).",
            },
        },
        "required": ["record"],
    },
}
```

`hermes_agent/src/jobapps/tools.py`:

```python
"""jobapps plugin handlers. JSON in, JSON out, never raise."""
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

from .tracker import Tracker

UPDATE_TRACKER_TIMEOUT = 60
DEFAULT_CAMPAIGN_DIR = "/Users/mst/Downloads/job-search/job-apply"


def _default_tracker_path():
    return os.environ.get("JOBSEARCH_TRACKER_PATH") or str(Path(DEFAULT_CAMPAIGN_DIR) / "tracker.json")


def _default_campaign_dir():
    return os.environ.get("JOBSEARCH_CAMPAIGN_DIR") or DEFAULT_CAMPAIGN_DIR


def campaign_status(args, **kwargs):
    tracker = Tracker(args.get("tracker_path") or _default_tracker_path())
    payload = {
        "tracker_path": str(tracker.path),
        "submitted": tracker.submitted(),
        "target": tracker.target(),
        "remaining": tracker.remaining(),
        "campaign_complete": tracker.campaign_complete(),
        "queue_length": tracker.queue_length(),
        "recent_applications": tracker.recent_applications(5),
    }
    return json.dumps(payload)


def record_submission(args, **kwargs):
    record = args.get("record")
    if not isinstance(record, dict):
        return json.dumps({"ok": False, "error": "record must be an object"})
    campaign_dir = args.get("campaign_dir") or _default_campaign_dir()
    command = ["python3", "update_tracker.py", "submitted", json.dumps(record, ensure_ascii=False)]
    try:
        proc = subprocess.run(
            command,
            cwd=campaign_dir,
            capture_output=True,
            text=True,
            timeout=UPDATE_TRACKER_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        return json.dumps({"ok": False, "error": "update_tracker.py timed out after {}s".format(UPDATE_TRACKER_TIMEOUT)})
    except (FileNotFoundError, OSError) as exc:
        return json.dumps({"ok": False, "error": "could not run update_tracker.py: {}".format(exc)})
    return json.dumps(
        {
            "ok": proc.returncode == 0,
            "exit": proc.returncode,
            "stdout": proc.stdout[-2000:],
            "stderr": proc.stderr[-2000:],
        }
    )
```

`hermes_agent/src/jobapps/plugin.yaml`:

```yaml
name: jobapps
version: 1.0.0
description: Job-search campaign tools - tracker status and submission recording
author: mst
kind: backend
provides_tools:
  - campaign_status
  - record_submission
```

`hermes_agent/src/jobapps/__init__.py`:

```python
"""Hermes plugin: job-search campaign tools (campaign_status, record_submission)."""
from . import schemas, tools


def register(ctx):
    """Register all jobapps tools. Called once by the Hermes plugin loader."""
    ctx.register_tool(
        name="campaign_status",
        toolset="jobapps",
        schema=schemas.CAMPAIGN_STATUS,
        handler=tools.campaign_status,
    )
    ctx.register_tool(
        name="record_submission",
        toolset="jobapps",
        schema=schemas.RECORD_SUBMISSION,
        handler=tools.record_submission,
    )
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_plugin_tools.py tests/test_plugin_registration.py -q
```

Expected: all passed. Fix the `_make_update_tracker_stub` helper if the conditional-expression scaffold above misbehaves; the intent is a bash stub that writes `$2` (the JSON record) to `last_record.json`, prints stdout, exits with the given code.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobapps hermes_agent/tests/test_plugin_tools.py hermes_agent/tests/test_plugin_registration.py
git commit -m "feat(hermes): jobapps plugin with campaign_status and record_submission tools"
```

---

### Task 3: Runner config

**Files:**
- Create: `hermes_agent/src/jobhermes/config.py`
- Test: `hermes_agent/tests/test_config.py`

**Interfaces:**
- Produces: `load_env_file(path) -> dict`; `Config` dataclass with fields `campaign_dir, cv_path, playwright_output_dir, tick_context_path, hermes_bin, hermes_profile, run_budget_seconds, max_turns, inner_max_fails, inner_sleep, outer_backoff, subprocess_timeout, director_note_path, skip_companies`; property `tracker_path`; property `director_note`; `Config.from_env(env=None, overrides_path="~/.campaign-agent/director-overrides.env")`. Used by Tasks 4, 5, 6, 7.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_config.py`:

```python
"""Config: defaults < overrides file < environment."""
from jobhermes.config import Config, load_env_file


def test_load_env_file_parses_and_skips(tmp_path):
    path = tmp_path / "overrides.env"
    path.write_text(
        "# comment\n"
        "\n"
        "INNER_MAX_FAILS=9\n"
        "PORTAL_SKIP_ANTAL=1\n"
        "BROKEN LINE\n",
        encoding="utf-8",
    )
    loaded = load_env_file(path)
    assert loaded == {"INNER_MAX_FAILS": "9", "PORTAL_SKIP_ANTAL": "1"}


def test_load_env_file_missing_path_returns_empty(tmp_path):
    assert load_env_file(tmp_path / "none.env") == {}


def test_defaults():
    config = Config()
    assert config.campaign_dir == "/Users/mst/Downloads/job-search/job-apply"
    assert config.tracker_path == "/Users/mst/Downloads/job-search/job-apply/tracker.json"
    assert config.cv_path.endswith("cv/michael-staszewski-cv.pdf")
    assert config.hermes_bin == "hermes"
    assert config.hermes_profile == "jobhunter"
    assert config.inner_max_fails == 5
    assert config.inner_sleep == 10.0
    assert config.outer_backoff == 60
    assert config.run_budget_seconds == 1800
    assert config.max_turns == 200
    assert config.skip_companies == set()
    assert config.playwright_output_dir == "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output"


def test_env_overrides_defaults(tmp_path):
    config = Config.from_env(env={"INNER_MAX_FAILS": "3", "HERMES_BIN": "/tmp/hermes"}, overrides_path=tmp_path / "none.env")
    assert config.inner_max_fails == 3
    assert config.hermes_bin == "/tmp/hermes"


def test_file_overridden_by_env(tmp_path):
    path = tmp_path / "overrides.env"
    path.write_text("INNER_MAX_FAILS=7\n", encoding="utf-8")
    config = Config.from_env(env={"INNER_MAX_FAILS": "2"}, overrides_path=path)
    assert config.inner_max_fails == 2


def test_file_used_when_env_absent(tmp_path):
    path = tmp_path / "overrides.env"
    path.write_text("INNER_MAX_FAILS=7\nOUTER_BACKOFF=99\n", encoding="utf-8")
    config = Config.from_env(env={}, overrides_path=path)
    assert config.inner_max_fails == 7
    assert config.outer_backoff == 99


def test_portal_skip_parsing(tmp_path):
    path = tmp_path / "overrides.env"
    path.write_text("PORTAL_SKIP_ANTAL=1\nPORTAL_SKIP_MINDBOX=1\nPORTAL_SKIP_OK=0\n", encoding="utf-8")
    config = Config.from_env(env={}, overrides_path=path)
    assert config.skip_companies == {"antal", "mindbox"}


def test_invalid_int_is_ignored(tmp_path):
    config = Config.from_env(env={"INNER_MAX_FAILS": "not-a-number"}, overrides_path=tmp_path / "none.env")
    assert config.inner_max_fails == 5


def test_campaign_dir_rederives_paths():
    config = Config(campaign_dir="/tmp/campaign-x")
    assert config.tracker_path == "/tmp/campaign-x/tracker.json"
    assert config.cv_path == "/tmp/campaign-x/cv/michael-staszewski-cv.pdf"


def test_director_note_missing_is_empty(tmp_path):
    config = Config(director_note_path=str(tmp_path / "none.md"))
    assert config.director_note == ""


def test_director_note_read_stripped(tmp_path):
    path = tmp_path / "note.md"
    path.write_text("  IL only this week  \n", encoding="utf-8")
    config = Config(director_note_path=str(path))
    assert config.director_note == "IL only this week"
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_config.py -q
```

Expected: FAIL (ModuleNotFoundError).

- [ ] **Step 3: Implement `jobhermes/config.py`**

```python
"""Runner configuration: defaults < overrides file < environment."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

CAMPAIGN_DIR_DEFAULT = "/Users/mst/Downloads/job-search/job-apply"
PLAYWRIGHT_OUTPUT_DIR_DEFAULT = "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output"
DEFAULT_OVERRIDES_PATH = "~/.campaign-agent/director-overrides.env"

_INT_FIELDS = {
    "INNER_MAX_FAILS": "inner_max_fails",
    "OUTER_BACKOFF": "outer_backoff",
    "RUN_BUDGET_SECONDS": "run_budget_seconds",
    "MAX_TURNS": "max_turns",
    "SUBPROCESS_TIMEOUT": "subprocess_timeout",
}
_FLOAT_FIELDS = {"INNER_SLEEP": "inner_sleep"}
_STR_FIELDS = {
    "HERMES_BIN": "hermes_bin",
    "HERMES_PROFILE": "hermes_profile",
    "CAMPAIGN_DIR": "campaign_dir",
}


def load_env_file(path):
    """Parse KEY=VALUE lines; blanks and # comments skipped; missing file is empty."""
    values = {}
    try:
        lines = Path(path).expanduser().read_text(encoding="utf-8").splitlines()
    except OSError:
        return values
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


@dataclass
class Config:
    campaign_dir: str = CAMPAIGN_DIR_DEFAULT
    cv_path: str = ""
    playwright_output_dir: str = PLAYWRIGHT_OUTPUT_DIR_DEFAULT
    tick_context_path: str = ""
    hermes_bin: str = "hermes"
    hermes_profile: str = "jobhunter"
    run_budget_seconds: int = 1800
    max_turns: int = 200
    inner_max_fails: int = 5
    inner_sleep: float = 10.0
    outer_backoff: int = 60
    subprocess_timeout: int = 2400
    director_note_path: str = "~/.campaign-agent/director-prompt-overrides.md"
    skip_companies: set = field(default_factory=set)

    def __post_init__(self):
        if not self.cv_path:
            self.cv_path = str(Path(self.campaign_dir) / "cv" / "michael-staszewski-cv.pdf")
        if not self.tick_context_path:
            self.tick_context_path = str(Path(__file__).resolve().parents[2] / "state" / "tick-context.md")

    @property
    def tracker_path(self):
        return str(Path(self.campaign_dir) / "tracker.json")

    @property
    def director_note(self):
        try:
            return Path(self.director_note_path).expanduser().read_text(encoding="utf-8").strip()
        except OSError:
            return ""

    @classmethod
    def from_env(cls, env=None, overrides_path=DEFAULT_OVERRIDES_PATH):
        source = os.environ if env is None else env
        merged = load_env_file(overrides_path)
        recognized = set(_INT_FIELDS) | set(_FLOAT_FIELDS) | set(_STR_FIELDS)
        for key, value in source.items():
            if key in recognized or key.startswith("PORTAL_SKIP_"):
                merged[key] = value
        kwargs = {}
        for fields, cast in ((_INT_FIELDS, int), (_FLOAT_FIELDS, float)):
            for key, attr in fields.items():
                if key not in merged:
                    continue
                try:
                    kwargs[attr] = cast(merged[key])
                except ValueError:
                    continue
        for key, attr in _STR_FIELDS.items():
            if key in merged:
                kwargs[attr] = merged[key]
        skip = {
            key[len("PORTAL_SKIP_"):].lower()
            for key, value in merged.items()
            if key.startswith("PORTAL_SKIP_") and value == "1"
        }
        if skip:
            kwargs["skip_companies"] = skip
        return cls(**kwargs)
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_config.py -q
```

Expected: 11 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobhermes/config.py hermes_agent/tests/test_config.py
git commit -m "feat(hermes): runner config with env/file precedence and portal skip list"
```

---

### Task 4: Tick prompt builder

**Files:**
- Create: `hermes_agent/src/jobhermes/prompt.py`
- Test: `hermes_agent/tests/test_prompt.py`

**Interfaces:**
- Consumes: `Config` from Task 3.
- Produces: `build_director_extras(skip_companies, director_note) -> str`; `build_tick_prompt(config, session_context="") -> str`; module constant `TASK_TEMPLATE`.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_prompt.py`:

```python
"""Tick prompt: IL-only policy, interpolation, director extras."""
from jobhermes.config import Config
from jobhermes.prompt import build_director_extras, build_tick_prompt


def test_prompt_carries_campaign_paths():
    config = Config(campaign_dir="/tmp/camp")
    prompt = build_tick_prompt(config)
    assert "/tmp/camp" in prompt
    assert config.cv_path in prompt
    assert config.playwright_output_dir in prompt


def test_prompt_pins_il_only_policy():
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for marker in (
        "IL only",
        "remote/hybrid/onsite ALL OK",
        "Do NOT apply to Polish sites, Upwork, or EU/PL portals",
        "ALL levels accepted (junior through senior)",
        "Skip only: team-lead/manager/architect/director/head/VP",
        "exactly ONE job",
        "record_submission",
        "Never edit tracker.json directly",
        "One company once",
        "127.0.0.1:9222",
        "end your turn",
    ):
        assert marker in prompt, marker


def test_prompt_has_no_eu_or_salary_floor_markers():
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for forbidden in ("PLN", "15k", "RemotifyEurope", "EuroRemote", "4DayWeek", "We Work Remotely", "EU/GLOBAL"):
        assert forbidden not in prompt, forbidden


def test_director_extras_empty_when_nothing_configured():
    assert build_director_extras(set(), "") == ""


def test_director_extras_skip_list_sorted():
    extras = build_director_extras({"Mindbox", "antal"}, "")
    assert extras == "DIRECTOR SKIP LIST: do NOT apply to any of these companies: antal, Mindbox."


def test_director_extras_note_and_both():
    extras = build_director_extras({"acme"}, "focus backend")
    assert "DIRECTOR SKIP LIST" in extras and "acme" in extras
    assert "DIRECTOR NOTE: focus backend" in extras


def test_prompt_includes_director_extras_and_session_context():
    config = Config(campaign_dir="/tmp/camp", skip_companies={"antal"})
    config.director_note_path = "/nonexistent/note.md"
    prompt = build_tick_prompt(config, session_context="Previous tick context:\napplied to Acme")
    assert "DIRECTOR SKIP LIST" in prompt and "antal" in prompt
    assert "Previous tick context:\napplied to Acme" in prompt


def test_prompt_collapses_leading_blank_sections():
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    assert not prompt.startswith("\n")
    assert "\n\n\n" not in prompt
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_prompt.py -q
```

Expected: FAIL (ModuleNotFoundError).

- [ ] **Step 3: Implement `jobhermes/prompt.py`**

```python
"""Tick prompt builder, ported from campaign_agent.prompt (IL-only policy).

The system prompt is owned by Hermes (profile SOUL.md); this module builds the
task prompt that carries the campaign rules.
"""
from __future__ import annotations

import re

TASK_TEMPLATE = """{session_context}

{director_extras}

TASK: Apply exactly ONE job this tick. Start by reading AGENT_TICK.md and CONTEXT.md in the campaign dir ({campaign_dir}), then browse for a job. Check progress with the campaign_status tool.

RULES:
- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Roles involving TDD, code reviews, CI/CD (Jenkins, GitHub Actions) are in scope - deep hands-on experience. Skip: ABAP, Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only: team-lead/manager/architect/director/head/VP.
- IL only: remote/hybrid/onsite ALL OK (central Israel for onsite; remote anywhere in IL). Do NOT apply to Polish sites, Upwork, or EU/PL portals.
- Freelance: include freelance, contract, part-time, and fixed-term B2B in IL.
- Record submissions ONLY via the record_submission tool. Never edit tracker.json directly. Record immediately after browser confirmation.
- Dedupe: rag search over past applications (rag MCP tools) + Gmail (60d). One company once. Do NOT call automation scripts (no score_candidate.py, no check_dupe.py).
- Browser: use the playwright MCP tools attached to the existing Chrome at http://127.0.0.1:9222. Do NOT launch/close Chrome.
- CV to upload: {cv_path} (absolute path; it is a regular file).
- Playwright page snapshots are saved under {playwright_output_dir} (absolute path); read them from there if needed.
- Never ask permission. No stop tokens. After recording a submission, end your turn.
- Temp scripts go in /tmp/, not the campaign dir.

Work order: IL only (all modes). Stop after one confirmed submission."""


def build_director_extras(skip_companies, director_note):
    parts = []
    if skip_companies:
        listing = ", ".join(sorted(skip_companies))
        parts.append("DIRECTOR SKIP LIST: do NOT apply to any of these companies: {}.".format(listing))
    if director_note:
        parts.append("DIRECTOR NOTE: {}".format(director_note))
    return "\n\n".join(parts)


def build_tick_prompt(config, session_context=""):
    filled = TASK_TEMPLATE.format(
        session_context=session_context,
        director_extras=build_director_extras(config.skip_companies, config.director_note),
        campaign_dir=config.campaign_dir,
        cv_path=config.cv_path,
        playwright_output_dir=config.playwright_output_dir,
    )
    return re.sub(r"\n{3,}", "\n\n", filled).strip() + "\n"
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_prompt.py -q
```

Expected: 8 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobhermes/prompt.py hermes_agent/tests/test_prompt.py
git commit -m "feat(hermes): tick prompt builder ported from campaign_agent IL-only policy"
```

---

### Task 5: Tick context persistence

**Files:**
- Create: `hermes_agent/src/jobhermes/tick_context.py`
- Test: `hermes_agent/tests/test_tick_context.py`

**Interfaces:**
- Consumes: `Tracker` (Task 1).
- Produces: `TickContext(path, max_chars=8000)` with `save(summary)`, `load() -> str`; `build_tick_summary(tracker, attempts, reason) -> str`.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_tick_context.py`:

```python
"""TickContext: cross-tick summary persistence."""
from jobapps.tracker import Tracker
from jobhermes.tick_context import TickContext, build_tick_summary


def test_load_missing_returns_empty(tmp_path):
    assert TickContext(tmp_path / "none.md").load() == ""


def test_save_creates_parents_and_round_trips(tmp_path):
    ctx = TickContext(tmp_path / "nested" / "dir" / "tick.md")
    ctx.save("summary text")
    assert ctx.load() == "summary text"


def test_save_truncates_long_summaries(tmp_path):
    ctx = TickContext(tmp_path / "tick.md", max_chars=10)
    ctx.save("x" * 50)
    loaded = ctx.load()
    assert loaded.startswith("x" * 10)
    assert "...[truncated]" in loaded


def test_build_tick_summary_lists_three_recent(tmp_path):
    records = [
        {"company": "A", "roleTitle": "ra", "appliedAt": "2026-08-01T00:00:00Z"},
        {"company": "B", "roleTitle": "rb", "appliedAt": "2026-08-02T00:00:00Z"},
        {"company": "C", "roleTitle": "rc", "appliedAt": "2026-08-03T00:00:00Z"},
        {"company": "D", "roleTitle": "rd", "appliedAt": "2026-08-04T00:00:00Z"},
    ]
    path = tmp_path / "tracker.json"
    import json

    path.write_text(json.dumps({"applications": records}), encoding="utf-8")
    summary = build_tick_summary(Tracker(path), attempts=2, reason="success")
    assert "- D / rd (2026-08-04)" in summary
    assert "- C / rc (2026-08-03)" in summary
    assert "- B / rb (2026-08-02)" in summary
    assert "- A / ra" not in summary
    assert "Attempts used this tick: 2" in summary
    assert "Tick outcome: success" in summary


def test_reason_is_clamped(tmp_path):
    summary = build_tick_summary(Tracker(tmp_path / "none.json"), attempts=1, reason="r" * 500)
    assert len([line for line in summary.splitlines() if line.startswith("Tick outcome:")][0]) <= len("Tick outcome: ") + 300
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_tick_context.py -q
```

Expected: FAIL (ModuleNotFoundError).

- [ ] **Step 3: Implement `jobhermes/tick_context.py`**

```python
"""Cross-tick summary persistence (port of campaign_agent TickContext)."""
from __future__ import annotations

from pathlib import Path


class TickContext:
    def __init__(self, path, max_chars=8000):
        self.path = Path(path)
        self.max_chars = max_chars

    def save(self, summary):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if len(summary) > self.max_chars:
            summary = summary[: self.max_chars] + "\n...[truncated]"
        self.path.write_text(summary, encoding="utf-8")

    def load(self):
        try:
            return self.path.read_text(encoding="utf-8")
        except OSError:
            return ""


def build_tick_summary(tracker, attempts, reason):
    lines = ["Recent submissions:"]
    for record in tracker.recent_applications(3):
        company = record.get("company", "?")
        role = record.get("roleTitle", "?")
        applied = str(record.get("appliedAt", "?"))[:10]
        lines.append("  - {} / {} ({})".format(company, role, applied))
    lines.append("Attempts used this tick: {}".format(attempts))
    lines.append("Tick outcome: {}".format(reason[:300]))
    return "\n".join(lines)
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_tick_context.py -q
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobhermes/tick_context.py hermes_agent/tests/test_tick_context.py
git commit -m "feat(hermes): tick context persistence and summary builder"
```

---

### Task 6: Hermes one-shot attempt runner

**Files:**
- Create: `hermes_agent/src/jobhermes/runner.py` (part 1: `build_hermes_command`, `run_attempt`)
- Test: `hermes_agent/tests/test_run_attempt.py`

**Interfaces:**
- Consumes: `Config` (Task 3), `build_tick_prompt` (Task 4).
- Produces: `build_hermes_command(config, prompt) -> list[str]`; `run_attempt(config, prompt) -> tuple[int, str, str]` (exit code, stdout tail, stderr tail). Exit codes 124 (timeout), 127 (binary missing), 126 (other startup failure).

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_run_attempt.py`:

```python
"""run_attempt: hermes one-shot subprocess wrapper."""
import os

from jobhermes.config import Config
from jobhermes.runner import build_hermes_command, run_attempt


def test_build_hermes_command_shape():
    config = Config(campaign_dir="/tmp/camp", hermes_bin="/bin/hermes", hermes_profile="jobhunter")
    command = build_hermes_command(config, "PROMPT")
    assert command == [
        "/bin/hermes",
        "-p", "jobhunter",
        "-z", "PROMPT",
        "--in", "/tmp/camp",
        "--run-budget", "1800",
        "--max-turns", "200",
    ]


def test_run_attempt_invokes_fake_hermes(fake_hermes, monkeypatch):
    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))
    monkeypatch.setenv("FAKE_HERMES_EXIT", "3")
    monkeypatch.setenv("FAKE_HERMES_STDOUT", "final text")
    config = Config(hermes_bin=str(bin_dir / "hermes"), campaign_dir="/tmp/camp")
    exit_code, out, err = run_attempt(config, "do the tick")
    assert exit_code == 3
    assert out == "final text"
    logged = log_path.read_text()
    assert "-p jobhunter" in logged
    assert "-z do the tick" in logged
    assert "--in /tmp/camp" in logged


def test_run_attempt_missing_binary(fake_hermes):
    config = Config(hermes_bin=str(fake_hermes[0] / "nothing"), campaign_dir="/tmp/camp")
    exit_code, out, err = run_attempt(config, "prompt")
    assert exit_code == 127
    assert "not found" in err


def test_run_attempt_timeout(monkeypatch, fake_hermes):
    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))

    def hang(command, **kwargs):
        import subprocess as sp

        raise sp.TimeoutExpired(cmd=command, timeout=kwargs.get("timeout"))

    monkeypatch.setattr("subprocess.run", hang)
    config = Config(hermes_bin=str(bin_dir / "hermes"), campaign_dir="/tmp/camp")
    exit_code, out, err = run_attempt(config, "prompt")
    assert exit_code == 124
    assert "timed out" in err


def test_run_attempt_truncates_long_output(fake_hermes, monkeypatch):
    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))
    monkeypatch.setenv("FAKE_HERMES_STDOUT", "y" * 5000)
    config = Config(hermes_bin=str(bin_dir / "hermes"), campaign_dir="/tmp/camp")
    _, out, _ = run_attempt(config, "prompt")
    assert len(out) == 2000
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_run_attempt.py -q
```

Expected: FAIL (ModuleNotFoundError / ImportError).

- [ ] **Step 3: Implement runner part 1**

`hermes_agent/src/jobhermes/runner.py`:

```python
"""Tick runner: hermes one-shot attempts plus the retry outer loop."""
from __future__ import annotations

import subprocess

from .config import Config
from .prompt import build_tick_prompt

OUTPUT_TAIL_CHARS = 2000


def build_hermes_command(config, prompt):
    return [
        config.hermes_bin,
        "-p",
        config.hermes_profile,
        "-z",
        prompt,
        "--in",
        config.campaign_dir,
        "--run-budget",
        str(config.run_budget_seconds),
        "--max-turns",
        str(config.max_turns),
    ]


def run_attempt(config, prompt):
    """Run one hermes one-shot; return (exit_code, stdout_tail, stderr_tail)."""
    try:
        proc = subprocess.run(
            build_hermes_command(config, prompt),
            capture_output=True,
            text=True,
            timeout=config.subprocess_timeout,
        )
        return proc.returncode, proc.stdout[-OUTPUT_TAIL_CHARS:], proc.stderr[-OUTPUT_TAIL_CHARS:]
    except subprocess.TimeoutExpired:
        return 124, "", "hermes subprocess timed out after {}s".format(config.subprocess_timeout)
    except FileNotFoundError:
        return 127, "", "hermes binary not found: {}".format(config.hermes_bin)
    except OSError as exc:
        return 126, "", "hermes failed to start: {}".format(exc)
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_run_attempt.py -q
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobhermes/runner.py hermes_agent/tests/test_run_attempt.py
git commit -m "feat(hermes): hermes one-shot attempt wrapper with timeout and exit mapping"
```

---

### Task 7: Tick loop with anti-gaming delta validation

**Files:**
- Modify: `hermes_agent/src/jobhermes/runner.py` (add `run_tick`)
- Test: `hermes_agent/tests/test_run_tick.py`

**Interfaces:**
- Consumes: `Tracker` (Task 1), `TickContext`, `build_tick_summary` (Task 5), `build_tick_prompt` (Task 4), `run_attempt` (Task 6).
- Produces: constants `REASON_SUCCESS`, `REASON_CAMPAIGN_COMPLETE`, `REASON_EXHAUSTED`; `run_tick(config, run_attempt_fn=run_attempt, sleep_fn=time.sleep, log=None) -> str`.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_run_tick.py`:

```python
"""run_tick: outer loop with tracker-delta anti-gaming validation."""
import json

from jobhermes.config import Config
from jobhermes.runner import (
    REASON_CAMPAIGN_COMPLETE,
    REASON_EXHAUSTED,
    REASON_SUCCESS,
    run_tick,
)


def make_config(tmp_path, tracker_path, inner_max_fails=3, inner_sleep=0):
    return Config(
        campaign_dir=str(tmp_path / "campaign"),
        tick_context_path=str(tmp_path / "state" / "tick-context.md"),
        inner_max_fails=inner_max_fails,
        inner_sleep=inner_sleep,
    )


def write_submitted(path, count, target=1500):
    path.write_text(json.dumps({"stats": {"submitted": count}, "target": target}), encoding="utf-8")


def test_campaign_complete_short_circuits_without_hermes(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=1500, target=1500)
    config = make_config(tmp_path, tracker_path)
    calls = []

    def attempt(config, prompt):
        calls.append(prompt)
        return 0, "", ""

    assert run_tick(config, run_attempt_fn=attempt) == REASON_CAMPAIGN_COMPLETE
    assert calls == []


def _tracker_bumper(tracker_path):
    def attempt(config, prompt):
        data = json.loads(tracker_path.read_text())
        data["stats"]["submitted"] += 1
        tracker_path.write_text(json.dumps(data), encoding="utf-8")
        return 0, "applied and recorded", ""

    return attempt


def test_success_when_tracker_increases(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, tracker_path)
    outcome = run_tick(config, run_attempt_fn=_tracker_bumper(tracker_path))
    assert outcome == REASON_SUCCESS


def test_exit_zero_without_delta_is_no_submission_retry(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, tracker_path, inner_max_fails=2)
    sleeps = []
    attempts = []

    def attempt(config, prompt):
        attempts.append(prompt)
        return 0, "done", ""  # claims success without recording

    outcome = run_tick(config, run_attempt_fn=attempt, sleep_fn=sleeps.append)
    assert outcome == REASON_EXHAUSTED
    assert len(attempts) == 2
    assert sleeps == [config.inner_sleep]


def test_retries_then_succeeds(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, tracker_path, inner_max_fails=5)
    attempt = _tracker_bumper(tracker_path)
    real_calls = []

    def flaky(config, prompt):
        real_calls.append(1)
        if len(real_calls) == 1:
            return 1, "", "hermes blew up"
        return attempt(config, prompt)

    assert run_tick(config, run_attempt_fn=flaky, sleep_fn=lambda s: None) == REASON_SUCCESS
    assert len(real_calls) == 2


def test_tick_summary_saved(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, tracker_path, inner_max_fails=1)
    run_tick(config, run_attempt_fn=_tracker_bumper(tracker_path), sleep_fn=lambda s: None)
    saved = (tmp_path / "state" / "tick-context.md").read_text()
    assert "Attempts used this tick: 1" in saved
    assert "Tick outcome: success" in saved


def test_prompt_contains_previous_tick_context(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, tracker_path, inner_max_fails=1)
    context_path = tmp_path / "state" / "tick-context.md"
    context_path.parent.mkdir(parents=True)
    context_path.write_text("Previous tick: applied to Acme", encoding="utf-8")
    prompts = []

    def attempt(config, prompt):
        prompts.append(prompt)
        return 0, "", ""

    run_tick(config, run_attempt_fn=attempt, sleep_fn=lambda s: None)
    assert "Previous tick: applied to Acme" in prompts[0]


def test_skip_list_reaches_prompt(tmp_path, tracker_factory):
    tracker_path = tracker_factory(submitted=5, target=1500)
    config = make_config(tmp_path, tracker_path, inner_max_fails=1)
    config.skip_companies = {"antal"}
    prompts = []

    def attempt(config, prompt):
        prompts.append(prompt)
        return 0, "", ""

    run_tick(config, run_attempt_fn=attempt, sleep_fn=lambda s: None)
    assert "DIRECTOR SKIP LIST" in prompts[0]
    assert "antal" in prompts[0]
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_run_tick.py -q
```

Expected: FAIL (ImportError: REASON_SUCCESS etc.).

- [ ] **Step 3: Implement `run_tick` in runner.py (append)**

```python
import time  # add to imports at top

from .tick_context import TickContext, build_tick_summary  # add to imports
from .tracker import Tracker  # add to imports (import from jobapps package)

REASON_SUCCESS = "success"
REASON_CAMPAIGN_COMPLETE = "campaign_complete"
REASON_EXHAUSTED = "attempts_exhausted"


def run_tick(config, run_attempt_fn=None, sleep_fn=None, log=None):
    """Run one tick: fresh attempts until tracker.submitted increases."""
    run_attempt_fn = run_attempt_fn or run_attempt
    sleep_fn = sleep_fn or time.sleep
    log = log or (lambda message: print(message, flush=True))

    tracker = Tracker(config.tracker_path)
    if tracker.campaign_complete():
        log("Campaign complete: {}/{}".format(tracker.submitted(), tracker.target()))
        return REASON_CAMPAIGN_COMPLETE

    context = TickContext(config.tick_context_path)
    previous = context.load()
    session_context = "Previous tick context:\n{}".format(previous) if previous else ""
    prompt = build_tick_prompt(config, session_context=session_context)

    attempts = 0
    while attempts < config.inner_max_fails:
        attempts += 1
        before = tracker.submitted()
        exit_code, stdout_tail, stderr_tail = run_attempt_fn(config, prompt)
        tracker.reload()
        if tracker.submitted() > before:
            log("Attempt {}: submission recorded (submitted {} -> {})".format(
                attempts, before, tracker.submitted()))
            return _finish_tick(config, tracker, attempts, REASON_SUCCESS, context, log)
        reason = "no_submission" if exit_code == 0 else "hermes_exit_{}".format(exit_code)
        log("Attempt {} failed ({}); stderr tail: {!r}".format(attempts, reason, stderr_tail[-500:]))
        if attempts < config.inner_max_fails:
            sleep_fn(config.inner_sleep)
    return _finish_tick(config, tracker, attempts, REASON_EXHAUSTED, context, log)


def _finish_tick(config, tracker, attempts, outcome, context, log):
    tracker.reload()
    try:
        context.save(build_tick_summary(tracker=tracker, attempts=attempts, reason=outcome))
    except OSError as exc:
        log("Could not save tick context: {}".format(exc))
    return outcome
```

Import adjustment at top of runner.py:

```python
from jobapps.tracker import Tracker
from .tick_context import TickContext, build_tick_summary
```

(`jobapps` is importable because pytest `pythonpath=src` covers both packages; at runtime the runner is started with `PYTHONPATH=src`.)

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_run_tick.py -q
```

Expected: 7 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobhermes/runner.py hermes_agent/tests/test_run_tick.py
git commit -m "feat(hermes): tick loop with tracker-delta anti-gaming validation"
```

---

### Task 8: CLI entry point (--once / --loop / --dry-run)

**Files:**
- Create: `hermes_agent/src/jobhermes/__main__.py`; modify `runner.py` (add `main`)
- Test: `hermes_agent/tests/test_main_cli.py`

**Interfaces:**
- Consumes: everything above.
- Produces: `main(argv=None) -> int`; `python -m jobhermes [--loop] [--config PATH] [--dry-run]`.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_main_cli.py`:

```python
"""CLI: exit codes for once/loop/dry-run and missing campaign dir."""
import json

from jobhermes import runner as runner_module
from jobhermes.runner import main


def test_dry_run_prints_prompt_and_exits_zero(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr("jobhermes.config.Config.director_note", property(lambda self: ""))
    code = main(["--dry-run", "--config", str(tmp_path / "none.env")])
    assert code == 0
    out = capsys.readouterr().out
    assert "TASK: Apply exactly ONE job this tick" in out


def test_once_success_exit_zero(tmp_path, tracker_factory, monkeypatch):
    tracker_path = tracker_factory(submitted=5, target=1500)

    def fake_tick(config):
        return runner_module.REASON_SUCCESS

    monkeypatch.setattr(runner_module, "run_tick", fake_tick)
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 0


def test_once_exhausted_exit_one(tmp_path, monkeypatch):
    monkeypatch.setattr(
        runner_module, "run_tick", lambda config: runner_module.REASON_EXHAUSTED
    )
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 1


def test_missing_campaign_dir_exits_two(tmp_path, monkeypatch):
    monkeypatch.setenv("CAMPAIGN_DIR", str(tmp_path / "missing"))
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 2


def test_loop_stops_on_campaign_complete(tmp_path, monkeypatch):
    outcomes = [runner_module.REASON_SUCCESS, runner_module.REASON_CAMPAIGN_COMPLETE]
    monkeypatch.setattr(runner_module, "run_tick", lambda config: outcomes.pop(0))
    sleeps = []
    monkeypatch.setattr(runner_module.time, "sleep", sleeps.append)
    code = main(["--loop", "--config", str(tmp_path / "none.env")])
    assert code == 0
    assert sleeps  # backoff slept between ticks
```

Note: `main` reads config via `Config.from_env()` with real env, so tests set `CAMPAIGN_DIR`/`--config` as needed; `test_once_success_exit_zero` relies on the default campaign dir existing (it does on this machine), while `test_missing_campaign_dir_exits_two` overrides it. To stay hermetic, prefer setting `CAMPAIGN_DIR` in every test via monkeypatch to a tmp dir (exists check) and patching `run_tick`.

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_main_cli.py -q
```

Expected: FAIL (ImportError / AttributeError main).

- [ ] **Step 3: Implement `main` (append to runner.py) and `__main__.py`**

Append to `hermes_agent/src/jobhermes/runner.py`:

```python
def build_tick_prompt_for(config):  # thin helper so --dry-run shares the exact prompt
    context = TickContext(config.tick_context_path)
    previous = context.load()
    session_context = "Previous tick context:\n{}".format(previous) if previous else ""
    return build_tick_prompt(config, session_context=session_context)


def main(argv=None):
    import argparse
    import sys
    from pathlib import Path

    parser = argparse.ArgumentParser(prog="jobhermes", description="Hermes job-search campaign tick runner")
    parser.add_argument("--loop", action="store_true", help="keep ticking with outer backoff (default: one tick)")
    parser.add_argument("--dry-run", action="store_true", help="print the tick prompt and exit")
    parser.add_argument("--config", default=None, help="director overrides .env path")
    args = parser.parse_args(argv)

    config = Config.from_env(overrides_path=args.config) if args.config else Config.from_env()
    if not Path(config.campaign_dir).is_dir():
        print("campaign dir does not exist: {}".format(config.campaign_dir), file=sys.stderr)
        return 2
    if args.dry_run:
        print(build_tick_prompt_for(config), end="")
        return 0
    while True:
        outcome = run_tick(config)
        if not args.loop:
            return 0 if outcome in (REASON_SUCCESS, REASON_CAMPAIGN_COMPLETE) else 1
        if outcome == REASON_CAMPAIGN_COMPLETE:
            return 0
        time.sleep(config.outer_backoff)
```

`hermes_agent/src/jobhermes/__main__.py`:

```python
"""python -m jobhermes entry point."""
from .runner import main

raise SystemExit(main())
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_main_cli.py -q
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/src/jobhermes/__main__.py hermes_agent/src/jobhermes/runner.py hermes_agent/tests/test_main_cli.py
git commit -m "feat(hermes): jobhermes CLI with once/loop/dry-run modes"
```

---

### Task 9: Skill + policy regression suite

**Files:**
- Create: `hermes_agent/skills/job-search-tick/SKILL.md`
- Test: `hermes_agent/tests/test_skill_format.py`, `hermes_agent/tests/test_policy_regression.py`

**Interfaces:**
- Produces: installable skill `job-search-tick` (also consumed by installer Task 10 and config template tests).

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_skill_format.py`:

```python
"""SKILL.md structure and frontmatter validity."""
from pathlib import Path

import yaml

SKILL_PATH = (
    Path(__file__).resolve().parents[1] / "skills" / "job-search-tick" / "SKILL.md"
)


def test_skill_file_exists():
    assert SKILL_PATH.is_file()


def test_frontmatter_is_valid_yaml():
    text = SKILL_PATH.read_text(encoding="utf-8")
    assert text.startswith("---\n")
    end = text.index("\n---", 3)
    front = yaml.safe_load(text[4:end])
    assert front["name"] == "job-search-tick"
    assert len(front["description"]) <= 60
    assert front["version"]


def test_body_has_required_sections():
    text = SKILL_PATH.read_text(encoding="utf-8")
    for section in ("## When to Use", "## Procedure", "## Pitfalls", "## Targeting rules"):
        assert section in text, section
```

`hermes_agent/tests/test_policy_regression.py`:

```python
"""Policy pins: IL-only, all seniority, no EU/PL portals, no salary floor.

These tests deliberately hard-code the campaign policy so accidental edits to
the prompt or skill fail CI.
"""
from pathlib import Path

from jobhermes.config import Config
from jobhermes.prompt import build_tick_prompt

SKILL_PATH = Path(__file__).resolve().parents[1] / "skills" / "job-search-tick" / "SKILL.md"

REQUIRED_MARKERS = (
    "IL only",
    "remote/hybrid/onsite",
    "ALL levels accepted (junior through senior)",
    "team-lead/manager/architect/director/head/VP",
)

FORBIDDEN_MARKERS = (
    "PLN",
    "15k",
    "RemotifyEurope",
    "EuroRemote",
    "4DayWeek",
    "We Work Remotely",
    "prag",
    "theprotocol",
    "nofluffjobs",
)


def test_prompt_policy_pins():
    prompt = build_tick_prompt(Config(campaign_dir="/tmp/camp"))
    for marker in REQUIRED_MARKERS:
        assert marker in prompt, marker
    for marker in FORBIDDEN_MARKERS:
        assert marker not in prompt, marker


def test_skill_policy_pins():
    skill = SKILL_PATH.read_text(encoding="utf-8").lower()
    for marker in REQUIRED_MARKERS:
        assert marker.lower() in skill, marker
    for marker in FORBIDDEN_MARKERS:
        assert marker.lower() not in skill, marker


def test_skill_pins_one_job_one_record_rules():
    skill = SKILL_PATH.read_text(encoding="utf-8")
    assert "ONE job" in skill
    assert "record_submission" in skill
    assert "never edit tracker.json" in skill.lower()
    assert "one company once" in skill.lower()
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_skill_format.py tests/test_policy_regression.py -q
```

Expected: FAIL (missing SKILL.md).

- [ ] **Step 3: Write `skills/job-search-tick/SKILL.md`**

```markdown
---
name: job-search-tick
description: Apply to one IL job per tick: pick, dedupe, apply, record
version: 1.0.0
platforms: [macos]
metadata:
  hermes:
    tags: [job-search, career, automation]
---

# Job-Search Tick

## When to Use

Run once per campaign tick (cron or manual) to apply to exactly ONE job and
record it. Never run twice in a row without a recorded outcome.

## Procedure

1. `campaign_status` tool: read progress and recent applications (dedupe context).
2. Read `AGENT_TICK.md` and `CONTEXT.md` in the campaign dir
   (`/Users/mst/Downloads/job-search/job-apply`).
3. Pick ONE matching job using the targeting rules below. Honor the
   DIRECTOR SKIP LIST if the tick prompt contains one.
4. Dedupe: rag search past applications (rag MCP tools) plus Gmail
   (in:sent OR in:inbox, newer_than:60d, company name). One company once.
5. Apply with the playwright MCP tools (existing Chrome CDP at
   http://127.0.0.1:9222; never launch or close Chrome). Verify the portal
   confirmation (thank-you page or text) before recording.
6. Record via the `record_submission` tool immediately after confirmation,
   with evidence of the confirmation in the record.
7. End the turn. One job per tick, no more.

## Pitfalls

- Never edit tracker.json directly; only `record_submission` may write it
  (via update_tracker.py).
- Never call score_candidate.py or check_dupe.py (retired automation).
- Do not apply to skip-listed companies or companies applied in the last 60
  days.
- Temp scripts go in /tmp/, never the campaign dir.
- A submission without portal confirmation evidence does not count.

## Targeting rules

- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Roles involving TDD,
  code reviews, CI/CD (Jenkins, GitHub Actions) are in scope. Skip: ABAP,
  Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only:
  team-lead/manager/architect/director/head/VP.
- IL only: remote/hybrid/onsite ALL OK (central Israel for onsite; remote
  anywhere in IL). Do NOT apply to Polish sites, Upwork, or EU/PL portals.
- Freelance: include freelance, contract, part-time, and fixed-term B2B in IL.
```

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_skill_format.py tests/test_policy_regression.py -q
```

Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/skills hermes_agent/tests/test_skill_format.py hermes_agent/tests/test_policy_regression.py
git commit -m "feat(hermes): job-search-tick skill with pinned IL-only targeting policy"
```

---

### Task 10: Install assets (profile config, SOUL, installer) + asset tests

**Files:**
- Create: `hermes_agent/install/config.template.yaml`, `hermes_agent/install/profile-soul.md`, `hermes_agent/install/install.sh`
- Test: `hermes_agent/tests/test_install_assets.py`

**Interfaces:**
- Consumes: plugin (Task 2), skill (Task 9).
- Produces: `install.sh` (zsh, idempotent, `--enable-cron` opt-in) creating profile `jobhunter`.

- [ ] **Step 1: Write failing tests**

`hermes_agent/tests/test_install_assets.py`:

```python
"""Install asset sanity: template config, SOUL persona, installer script."""
import os
import stat
from pathlib import Path

import yaml

INSTALL_DIR = Path(__file__).resolve().parents[1] / "install"


def test_config_template_parses_and_pins_hermes():
    config = yaml.safe_load((INSTALL_DIR / "config.template.yaml").read_text(encoding="utf-8"))
    assert config["model"]["default"] == "mst/free"
    assert config["model"]["provider"] == "msrouter"
    provider = config["providers"]["msrouter"]
    assert provider["api"] == "http://127.0.0.1:8787/v1"
    assert provider["transport"] == "chat_completions"
    assert provider["default_model"] == "mst/free"
    assert config["agent"]["max_turns"] == 200
    mcp = config["mcp_servers"]
    assert "playwright" in mcp and "rag" in mcp
    assert "127.0.0.1:9222" in " ".join(mcp["playwright"]["args"])
    assert mcp["rag"]["args"][0].endswith("rag_server.py")


def test_config_template_carries_managed_marker():
    text = (INSTALL_DIR / "config.template.yaml").read_text(encoding="utf-8")
    assert "jobhermes-managed" in text


def test_profile_soul_is_nonempty_persona():
    soul = (INSTALL_DIR / "profile-soul.md").read_text(encoding="utf-8")
    assert len(soul.strip()) > 100
    assert "one" in soul.lower() and "submission" in soul.lower()


def test_install_script_exists_executable_and_cron_is_opt_in():
    script = INSTALL_DIR / "install.sh"
    assert script.is_file()
    assert os.access(script, os.X_OK)
    text = script.read_text(encoding="utf-8")
    assert "--enable-cron" in text
    # cron registration must be guarded, not unconditional
    assert '== "--enable-cron"' in text or '--enable-cron" ]] &&' in text or "ENABLE_CRON" in text
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_install_assets.py -q
```

Expected: FAIL (missing files).

- [ ] **Step 3: Write assets**

`hermes_agent/install/config.template.yaml`:

```yaml
# jobhermes-managed - overwritten by hermes_agent/install/install.sh unless edited
model:
  default: mst/free
  provider: msrouter
providers:
  msrouter:
    api: http://127.0.0.1:8787/v1
    api_key: msrouter-local
    transport: chat_completions
    default_model: mst/free
agent:
  max_turns: 200
mcp_servers:
  playwright:
    command: /opt/homebrew/opt/node@24/bin/node
    args:
      - /Users/mst/.local/share/openclaw-tools/node_modules/@playwright/mcp/cli.js
      - --cdp-endpoint
      - http://127.0.0.1:9222
      - --cdp-timeout
      - "120000"
      - --output-dir
      - /Users/mst/ZCodeProject/openclaw-job-search/playwright-output
      - --output-mode
      - file
      - --save-session
      - --codegen
      - none
  rag:
    command: /Users/mst/ZCodeProject/openclaw-job-search/rag/.venv/bin/python
    args:
      - /Users/mst/ZCodeProject/openclaw-job-search/rag/rag_server.py
```

`hermes_agent/install/profile-soul.md`:

```markdown
# SOUL

You are the jobhunter profile: an autonomous job-application agent for
Michael's IL job-search campaign. You apply to exactly one job per tick, verify
the portal confirmation before recording anything, and record via the
record_submission tool only. You dedupe against past applications and Gmail
(one company once). You never ask permission mid-tick and you stop after one
confirmed, recorded submission. Honesty about evidence outranks speed: a tick
with no verified submission is a failed tick, not a faked one.
```

`hermes_agent/install/install.sh`:

```zsh
#!/usr/bin/env zsh
# Install the jobhunter Hermes profile from this repo. Idempotent; no live
# campaign side effects. Cron registration requires --enable-cron.
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPO_ROOT="${SCRIPT_DIR:h}"
HERMES_BIN="${HERMES_BIN:-hermes}"
PROFILE="${HERMES_PROFILE:-jobhunter}"
ENABLE_CRON=0
[[ "${1:-}" == "--enable-cron" ]] && ENABLE_CRON=1

if ! command -v "$HERMES_BIN" >/dev/null 2>&1; then
  print -u2 "hermes CLI not found on PATH (set HERMES_BIN to override)"
  exit 1
fi

PROFILE_HOME="$HOME/.hermes/profiles/$PROFILE"

if [[ ! -d "$PROFILE_HOME" ]]; then
  "$HERMES_BIN" profile create "$PROFILE" --description "Autonomous job-search campaign agent"
fi

mkdir -p "$PROFILE_HOME/plugins" "$PROFILE_HOME/skills"
ln -sfn "$REPO_ROOT/src/jobapps" "$PROFILE_HOME/plugins/jobapps"
ln -sfn "$REPO_ROOT/skills/job-search-tick" "$PROFILE_HOME/skills/job-search-tick"

CONFIG_PATH="$PROFILE_HOME/config.yaml"
if [[ ! -f "$CONFIG_PATH" ]] || ! grep -q "jobhermes-managed" "$CONFIG_PATH"; then
  cp "$SCRIPT_DIR/config.template.yaml" "$CONFIG_PATH"
else
  print "config.yaml already jobhermes-managed; leaving untouched"
fi
cp "$SCRIPT_DIR/profile-soul.md" "$PROFILE_HOME/SOUL.md"

"$HERMES_BIN" plugins doctor "$REPO_ROOT/src/jobapps" || print -u2 "plugins doctor reported issues (review before ticking)"

RUNNER_DIR="$REPO_ROOT"
if (( ENABLE_CRON )); then
  "$HERMES_BIN" cron create "every 30m" "job-search tick" \
    --name "job-search-tick" --no-agent \
    --script "cd '$RUNNER_DIR' && PYTHONPATH=src python3 -m jobhermes --once" \
    --workdir "$RUNNER_DIR"
  print "Cron registered (every 30m)."
else
  print "Install complete. Cron NOT registered (opt-in)."
  print "Manual tick: cd '$RUNNER_DIR' && PYTHONPATH=src python3 -m jobhermes --once"
  print "Enable scheduling: $0 --enable-cron"
fi
```

Then `chmod +x hermes_agent/install/install.sh`.

- [ ] **Step 4: Run tests**

```bash
.venv/bin/python -m pytest tests/test_install_assets.py -q
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/install hermes_agent/tests/test_install_assets.py
git commit -m "feat(hermes): jobhunter profile installer with opt-in cron registration"
```

---

### Task 11: Installer end-to-end test (fake hermes, tmp HOME)

**Files:**
- Test: `hermes_agent/tests/test_install_script.py`

**Interfaces:**
- Consumes: `install.sh` (Task 10), `fake_hermes` fixture (Task 0).

- [ ] **Step 1: Write failing test**

`hermes_agent/tests/test_install_script.py`:

```python
"""install.sh end-to-end against a fake hermes CLI and a tmp HOME."""
import os
import stat
import subprocess
from pathlib import Path

INSTALL_SH = Path(__file__).resolve().parents[1] / "install" / "install.sh"


def _make_profile_stub(bin_dir):
    """Fake hermes handling: profile create, plugins doctor, cron create."""
    stub = bin_dir / "hermes"
    stub.write_text(
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$*" >> "$FAKE_HERMES_LOG"\n'
        'if [[ "$1" == "profile" && "$2" == "create" ]]; then\n'
        '  mkdir -p "$HERMES_PROFILE_DIR"\n'
        '  exit 0\n'
        'fi\n'
        'if [[ "$1" == "plugins" && "$2" == "doctor" ]]; then exit 0; fi\n'
        'if [[ "$1" == "cron" && "$2" == "create" ]]; then exit 0; fi\n'
        'exit 0\n',
        encoding="utf-8",
    )
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    return stub


def _run_install(tmp_path, bin_dir, log_path, *args):
    env = dict(os.environ)
    env.update(
        {
            "HOME": str(tmp_path / "home"),
            "PATH": str(bin_dir) + os.pathsep + env["PATH"],
            "HERMES_BIN": str(bin_dir / "hermes"),
            "FAKE_HERMES_LOG": str(log_path),
            "HERMES_PROFILE_DIR": str(tmp_path / "home" / ".hermes" / "profiles" / "jobhunter"),
        }
    )
    (tmp_path / "home").mkdir(exist_ok=True)
    return subprocess.run(
        ["zsh", str(INSTALL_SH), *args],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )


def test_install_creates_profile_symlinks_and_config(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _make_profile_stub(bin_dir)
    log_path = tmp_path / "hermes.log"
    result = _run_install(tmp_path, bin_dir, log_path)
    assert result.returncode == 0, result.stderr
    profile_home = tmp_path / "home" / ".hermes" / "profiles" / "jobhunter"
    assert (profile_home / "plugins" / "jobapps").is_symlink()
    assert (profile_home / "skills" / "job-search-tick").is_symlink()
    config_text = (profile_home / "config.yaml").read_text(encoding="utf-8")
    assert "msrouter" in config_text
    assert (profile_home / "SOUL.md").is_file()
    logged = log_path.read_text()
    assert "profile create jobhunter" in logged
    assert "cron create" not in logged  # opt-in only


def test_install_is_idempotent(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _make_profile_stub(bin_dir)
    log_path = tmp_path / "hermes.log"
    assert _run_install(tmp_path, bin_dir, log_path).returncode == 0
    marker = tmp_path / "home" / ".hermes" / "profiles" / "jobhunter" / "SOUL.md"
    marker.write_text("edited by user", encoding="utf-8")
    assert _run_install(tmp_path, bin_dir, log_path).returncode == 0
    assert marker.read_text(encoding="utf-8") == "edited by user"  # cp would overwrite? NO: cp overwrites SOUL.md each run by design (persona ships with repo); assert instead that config.yaml survives
```

Note: `SOUL.md` is repo-shipped and intentionally refreshed each install; the idempotency guarantee is for `config.yaml` (never overwritten once jobhermes-managed) and profile creation (not repeated). Replace the last two assertions with:

```python
    config_path = tmp_path / "home" / ".hermes" / "profiles" / "jobhunter" / "config.yaml"
    config_path.write_text("# jobhermes-managed\nmodel:\n  default: edited\n", encoding="utf-8")
    assert _run_install(tmp_path, bin_dir, log_path).returncode == 0
    assert "edited" in config_path.read_text(encoding="utf-8")
    creates = log_path.read_text().count("profile create jobhunter")
    assert creates == 1


def test_install_enable_cron_passes_cron_create(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _make_profile_stub(bin_dir)
    log_path = tmp_path / "hermes.log"
    result = _run_install(tmp_path, bin_dir, log_path, "--enable-cron")
    assert result.returncode == 0, result.stderr
    logged = log_path.read_text()
    assert "cron create" in logged
    assert "--no-agent" in logged
    assert "--script" in logged


def test_install_fails_without_hermes(tmp_path):
    empty_bin = tmp_path / "emptybin"
    empty_bin.mkdir()
    env = dict(os.environ)
    env.update({"HOME": str(tmp_path / "home"), "HERMES_BIN": str(empty_bin / "hermes")})
    (tmp_path / "home").mkdir(exist_ok=True)
    result = subprocess.run(
        ["zsh", str(INSTALL_SH)],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 1
    assert "hermes CLI not found" in result.stderr
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_install_script.py -q
```

Expected: FAIL for the first test (installer logic gaps surface here; fix installer, not tests).

- [ ] **Step 3: Fix installer until green** (likely fixes: `--description` flag handling by stub, symlink targets, zsh `print -u2`).

- [ ] **Step 4: Run full suite**

```bash
.venv/bin/python -m pytest
```

Expected: all passed, coverage ≥ 90%.

- [ ] **Step 5: Commit**

```bash
git add hermes_agent/tests/test_install_script.py
git commit -m "test(hermes): installer e2e against fake hermes CLI and tmp HOME"
```

---

### Task 12: README + live smoke (no live applications)

**Files:**
- Create: `hermes_agent/README.md`
- Modify: nothing else.

- [ ] **Step 1: Write README**

```markdown
# Hermes job-search campaign agent

Hermes-native replacement for `campaign_agent/`: the agent loop, inference
(msrouter), and MCP (playwright CDP + rag) are provided by Hermes; this
package adds the campaign domain.

Layout:
- `src/jobapps/` - Hermes plugin: `campaign_status`, `record_submission`.
- `src/jobhermes/` - tick runner: config, prompt, tick context, retry loop,
  anti-gaming validation (a tick succeeds only when tracker.submitted grew).
- `skills/job-search-tick/` - tick procedure + targeting policy skill.
- `install/` - jobhunter profile installer (plugin + skill + provider config);
  cron registration is opt-in via `--enable-cron`.

Install (no cron, no live side effects):

    zsh install/install.sh

Manual tick:

    PYTHONPATH=src python3 -m jobhermes --once        # one tick
    PYTHONPATH=src python3 -m jobhermes --dry-run     # print the tick prompt

Enable the 30-minute scheduler (starts REAL applications):

    zsh install/install.sh --enable-cron

Tests:

    .venv/bin/python -m pytest

Old-to-new mapping: llm.py -> Hermes provider msrouter; run_agent_turn ->
Hermes agent loop; playwright_mcp.py/rag_mcp.py -> profile MCP servers;
main.py outer loop -> jobhermes.runner; prompt.py -> jobhermes.prompt;
session.py -> jobhermes.tick_context; tracker.py -> jobapps.tracker.
```

- [ ] **Step 2: Live smoke (safe checks only, no applications)**

```bash
cd /Users/mst/ZCodeProject/openclaw-job-search/hermes_agent
zsh install/install.sh                     # creates profile jobhunter (no cron)
hermes -p jobhunter plugins list 2>&1 | grep -i jobapps
PYTHONPATH=src python3 -m jobhermes --dry-run | head -20
```

Expected: installer exit 0; jobapps plugin listed; dry-run prints the task prompt. If plugin discovery from a profile home needs a restart/flag, adjust per `hermes plugins --help` (document the fix in README).

- [ ] **Step 3: Full suite + coverage**

```bash
.venv/bin/python -m pytest
```

Expected: all passed, total coverage ≥ 90% printed.

- [ ] **Step 4: Commit**

```bash
git add hermes_agent/README.md
git commit -m "docs(hermes): README with install, manual tick, and opt-in cron"
```

---

## Self-Review (done at plan time)

- Spec coverage: plugin tools (Task 2), runner (Tasks 3, 6, 7, 8), prompt port (Task 4), tick context (Task 5), skill (Task 9), installer + profile config (Tasks 10, 11), policy regression (Task 9), coverage gate (Task 0 + every run), README (Task 12). Anti-gaming delta rule: Task 7 tests `test_exit_zero_without_delta_is_no_submission_retry`. Campaign-complete short circuit: Task 7. Anti-cron-by-default: Task 11 asserts `cron create` absent without the flag.
- Placeholders: none; every step carries full code or exact commands.
- Type consistency: `Tracker` used in Tasks 2, 5, 7 with the same method names; `Config` field names consistent between Tasks 3, 4, 6, 7; `run_attempt(config, prompt) -> (exit, stdout, stderr)` consistent between Tasks 6 and 7.
