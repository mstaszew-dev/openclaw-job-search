# Campaign platform architecture

How the job-search campaign runs on the lubuntu k3s laptop: what runs where,
how the shared browser is arbitraged, what the apply loop promises, and how to
cut over, roll back and recover. The deploy mechanics live in
`KOTLIN_DEPLOY.md`; this document is the system itself.

## Topology

Single-node k3s. Two namespaces matter:

| Workload | ns | Image | Role |
|---|---|---|---|
| lubuntu-agent | default | (pod spec) | Owns the ONE campaign Chrome: Xvfb, VNC :30590, CDP relayed on NodePort :30922 (socat). Logins happen here over VNC, once. |
| mongo-0 (StatefulSet) | campaign | mongo:7 | Single-node replica set rs0. All bookkeeping, dedupe, the browser lease. Data in /home/mstro/k3s-pod-data/mongodb. |
| kafka-0 | campaign | apache/kafka:3.7.1 | KRaft single broker. Owns delivery; Mongo owns truth. Data in .../kafka. |
| finder | campaign | localhost:5000/campaign-finder:v1 | Discovery windows against the boards; produces campaign.jobs. |
| apply-worker | campaign | localhost:5000/campaign-worker:v1 | Consumes campaign.jobs; live applies or shadow releases. |
| campaign-api | campaign | localhost:5000/campaign-api:v1 | Read-only visibility: :30080/api/v1/stats and /jobs. |
| msrouter | campaign | localhost:5000/msrouter:k3s-headless-v1 | OpenAI-compatible gateway to free-tier models. ClusterIP :3000. LIVE (replicas 1) since the 2026-10-20 cutover; paused (replicas 0, zero tokens) 2026-10-08..2026-10-20. |

NodePorts: campaign-api :30080, CDP :30922, VNC :30590. Chrome rejects a DNS
Host header, so the CDP path is addressed by node IP, not service DNS.

## Data stores

Mongo (db `campaign`, root user `campaign`, secret `campaign-mongo`):

- `job_listings` - normalized board listings (source + sourceJobId unique).
- `applications` - the submission ledger, written once per real submission
  (`insertIfAbsent`); dedupe reads it - plus `skips` rows with reason
  DUPLICATE (including the auto-written blockedRepeat) - never job_state.
- `skips`, `blockers`, `events` - skip ledger, portal blockers, audit events.
- `job_state` - per-listing apply state machine (PENDING/IN_FLIGHT/SUBMITTED/
  ATTEMPTED/SKIPPED_DUPLICATE/SHADOW_RELEASED/FAILED/DEAD) with the
  cross-restart `attempts` counter.
- `browser_leases` - one document (_id "chrome") is the whole lease mechanism.

Kafka: topic `campaign.jobs` (3 partitions), DLQ `campaign.jobs.dlq`, consumer
group `apply-workers`. A record in the DLQ means a DEAD job; inspect with the
kafka-console-consumer command in KOTLIN_DEPLOY.md.

## Shared Chrome and the lease

The finder's scans and the worker's applies drive the same physical browser.
Mutual exclusion is one Mongo document (`browser_leases`, _id "chrome"), no
LockManager circus:

- **TTL-bounded steal.** A holder that crashes without releasing wedges the
  browser at most one ttl, then the next acquirer steals.
- **Owner-guarded release.** Only the current owner's key deletes the doc, so
  a late release after a steal cannot wipe someone else's lease.
- **Self-renewing hold.** A daemon watchdog re-extends the ttl every
  `leaseRenewSeconds` while the holder works, so the ttl only matters after a
  crash. Worker: ttl 25 min, renew 300 s, acquire-wait 600 s (a candidate may
  queue for the browser that long before it is deferred for redelivery).
  Finder: ttl 30 min, renew 300 s; a typical discovery window is ~12 min.
- **Kill switch.** `CHROME_LEASE_ENABLED=false` hands out no-op handles.
- **Clock.** Expiry compares pod wall-clock dates; multi-second skew shifts a
  steal by the skew, not the ttl.
