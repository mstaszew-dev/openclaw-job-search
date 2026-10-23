# Apply Loop Breaker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the apply agent from burning its whole 60-step cap (and ~10 minutes of queue time) on a hopeless loop: abort after 3 consecutive failures of the same tool, and raise the cap to 80 so genuinely long healthy flows get headroom.

**Architecture:** `BrowserAgent.run`'s tool loop currently appends every tool result to the message history and keeps going. Tool failures already flow through two catch sites (`browserTool` and the `upload_cv` branch), each returning a fixed-format error string. Those sites will return a small `ToolOutcome(toolName, text, failed)` instead of a bare String; the loop tracks a consecutive-failure streak keyed by tool NAME (not args - today's `fill_form` loop varied args every retry and must still be caught), resets on any success or a different tool, and returns `ApplyResult.Failed` when the streak hits 3. The cap raise flows through `AgentLoopBounds`, which already derives Kafka's poll budget from the same constant.

**Tech Stack:** Kotlin, JUnit 5 + mockk + AssertJ (existing `BrowserAgentTest` patterns), Spring @Component wiring, k3s deploy contract.

**Spec:** conversation of 2026-10-21 with the operator: cap-outs observed on `nofluffjobs:java-developer-darize-remote` (upload-refusal loop, 60 steps) and `nofluffjobs:fullstack-developer-react-typescript-java-braver-it-remote` (dead listing, navigate HTTP error retried ~50x to the cap); agreed fix = loop breaker (threshold 3, keyed by tool name) + modest cap raise to 80.

## Global Constraints

- TDD for all Kotlin changes: failing test first, watch it fail, make it pass.
- English only; no em dashes anywhere in code, comments, docs, or commit messages.
- Never echo secret values (Slack tokens, Mongo passwords) into logs, code, or docs.
- Image tags `:v1` never change; deploy = rebuild + `k3s ctr images import` + `rollout restart`.
- `AgentLoopBounds` lives in `kotlin/common`: after Task 2, BOTH finder and worker images must be rebuilt.
- Slack failures must never break the pipeline (existing invariant; do not introduce a path where they can).
- Worker-side ledger tools (`record_submission`, `skip_task`) keep escaping the loop on Mongo failure - they must never be counted toward the breaker and never be retried by the model.

## Review Focus

- Alternating failures of two different tools with no success between (navigate fail, click fail, navigate fail, ...): expected behavior is NO breaker abort (streak is per-tool-name, reset on name change); the step cap still bounds it. Accepted; not pinned by a test - the cap covers it.
- A tool that succeeds after two failures (transient hiccup): must NOT abort - pinned by `a success of the failing tool resets the streak`.
- `record_submission`/`skip_task` throwing (Mongo down): must escape the loop as today (redelivery), never trip the breaker - preserved because they never return `ToolOutcome`; existing Recorder tests cover the escape.
- The loop message appended to history must be `dispatched.text`, not `dispatched.toString()` - a data-class toString leaking into the LLM history would confuse the model (and is the most likely silent mistake in this refactor).
- `WORKER_MAX_STEPS` in `deploy/k8s/worker.yaml` pins the cap in production: changing only the code default changes nothing at runtime. Both must move together (Task 2 does both).

---

### Task 1: Loop breaker in `BrowserAgent`

**Files:**
- Modify: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgent.kt`
- Test: `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgentTest.kt`

**Interfaces:**
- Consumes: existing `dispatch`/`browserTool` structure, `LoopState`, `ApplyResult.Failed`, `ScriptedTransport`/mockk test harness.
- Produces: `private data class ToolOutcome(val toolName: String, val text: String, val failed: Boolean)`; `dispatch` now returns `Any` where the non-terminal case is `ToolOutcome`; companion `const val LOOP_BREAK_THRESHOLD = 3`; `LoopState` gains `var failingTool: String? = null` and `var failureStreak: Int = 0`. Terminal failure message format: `"loop breaker: $toolName failed $LOOP_BREAK_THRESHOLD consecutive times; last error: ${text.take(150)}"`.

- [ ] **Step 1: Write the failing tests** (append to `BrowserAgentTest.kt`, existing harness style)

```kotlin
@Test
fun `loop breaker aborts after three consecutive failures of the same tool`() {
    every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
    val transport = ScriptedTransport(
        listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
    )

    val result = run(agent(transport, maxSteps = 10))

    assertThat(result).isEqualTo(
        ApplyResult.Failed(
            "loop breaker: browser_navigate failed 3 consecutive times; " +
                "last error: tool browser_navigate failed: net::ERR_DEAD",
        ),
    )
    verify(exactly = 3) { browser.callToolText(any(), any(), any()) }
    verify { recorder.recordSubmitted(any(), any(), any()) wasNot Called }
}

@Test
fun `a successful different tool between failures resets the streak`() {
    every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
    val transport = ScriptedTransport(
        listOf(
            toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
            toolCallResponse("browser_snapshot", "{}"),
        ),
    )

    val result = run(agent(transport, maxSteps = 7))

    assertThat(result).isEqualTo(ApplyResult.Failed("step cap 7 reached without outcome"))
}

