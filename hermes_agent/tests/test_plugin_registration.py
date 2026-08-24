"""Plugin registration contract and manifest consistency."""
from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

import yaml

from jobapps import register, schemas

PLUGIN_DIR = Path(__file__).resolve().parents[1] / "src" / "jobapps"


class FakeCtx:
    def __init__(self) -> None:
        self.tools: dict[str, dict[str, Any]] = {}

    def register_tool(
        self, name: str, toolset: str, schema: dict, handler: Callable, **kwargs: Any
    ) -> None:
        self.tools[name] = {"toolset": toolset, "schema": schema, "handler": handler}


def test_register_registers_both_tools_into_jobapps_toolset() -> None:
    ctx = FakeCtx()
    register(ctx)
    assert set(ctx.tools) == {"campaign_status", "record_submission"}
    for entry in ctx.tools.values():
        assert entry["toolset"] == "jobapps"
        assert callable(entry["handler"])


def test_schemas_are_valid_tool_shapes() -> None:
    for schema in (schemas.CAMPAIGN_STATUS, schemas.RECORD_SUBMISSION):
        assert schema["name"]
        assert schema["description"]
        params = schema["parameters"]
        assert params["type"] == "object"
        assert set(params["required"]) <= set(params["properties"])


def test_manifest_matches_registered_tools() -> None:
    manifest = yaml.safe_load((PLUGIN_DIR / "plugin.yaml").read_text(encoding="utf-8"))
    assert manifest["name"] == "jobapps"
    assert sorted(manifest["provides_tools"]) == ["campaign_status", "record_submission"]
