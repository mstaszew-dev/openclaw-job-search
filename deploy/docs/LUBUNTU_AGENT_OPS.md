# lubuntu-agent ops runbook (browser stack + CDP)

The shared automation browser lives in the `lubuntu-agent` pod (namespace
`default`), not in `campaign`. Both the finder (listing crawls) and the
apply-worker (form fills) reach it through the same chain:

```
finder/worker -> http://192.168.18.13:30922 (NodePort)
  -> pod :9222 (socat) -> 127.0.0.1:9223 (google-chrome CDP)
```

Chrome is a headful browser on Xvfb :99, supervised by the root
`/entrypoint.sh` watchdog, which re-runs `/home/agent/pod-user-start.sh`
every 60s for anything that dies.

## Symptoms of a broken chain

- Finder WARNs every ~30s:
  `collect <board> stack <stack> failed: tool browser_navigate failed:
  TimeoutError: async initializeServer: Timeout 30000ms exceeded`
  with a call log ending in `<ws connected>`. A ws that connects but an
  init that never finishes means the browser is up but starved, not down.
- Worker silence with lag 0 is a separate, downstream effect: with the
  finder dead nothing new is enqueued, so an idle worker proves nothing.

## Diagnosis sequence (cheapest first)

1. From the k3s host: `curl -s -m 8 http://192.168.18.13:30922/json/version`.
   A JSON answer with `webSocketDebuggerUrl` proves the whole chain up to
   Chrome. Note the browser UUID - it identifies WHICH Chrome is answering.
2. Compare that UUID against the one in recent finder failure logs. If the
   failures reference an older UUID, something stale is answering
   (see failure mode 3).
3. Inside the pod: `cat /proc/net/tcp | grep :2406` (9222) and check for a
   `:2407` (9223) LISTEN row (`0A`). No 9223 listener = Chrome is down no
   matter what the watchdog claims.
4. Watchdog activity: `tail /tmp/entrypoint.log` (WATCHDOG lines) and
   `tail /tmp/user-stack.log` (what the restart actually did).

## Known failure modes

1. **OOM at the 3Gi pod limit.** `memory.events` shows the truth:
   `max <hits>` (ceiling churn = CDP hangs while Chrome thrashes) and
   `oom_kill <n>`. The 2026-10-21 outage was exactly this: Chrome hung at
   04:03Z, and a restart crash-looped because session restore tried to
   bring back 43 targets inside the same limit.
   Root cause of the recurring "wedge" (confirmed 2026-10-22 by probe):
   job pages keep out-of-process iframe targets (adzuna app_complete,
   hubspot chat) alive, and every later playwright-mcp connectOverCDP
   hangs at init while they exist (`ws connected` then
   `async initializeServer: Timeout 30000ms`; 172ms on a clean browser).
   Shipped fix: worker and finder blank their tab (about:blank) at unit
   end, so the iframes die with the page. Until it deployed, only a
   restart cleared it.
2. **Stale flock makes self-healing a silent no-op.** `pod-user-start.sh`
   starts with `flock -n 9 || exit 0`. One wedged invocation holds
   `/tmp/user-stack.lock` forever, and every watchdog tick after that exits
   0 without starting anything. Signature: WATCHDOG "restarting user stack"
   every 60s while `/tmp/chrome.log` mtime never moves. (Same signature
   visible on 2026-10-08 with pyagent - the stall predates this runbook.)
   Recurred 2026-10-22 19:14Z; rm lock + manual start recovered in ~12s.
3. **Probes that lie.** `pgrep -f "remote-debugging-port=9223"` matches any
   process whose cmdline contains that string - including the probing
   shell itself. A "CHROME-BACK" answer from such a probe can be a false
   positive. Verify with `/json/version` or a /proc cmdline scan for
   `/usr/bin/google-chrome *` instead. Conversely, the /proc kill loop
   from the recovery procedure DIES silently when driven through quoting
   layers (`ssh ... kubectl exec ... sh -c "..."`): the `$` anchors get
   eaten and the grep matches nothing, so Chrome survives while every
   later step "succeeds". Through quoting layers use
   `kill -9 $(pgrep -f "google-chrom[e]")` - the bracket keeps the probing
   shell from self-matching; verify death via `/json/version` refusing.
4. **Never close single pages via `/json/close/<id>`.** Measured
   2026-10-22 ~17:12Z: the targeted close crashed Chrome outright (0
   processes, CDP refused). Recovery is this runbook's restart; the
   shipped tab-hygiene fix (failure mode 1) removes the need.

## Recovery procedure (no pod restart)

A pod restart is the last resort: tailscale state lives in the container
filesystem, so a new pod needs interactive re-auth.

```sh
# inside the pod (kubectl -n default exec -i lubuntu-agent-<id> -- sh):
# 1. kill any chrome, clear restore payload so it boots small.
#    ORDER MATTERS: rm AFTER the kill and WITHOUT a pause - the watchdog
#    relaunches Chrome within seconds and a crash-exit Chrome restores the
#    poisoned tabs from the session files (seen 2026-10-22).
for p in $(ls /proc | grep -E '^[0-9]+$'); do
  cmd=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)
  case "$cmd" in /usr/bin/google-chrome*) kill -9 $p;; esac
done
rm -rf /home/agent/.playwright-chrome/Default/Sessions \
       "/home/agent/.playwright-chrome/Default/Session Storage" \
       "/home/agent/.playwright-chrome/Default/Last Session" \
       "/home/agent/.playwright-chrome/Default/Last Tabs"
# 2. clear a stale lock if /tmp/chrome.log is not being touched
ls -l /proc/*/fd 2>/dev/null | grep user-stack.lock   # find the holder
kill -9 <holder-pid> 2>/dev/null; rm -f /tmp/user-stack.lock
# 3. start the stack as agent
su agent -c 'DISPLAY=:99 /home/agent/pod-user-start.sh >> /tmp/user-stack.log 2>&1' &
# 4. verify: new browser UUID on 9223, then from the host on 30922
curl -s http://127.0.0.1:9223/json/version | grep webSocketDebuggerUrl
```

Then confirm the finder stops WARNing within a couple of minutes and that
`job_listings` documents get fresh `updatedAt` values.

## Open hardening decisions (owner sign-off needed)

- Raise the pod memory limit (3Gi -> 5-6Gi). Node has headroom; the cost is
  the pod restart and tailscale re-auth described above.
- Fix the flock stall: bounded wait (`flock -w 30`) or stale-holder
  detection in `pod-user-start.sh`, so self-healing cannot wedge silently.
- Optional: an API requeue endpoint that re-produces `campaign.jobs`
  messages. Mongo-only job_state requeue resets rows but the worker is
  purely Kafka-driven; today recovery relies on finder re-discovery, which
  stops covering a listing once it rotates off the boards.
