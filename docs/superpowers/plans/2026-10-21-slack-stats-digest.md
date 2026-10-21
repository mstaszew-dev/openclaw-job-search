# Slack Stats Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the campaign counters continuously visible in Slack: the worker posts an hourly `📊 campaign: N/2100 submitted` digest to `#jobcampaign`, so the submitted count and target are always recent in the channel without waiting for an outcome post (the funnel is dry; outcome posts can be days apart). Also pin the per-application-budget decision rule (steps/attempts) so "more ticks per application" is an evidence-driven env change, not a guess.

**Architecture:** A small `SlackStatsReporter` `@Component` in the worker's `apply` package next to `SlackNotifier`. It reuses the existing `SlackClient` + `StatsClient` beans and Spring's already-enabled scheduling (`@EnableScheduling` on `WorkerConfig`; interval style copied from `FinderPipeline`'s `fixedDelayString` seconds-property pattern). No new infra, no new transport, no worker.yaml change (the interval placeholder reads env directly with an in-code default).

**Tech Stack:** Kotlin, Spring `@Scheduled`, JUnit 5 + AssertJ with the same private fake-transport style as `SlackNotifierTest`/`StatsClientTest`.

## Global Constraints

- English only; never echo secret values (sha256-prefix compare only).
- Typography: no em dashes; use " - " separators (Slack copy included).
- Slack failures must NEVER break anything: the reporter swallows all exceptions like `SlackNotifier` does. The digest is enrichment, not dependency.
- A stats outage skips the digest entirely (no post) - unlike the outcome footer, the numbers ARE the content, so there is no bare message to fall back to. Next tick retries.
- Image tag `:v1`; deploy contract = rebuild + `docker save | k3s ctr images import -` + `rollout restart`. Worker is idle (lag 0, no in-flight) -> restart is safe.
- Canonical flow: Mac commit -> `git push origin main` -> bundle -> scp -> laptop `git merge --ff-only` -> build/import/restart.
- TDD for all Kotlin changes; fresh-context review subagent before push.

## Design Decisions

- **Cadence: hourly (3600s), first post ~45s after pod start.** `initialDelay = 45s` means every deploy immediately proves the wiring with a fresh digest in Slack; hourly keeps the channel readable (24 posts/day in an otherwise quiet operator channel). Env-tunable via `WORKER_STATS_REPORT_INTERVAL_SECONDS` without any yaml change.
- **Message format** reuses the footer grammar so the channel has ONE stats dialect:
  `📊 campaign: 1990/2100 submitted - 110 remaining` plus ` - N blockers open` when N > 0.
- **No change-detection / dedupe of identical digests.** YAGNI: the stable hourly line IS the dashboard the user asked for.
- **Per-application budgets: no code change.** Current evidence (2026-10-21): dead 0, DLQ 0, blockers 0, attempted work all completing - submissions are NOT failing; the funnel is simply dry (finder discovery is the bottleneck, not the worker). Raising steps/attempts now would change nothing. The decision rule (below) is documented so the knob move happens only when failure text earns it:
  - `⚠️ ... step cap reached without outcome` clusters -> raise `WORKER_MAX_STEPS` 60 -> 90 (env + `kubectl apply` + rollout restart; poll budget derives from the same env, so no drift).
  - `💀 ... attempt bound burned` clusters -> raise `WORKER_MAX_ATTEMPTS` 3 -> 5 (same env-only path).
  - Neither text appearing = budgets are not the problem; do not touch them.

## Review Focus

- The reporter must never throw out of `report()` (a scheduled method that throws only pollutes logs, but the contract is swallow-all like `SlackNotifier`). Test: throwing Slack transport + stats present -> no propagation.
- Stats null -> zero Slack calls (skip, don't post bare). Test: failing stats transport -> `calls == 0`.
- Exact wire text pinned for both the clean line and the blockers variant.
- Interval property: default 3600 in the placeholder (no hard dependency on worker.yaml edits), matching `finder.run-interval-seconds` style.

---

### Task 1: SlackStatsReporter (TDD)

**Files:**
- Test: `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/SlackStatsReporterTest.kt`
- Create: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/SlackStatsReporter.kt`

**Interfaces:**
```kotlin
@Component
class SlackStatsReporter(private val client: SlackClient, private val stats: StatsClient) {
    @Scheduled(
        fixedDelayString = "\${worker.stats-report-interval-seconds:3600}000",
        initialDelayString = "45000",
    )
    fun report()   // package-visible logic, public for direct test invocation
}
```

- [ ] **Step 1: Failing tests first** (reuse the private fake style from `SlackNotifierTest`: `CapturingNotifierTransport : ChatTransport`, `statsTransport(json)`; same `STATS_JSON` fixture with `remaining` adjusted to keep arithmetic consistent). Pin:
  1. `the digest posts the campaign line` -> wire text exactly `📊 campaign: 1990/2100 submitted - 110 remaining`
  2. `the digest names open blockers when any exist` -> `📊 campaign: 1989/2100 submitted - 111 remaining - 2 blockers open`
  3. `a stats outage skips the digest entirely` -> stats transport throws -> `calls == 0`, no exception
  4. `a Slack outage never propagates out of the reporter` -> throwing `ChatTransport` -> `assertDoesNotThrow { reporter.report() }`
- [ ] **Step 2: Run tests, confirm red** (`./gradlew :apply-worker:test --tests '*SlackStatsReporterTest*'` fails to compile: class missing).
- [ ] **Step 3: Implement** `SlackStatsReporter`: fetch stats, null -> return; build line with the blockers suffix conditional; `runCatching { client.postMessage(line) }` with `.onFailure { log.warn(...) }` / success -> `log.info("Posted stats digest: {}", line)` (info success line doubles as the deploy verification signal). Class doc: why a digest exists (outcome posts are rare when the funnel is dry), why stats-null skips entirely, swallow-all contract.
- [ ] **Step 4: Green** - the 4 new tests pass; `./gradlew :apply-worker:test` full suite still green.

### Task 2: Config knob + docs

**Files:**
- Edit: `kotlin/apply-worker/src/main/resources/application.yml` (under `worker:`)
- Edit: `deploy/docs/ARCHITECTURE.md` (Slack communication section)

- [ ] **Step 1:** application.yml, under `worker:`: `stats-report-interval-seconds: ${WORKER_STATS_REPORT_INTERVAL_SECONDS:3600}` with a two-line comment (hourly digest to #jobcampaign; 45s initial delay so every deploy posts once immediately).
- [ ] **Step 2:** ARCHITECTURE.md Slack section: add a short paragraph - hourly `SlackStatsReporter` digest carries the live submitted/target/remaining (+ blockers) line even when no outcomes fire, so the channel stays a dashboard; env knob; stats outage skips a tick. Add the per-application-budget decision rule (step-cap text -> `WORKER_MAX_STEPS` 60->90; attempt-bound text -> `WORKER_MAX_ATTEMPTS` 3->5; no such text -> do not touch), stating today's evidence (dead 0, DLQ 0, blockers 0).

### Task 3: Build, review, commit, push

- [ ] **Step 1:** Full `./gradlew build` (all modules) green.
- [ ] **Step 2:** Fresh-context review subagent (code-review agent) on the diff; fix BLOCKER/SHOULD findings.
- [ ] **Step 3:** Commit (typography rule: no em dashes) + `git push origin main`.

### Task 4: Deploy + verify

- [ ] **Step 1:** Laptop flow (proven contract): `git bundle create` -> scp -> `git merge --ff-only FETCH_HEAD` in `~/k3s-pod-data/agent-home/campaign-data/openclaw-job-search` -> `docker build -f deploy/docker/Dockerfile.jvm --target worker -t localhost:5000/campaign-worker:v1 kotlin/` -> `docker save | sudo k3s ctr images import -` -> `rollout restart deploy/apply-worker` (idle worker, no orphan risk).
- [ ] **Step 2:** Verify within ~60s: pod log shows `Posted stats digest: 📊 campaign: 1990/2100 submitted - 110 remaining`; user sees the digest in #jobcampaign.

### Task 5: Monitoring continuation

- [ ] **Step 1:** Read the monitor3 report when it fires (~11:47Z): browser alive, no new timeouts vs 105 baseline, outcomes, lag.
- [ ] **Step 2:** Ongoing slow-cadence checks (2-3/day) now additionally watch: digest posts appearing hourly in the worker log, and any `⚠️`/`💀` Slack posts - if either budget-decision text appears repeatedly, apply the documented knob move.
