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
# 12-min window let a wedge age out between cycles and added a
# full 15-min wait to recovery (observed 2026-10-25 09:47Z).
# init_timeout counts ONLY the exact browser-wedge text: it alone drives
# the under-lease ESCAPE in Gate 2, because "Stream closed" is generic
# exception text (JDK HttpClient, Mongo, Kafka stack traces) and must not
# be able to wipe a browser under a live lease (review 2026-10-27).
# all_sig matches everything the worker treats as non-walling - it opens
# Gate 1 only (some infra failure exists), the cheap gate.
sig_finder=$(kubectl -n campaign logs deploy/finder --since=16m 2>/dev/null | grep -acE "initializeServer: Timeout|Stream closed|ECONNREFUSED")
sig_worker=$(kubectl -n campaign logs deploy/apply-worker --since=16m 2>/dev/null | grep -acE "initializeServer: Timeout|Stream closed|ECONNREFUSED")
init_worker=$(kubectl -n campaign logs deploy/apply-worker --since=16m 2>/dev/null | grep -ac "initializeServer: Timeout")
[ "${sig_finder:-0}" = "0" ] && [ "${sig_worker:-0}" = "0" ] && exit 0

# Gate 2: the apply worker must hold no browser lease. An unreadable count
# stays empty and fails safe below.
# ESCAPE (2026-10-27 deadlock): a wedged browser fails every unit in ~3
# minutes, but the worker re-acquires the lease for the next queued unit
# immediately, so leases never reach 0 and the wipe never fires while good
# listings burn. When the worker shows BOTH a heavy wedge signature AND at
# least three recent unit terminals that all died fast against it, the
# lease protects nothing - wipe anyway.
lease=$(kubectl -n campaign exec mongo-0 -- sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --eval "print(db.getSiblingDB(\"campaign\").browser_leases.countDocuments({}))"' 2>/dev/null | tail -1)
if [ "$lease" != "0" ]; then
    wedge_loop=0
    # ESCAPE threshold counts only the exact initializeServer timeout text:
    # the generic widened signatures (Stream closed etc.) must never push a
    # lease-protected wipe over the line (see Gate 1 comment).
    if [ "${init_worker:-0}" -ge 10 ]; then
        recent=$(kubectl -n campaign logs deploy/apply-worker --since=16m 2>/dev/null | grep -aE "Failed after [0-9]+ steps|Submitted after|Blocked after" | tail -3)
        fails=$(printf '%s' "$recent" | grep -ac "Failed after")
        total=$(printf '%s' "$recent" | grep -acE "after [0-9]+ steps")
        # all recent terminals are failures AND the worker wedge count is high
        if [ "$total" -ge 3 ] && [ "$fails" = "$total" ]; then
            wedge_loop=1
        fi
    fi
    if [ "$wedge_loop" = "1" ]; then
        log "wedge signature (init=$init_worker) with $fails fast-failed terminals under lease='$lease'; the lease protects nothing, wiping"
    else
        log "wedge signature (finder=$sig_finder worker=$sig_worker) but lease='$lease'; not wiping"
        exit 0
    fi
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