- Acquire-wait is silent by design (no log spam while a window waits).

Known limitation: a holder does not abort an in-flight apply if its lease is
stolen mid-loop. The watchdog makes this reachable only after a crash (ttl
lapse), in which case the "zombie" apply may overlap one finder scan. The
abort callback is deliberately deferred.

Observed in production (2026-10-20 rollout): killing a pod mid-window left
its lease in `browser_leases` until the ttl lapsed - the new finder deferred
its first window ("browser busy (apply in progress); deferring discovery")
and the worker's candidates queued in acquire-wait, all silent, until the
steal. Expect up to one ttl of quiet after restarting a service that was
mid-window; this is the design working, not a wedge.

## Finder pipeline

Per window (scheduler, fixedDelay 900 s; a full discovery pass takes ~12 min
as observed in production): acquire lease -> `seenThisWindow.clear()` -> for
each source (nofluffjobs, justjoin, theprotocol) and stack (java, kotlin,
php, node, react): collect via Playwright MCP stdio (`@playwright/mcp` against CDP
:30922) -> normalize + upsert `job_listings` -> dedupe.check (applications
ledger + DUPLICATE skips; MatchedOn ID/COMPANY/URL) -> eligibility (salary
floor, region, remote/B2B policy) -> CV score -> produce one `campaign.jobs`
record per new candidate. Within a window, `seenThisWindow` collapses the
same listing surfacing on several filter pages (duplicate records used to
burn apply attempts downstream). The set is cleared at window start so the
NEXT window decides fresh; a cleared set is what allows a failed apply to be
re-enqueued and retried.

Scoring: since the 2026-10-20 cutover `FINDER_SCORING_ENABLED=true` and every
new candidate is scored by CvScorer against msrouter (tokens spent). The
neutral fallback score 70 - which passes the >= 60 filter - fires when
scoring is disabled (the pause posture) or when the gateway/model call
fails, so candidates are never dropped for gateway reasons.

## Apply worker

One message at a time (matches the one-tab discipline). Per delivery:

1. `beginAttempt` upserts job_state and returns the cumulative attempt
   count (survives restarts; the broker is not the source of truth).
2. **Over-bound guard (live mode only).** attempt > `worker.maxAttempts` (3)
   must not reopen the browser. It re-checks dedupe first: a crash between
   the portal submission and the offset commit redelivers an
   already-submitted job, and that must surface as SKIPPED_DUPLICATE, not
   DEAD. Non-duplicates dead-letter (awaited send, then commit; a failed DLQ
   send leaves the message uncommitted for redelivery). Shadow mode never
   opens the browser, so over-bound deliveries there take the plain release
   path.
3. **Live apply.** Acquire the lease (bounded wait; BrowserBusy deferral on
   window overlap) -> load listing -> dedupe gate BEFORE the browser -> LLM
   tool loop against the portal:

   - The request carries the system prompt AND the 9 tools as native OpenAI
     function schemas (`BrowserAgent.TOOL_SCHEMAS`). The schema types mirror
     the real consumers: the six forwarded browser tools are validated by
     @playwright/mcp (fill_form.fields is an ARRAY of field objects,
     wait_for.time a NUMBER), upload_cv is worker-side (camelCase cssInput;
     CDP `DOM.setFileInputFiles` fallback), record_submission/skip_task are
     the terminal worker verbs. Without schemas, free-tier models improvise
     tool calls as prose - the root cause of the 2026-10-19 incident (23+
     FAILED applies, "model produced no tool call").
   - Native `tool_calls` are preferred; surplus calls are logged and dropped;
     the legacy text `parseToolCall` stays as fallback only.
   - Known limitation: tool results are fed back as user text, not a
     `role=tool` protocol turn. The Python era ran 1983 submissions on the
     user-text path; porting role=tool is deferred until a model needs it.

