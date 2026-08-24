"""install.sh end-to-end against a fake hermes CLI and a tmp HOME."""
from __future__ import annotations

import os
import stat
import subprocess
from pathlib import Path

INSTALL_SH = Path(__file__).resolve().parents[1] / "install" / "install.sh"


def _make_profile_stub(bin_dir: Path) -> Path:
    """Fake hermes handling: profile create, plugins doctor, cron create."""
    stub = bin_dir / "hermes"
    stub.write_text(
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$*" >> "$FAKE_HERMES_LOG"\n'
        'if [[ "$1" == "profile" && "$2" == "create" ]]; then\n'
        '  mkdir -p "$HERMES_PROFILE_DIR"\n'
        "  exit 0\n"
        "fi\n"
        'if [[ "$1" == "plugins" && "$2" == "doctor" ]]; then exit 0; fi\n'
        'if [[ "$1" == "cron" && "$2" == "create" ]]; then exit 0; fi\n'
        "exit 0\n",
        encoding="utf-8",
    )
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    return stub


def _run_install(tmp_path: Path, bin_dir: Path, log_path: Path, *args: str):
    env = dict(os.environ)
    home = tmp_path / "home"
    home.mkdir(exist_ok=True)
    env.update(
        {
            "HOME": str(home),
            "PATH": str(bin_dir) + os.pathsep + env["PATH"],
            "HERMES_BIN": str(bin_dir / "hermes"),
            "FAKE_HERMES_LOG": str(log_path),
            "HERMES_PROFILE_DIR": str(home / ".hermes" / "profiles" / "jobhunter"),
        }
    )
    return subprocess.run(
        ["zsh", str(INSTALL_SH), *args],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )


def test_install_creates_profile_symlinks_and_config(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _make_profile_stub(bin_dir)
    log_path = tmp_path / "hermes.log"
    result = _run_install(tmp_path, bin_dir, log_path)
    assert result.returncode == 0, result.stderr
    profile_home = tmp_path / "home" / ".hermes" / "profiles" / "jobhunter"
    assert (profile_home / "plugins" / "jobapps").is_symlink()
    assert (profile_home / "plugins" / "jobapps" / "plugin.yaml").is_file()
    assert (profile_home / "skills" / "job-search-tick").is_symlink()
    assert (profile_home / "skills" / "job-search-tick" / "SKILL.md").is_file()
    config_text = (profile_home / "config.yaml").read_text(encoding="utf-8")
    assert "msrouter" in config_text
    assert (profile_home / "SOUL.md").is_file()
    logged = log_path.read_text(encoding="utf-8")
    assert "profile create jobhunter" in logged
    assert "cron create" not in logged  # opt-in only
    assert "plugins doctor" in logged


def test_install_is_idempotent(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _make_profile_stub(bin_dir)
    log_path = tmp_path / "hermes.log"
    assert _run_install(tmp_path, bin_dir, log_path).returncode == 0
    profile_home = tmp_path / "home" / ".hermes" / "profiles" / "jobhunter"
    config_path = profile_home / "config.yaml"
    config_path.write_text(
        "# jobhermes-managed\nmodel:\n  default: edited\n", encoding="utf-8"
    )
    soul_path = profile_home / "SOUL.md"
    soul_path.write_text("<!-- jobhermes-managed -->\nuser-tweaked persona\n", encoding="utf-8")
    result = _run_install(tmp_path, bin_dir, log_path)
    assert result.returncode == 0, result.stderr
    assert "edited" in config_path.read_text(encoding="utf-8")
    assert "user-tweaked persona" in soul_path.read_text(encoding="utf-8")
    creates = log_path.read_text(encoding="utf-8").count("profile create jobhunter")
    assert creates == 1


def test_install_enable_cron_passes_script_path(tmp_path: Path) -> None:
    """--script must receive an existing script path (hermes contract), not a
    shell command string."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _make_profile_stub(bin_dir)
    log_path = tmp_path / "hermes.log"
    result = _run_install(tmp_path, bin_dir, log_path, "--enable-cron")
    assert result.returncode == 0, result.stderr
    logged = log_path.read_text(encoding="utf-8")
    assert "cron create" in logged
    assert "--no-agent" in logged
    assert "--script" in logged
    # extract the value passed after --script and verify it is a real file
    # under the (fake) hermes home that invokes the runner
    cron_line = next(ln for ln in logged.splitlines() if "cron create" in ln)
    idx = cron_line.index("--script") + len("--script ")
    script_value = cron_line[idx:].split()[0]
    assert script_value.startswith(str(tmp_path / "home"))
    assert script_value.endswith("job-search-tick.sh")
    script_path = Path(script_value)
    assert script_path.is_file()
    body = script_path.read_text(encoding="utf-8")
    assert "-m jobhermes --once" in body
    # prefers the project venv over ambient PATH python (cron PATH is minimal)
    assert ".venv/bin/python" in body or "python3" in body
    assert script_path.stat().st_mode & stat.S_IXUSR


def test_install_fails_without_hermes(tmp_path: Path) -> None:
    empty_bin = tmp_path / "emptybin"
    empty_bin.mkdir()
    home = tmp_path / "home"
    home.mkdir(exist_ok=True)
    env = dict(os.environ)
    env.update({"HOME": str(home), "HERMES_BIN": str(empty_bin / "hermes")})
    result = subprocess.run(
        ["zsh", str(INSTALL_SH)],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 1
    assert "hermes CLI not found" in result.stderr