@Test
fun `a success of the failing tool resets the streak`() {
    var calls = 0
    every { browser.callToolText(eq("browser_navigate"), any(), any()) } answers {
        calls++
        if (calls % 3 == 0) "landing page" else throw RuntimeException("flaky")
    }
    val transport = ScriptedTransport(
        listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
    )

    val result = run(agent(transport, maxSteps = 7))

    assertThat(result).isEqualTo(ApplyResult.Failed("step cap 7 reached without outcome"))
}

@Test
fun `upload_cv failures count toward the loop breaker`() {
    every { cvUploader.upload(any(), any(), any(), any()) } throws RuntimeException("chooser refused")
    val transport = ScriptedTransport(
        listOf(toolCallResponse("upload_cv", """{"selector":"#file"}""")),
    )

    val result = run(agent(transport, maxSteps = 10))

    assertThat(result).isInstanceOf(ApplyResult.Failed::class.java)
    assertThat((result as ApplyResult.Failed).detail)
        .startsWith("loop breaker: upload_cv failed 3 consecutive times")
    verify(exactly = 3) { cvUploader.upload(any(), any(), any(), any()) }
}
```

Notes: `ScriptedTransport` repeats its last response, so one entry covers all steps. The default `browser` mock already returns `"page snapshot"` for any successful `callToolText`, which is what the reset tests rely on.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd kotlin && ./gradlew :apply-worker:test --tests '*BrowserAgentTest*'`
Expected: the four new tests FAIL (loop runs to the injected cap or to mock exhaustion), existing tests PASS.

- [ ] **Step 3: Implement in `BrowserAgent.kt`**

1. `LoopState`: add `var failingTool: String? = null` and `var failureStreak: Int = 0`.
2. Add `private data class ToolOutcome(val toolName: String, val text: String, val failed: Boolean)`.
3. Companion: add `const val LOOP_BREAK_THRESHOLD = 3`.
4. `browserTool`: return `ToolOutcome(call.name, <same strings as today>, failed = false|true)` from the try/catch (catch keeps the existing WARN log and `"tool ${call.name} failed: ..."` text).
5. `upload_cv` branch: return `ToolOutcome("upload_cv", <same strings>, failed = ...)` from try/catch.
6. Loop body: replace the `else ->` branch with

```kotlin
is ToolOutcome -> {
    if (dispatched.failed && state.failingTool == dispatched.toolName) state.failureStreak += 1 else {
        state.failingTool = if (dispatched.failed) dispatched.toolName else null
        state.failureStreak = if (dispatched.failed) 1 else 0
    }
    state.messages += ChatMessage("user", dispatched.text.take(4000))
    if (state.failureStreak >= LOOP_BREAK_THRESHOLD) {
        return out(
            step,
            ApplyResult.Failed(
                "loop breaker: ${dispatched.toolName} failed $LOOP_BREAK_THRESHOLD " +
                    "consecutive times; last error: ${dispatched.text.take(150)}",
            ),
        )
    }
}
```

7. Class KDoc: extend the "Bounded by a hard step cap" sentence with one line: the loop breaker aborts after `LOOP_BREAK_THRESHOLD` consecutive failures of the same tool (2026-10-21: a dead listing and a refused upload each burned the whole cap).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd kotlin && ./gradlew :apply-worker:test --tests '*BrowserAgentTest*'`
Expected: ALL pass (4 new + 4 existing).

- [ ] **Step 5: Commit**

```bash
git add kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgent.kt \
        kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgentTest.kt
git commit -m "feat: abort apply loop after 3 consecutive failures of the same tool"
```

---

### Task 2: Raise the step cap 60 -> 80

**Files:**
- Modify: `kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/messaging/AgentLoopBounds.kt`
- Modify: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/WorkerLoop.kt:56` (comment only)
- Modify: `deploy/k8s/worker.yaml:71-74`
- Test: `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgentTest.kt` (the `parseMaxSteps` test only)

**Interfaces:**
- Consumes: `AgentLoopBounds` (single source of truth; `KafkaWiring.MAX_POLL_INTERVAL_MS` and `PollBudgetTest` derive from it and need NO edits).
- Produces: default cap 80 everywhere (code default, worker.yaml env).

- [ ] **Step 1: Update the failing test first**

In `BrowserAgentTest.kt`, rename `parseMaxSteps defaults to 60, clamps below one, and ignores garbage` to `parseMaxSteps defaults to 80, clamps below one, and ignores garbage`, and change the default assertions `60` -> `80` (null, "", "  ", "abc", and `" 60 "` -> `" 80 "` with `80`). Keep the clamp cases (`0`->1, `-5`->1, `999999`->10000) unchanged.

- [ ] **Step 2: Run to verify it fails**

Run: `cd kotlin && ./gradlew :apply-worker:test --tests '*BrowserAgentTest*'`
Expected: the renamed test FAILS (actual default is still 60).

- [ ] **Step 3: Change the constants and pinned comments**

1. `AgentLoopBounds.DEFAULT_MAX_STEPS = 80`; KDoc line becomes `/** 80 x (90s model + 120s Playwright) = 4.7h bounded worst case. */`
2. `WorkerLoop.kt:56`: comment text `default 60: 60 steps x` -> `default 80: 80 steps x`, and `= 3.5h` -> `= 4.7h` on the same line.
3. `deploy/k8s/worker.yaml` line 72-74: comment `60 = 3.5h bounded worst case` -> `80 = 4.7h bounded worst case`, `value: "60"` -> `value: "80"`.

