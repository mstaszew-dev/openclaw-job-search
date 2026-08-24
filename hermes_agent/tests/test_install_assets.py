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


def test_profile_soul_is_nonempty_persona() -> None:
    soul = (INSTALL_DIR / "profile-soul.md").read_text(encoding="utf-8")
    assert len(soul.strip()) > 100
    assert "one" in soul.lower() and "submission" in soul.lower()


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
