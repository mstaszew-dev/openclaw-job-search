"""SKILL.md structure and frontmatter validity."""
from __future__ import annotations

from pathlib import Path

import yaml

SKILL_PATH = Path(__file__).resolve().parents[1] / "skills" / "job-search-tick" / "SKILL.md"


def test_skill_file_exists() -> None:
    assert SKILL_PATH.is_file()


def test_frontmatter_is_valid_yaml() -> None:
    text = SKILL_PATH.read_text(encoding="utf-8")
    assert text.startswith("---\n")
    end = text.index("\n---", 3)
    front = yaml.safe_load(text[4:end])
    assert front["name"] == "job-search-tick"
    assert len(front["description"]) <= 60
    assert front["version"]


def test_body_has_required_sections() -> None:
    text = SKILL_PATH.read_text(encoding="utf-8")
    for section in ("## When to Use", "## Procedure", "## Pitfalls", "## Targeting rules"):
        assert section in text, section