- [ ] **Step 4: Run the affected suites**

Run: `cd kotlin && ./gradlew :apply-worker:test :common:test`
Expected: ALL pass, including `PollBudgetTest` (it derives from `EFFECTIVE_MAX_STEPS`; its 30-min-headroom and Int-range assertions hold at 80 by construction: 80 x 210s + 30min = 5.2h < Int.MAX ms).

- [ ] **Step 5: Commit**

```bash
git add kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/messaging/AgentLoopBounds.kt \
        kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/WorkerLoop.kt \
        kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgentTest.kt \
        deploy/k8s/worker.yaml
git commit -m "feat: raise WORKER_MAX_STEPS default to 80 (loop breaker bounds runaway attempts)"
```

---

### Task 3: Full build + fresh-context review

**Files:** none created; review covers the two commits above.

- [ ] **Step 1: Full build**

Run: `cd kotlin && ./gradlew build`
Expected: BUILD SUCCESSFUL (all modules, all tests).

- [ ] **Step 2: Fresh-context review subagent**

Dispatch the code-review agent on the diff of the two commits (base = `origin/main` before Task 1). Address every BLOCKER and SHOULD finding with the same TDD discipline; NICE findings at discretion.

- [ ] **Step 3: Push**

```bash
git push origin main
```

---

### Task 4: Deploy + production verification

**Files:** none modified; follows `deploy/docs/KOTLIN_DEPLOY.md` exactly.

- [x] **Step 1: Build and ship both images** (common module changed -> BOTH images)

Bundle -> scp -> ff-merge on the laptop repo -> build `Dockerfile.jvm-services --target finder-jvm` AND `Dockerfile.jvm --target worker` -> `k3s ctr images import` both -> `kubectl -n campaign rollout restart deployment/finder deployment/apply-worker` -> wait for both pods Running.

- [x] **Step 2: Verify the cap override landed**

`WORKER_MAX_STEPS=80` confirmed in the live pod (needed `kubectl apply -f deploy/k8s/worker.yaml`; a rollout restart alone does not apply manifest changes).

- [x] **Step 3: Verify the loop breaker live**

Image vintage proven at the jar level (ToolOutcome class present in the live worker.jar). First terminal outcomes under the new code: see ops log below.

- [x] **Step 4: Report to operator**: loop-breaker abort lines observed (or why none yet), queue drain rate, submitted counter movement.

## Post-deploy operations log (2026-10-21, ~17:20-18:10Z)

1. **Browser wedge recurred before the deploy** (17:20-17:23Z): every tool failed
   `async initializeServer: Timeout 30000ms exceeded` - same signature as the
   morning wedge (CDP answers, MCP target enumeration hangs). No recaptcha
   webworker this time; the suspects were the hubspot/adzuna OOPIF iframes on
   the leftover NFJ job page.
