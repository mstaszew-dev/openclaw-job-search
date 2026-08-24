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
SOUL_PATH="$PROFILE_HOME/SOUL.md"
if [[ ! -f "$SOUL_PATH" ]] || ! grep -q "jobhermes-managed" "$SOUL_PATH"; then
  cp "$SCRIPT_DIR/profile-soul.md" "$SOUL_PATH"
else
  print "SOUL.md already jobhermes-managed (user-edited); leaving untouched"
fi

"$HERMES_BIN" plugins doctor "$REPO_ROOT/src/jobapps" || print -u2 "plugins doctor reported issues (review before ticking)"

if (( ENABLE_CRON )); then
  # hermes cron --script expects a path to a script, not a shell command
  SCRIPTS_DIR="$HOME/.hermes/scripts"
  mkdir -p "$SCRIPTS_DIR"
  TICK_SCRIPT="$SCRIPTS_DIR/job-search-tick.sh"
  PYTHON_BIN="python3"
  if [[ -x "$REPO_ROOT/.venv/bin/python" ]]; then
    PYTHON_BIN="$REPO_ROOT/.venv/bin/python"
  fi
  cat > "$TICK_SCRIPT" <<EOF
#!/usr/bin/env bash
# jobhermes-managed wrapper for the scheduled campaign tick
set -euo pipefail
cd "$REPO_ROOT"
exec env PYTHONPATH=src "$PYTHON_BIN" -m jobhermes --once
EOF
  chmod +x "$TICK_SCRIPT"
  "$HERMES_BIN" cron create "every 30m" "job-search tick" \
    --name "job-search-tick" --no-agent \
    --script "$TICK_SCRIPT" \
    --workdir "$REPO_ROOT"
  print "Cron registered (every 30m), script: $TICK_SCRIPT"
else
  print "Install complete. Cron NOT registered (opt-in)."
  print "Manual tick: cd '$REPO_ROOT' && PYTHONPATH=src python3 -m jobhermes --once"
  print "Enable scheduling: $0 --enable-cron"
fi
