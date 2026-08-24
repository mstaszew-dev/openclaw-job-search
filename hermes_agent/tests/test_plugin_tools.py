"""Plugin tool handlers: JSON in/out, never raise."""
from __future__ import annotations

import json
import stat
import subprocess
from pathlib import Path

from jobapps import tools


def test_campaign_status_reads_tracker(tracker_factory) -> None:
    path = tracker_factory(submitted=4, target=10)
    result = json.loads(tools.campaign_status({"tracker_path": str(path)}))
    assert result["submitted"] == 4
    assert result["target"] == 10
    assert result["remaining"] == 6
    assert result["campaign_complete"] is False
    assert result["queue_length"] == 0
    assert result["recent_applications"] == []
    assert result["tracker_path"] == str(path)


def test_campaign_status_missing_file_is_zeroed(tmp_path: Path) -> None:
    result = json.loads(tools.campaign_status({"tracker_path": str(tmp_path / "none.json")}))
    assert result["submitted"] == 0
    assert result["campaign_complete"] is False


def test_campaign_status_default_path_from_env(
    monkeypatch, tracker_factory
) -> None:
    path = tracker_factory(submitted=1, target=1)
    monkeypatch.setenv("JOBSEARCH_TRACKER_PATH", str(path))
    result = json.loads(tools.campaign_status({}))
    assert result["campaign_complete"] is True


def test_campaign_status_hardcoded_default_when_env_unset(
    monkeypatch, tmp_path: Path
) -> None:
    monkeypatch.delenv("JOBSEARCH_TRACKER_PATH", raising=False)
    monkeypatch.delenv("JOBSEARCH_CAMPAIGN_DIR", raising=False)
    monkeypatch.setattr(tools, "DEFAULT_CAMPAIGN_DIR", str(tmp_path))
    result = json.loads(tools.campaign_status({}))
    assert result["tracker_path"] == str(tmp_path / "tracker.json")
    assert result["submitted"] == 0


def _make_update_tracker_stub(
    campaign_dir: Path, exit_code: int = 0, stdout: str = "submitted 5/1500"
) -> Path:
    stub = campaign_dir / "update_tracker.py"
    stub.write_text(
        "import sys\n"
        "record = sys.argv[2]\n"
        "with open({out!r}, 'w') as fh:\n"
        "    fh.write(record)\n"
        "print({stdout!r})\n"
        "raise SystemExit({exit_code})\n".format(
            out=str(campaign_dir / "last_record.json"),
            stdout=stdout,
            exit_code=exit_code,
        ),
        encoding="utf-8",
    )
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    return stub


def test_record_submission_runs_update_tracker(tmp_path: Path) -> None:
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign)
    args = {
        "record": {
            "source": "drushim",
            "sourceJobId": "123",
            "company": "Acme",
            "roleTitle": "Backend",
        },
        "campaign_dir": str(campaign),
    }
    result = json.loads(tools.record_submission(args))
    assert result["ok"] is True
    assert result["exit"] == 0
    assert "submitted" in result["stdout"]
    written = json.loads((campaign / "last_record.json").read_text(encoding="utf-8"))
    assert written["company"] == "Acme"
    assert written["source"] == "drushim"


def test_record_submission_nonzero_exit_is_not_ok(tmp_path: Path) -> None:
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign, exit_code=1)
    result = json.loads(tools.record_submission({"record": {}, "campaign_dir": str(campaign)}))
    assert result["ok"] is False
    assert result["exit"] == 1


def test_record_submission_rejects_non_object_record() -> None:
    result = json.loads(tools.record_submission({"record": "not-a-dict"}))
    assert result["ok"] is False
    assert "record" in result["error"]


def test_record_submission_missing_cwd_returns_error_not_exception(
    tmp_path: Path,
) -> None:
    result = json.loads(
        tools.record_submission({"record": {}, "campaign_dir": str(tmp_path / "missing")})
    )
    assert result["ok"] is False
    assert "error" in result


def test_record_submission_timeout_returns_error(monkeypatch, tmp_path: Path) -> None:
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign)
    monkeypatch.setattr(tools, "UPDATE_TRACKER_TIMEOUT", 0)

    def hang(command, **kwargs):
        raise subprocess.TimeoutExpired(cmd=command, timeout=0)

    monkeypatch.setattr("subprocess.run", hang)
    result = json.loads(tools.record_submission({"record": {}, "campaign_dir": str(campaign)}))
    assert result["ok"] is False
    assert "timed out" in result["error"]
