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
