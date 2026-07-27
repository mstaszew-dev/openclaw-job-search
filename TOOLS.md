# TOOLS.md - Local Notes

Skills define _how_ tools work. This file is for _your_ specifics — the stuff that's unique to your setup: camera names and locations, SSH hosts and aliases, preferred TTS voices, speaker/room names, device nicknames, anything environment-specific.

## Examples

```markdown
### Cameras

- living-room → Main area, 180° wide angle
- front-door → Entrance, motion-triggered

### SSH

- home-server → 192.168.1.100, user: admin

### TTS

- Preferred voice: "Nova" (warm, slightly British)
- Default speaker: Kitchen HomePod
```

## Why Separate?

Skills are shared. Your setup is yours. Keeping them apart means you can update skills without losing your notes, and share skills without leaking your infrastructure.

---

Add whatever helps you do your job. This is your cheat sheet.

## Job Campaign Tools

- Canonical campaign: `/Users/mst/Downloads/job-search/job-apply` (workspace link: `campaign/`).
- ZCode runner/context: `/Users/mst/ZCodeProject/joblooper` (workspace link: `joblooper/`).
- Browser: MCP server `playwright`, connected to the user's running Chrome through CDP `http://127.0.0.1:9222`.
- Playwright artifacts: `playwright-output/`.
- Current state: `campaign/tick_status.sh` and `campaign/tracker.json`.
- Mandatory gates: `campaign/check_dupe.py` then Gmail browser search, then `campaign/score_candidate.py`.
- Confirmed writes only: `campaign/update_tracker.py submitted` after visible browser confirmation.
- Recruiter replies and onboarding steps for an already submitted role use
  `campaign/update_tracker.py followUp`; they never increment `stats.submitted`.
- Read `campaign/CONTEXT.md` and the matching `applications[].followUps[]` before
  acting on an active recruiter conversation, so the company is not submitted twice.
- OpenRouter authentication is resolved from the existing OpenCode credential store. Never print, copy, summarize, or expose that credential.

## Related

- [Agent workspace](/concepts/agent-workspace)
