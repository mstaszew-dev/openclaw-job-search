# Tests for the ./campaign-agent zsh launcher (repo root).
#
# The launcher is supervised by the msrouter Director: it must run the python
# child with -m campaign_agent.main from the campaign_agent/ directory and
# forward SIGTERM to that child so no orphan survives a restart.
#
# Run locally:  bats tests/
# Requires: bats, zsh, python3 (for the launcher's realpath resolution).

setup() {
  TEST_TMP="$(mktemp -d)"
  STUB_LOG="$TEST_TMP/stub.log"
  mkdir -p "$TEST_TMP/campaign_agent/.venv/bin"

  # Stub interpreter standing in for campaign_agent/.venv/bin/python.
  # Records its args + cwd, then either exits (default), fails (fail), or
  # hangs until TERM (hang).
  cat > "$TEST_TMP/campaign_agent/.venv/bin/python" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$STUB_LOG"
printf '%s\n' "\$(pwd)" >> "$STUB_LOG"
if [ "\${STUB_MODE:-}" = "fail" ]; then
  exit 7
fi
if [ "\${STUB_MODE:-}" = "hang" ]; then
  trap 'echo TERM-forwarded >> "$STUB_LOG"; exit 0' TERM
  echo started >> "$STUB_LOG"
  while :; do sleep 0.1; done
fi
STUB
  chmod +x "$TEST_TMP/campaign_agent/.venv/bin/python"

  cp "$BATS_TEST_DIRNAME/../campaign-agent" "$TEST_TMP/campaign-agent"
  chmod +x "$TEST_TMP/campaign-agent"
}

teardown() {
  # Kill the launcher and any stub child so a regression in TERM forwarding
  # cannot leave a spinning orphan behind. The tmp path is unique per run.
  [ -n "${LAUNCHER_PID:-}" ] && kill -9 "$LAUNCHER_PID" 2>/dev/null
  pkill -9 -f "$TEST_TMP/campaign_agent" 2>/dev/null
  rm -rf "$TEST_TMP"
}

@test "launches the python child with -m campaign_agent.main" {
  # STUB_LOG is baked into the stub at setup time; no need to pass it via env.
  run "$TEST_TMP/campaign-agent"
  [ "$status" -eq 0 ]
  grep -qx -- '-m campaign_agent.main' "$STUB_LOG"
}

@test "runs the python child from the campaign_agent directory" {
  run "$TEST_TMP/campaign-agent"
  [ "$status" -eq 0 ]
  grep -E '/campaign_agent$' "$STUB_LOG"
}

@test "propagates a non-zero python exit code" {
  run env STUB_MODE=fail "$TEST_TMP/campaign-agent"
  [ "$status" -eq 7 ]
}

@test "forwards SIGTERM from the launcher to the python child" {
  STUB_MODE=hang "$TEST_TMP/campaign-agent" &
  LAUNCHER_PID=$!
  for _ in $(seq 1 50); do
    grep -q '^started$' "$STUB_LOG" 2>/dev/null && break
    sleep 0.1
  done
  grep -q '^started$' "$STUB_LOG"

  # The Director signals the launcher; the launcher's trap must TERM the
  # python child (the launcher's own exit status is incidental: zsh's wait
  # returns 143 when interrupted, which errexit turns into exit 143).
  kill -TERM "$LAUNCHER_PID"
  wait "$LAUNCHER_PID" || true

  # The child's trap fires after its current 0.1s sleep; poll briefly so the
  # test stays deterministic on slow machines.
  for _ in $(seq 1 50); do
    grep -q 'TERM-forwarded' "$STUB_LOG" 2>/dev/null && break
    sleep 0.1
  done
  grep -q 'TERM-forwarded' "$STUB_LOG" || { echo "--- stub log ---" >&2; cat "$STUB_LOG" >&2; return 1; }
}
