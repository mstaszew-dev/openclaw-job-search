"""Hermes plugin: job-search campaign tools (campaign_status, record_submission)."""
from __future__ import annotations

from typing import Any

from . import schemas, tools


def register(ctx: Any) -> None:
    """Register all jobapps tools. Called once by the Hermes plugin loader."""
    ctx.register_tool(
        name="campaign_status",
        toolset="jobapps",
        schema=schemas.CAMPAIGN_STATUS,
        handler=tools.campaign_status,
    )
    ctx.register_tool(
        name="record_submission",
        toolset="jobapps",
        schema=schemas.RECORD_SUBMISSION,
        handler=tools.record_submission,
    )
