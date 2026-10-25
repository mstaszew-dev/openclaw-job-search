#!/bin/sh
# wedge-watchdog.sh - laptop-side browser wedge recovery.
#
# Detects the CDP wedge (initializeServer stalls 30s on every consumer
# navigate) via the exact production signature in finder/worker logs, and
# wipes the lubuntu-agent user stack when the apply worker holds no browser
# lease. The node watchdog rebuilds the stack within ~75s.
#
# Installed on the laptop at
#   /home/mstro/k3s-pod-data/agent-home/campaign-data/
# alongside wipe-payload.sh, driven by cron every 5 minutes:
#   */5 * * * * flock -n /tmp/wedge-watchdog.lock /home/mstro/k3s-pod-data/agent-home/campaign-data/wedge-watchdog.sh
#
# Gates, in order:
#   1. signature: "initializeServer: Timeout" in finder or worker logs,
#      last 12 minutes (the wedge cannot hide from the 15-min finder cycle).
#   2. lease: browser_leases must be empty (never wipe under a live unit).
#   3. cooldown: at most one wipe per 15 minutes, so the signature lines
#      that triggered the last wipe cannot re-trigger while they age out.
# Failure direction is always "do nothing": unreadable lease, missing pod,
# or any surprise skips the wipe and waits for the next run.

export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"

DIR=/home/mstro/k3s-pod-data/agent-home/campaign-data
LOG=$DIR/watchdog.log
COOLDOWN_FILE=$DIR/watchdog.lastwipe
COOLDOWN_SECS=900

log() { echo "$(date -u +%FT%TZ) $*" >> "$LOG"; }

# Keep the log bounded.
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 200000 ] && : > "$LOG"

# Gate 1: wedge signature in the last 16 minutes. The finder cycles every
# 15 min, so a 16-min window guarantees the signatures of every wedged
# cycle are still visible at least one cron tick after the cycle ends; a
# 12-min window let a cycle's wedge age out between cycles and added a
# full 15-min wait to recovery (observed 2026-10-25 09:47Z).
sig_finder=$(kubectl -n campaign logs deploy/finder --since=16m 2>/dev/null | grep -ac "initializeServer: Timeout")
sig_worker=$(kubectl -n campaign logs deploy/apply-worker --since=16m 2>/dev/null | grep -ac "initializeServer: Timeout")
[ "${sig_finder:-0}" = "0" ] && [ "${sig_worker:-0}" = "0" ] && exit 0

# Gate 2: the apply worker must hold no browser lease. An unreadable count
# stays empty and fails safe below.
lease=$(kubectl -n campaign exec mongo-0 -- sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --eval "print(db.getSiblingDB(\"campaign\").browser_leases.countDocuments({}))"' 2>/dev/null | tail -1)
if [ "$lease" != "0" ]; then
    log "wedge signature (finder=$sig_finder worker=$sig_worker) but lease='$lease'; not wiping"
    exit 0
fi

# Gate 3: cooldown.
if [ -f "$COOLDOWN_FILE" ]; then
    last=$(cat "$COOLDOWN_FILE" 2>/dev/null)
    now=$(date -u +%s)
    [ -n "$last" ] && [ $((now - last)) -lt "$COOLDOWN_SECS" ] && exit 0
fi

# Wipe.
pod=$(kubectl -n default get pod -l app=lubuntu-agent -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
if [ -z "$pod" ]; then
    log "wedge signature but lubuntu-agent pod not found; not wiping"
    exit 0
fi
if kubectl -n default exec -i "$pod" -- sh < "$DIR/wipe-payload.sh" >> "$LOG" 2>&1; then
    date -u +%s > "$COOLDOWN_FILE"
    log "wiped $pod after wedge signature (finder=$sig_finder worker=$sig_worker)"
else
    log "wipe exec failed on $pod; will retry next run"
fi