4. **Terminal writes.** record_submission is instructed (prompt rule) to fire
   only on visible confirmation evidence; the worker records whatever the
   model calls, and SubmissionVerifier recomputes the Recorder verdict, so an
   evidence-less call lands as ATTEMPTED, never SUBMITTED - job_state and the
   applications row cannot diverge. Shadow mode: dedupe re-check,
   SHADOW_RELEASED, commit - no browser, no application rows.

## msrouter (LLM gateway)

Two configuration layers: secret `campaign-msrouter` (built from
`$DATA/msrouter.env` - the upstream provider keys - plus an optional
gateway-token literal, so pods see one secret) and the env file itself, kept
out of git. Image is built from the msrouter checkout's k3s-headless branch
with dist/ prebuilt (`npm ci && npm run build` in the checkout first - the
Dockerfile only packs it and fails fast if `dist/main.js` is missing). The
headless runtime opens `.run/msrouter.log` relative to the app dir, so the
image creates `/app/.run`; rebuilding the image after host-side GC is
`docker build -f deploy/docker/Dockerfile.msrouter -t
localhost:5000/msrouter:k3s-headless-v1 .../apps/msrouter`.

Postures: LIVE since the 2026-10-20 cutover - replicas 1,
`FINDER_SCORING_ENABLED=true`, `APPLY_MODE=live`, real tokens spent on
scoring and apply tool loops. The zero-spend posture (rollback-safe state):
`spec.replicas: 0` on msrouter + `FINDER_SCORING_ENABLED=false` on the finder
+ `APPLY_MODE=shadow` on the worker - scoring falls back to 70, no browser
opens, no tokens; a live apply REQUIRES the gateway (the tool loop is an LLM
loop), so worker-live and gateway-down must never coexist.

## Cutover / rollback

- Cutover: stop the Mac Python agent (same campaign), then
  `kubectl -n campaign set env deploy/apply-worker APPLY_MODE=live`.
- Rollback: `APPLY_MODE=shadow` + rollout restart. Shadow is safe next to any
  other driver of the campaign.
- Code ships by rebuild + import + restart, never by tag bump: the
  `localhost:5000/*:v1` tags are static with `imagePullPolicy: IfNotPresent`,
  so after `docker save <img> | k3s ctr images import -` run
  `kubectl -n campaign rollout restart deploy/<name>` or the pod keeps the
  old image.
- Verify: `curl :30080/api/v1/stats` (submitted/attempted/inFlight/dead/
  dlqDepth/consumerLag), `bash deploy/campaign-status.sh`, worker logs
  (`live:`, dead-letter lines). The "Resetting offset for partition
  campaign.jobs-1 to offset=0" line on restart is harmless: retention can
  empty a partition (log-end 0), and the replay is absorbed by dedupe.
  Restarting a service that held the lease buys up to one ttl of quiet
  (see the lease section) - do not mistake it for a wedge.

## Incident recovery: requeueing parser-era failures

When a systemic bug (e.g. the missing-tools bug) fails applies WITHOUT
reaching the portal, the affected job_state rows are FAILED with a
characteristic `lastError` and no applications row. After shipping the fix:

```
db.job_state.updateMany(
  {status:"FAILED", lastError:{$regex:"<the error signature>"}},
  {$set:{status:"PENDING", attempts:0}, $unset:{lastError:""}}
)
```

Resetting `attempts` is required, not cosmetic: the over-bound guard would
otherwise dead-letter every retry of those listings without a browser, ever.
The next finder window re-enqueues what is still listed; listings already
delisted stay PENDING forever and are swept by the operator. Bounded by
design: no browser is opened by the reset itself.

## Incident recovery: resolving stale blockers

Open blockers are not bookkeeping: the Recorder counts UNRESOLVED blocks per
companyKey (listing scope for sentinel keys), and a second open block
auto-writes a `blockedRepeat` skip that DedupService matches like any
DUPLICATE row - so months-old blocks silently suppress new listings from
those companies. Resolved blocks do not guard (by design); a fresh block
from a live apply re-arms the guard with current evidence.

