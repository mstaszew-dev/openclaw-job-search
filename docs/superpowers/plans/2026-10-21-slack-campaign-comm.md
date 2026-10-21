# Slack Campaign Communication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the msrouter director's Slack communication to the Kotlin campaign platform: worker posts submissions, blocks, failures, and dead-letters to `#jobcampaign`, using the working Mac Slack credentials.

**Architecture:** A thin `SlackClient` in `common/net` (mirrors `LlmClient`'s injectable-transport seam) + a `SlackNotifier` facade in the worker that formats campaign events and swallows all errors. `WorkerLoop` calls the notifier at its four terminal-outcome sites. The k8s secret gets the real Mac creds; the worker deployment reads them via `secretKeyRef`.

**Tech Stack:** Kotlin, JDK `java.net.http` (reflection-free, native-image friendly), Spring bean wiring in existing `WorkerConfig`, JUnit 5 + AssertJ with fake transports (no MockWebServer dependency).

**Spec:** Reference implementation `~/ZCodeProject/msrouter/src/director/surface.ts` (`deliverToSlack`): bot token + `chat.postMessage` to `#jobcampaign` first; webhook ONLY when no bot token (webhooks are channel-bound); no creds = permanent silent success (not a retryable failure); failed posts logged, never thrown. Cred findings (this session): all three `campaign-msrouter` secret Slack values differ from Mac `~/ZCodeProject/msrouter/.env` (sha256-compared: `94ebfce4`≠`e50019be`, `de8b63ad`≠`d47c1e25`, `e106dd53`≠`15475b40`) — the pod cannot post. msrouter director itself stays disabled (`DIRECTOR_INTERVAL_MINUTES=-1`): its worker-supervision role is obsolete on k3s; its Slack comm role is what we port.

## Global Constraints

- English only; never echo secret values in code, logs, tests, commits, or terminal output (hash-compare only).
- Slack failures must NEVER break the apply pipeline: every notifier call swallows all exceptions and logs.
- Delivery semantics match msrouter `deliverToSlack` exactly (see Spec).
- HTTP via JDK `HttpClient`, same style as `LlmClient`/`JdkChatTransport`.
- Step cap: default 60, NOT 250 (reasoning in Task 4). Env-configurable via `WORKER_MAX_STEPS`; poll budget must be derived from the SAME effective value (PollBudgetTest exists precisely to stop that drift).
- Image tag `:v1`; deploy contract = rebuild + `docker save | k3s ctr images import -` + `rollout restart`.
- Canonical flow: Mac commit → `git push origin main` → bundle → scp → laptop `git merge --ff-only origin/main`.
- TDD for all Kotlin changes; fresh-context review subagent before push.

## Review Focus

- Slack outage/timeout mid-apply: worker must complete the apply and commit normally (notifier swallows, 5s transport timeout). Test: notifier test with throwing transport asserts no propagation.
- Unconfigured creds (secret missing key): silence, no error spam, pipeline unaffected. Test: no-creds client returns true and never touches transport.
- Slack `ok:false` (bad token) with webhook also present: do NOT silently fall back to webhook (channel-bound, could post to wrong place) — return false. Test pins this.
- `WORKER_MAX_STEPS` set absurdly high (e.g. 5000): poll budget must still cover it (derived from same env) or the consumer gets evicted mid-apply. Test: budget formula test + parse-function clamp test.
- Step-cap failure text must include the EFFECTIVE cap (an operator requeueing from Slack reads this number). Test: cap message uses instance value.

---

### Task 1: SlackClient in common/net

**Files:**
- Create: `kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/net/SlackClient.kt`
- Test: `kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/net/SlackClientTest.kt`

**Interfaces:**
- Consumes: nothing (standalone).
- Produces (Task 2/3 rely on):
```kotlin
fun interface SlackTransport { fun post(url: String, body: String, timeout: Duration): String }
// JdkSlackTransport: throws on non-2xx, returns body string.
class JdkSlackTransport(private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) : SlackTransport
class SlackClient(
    private val botToken: String?,      // nullable
    private val channel: String?,       // nullable
    private val webhookUrl: String?,    // nullable
    private val transport: SlackTransport = JdkSlackTransport(),
) {
    fun postMessage(text: String): Boolean   // true = delivered or intentionally silent (no creds)
}
```

- [ ] **Step 1: Write the failing tests** — fake `SlackTransport` capturing URL/headers-carrying body; pin these behaviors:
  1. token+channel: POSTs `https://slack.com/api/chat.postMessage`, body `{"channel":"#jobcampaign","text":"hi"}`, `{"ok":true}` → returns true
  2. token+channel with `{"ok":false,"error":"invalid_auth"}` → returns false, webhook NOT called (single transport capture proves one POST)
  3. all three creds null → returns true, transport never called
  4. webhook-only (token null, webhook set): POSTs the webhook URL, body `{"text":"hi"}` → returns true
  5. transport throws → returns false (caught)
- [ ] **Step 2: Run** `./gradlew :common:test --tests '*SlackClientTest*'` — Expected: FAIL (SlackClient unresolved)
- [ ] **Step 3: Implement** per Interfaces block. Bearer header on the chat.postMessage call; JSON via the module's `ObjectMapper`. Body-parse the `ok` field only on the token path; webhook path returns true on 2xx (non-2xx = transport threw).
- [ ] **Step 4: Run** — Expected: PASS
- [ ] **Step 5: Commit** `feat: SlackClient with msrouter-parity delivery semantics`

### Task 2: SlackNotifier facade in apply-worker

**Files:**
- Create: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/SlackNotifier.kt`
- Test: `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/SlackNotifierTest.kt`

**Interfaces:**
- Consumes: `SlackClient.postMessage(String): Boolean` (Task 1).
- Produces (Task 3 relies on):
```kotlin
@Component
class SlackNotifier(private val client: SlackClient) {
    fun submission(company: String?, roleTitle: String?, source: String?)
    fun blocked(jobId: String, reason: String)
    fun failed(jobId: String, error: String)
    fun deadLettered(jobId: String, error: String)
}
```
- Formats (exact, plain text, msrouter house style):
  - `✅ campaign: submitted to <company> — <roleTitle> (<source>)`
  - `🚧 campaign: blocked <jobId> — <reason>`
  - `⚠️ campaign: apply failed <jobId> — <error>`
  - `💀 campaign: dead-lettered <jobId> — <error>`

- [ ] **Step 1: Write failing tests**: each method posts exactly the format above (capturing fake transport from Task 1); client throwing → no exception propagates; null company renders as `?`.
- [ ] **Step 2: Run** `./gradlew :apply-worker:test --tests '*SlackNotifierTest*'` — Expected: FAIL (unresolved)
- [ ] **Step 3: Implement**: every method body is `runCatching { client.postMessage(format(...)) }` + debug log on false/throw. Never rethrow.
- [ ] **Step 4: Run** — Expected: PASS
- [ ] **Step 5: Commit** `feat: SlackNotifier formats campaign outcomes for #jobcampaign`

### Task 3: WorkerLoop wiring at the four outcome sites

**Files:**
- Modify: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/WorkerLoop.kt` (constructor + Submitted branch ~331, Blocked ~342, Failed ~343, deadLetter ~148 — over-bound ~139 flows through deadLetter)
- Modify: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/WorkerConfig.kt` (add `@Bean fun slackClient(): SlackClient = SlackClient(System.getenv("SLACK_BOT_TOKEN"), System.getenv("SLACK_CHANNEL"), System.getenv("SLACK_WEBHOOK"))` — env read at the explicit wiring site, codebase pattern)
- Test: extend `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/WorkerLoopTest.kt`

**Interfaces:**
- Consumes: `SlackNotifier` (Task 2).
- Produces: live Slack posts on terminal outcomes.

- [ ] **Step 1: Write failing tests** in WorkerLoopTest style (constructor gains `notifier: SlackNotifier` — use a fake recording calls): Failed outcome → `failed(job.id, "step cap ...")` once; Submitted outcome → `submission(...)` once; dead-letter path → `deadLettered(...)` once. DuplicateNow/Blocked-exists paths: Blocked notifies, Duplicate does not.
- [ ] **Step 2: Run** `./gradlew :apply-worker:test --tests '*WorkerLoopTest*'` — Expected: FAIL (no notifier param)
- [ ] **Step 3: Implement**: constructor param after `recorder`; one notifier call per site, placed AFTER the recording/repo writes succeed (Slack reports persisted facts, not intentions).
- [ ] **Step 4: Run full module tests** `./gradlew :apply-worker:test` — Expected: PASS (all pre-existing tests too)
- [ ] **Step 5: Commit** `feat: worker posts submissions, blocks, failures, dead-letters to Slack`

### Task 4: WORKER_MAX_STEPS env cap + poll budget + step telemetry

**Reasoning on the number (user asked to reason twice):** a step = one model call (≤90s) + one tool call (≤120s) = ≤210s worst case. 25 → 87.5 min worst case and two live applies already stalled at the cap; typical applies need 10–20 steps and portal forms (theprotocol) legitimately need more. 250 → 14.6h worst case: monopolizes the shared Chrome lease for up to a day, forces `max.poll.interval.ms` to ~15h, and a stuck apply sits half a day before anyone notices — while the Slack alert from Task 3 makes failures visible the moment they happen, so early-terminate + human requeue beats a giant cap. **Decision: default 60** (2.4× headroom; 3.5h bounded worst case), env-overridable so the next re-sizing is a config change, not a rebuild.

**Files:**
- Modify: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgent.kt` (companion: `DEFAULT_MAX_STEPS = 60`, `EFFECTIVE_MAX_STEPS = parseMaxSteps(System.getenv("WORKER_MAX_STEPS"))`, `fun parseMaxSteps(raw: String?): Int` = null/blank/NaN→60, `<1`→1, else parsed; constructor `maxSteps: Int = EFFECTIVE_MAX_STEPS`; loop `repeat(maxSteps)`; cap message uses `maxSteps`; one info log of steps-used at each outcome return)
- Modify: `kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/messaging/KafkaWiring.kt` (`AGENT_MAX_STEPS = BrowserAgent.EFFECTIVE_MAX_STEPS`; `MAX_POLL_INTERVAL_MS` ≥ effective worst case: set to `Duration.ofHours(4).toMillis()` = 14,400,000 with comment `> 60 steps × 210s = 3.5h`)
- Test: `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/PollBudgetTest.kt` + `BrowserAgentTest.kt`

**Interfaces:**
- Consumes: existing `KafkaWiring.AGENT_MAX_STEPS` mirror + PollBudgetTest pinning.
- Produces: `BrowserAgent.EFFECTIVE_MAX_STEPS`, `BrowserAgent.parseMaxSteps(String?): Int`, per-apply step-count telemetry.

- [ ] **Step 1: Write failing tests**: parseMaxSteps(null)=60, ("250")=250, ("abc")=60, ("0")=1, (" 60 ")=60; PollBudgetTest: budget > EFFECTIVE_MAX_STEPS × (STEP_TIMEOUT+TOOL_TIMEOUT), mirrored AGENT_MAX_STEPS == EFFECTIVE_MAX_STEPS; BrowserAgentTest: agent constructed with maxSteps=2 fails with message "step cap 2 reached without outcome".
- [ ] **Step 2: Run** `./gradlew :apply-worker:test :common:test` — Expected: FAIL
- [ ] **Step 3: Implement** per Files block. Keep `STEP_TIMEOUT`/`TOOL_TIMEOUT` as-is; `MAX_STEPS` const is replaced by `DEFAULT_MAX_STEPS`/`EFFECTIVE_MAX_STEPS` (fix any compile references; grep first — CvUploader imports TOOL_TIMEOUT only).
- [ ] **Step 4: Run full test suite** `./gradlew test` (all modules) — Expected: PASS
- [ ] **Step 5: Commit** `feat: WORKER_MAX_STEPS env cap (default 60), derived poll budget, step telemetry`

### Task 5: Ops — secret sync, manifest wiring, deploy, live round-trip

**Files:**
- Modify: `deploy/k8s/worker.yaml` (container env: `SLACK_BOT_TOKEN`, `SLACK_CHANNEL`, `SLACK_WEBHOOK` via `valueFrom.secretKeyRef` name `campaign-msrouter`; `WORKER_MAX_STEPS: "60"` plain value with comment)

No TDD (ops); verification is hash + live post.

- [ ] **Step 1: Sync secret from Mac .env** (values never printed): build env file locally from `~/ZCodeProject/msrouter/.env` grep of the three keys, pipe through `kubectl create secret generic campaign-msrouter --from-env-file=- --dry-run=client -o yaml | kubectl apply -f -` over ssh stdin, then re-run the sha256 comparison — all three must now match Mac.
- [ ] **Step 2: Edit worker.yaml** per Files block (follow existing env entry style).
- [ ] **Step 3: Build + ship**: `./gradlew :apply-worker:bootJar` (from kotlin/, full test suite green first) → `docker build -f deploy/docker/Dockerfile.jvm -t localhost:5000/campaign-worker:v1 .` (context `kotlin/`) → `docker save | ssh ... k3s ctr images import -` → `kubectl rollout restart deploy/apply-worker` → wait ready.
- [ ] **Step 4: Live round-trip**: `kubectl run slack-rt --rm -i --restart=Never --image=curlimages/curl --command -- sh -c 'curl -s -X POST https://slack.com/api/chat.postMessage -H "Authorization: Bearer $SLACK_BOT_TOKEN" -H "Content-Type: application/json" -d "{\"channel\":\"$SLACK_CHANNEL\",\"text\":\"🔍 campaign: Slack comm wired — k3s worker reporting in.\"}"'` with the secret env attached — expect `"ok":true` in output (values not echoed).
- [ ] **Step 5: Verify pod env keys present** (`env | grep -o '^[A-Z_]*'` in worker pod shows the three names; values unprinted).
- [ ] **Step 6: Commit** manifest change `feat: worker gets Slack creds from secret + WORKER_MAX_STEPS`

### Task 6: Docs, review, ship, monitor

**Files:**
- Modify: `deploy/docs/ARCHITECTURE.md` (new "Slack communication" subsection: event types, delivery semantics, secret source, disabled-director rationale)
- Modify: `deploy/docs/KOTLIN_DEPLOY.md` (secret-sync-from-Mac step; WORKER_MAX_STEPS knob)

- [ ] **Step 1: Write both doc edits.**
- [ ] **Step 2: Fresh-context review subagent** over the full uncommitted diff + the 5 task commits — BLOCKER/SHOULD/NICE; fix BLOCKERs before push, re-verify.
- [ ] **Step 3: Commit docs, push**: `git push origin main` → `git bundle create $TMPD/kotlin-dist.bundle main` → scp → laptop `git fetch origin && git merge --ff-only origin/main`.
- [ ] **Step 4: Monitor 30 min** (background watcher): stats, worker logs for `chat.postMessage failed` / step telemetry lines; confirm a real event posts to #jobcampaign (a submission or failure); report.