2. **Closing the last page kills Chrome.** `/json/close/<pageId>` on the only
   real page target terminated the whole browser (it came back at 17:24:17Z
   automatically; parent chain is the lubuntu-agent pod's `/entrypoint.sh`, so
   Chrome IS supervised - the runbook's "must relaunch manually" note is stale).
   Next time: open `about:blank` first, or close+accept the restart deliberately.
   The wedge died with the old instance; the in-flight attempt recovered
   mid-loop and went back to real work.
3. **Image tag trap (the big one).** I built and imported the worker image as
   `localhost:5000/campaign-apply-worker:v1` (misreading the runbook's
   `campaign-<name>:v1` placeholder), but the manifest runs
   `localhost:5000/campaign-worker:v1`. Two rollouts later the pod was still on
   the old image. Detection: compared the live jar's class list
   (`BrowserAgent$ToolOutcome` absent). Fix: `docker tag` the new image to
   `campaign-worker:v1`, re-import, restart. The actual image names are
   `campaign-finder:v1` and `campaign-worker:v1` - verify against the manifests,
   not the runbook placeholder. Env checks (WORKER_MAX_STEPS) do NOT prove
   image vintage; only a jar-level class check or a behavior signature does.
4. **Stale lease recurred on every pod kill** (2x during the deploy): a pod
   killed mid-apply leaves its `browser_leases` entry until TTL (25 min); the
   next pod parks in `acquire` wait (silent, no log lines) and grabs the lease
   seconds after the stale entry is deleted. Both times
   `deleteOne({_id:"chrome", owner: <dead-pod-owner>})` unblocked instantly.
   Follow-up candidate: release the lease on SIGTERM, or a startup sweep that
   deletes leases whose owner pod no longer exists.
5. **`k3s ctr` needs root** on the laptop (`permission denied` on the
   containerd socket as mstro); use `sudo k3s ctr images import -`.
6. Worker redeliveries of twice-killed attempts (Link Group page) grind on
   `applyButton does not match` clicks interleaved with successful snapshots:
   per design this does NOT trip the breaker (streak resets on the snapshot
   successes) and is bounded by the step cap instead.

## Post-deploy operations log (2026-10-22, ~09:00-12:40Z)

7. **14h zero-submission drought rooted in msrouter model rotation.** Last
   submission 07:15Z Oct 21; submitted pinned at 1990 for ~14h while the worker
   burned every queued job. Failure signature across all attempts: the model
   clicked hallucinated element names (`"applyButton" does not match any
   elements`) and stale snapshot refs (`aria-ref=f8e718` resolving to a
   posting-arrows widget). msrouter's `servedBy` over the outage window showed
   `cline/nvidia/nemotron-3-ultra-550b-a55b:free` serving 2245 requests vs
   poolside's 810 (last poolside service 16:39Z Oct 21): poolside
   (`laguna-s-2.1:free`, the model that produced all 1990 submissions)
   rate-limits under sustained campaign load, and the chain walks down
   `CLINE_MODELS` to nemotron-ultra, which cannot drive the browser flow.
   Simple single-turn tool-call probes do NOT discriminate (both models 5/5);
   the live failure is multi-turn ref discipline over long contexts.
8. **Fix: model list surgery in `campaign-data/msrouter.env`** (laptop, secret
   `campaign-msrouter`, no code change): `CLINE_MODEL` stays
   `poolside/laguna-s-2.1:free`; `CLINE_MODELS` replaced with
   `minimax/minimax-m3.1-flash:free,thinkingmachines/inkling:free,google/gemma-4-31b-it:free,recursive/flint-v1.0:free`
   (drops all nemotron variants plus the tiny dots/cohere/liquid entries);
   nemotron-3-ultra removed from the head of `UNOROUTER_MODELS` too (glm
   family now first). Backup at `msrouter.env.bak-20261022`. Recreated the
   secret (`--from-env-file` + `kubectl apply`) and rolled msrouter (12:20Z).
   Backups are unproven; watch servedBy + submissions and prune any backup
   that babbles.
9. **Browser wedge recurred (3rd time) and broke the whole finder window**
   (12:24-12:29Z Oct 22): every collector call failed the signature
   `initializeServer: Timeout 30000ms exceeded` while CDP answered
   `/json/version`; 8 stale job pages from Oct 21 attempts were still open
   (OOPIF suspects). Lease table was empty, so nothing parked.
   **Restart recipe that worked:** the Chrome supervisor pod is
   `lubuntu-agent` in namespace **default** (not campaign; `kubectl -n campaign
   get pods` hides it). `kubectl -n default delete pod <lubuntu-agent-pod>` ->
   fresh Chrome in ~25s with one clean `about:blank`. Verify by page count
   before the next window.

## Post-deploy operations log (2026-10-22 afternoon, ~13:20-16:30Z)

10. **The chain head whack-a-mole.** With poolside rate-limited, the routing
    chain's healthy head serves the campaign - first `openrouter/free`
    (a per-turn alias that resolves to a RANDOM upstream free model; keys 1-4
    served the whole grind), and after dropping that, `opencodego`
    (beam-free/longcat). Every provider ahead of `cline` serves unproven
    models with new failure signatures (`model produced no tool call`,
    `Ref f19e110 not found`, broken `browser_evaluate` JS). Env-only fix:
    emptied every pre-cline model id (ZAI/TOKENROUTER/OPENCODEGO/GROQ/
    UNOROUTER/GEMINI/OPENROUTER MODEL lines; API keys untouched) so the
    campaign pod's chain is cline-only: poolside + 4 backups. When all are
    rate-limited the request fails fast instead of burning 80 steps on
    roulette garbage - that preserves the attempt budget.
11. **The real drought mechanism: fill_form argument fidelity.** With
    poolside serving again (13:55Z+), attempts ground 30-80 steps but still
    produced 0 submissions; the dominant killer was
    `browser_fill_form: Invalid arguments` x3 -> loop breaker. Probes with
    the worker's exact text-JSON protocol showed the model emits `fields` as
    an OBJECT MAP `{label: value}` (poolside 6/6, flint 3/6) where
    playwright-mcp 0.0.83 requires an ARRAY of `{target,name,type,value}`
    objects with a required type enum, and omits `function` for
    browser_evaluate. Yesterday's successes (02:07Z, 07:15Z) ran the same
    toolset (01:49Z deploy), so today's poolside backend is degraded, not the
    toolset change. Fix: `ArgCoerce` (b5baba4) - deterministic pre-dispatch
    normalization (map -> typed array with snapshot-inferred types, type
    synonyms, value stringification, single-field wrap, evaluate aliases);
    schema-valid and uncoercible input pass through unchanged so failures
    stay loud. Reviewed (5 SHOULDs addressed), tested (10 unit + 1 agent
    level), deployed 15:00Z. Note: minimax/inkling/gemma-4-31b backups were
    all HTTP 500 dead upstreams during the afternoon probe window.
12. **Browser wedge recurred again (4th time in 36h) at 16:01-16:06Z** with 9
    stale tabs from the day's attempts: every collector failed
    `initializeServer: Timeout 30000ms` and the 16:0xZ windows collected
    seen=0. Same restart recipe (lubuntu-agent pod, default namespace) ->
    1 clean page. Durable fix candidate: close the attempt/collect page when
    done so stale tabs cannot accumulate between windows.

## Post-deploy operations log (2026-10-22 evening, ~16:45-19:40Z)

13. **First submission of the new build: 1991/2100.** inteca (Software
    Engineer Java/AI, NFJ) went SUBMITTED at 17:27:43Z - the FIRST attempt
    after the 17:14Z Chrome restart, on the ArgCoerce build deployed 15:00Z.
    The fill_form coercion held (zero fill_form zod errors all evening). The
    18:12Z window breakdown: 4 attempts died fast on the wedge (browser_
    navigate initializeServer timeouts x3 -> breaker), one 65-step attempt
    filled its form fully and died at upload_cv (`browser_file_upload can
    only be used when there is related modal state` - the click never armed
    a chooser), one Blocked (model skip_task). One residual zod error on
    browser_evaluate: the model emitted no recognized script key at all
    (nothing to coerce; left as is).
14. **Wedge root cause proven by direct probe.** From the worker pod,
    playwright's connectOverCDP to the NodePort hangs >15s whenever the
    browser carries the NFJ job-page OOPIF iframes (adzuna app_complete +
    hubspot chat); after a clean restart it connects in 172ms. Signature in
    MCP logs: `ws connected` then `async initializeServer: Timeout 30000ms`.
    The finder starved on the same thing (seen=0 in every window since
    ~16:00Z; 45 WARNs/2h). Also: closing a single page via
    `/json/close/<id>` crashed Chrome entirely (0 processes, CDP refused,
    ~17:12Z) - do not use it; recovery is the pod restart.
15. **Session restore is a wedge amplifier, and a kill-loop quoting trap.**
    A crash-exit Chrome restores the poisoned tabs on relaunch: wiping
    `Default/Sessions*` must happen AFTER the kill and BEFORE the watchdog
    relaunch (watchdog restarts Chrome within seconds). Second trap: the
    /proc kill loop's grep pattern (`^[0-9]+$.`) got mangled through the
    ssh/kubectl/sh -c quoting layers and matched NOTHING, so Chrome never
    died and the "restored" 3 pages were the same zombie browser. Working
    recipe: `kill -9 $(pgrep -f "google-chrom[e]")` (bracket trick so the
    probing shell cannot self-match), then rm the session dirs, then let
    the watchdog or a manual start bring Chrome up.
16. **Flock stall (runbook failure mode 2) recurred at 19:14Z.** Watchdog
    logged `restarting user stack` every 60s while /tmp/user-stack.log
    never moved - pod-user-start.sh exits on the stale flock. Recovery per
    runbook: rm /tmp/user-stack.lock, then manual start
    (`su agent -c 'DISPLAY=:99 /home/agent/pod-user-start.sh ...'`); Chrome
    up in ~12s with 1 clean page. The bounded-wait flock fix is still open
    for owner sign-off, as is the memory-limit raise (3Gi -> 5-6Gi).
17. **Durable fix shipped: tab hygiene at unit end (fea028b).** Worker
    BrowserAgent blanks its tab (navigate about:blank, 5s bound, best
    effort) in the per-task MCP client close() - covers every exit path
    including recorder exceptions; finder McpListingCollector does the same
    (10s bound) in a finally around the stacks loop. Job-page iframes die
    with the page while Chrome stays up, breaking the poison-next-spawn
    cycle; session restore also becomes harmless (clean exit, no tabs).
    Plus CvUploader re-arms the chooser once (re-click + upload) when a
    selector was given and the first upload finds no modal state - rescues
    the 65-step form-filled-then-died class. Reviewed fresh-context (4
    SHOULDs addressed: cleanup on exception paths, retry-branch tests,
    step-budget note, warn-level cleanup failures); tests: 20 worker + 6
    collector. Deployed to both apply-worker and finder after this entry.
18. **upload_cv double-failure fixed: CDP unique-input scan + error-visibility
    caps (2026-10-22 evening).** Five attempts died at upload_cv (media-adtech
    x2 via redelivery, darize, upvanta x2): the model calls upload_cv without
    selector and without arming a chooser (refusal: "no related modal
    state"), and the CDP fallback refused too - live inspection proved the
    erecruiter input[name=cvFiles] is a plain visible main-frame input, so
    the failure was matchPageTarget getting a pageUrl whose tab had since
    navigated (NFJ job page -> form.erecruiter.pl). Debuggability bug on top:
    take(100)/take(150)/take(300) cut every error exactly before the real
    detail. Fix (after fresh-context review, 2 BLOCKER + 4 SHOULD addressed):
    CdpFileUploader scans the pages sharing the evaluated url's HOST for the
    cssInput when the URL match fails and proceeds only on a UNIQUE owner
    (no host, no same-host page, 0 or 2+ owners refuse - uniqueness alone is
    not tab identity; probe failures are counted into the detail instead of
    reading as "input absent"; probes ride a 4s deadline; the shared
    HttpClient backs the ws sessions); caps raised (chooser errors 250, join
    550, final message 550/400, dispatch wrapper 800, loop breaker 1200);
    prompt says pass the upload control's ref as selector and never click it
    first (review B1: a model click arms the chooser and playwright-mcp then
    refuses upload_cv's own click - CvUploader now falls through to the
    upload on that refusal). TokenRouter research:
    live catalog = exactly z-ai/glm-5.3-free (no backend channel, dead id);
    the email-advertised MiniMax-M3.1-Flash is gated for our token ("token
    has no access" - needs a fresh token on tokenrouter.com); laptop
    msrouter.env now sets the glm id (parked 404, self-heals), secret
    rebuilt, msrouter restarted 21:50Z. Freeze flags staged in
    pod-user-start.sh (backgrounding-occluded/renderer-backgrounding/
    timer-throttling/MemorySaver) for the next Chrome restart - the wedge
    escalated to 3-step navigate deaths (iteamly 21:56Z) while this entry
    was written.

### Late-night addendum (2026-10-22, ~22:40-23:45Z)

- Deploy window 22:47:57Z (lease-free gate held): Chrome killed + session
  wipe + worker rollout together. Chrome came back ~40s later WITH the
  freeze flags (verified in /proc/<pid>/cmdline: all four + blocklist);
  the tab hygiene left the browser at 1 page / 5 targets.
- IMAGE-GC TRAP: the rollout stuck in ImagePullBackOff - kubelet image GC
  (disk >85%) had evicted the fresh `k3s ctr` import within minutes and
  even the old image; kubelet then tried to PULL localhost:5000 (no
  registry). `docker builder prune -af` reclaimed 62.5GB (86% -> 61%),
  re-import, crictl verified, delete-pod -> rolled out 22:55Z. Recorded in
  KOTLIN_DEPLOY.md.
- First attempt on the new build (matrix-global-services NFJ, 22:55-23:05,
  40 steps): the PROMPT FIX WORKS - the model passed `#file` as selector
  (no more selector-less calls). New failure exposed by the new caps: the
  pre-click on #file TIMED OUT (locator resolved, click never completed -
  hidden input[type=file] behind a styled dropzone), and the early-return
  "could not open the file chooser" preempted the CDP fallback 3x (loop
  breaker). But setFileInputFiles is precisely the tool for hidden inputs.
  Fix (e9e8f78c, TDD, RED reproduced the production text verbatim): any
  pre-click failure is recorded and falls through - upload (refused, no
  chooser) -> CDP fallback with the host-correlated scan. No re-click
  after a failed click. Rolled 23:20:57Z (worker only; Chrome stays).
- Idle-not-broken: after a worker restart, Kafka retry timers die with
  the pod and recovery relies on finder re-discovery (known gap, runbook
  hardening). The worker sat silent 23:21-23:45 while the finder held the
  browser 23:23-~23:31; msrouter request log confirmed zero LLM calls in
  that stretch (no hidden attempt). Lease heartbeat (expiresAt advancing)
  is the cheap "attempt in progress" probe; jstack via kill -3 works when
  the log is quiet.
- Laptop clone note: its git origin is the LOCAL bundle file
  (campaign-data/kotlin-dist.bundle), so sync = scp bundle + cp over that
  path + `git pull origin main`; pulling the fresh /tmp bundle directly
  also works.

### Round-2 addendum (2026-10-23, ~00:00-02:00Z)

- WEDGE RECURRENCE: ~75 min after the 22:47 restart the browser wedged
  again (initializeServer Timeout 30000ms on the finder's 23:23 window;
  an orphaned adzuna app_complete OOPIF target sat in /json/list). The
  freeze flags did NOT prevent it; pod memory was 191Mi (no pressure).
  Kill+wipe at 00:00:24Z recovered in ~30s. The flags help throttling,
  not whatever breaks MCP session init - the runbook recovery stays the
  operative fix; root cause still open.
- FENCE BUG (f3ebae8d): the CDP fallback's activeUrl took the LAST
  non-blank line of browser_evaluate output - playwright-mcp fences that
  output, so activeUrl was the closing ``` and every fallback died with
  "no page target for ```" until the host-correlation caps surfaced it.
  Fix: last line containing "://", trimmed of quotes/backticks.
- MARKER IDENTITY (6c1f7953): with the url parsed, the darize job still
  refused - "input found on 2 pages": a STALE erecruiter form tab plus
  the current one share the same url, defeating url match AND the
  unique-owner scan (uploading into the stale tab would silently lose
  the CV). Fix: upload_cv injects window.__cvUploadMarker=cv-<uuid> via
  the same evaluate, and CdpFileUploader probes each page's session for
  it (marker holder = the MCP-driven tab; url/scan stay as fallback).
  Also: CDP fallback outcome now INFO-logs (a silent success was
  indistinguishable from a wedged fallback).
- THROUGHPUT CEILING: the free LLM chain (z-ai/glm-5.3-free via
  TokenRouter) is heavily rate-limited - LlmClient retry sleeps of 10-30s
  per step, msrouter log full of 429s; one erecruiter attempt = 30-80
  steps = 20-40 min, and the free model still flails on complex forms
  (hidden labels, malformed fill_form JSON - the loop breaker catches
  that correctly). Upload chain verified live stage by stage; the
  binding constraint is now the model, not the worker. Unblocking
  MiniMax-M3.1-Flash (fresh token on tokenrouter.com) is the highest-
  leverage next step; stale same-form tabs are the next worker-side gap
  worth a look (hygiene could close same-url tabs older than the active
  unit at unit end).

### Round-3 addendum (2026-10-23, ~03:00-04:30Z)

- MODEL MAP (asked and measured): the worker's LLM chain is the POD msrouter
  (campaign ns): cline poolside/laguna-s-2.1:free primary, then
  thinkingmachines/inkling:free, recursive/flint-v1.0:free, gemma-4, plus
  cloudflare/gemini/groq/mistral/unorouter CSVs. zai/openrouter/tokenrouter
  are EMPTY in the pod - they only exist in the Mac's local msrouter (which
  serves local tools, not the campaign). 6h servedBy: poolside 673, inkling
  401, flint 69 - the "429-heavy glm" note in round-2 was the Mac chain, not
  the pod's.
- FILE ROOTS ROOT CAUSE (upload chain dead by config since the k3s
  migration): the new outcome log paid off on its first live attempts
  (upvanta x3). (1) browser_file_upload refused the CV outright: "File
  access denied: /cv/... is outside allowed roots. Allowed roots:
  /app/.playwright-mcp, /app" - playwright-mcp validates upload paths
  against its cwd (/app) + output dir, so the chooser path could NEVER
  accept /cv. (2) The CDP fallback was equally dead: DOM.setFileInputFiles
  resolves the path in CHROME's pod (lubuntu-agent), where /cv never
  existed. Fix = path alignment: the cv hostPath mounts at /app/cv in the
  worker (inside the allowed root), CV_PATH_PL=/app/cv/..., and the
  lubuntu-agent deployment gets the SAME hostPath at /app/cv so Chrome
  resolves the identical string. Lubuntu spec lives outside the repo -
  applied via kubectl patch; the invariant is documented here and in
  KOTLIN_DEPLOY.md.
- EVALUATE NULL VISIBILITY: the same upvanta attempts failed the evaluate
  step with NO trace - browser_evaluate had answered with an error TEXT
  (no exception), the url parse silently yielded null ("no evaluated url"
  three times, no reason logged). Fix: a null-url parse now warn-logs the
  raw evaluate output (or the throw), TDD'd with a logback ListAppender.
  Also un-orphaned the fall-through doc comment in CvUploaderTest (it had
  drifted above the fence test).

### Round-4 addendum (2026-10-23, ~10:30-11:15Z): worker chain widened with zai + opencodego

- CORRECTION to the round-3 model map: zai/openrouter/tokenrouter are NOT
  Mac-only. The POD has ZAI_API_KEY / OPENCODEGO_API_KEY / TOKENROUTER_API_KEY
  (they ride in secret campaign-msrouter, built with --from-env-file from
  laptop $DATA/msrouter.env - the image ships no env). Their MODEL slots were
  simply empty, which is why 72h of servedBy was 100% cline (1482 calls, zero
  fall-throughs).
- LIVE PROBE MATRIX (run inside the pod, node fetch, keys never left it):
  zai glm-5.3-flash HTTP 200; zai glm-4.7-flash transient 429 (code 1305).
  opencodego beam-free HTTP 200 - but ONLY with an x-opencode-session header
  (bare calls get 400 MissingSessionID; msrouter auto-generates the header,
  see src/providers/instances.ts:84, so bare probes are a false alarm).
  longcat-2.5-preview-free upstream 429/down; opencodego glm-5.3-flash
  GoUsageLimitError monthly quota exhausted. tokenrouter key valid and
  z-ai/glm-5.3-free listed, but completions 503 "No available channel under
  group default" - still gated on the tokenrouter.com dashboard.
- CHANGE (config only, no code, no image rebuild): laptop
  /home/mstro/k3s-pod-data/agent-home/campaign-data/msrouter.env now sets
  ZAI_MODEL=glm-5.3-flash, ZAI_MODEL_2=glm-4.7-flash,
  OPENCODEGO_MODEL=beam-free; secret rebuilt (apply from env-file, then a
  base64 patch for the gateway-token literal - this kubectl rejects
  combining --from-env-file with --from-literal, so bootstrap line 84's
  combined form would fail as written); msrouter rolled. Startup line now
  reports zai:true, opencodego:true, tokenrouter:true.
- SEMANTICS: these join the fallback TAIL after cline's own backups exhaust -
  they absorb 429 storms, they do not preempt poolside. A dead model id costs
  one failed call then parks 24h (self-heals), which is why longcat stays out
  of the slots for now.



## Final Review (after implementation)

- [x] Do not claim done until: full `./gradlew build` green, review findings addressed, pushed, deployed, and a `loop breaker` line observed in production logs (or an explicit note why none fired yet).

**Production proof (18:20:39Z):**

```
job nofluffjobs:full-stack-engineer-python-react-azure-1dea-remote: Failed after 33 steps
job ... failed: loop breaker: browser_click failed 3 consecutive times; last error: tool browser_click failed: ... TimeoutError ...
```

The breaker ended a mid-apply grind 47 steps short of the cap. First cap
observation under the new code: the Limur junior job ran `Failed after 80
steps` (18:17:59Z) - the raised cap live; its click failures were interleaved
with successful snapshots, so per design the streak kept resetting and the cap
was the correct backstop. Both terminal paths behaved as specified.

## Round 5 addendum (2026-10-23): apply cap-outs - 5s tool timeouts + reset semantics

Evidence (worker logs, pod started 08:26Z): 4x `Failed after 80 steps`
(iteamly x2, scalo-2, braver-it) plus submits that needed 63/78 steps. Live
signatures: `TimeoutError: browserBackend.callTool: Timeout 5000ms exceeded`
on browser_click (11:03/11:09/11:21Z) and browser_wait_for (`waiting for
getByText('dziękujemy')`). playwright-mcp 0.0.83 in-image documents the
defaults in config.d.ts/README: `timeouts.action` **5000ms**,
`timeouts.expect` **5000ms**; `--help` exposes `--timeout-action` but NO
expect flag, so the config file route is the only way to raise expect.
Root-cause chain: a click that needs >5s of actionability (or a confirmation
text that takes >5s to render) fails -> the model snapshots between retries ->
per the round-1 design ANY success (incl. a pure snapshot read) resets the
failure streak -> the loop breaker never fires -> the unit grinds all 80
steps and dies at the cap with the form fully filled ("reaches all steps,
times out"). Secondary step-burn: ArgCoerce pass-through survivors
(envelope-shaped `{name,arguments}` -> "expected string, received undefined
-> at url/function"; statement bodies in evaluate -> `SyntaxError:
Unexpected token 'const'`). Observability gap: no per-step logging, so the
silent 9-minute tail of a cap-out is unreconstructable.

Changes:
1. Config: `{"timeouts":{"action":30000,"expect":15000}}` written INLINE in
   the Dockerfile.jvm worker stage as /app/pw-mcp.json (the build context is
   kotlin/, so deploy/docker files cannot be COPYed) and WORKER_MCP_COMMAND
   gains `--config /app/pw-mcp.json`; worker.yaml mirrors the command.
   Clicks get the standard playwright 30s; wait_for confirmations 15s.
   Deploy order matters: import the image BEFORE applying the manifest - a
   new WORKER_MCP_COMMAND against the old image fails every MCP spawn.
2. ArgCoerce (TDD): envelope unwrap + double-encoded JSON parse (gated to
   browser tools inside normalize itself; ledger tools keep raw args by
   design), navigate url recovery (href/link/location aliases, bare-string
   url), evaluate keyword/await-led statement bodies wrapped
   `(async () => { ... })()`. The predicate is deliberately keyword-led only:
   a `;` inside a string literal (`labels.join(';')`) is a VALID expression
   under eval("(" + t + ")") and must stay untouched - wrapping it would
   silently return undefined (review SHOULD-1).
3. Loop breaker (TDD): the stuck tool's OWN success is the only reset. A
   same-tool failure increments the streak, a different-tool failure
   restarts it at 1, and any OTHER success (snapshot reads, no-op
   evaluate/wait_for) leaves it alone. This generalizes the round-1..4
   any-success-reset rule away entirely: interleaved loops of ANY flavor can
   now trip the breaker. Revisits the round-1 "cap as backstop" tradeoff:
   4 cap-outs/day at ~15-20 min each is too expensive for the rare
   premature-break risk (failed attempts retry up to maxAttempts anyway).
4. Per-step INFO log: `step n/max tool ok|failed (ms)` - the next cap-out
   must be reconstructable from logs alone.
5. TOOL_SCHEMAS descriptions: evaluate says "expression or arrow function,
   no top-level statements"; fill_form says "fields is an ARRAY".
Deploy: standard worker flow (build --target worker, ctr import, lease-gated
rollout). Acceptance: no new `Timeout 5000ms` lines, step logs visible,
cap-out rate drops; live probe on the laptop validates the config route
before the rebuild.

## Round 5 deployment + validation (2026-10-23 ~17:00Z)

- Pushed 10324db, bundle-synced, built on the laptop
  (campaign-worker:v1 = cfd87b675b97d), imported, manifest applied in a
  lease-free window 16:02:17Z, rolled out clean.
- Live A/B probe (worker pod, fresh browser, absent-text `browser_wait_for`):
  plain = failed at 5002 ms (`Timeout 5000ms exceeded`); with
  `--config pw-mcp.json` = failed at 30003 ms. The config file is honored.
  Mapping correction: `browser_wait_for` is bounded by the ACTION timeout
  (30 s), not the expect timeout - so item 1's "wait_for confirmations 15 s"
  is wrong in detail; waits get the 30 s action bound. The 5 s cliff is gone
  either way.
- First unit under the new code: iteamly (the 2x cap-out offender) ran with
  full step telemetry; step 10 click failed at 30061 ms - under the old
  defaults that exact slow click was a 5000 ms cliff feeding the grind.
- The laptop probe twice wedged on `initializeServer: Timeout 30000ms
  exceeded` (ws connected, init stalled) - the browser-side OOPIF-orphan
  wedge, NOT related to this change; cleared 16:29:18Z with the ops-doc
  full user-stack wipe (watchdog rebuilt in ~75 s). During the wedge the
  finder's collectors failed the same way, then recovered on the fresh
  browser (100 NFJ + 265 theprotocol listings at 16:44-16:46).
- Blocker sweep per ops failure mode 7: blockers collection had no
  unresolved rows - the false-blocked jobs were already eligible.