Sweep of 2026-10-20 (all pre-cutover rows, newest 2026-09-09): 112 blockers
resolved in place (`resolved:true`, resolution note appended to `detail`,
`at` preserved) and the 18 derived `blockedRepeat` skips deleted; live
`blockersOpen` returned to 0 via the stats API. Backup first, ALWAYS
including the `blockedRepeat` skips - the blocker docs are self-describing
after resolution, but the derived skip rows are NOT reconstructible from
them once deleted (backup: laptop
`campaign-data/backups/blockers-skips-20261020.json`, pre+post states).

## Slack communication (#jobcampaign)

The worker posts four outcome classes to the `#jobcampaign` Slack channel via
`SlackNotifier` -> `SlackClient` (common): valid submissions, new portal
blocks, apply failures (step-cap stalls included), and dead-letters.
Duplicates and skips stay silent. Delivery semantics are ported from
msrouter's director surface (`deliverToSlack`): bot token + `chat.postMessage`
first, incoming webhook only when no bot token exists, and missing credentials
is a permanent silent success. Every post is fire-and-forget: a Slack outage
is logged and swallowed, never propagated into the apply pipeline. Messages
report persisted facts, so each notifier call sits after the repo writes.
Every message also carries a live campaign line - `📊 submitted/target,
remaining`, plus `N blockers open` when any exist - fetched at post time
from campaign-api `GET /api/v1/stats` (`StatsClient`, `CAMPAIGN_API_URL`).
A stats outage drops only that footer line, never the post itself.

Credentials come from the `campaign-msrouter` secret (`SLACK_BOT_TOKEN`,
`SLACK_CHANNEL`, `SLACK_WEBHOOK`, all optional) - the same values the Mac's
`~/ZCodeProject/msrouter/.env` carries; verify equality by sha256, never by
printing them.

The msrouter director itself stays disabled in-cluster
(`DIRECTOR_INTERVAL_MINUTES=-1`): its worker-supervision role is obsolete on
k3s (the worker is a Deployment), and its Slack-communication role is what
`SlackNotifier` replaces. Its approval-round-trip (`pollSlackMessages`
matching approve/reject replies against pending patches) is NOT ported; the
campaign's guards plus human-runbook requeue cover that need today.

## Step cap and poll budget

`WORKER_MAX_STEPS` (default 60) caps the browser tool loop. Both the loop and
the Kafka `max.poll.interval.ms` derive from `AgentLoopBounds` in common, so
an override raises them together (worst case 60 x 210s = 3.5h, +30 min
margin). Each terminal outcome logs its step count; size the cap from that
telemetry, not from guesses. 250 would bound a stalled apply at 14.6h and is
deliberately not the default.

## Known limitations

- No in-flight abort on lost lease (see above).
- `role=tool` protocol not ported; results go back as user text.
- Free-tier models occasionally emit prose tool calls despite schemas; the
  text fallback catches single-call prose, multi-call prose still fails the
  attempt (bounded by the attempt bound + DLQ).
- Over-bound guard assumes one scheduler thread drives finder windows
  (single writer to `seenThisWindow`); the set is concurrent-safe regardless.
- BrowserBusy deferrals consume attempt budget: every delivery increments
  the attempt counter first, and the catch path dead-letters (DLQ + DEAD,
  committed) once `attempt >= maxAttempts` (3) - so a candidate can go DEAD
  after three busy rounds without ever seeing the browser. The over-bound
  pre-check (`attempt > maxAttempts`) is NOT reachable via busy rounds; it
  only sees redeliveries after a crash between the Mongo write and the
  offset commit. The lease ttl bounds the busy period (a 25-30 min hold
  without renewal - a crashed holder), so deferrals on ordinary window
  overlap recover well before the budget runs out.
- Delisted-while-PENDING listings are never re-enqueued (swept by operator).
- Mongo expiry/steal is wall-clock based; NTP loss on the node shifts steal
  timing, nothing worse.
