#!/usr/bin/env zsh
# Install the jobhunter Hermes profile from this repo. Idempotent; no live
# campaign side effects. Cron registration requires --enable-cron.
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPO_ROOT="${SCRIPT_DIR:h}"
HERMES_BIN="${HERMES_BIN:-hermes}"
PROFILE="${HERMES_PROFILE:-jobhunter}"
ENABLE_CRON=0
[[ "${1:-}" == "--enable-cron" ]] && ENABLE_CRON=1

if ! command -v "$HERMES_BIN" >/dev/null 2>&1; then
  print -u2 "hermes CLI not found on PATH (set HERMES_BIN to override)"
  exit 1
fi

PROFILE_HOME="$HOME/.hermes/profiles/$PROFILE"

if [[ ! -d "$PROFILE_HOME" ]]; then
  "$HERMES_BIN" profile create "$PROFILE" --description "Autonomous job-search campaign agent"
fi

mkdir -p "$PROFILE_HOME/plugins" "$PROFILE_HOME/skills"
ln -sfn "$REPO_ROOT/src/jobapps" "$PROFILE_HOME/plugins/jobapps"
ln -sfn "$REPO_ROOT/skills/job-search-tick" "$PROFILE_HOME/skills/job-search-tick"

CONFIG_PATH="$PROFILE_HOME/config.yaml"
if [[ ! -f "$CONFIG_PATH" ]] || ! grep -q "jobhermes-managed" "$CONFIG_PATH"; then
  cp "$SCRIPT_DIR/config.template.yaml" "$CONFIG_PATH"
else
  print "config.yaml already jobhermes-managed; leaving untouched"
fi
cp "$SCRIPT_DIR/profile-soul.md" "$PROFILE_HOME/SOUL.md"

"$HERMES_BIN" plugins doctor "$REPO_ROOT/src/jobapps" || print -u2 "plugins doctor reported issues (review before ticking)"

if (( ENABLE_CRON )); then
  "$HERMES_BIN" cron create "every 30m" "job-search tick" \
    --name "job-search-tick" --no-agent \
    --script "cd '$REPO_ROOT' && PYTHONPATH=src python3 -m jobhermes --once" \
    --workdir "$REPO_ROOT"
  print "Cron registered (every 30m)."
else
  print "Install complete. Cron NOT registered (opt-in)."
  print "Manual tick: cd '$REPO_ROOT' && PYTHONPATH=src python3 -m jobhermes --once"
  print "Enable scheduling: $0 --enable-cron"
fi
