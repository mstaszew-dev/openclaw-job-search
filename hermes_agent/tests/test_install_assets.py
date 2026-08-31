"""Install asset sanity: template config, SOUL persona, installer script."""
from __future__ import annotations

import os
from pathlib import Path

import yaml

INSTALL_DIR = Path(__file__).resolve().parents[1] / "install"


def test_config_template_parses_and_pins_hermes() -> None:
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


def test_config_template_carries_managed_marker() -> None:
    text = (INSTALL_DIR / "config.template.yaml").read_text(encoding="utf-8")
    assert "jobhermes-managed" in text


def test_config_template_enables_jobapps_plugin() -> None:
    """Without this block the plugin loads but its tools never register."""
    config = yaml.safe_load((INSTALL_DIR / "config.template.yaml").read_text(encoding="utf-8"))
    assert config["plugins"]["enabled"] == ["jobapps"]


def test_config_template_defines_bot_quick_commands() -> None:
    """Zero-token chat shortcuts for the dashboard/UI bot experience."""
    config = yaml.safe_load((INSTALL_DIR / "config.template.yaml").read_text(encoding="utf-8"))
    qc = config["quick_commands"]
    assert set(qc) == {"campaign-status", "last-tick", "prompt-preview"}
    assert all(entry["type"] == "exec" for entry in qc.values())
    assert "-m jobhermes --once --dry-run" in qc["prompt-preview"]["command"]


def test_profile_memory_seed_exists() -> None:
    seed = INSTALL_DIR / "profile-memory-seed.md"
    text = seed.read_text(encoding="utf-8")
    assert len(text.strip()) > 100
    assert "record_submission" in text
    assert "/Users/mst/Downloads/job-search/job-apply" in text


def test_profile_memory_seed_is_dual_region() -> None:
    """The bot's self-description comes from this seed; it must match the
    python agent's targeting, not the retired IL-only campaign."""
    seed = (INSTALL_DIR / "profile-memory-seed.md").read_text(encoding="utf-8")
    assert "IL + PL" in seed
    assert "NoFluffJobs" in seed
    assert "JustJoin.it" in seed
    assert "theProtocol.it" in seed
    assert "mst.rocking@gmail.com" in seed
    # Stale IL-only phrasing must never return.
    assert "IL job-search campaign" not in seed


def test_profile_soul_is_nonempty_persona() -> None:
    soul = (INSTALL_DIR / "profile-soul.md").read_text(encoding="utf-8")
    assert len(soul.strip()) > 100
    assert "one" in soul.lower() and "submission" in soul.lower()
    assert "jobhermes-managed" in soul  # reinstall clobber guard marker


def test_install_script_exists_executable_and_cron_is_opt_in() -> None:
    script = INSTALL_DIR / "install.sh"
    assert script.is_file()
    assert os.access(script, os.X_OK)
    text = script.read_text(encoding="utf-8")
    assert "--enable-cron" in text
    # cron registration must be guarded, not unconditional
    assert "ENABLE_CRON" in text
    assert '== "--enable-cron"' in text
    assert "set -euo pipefail" in text


def test_supervised_launcher_contract() -> None:
    """Launcher mirrors the Python agent's supervised-launcher contract."""
    launcher = INSTALL_DIR / "job-search-agent-hermes"
    assert launcher.is_file()
    assert os.access(launcher, os.X_OK)
    text = launcher.read_text(encoding="utf-8")
    assert "-m jobhermes --loop" in text  # continuous ticking like campaign_agent
    assert "PYTHONPATH=src" in text
    assert "exec" in text  # stays as supervisable parent process
    assert "campaign dir missing" in text  # refuses without the campaign dir
    assert "REAL job applications" in text  # loud warning before start
