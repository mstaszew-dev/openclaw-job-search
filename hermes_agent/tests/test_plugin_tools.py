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
    assert result["recent_applications"] == []
    assert result["tracker_path"] == str(path)
    assert "queue_length" not in result  # queue reporting removed


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
    campaign_dir: Path,
    exit_code: int = 0,
    stdout: str = "submitted: acme-1\nsubmitted 5/1500",
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
    assert result["counted"] is True
    assert result["effective_action"] == "submitted"
    written = json.loads((campaign / "last_record.json").read_text(encoding="utf-8"))
    assert written["company"] == "Acme"
    assert written["source"] == "drushim"


def test_record_submission_downgrade_to_attempted_is_not_ok(tmp_path: Path) -> None:
    """Evidence-less submissions print 'attempted:' and do not count."""
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(
        campaign,
        stdout=(
            "WARNING: Submission for acme-1 lacks valid portal confirmation "
            "evidence. Not counting as submitted.\n"
            "attempted: acme-1\n"
        ),
    )
    result = json.loads(
        tools.record_submission({"record": {"company": "Acme"}, "campaign_dir": str(campaign)})
    )
    assert result["exit"] == 0
    assert result["ok"] is False
    assert result["counted"] is False
    assert result["effective_action"] == "attempted"


def test_record_submission_duplicate_is_reported(tmp_path: Path) -> None:
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign, stdout="already recorded: acme-1 (no change)\n")
    result = json.loads(
        tools.record_submission({"record": {"company": "Acme"}, "campaign_dir": str(campaign)})
    )
    assert result["exit"] == 0
    assert result["ok"] is False
    assert result["counted"] is False
    assert result["effective_action"] == "duplicate"


def test_record_submission_unknown_output_is_not_counted(tmp_path: Path) -> None:
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign, stdout="something unexpected\n")
    result = json.loads(
        tools.record_submission({"record": {"company": "Acme"}, "campaign_dir": str(campaign)})
    )
    assert result["ok"] is False
    assert result["counted"] is False
    assert result["effective_action"] == "unknown"


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


def test_record_submission_default_campaign_dir_from_constant(
    monkeypatch, tmp_path: Path
) -> None:
    monkeypatch.delenv("JOBSEARCH_CAMPAIGN_DIR", raising=False)
    monkeypatch.setattr(tools, "DEFAULT_CAMPAIGN_DIR", str(tmp_path))
    result = json.loads(tools.record_submission({"record": {"company": "Acme"}}))
    assert result["ok"] is False
    assert result["exit"] != 0  # no update_tracker.py in the empty tmp campaign dir
    assert "No such file" in result["stderr"]


def test_path_overrides_ignored_without_env_gate(
    monkeypatch, tmp_path: Path
) -> None:
    """LLM-supplied path args must not redirect the tools in production."""
    monkeypatch.delenv("JOBSEARCH_ALLOW_OVERRIDES", raising=False)
    monkeypatch.setattr(tools, "DEFAULT_CAMPAIGN_DIR", str(tmp_path / "default-campaign"))
    result = json.loads(
        tools.campaign_status({"tracker_path": "/etc/passwd-lookalike.json"})
    )
    assert result["tracker_path"] == str(tmp_path / "default-campaign" / "tracker.json")
    # record_submission likewise ignores campaign_dir without the gate
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    _make_update_tracker_stub(campaign)
    result = json.loads(
        tools.record_submission({"record": {"company": "X"}, "campaign_dir": str(campaign)})
    )
    assert result["ok"] is False
    # either a startup error (default dir absent) or a non-zero recorder exit;
    # the stubbed campaign dir was NOT used either way
    assert "error" in result or result["exit"] != 0


def test_override_gate_requires_exact_one(monkeypatch, tracker_factory) -> None:
    """Only the literal value '1' opens the gate; truthy strings do not."""
    real = tracker_factory(submitted=9, target=9, name="real.json")
    decoy = tracker_factory(submitted=1, target=1, name="decoy.json")
    monkeypatch.delenv("JOBSEARCH_CAMPAIGN_DIR", raising=False)
    monkeypatch.delenv("JOBSEARCH_TRACKER_PATH", raising=False)
    monkeypatch.setattr(tools, "DEFAULT_CAMPAIGN_DIR", str(real.parent))
    expected_default = str(real.parent / "tracker.json")
    for value in ("true", "yes", "on", "01"):
        monkeypatch.setenv("JOBSEARCH_ALLOW_OVERRIDES", value)
        result = json.loads(tools.campaign_status({"tracker_path": str(decoy)}))
        assert result["tracker_path"] == expected_default, value
    monkeypatch.setenv("JOBSEARCH_ALLOW_OVERRIDES", "1")
    result = json.loads(tools.campaign_status({"tracker_path": str(decoy)}))
    assert result["tracker_path"] == str(decoy)


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
