"""run_attempt: hermes one-shot subprocess wrapper."""
from __future__ import annotations

from jobhermes.config import Config
from jobhermes.runner import build_hermes_command, run_attempt


def test_build_hermes_command_shape() -> None:
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


def test_run_attempt_invokes_fake_hermes(fake_hermes, monkeypatch) -> None:
    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))
    monkeypatch.setenv("FAKE_HERMES_EXIT", "3")
    monkeypatch.setenv("FAKE_HERMES_STDOUT", "final text")
    config = Config(hermes_bin=str(bin_dir / "hermes"), campaign_dir="/tmp/camp")
    exit_code, out, _err = run_attempt(config, "do the tick")
    assert exit_code == 3
    assert out == "final text"
    logged = log_path.read_text(encoding="utf-8")
    assert "-p jobhunter" in logged
    assert "-z do the tick" in logged
    assert "--in /tmp/camp" in logged
    assert "--run-budget 1800" in logged
    assert "--max-turns 200" in logged


def test_run_attempt_missing_binary(fake_hermes) -> None:
    config = Config(hermes_bin=str(fake_hermes[0] / "nothing"), campaign_dir="/tmp/camp")
    exit_code, _out, err = run_attempt(config, "prompt")
    assert exit_code == 127
    assert "not found" in err


def test_run_attempt_timeout_kills_process_group(fake_hermes, monkeypatch) -> None:
    """Timeout returns 124 fast AND kills hermes plus its spawned children."""
    import stat
    import subprocess
    import time as time_mod

    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))
    stub = bin_dir / "hermes"
    stub.write_text(
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$*" >> "$FAKE_HERMES_LOG"\n'
        'bash -c "sleep 30 # jobhermes-orphan-child-marker" &\n'
        "sleep 30\n",
        encoding="utf-8",
    )
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    config = Config(
        hermes_bin=str(stub), campaign_dir="/tmp/camp", subprocess_timeout=1
    )
    start = time_mod.monotonic()
    exit_code, _out, err = run_attempt(config, "prompt")
    assert exit_code == 124
    assert "timed out" in err
    assert time_mod.monotonic() - start < 15
    time_mod.sleep(0.3)  # grace for the group kill to land
    leftover = subprocess.run(
        ["pgrep", "-f", "jobhermes-orphan-child-marker"], capture_output=True
    )
    assert leftover.returncode != 0, "orphan child survived the process-group kill"


def test_run_attempt_oserror_maps_to_126(monkeypatch, fake_hermes) -> None:
    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))

    def boom(command, **kwargs):
        raise OSError("permission denied")

    monkeypatch.setattr("subprocess.Popen", boom)
    config = Config(hermes_bin=str(bin_dir / "hermes"), campaign_dir="/tmp/camp")
    exit_code, _, err = run_attempt(config, "prompt")
    assert exit_code == 126
    assert "permission denied" in err


def test_run_attempt_truncates_long_output(fake_hermes, monkeypatch) -> None:
    bin_dir, log_path = fake_hermes
    monkeypatch.setenv("FAKE_HERMES_LOG", str(log_path))
    monkeypatch.setenv("FAKE_HERMES_STDOUT", "y" * 5000)
    config = Config(hermes_bin=str(bin_dir / "hermes"), campaign_dir="/tmp/camp")
    _, out, _ = run_attempt(config, "prompt")
    assert len(out) == 2000

