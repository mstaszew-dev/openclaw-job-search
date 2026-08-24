"""CLI: exit codes for once/loop/dry-run and missing campaign dir."""
from __future__ import annotations

from pathlib import Path

from jobhermes import runner as runner_module
from jobhermes.runner import main


def _hermetic_env(monkeypatch, tmp_path: Path) -> None:
    campaign = tmp_path / "campaign"
    campaign.mkdir(exist_ok=True)
    monkeypatch.setenv("CAMPAIGN_DIR", str(campaign))


def test_dry_run_prints_prompt_and_exits_zero(
    tmp_path: Path, capsys, monkeypatch
) -> None:
    _hermetic_env(monkeypatch, tmp_path)
    code = main(["--dry-run", "--config", str(tmp_path / "none.env")])
    assert code == 0
    out = capsys.readouterr().out
    assert "TASK: Apply exactly ONE job this tick" in out
    assert str(tmp_path / "campaign") in out


def test_once_success_exit_zero(tmp_path: Path, monkeypatch) -> None:
    _hermetic_env(monkeypatch, tmp_path)
    monkeypatch.setattr(runner_module, "run_tick", lambda config: runner_module.REASON_SUCCESS)
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 0


def test_once_exhausted_exit_one(tmp_path: Path, monkeypatch) -> None:
    _hermetic_env(monkeypatch, tmp_path)
    monkeypatch.setattr(
        runner_module, "run_tick", lambda config: runner_module.REASON_EXHAUSTED
    )
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 1


def test_once_campaign_complete_exit_zero(tmp_path: Path, monkeypatch) -> None:
    _hermetic_env(monkeypatch, tmp_path)
    monkeypatch.setattr(
        runner_module,
        "run_tick",
        lambda config: runner_module.REASON_CAMPAIGN_COMPLETE,
    )
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 0


def test_missing_campaign_dir_exits_two(tmp_path: Path, monkeypatch, capsys) -> None:
    monkeypatch.setenv("CAMPAIGN_DIR", str(tmp_path / "missing"))
    code = main(["--config", str(tmp_path / "none.env")])
    assert code == 2
    assert "campaign dir does not exist" in capsys.readouterr().err


def test_loop_stops_on_campaign_complete(tmp_path: Path, monkeypatch) -> None:
    _hermetic_env(monkeypatch, tmp_path)
    outcomes = [
        runner_module.REASON_SUCCESS,
        runner_module.REASON_EXHAUSTED,
        runner_module.REASON_CAMPAIGN_COMPLETE,
    ]
    monkeypatch.setattr(runner_module, "run_tick", lambda config: outcomes.pop(0))
    sleeps: list[float] = []
    monkeypatch.setattr(runner_module.time, "sleep", sleeps.append)
    code = main(["--loop", "--config", str(tmp_path / "none.env")])
    assert code == 0
    assert sleeps  # backoff slept between ticks
