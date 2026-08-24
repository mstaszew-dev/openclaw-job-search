"""Config: defaults < overrides file < environment."""
from __future__ import annotations

from pathlib import Path

from jobhermes.config import Config, load_env_file


def test_load_env_file_parses_and_skips(tmp_path: Path) -> None:
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


def test_load_env_file_missing_path_returns_empty(tmp_path: Path) -> None:
    assert load_env_file(tmp_path / "none.env") == {}


def test_defaults() -> None:
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
    assert config.subprocess_timeout == 2400
    assert config.skip_companies == set()
    assert config.playwright_output_dir == (
        "/Users/mst/ZCodeProject/openclaw-job-search/playwright-output"
    )


def test_env_overrides_defaults(tmp_path: Path) -> None:
    config = Config.from_env(
        env={"INNER_MAX_FAILS": "3", "HERMES_BIN": "/tmp/hermes"},
        overrides_path=tmp_path / "none.env",
    )
    assert config.inner_max_fails == 3
    assert config.hermes_bin == "/tmp/hermes"


def test_file_overridden_by_env(tmp_path: Path) -> None:
    path = tmp_path / "overrides.env"
    path.write_text("INNER_MAX_FAILS=7\n", encoding="utf-8")
    config = Config.from_env(env={"INNER_MAX_FAILS": "2"}, overrides_path=path)
    assert config.inner_max_fails == 2


def test_file_used_when_env_absent(tmp_path: Path) -> None:
    path = tmp_path / "overrides.env"
    path.write_text("INNER_MAX_FAILS=7\nOUTER_BACKOFF=99\n", encoding="utf-8")
    config = Config.from_env(env={}, overrides_path=path)
    assert config.inner_max_fails == 7
    assert config.outer_backoff == 99


def test_portal_skip_parsing(tmp_path: Path) -> None:
    path = tmp_path / "overrides.env"
    path.write_text(
        "PORTAL_SKIP_ANTAL=1\nPORTAL_SKIP_MINDBOX=1\nPORTAL_SKIP_OK=0\n",
        encoding="utf-8",
    )
    config = Config.from_env(env={}, overrides_path=path)
    assert config.skip_companies == {"antal", "mindbox"}


def test_invalid_int_is_ignored(tmp_path: Path) -> None:
    config = Config.from_env(
        env={"INNER_MAX_FAILS": "not-a-number"},
        overrides_path=tmp_path / "none.env",
    )
    assert config.inner_max_fails == 5


def test_float_field_parsed(tmp_path: Path) -> None:
    config = Config.from_env(
        env={"INNER_SLEEP": "2.5"}, overrides_path=tmp_path / "none.env"
    )
    assert config.inner_sleep == 2.5


def test_campaign_dir_rederives_paths() -> None:
    config = Config(campaign_dir="/tmp/campaign-x")
    assert config.tracker_path == "/tmp/campaign-x/tracker.json"
    assert config.cv_path == "/tmp/campaign-x/cv/michael-staszewski-cv.pdf"


def test_explicit_cv_and_tick_context_paths_are_kept() -> None:
    config = Config(campaign_dir="/tmp/x", cv_path="/explicit/cv.pdf", tick_context_path="/explicit/tick.md")
    assert config.cv_path == "/explicit/cv.pdf"
    assert config.tick_context_path == "/explicit/tick.md"


def test_director_note_missing_is_empty(tmp_path: Path) -> None:
    config = Config(director_note_path=str(tmp_path / "none.md"))
    assert config.director_note == ""


def test_director_note_read_stripped(tmp_path: Path) -> None:
    path = tmp_path / "note.md"
    path.write_text("  IL only this week  \n", encoding="utf-8")
    config = Config(director_note_path=str(path))
    assert config.director_note == "IL only this week"
