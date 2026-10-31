# Wander Breaker Trip-2 Immediate Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After the first wander-trip corrective shove is ignored (model makes another read-only call instead of acting), trip the wander breaker immediately instead of granting another full 20-read grace period. This prevents the model from burning the step cap in read-only loops after being explicitly told to act.

**Architecture:** Single-file change to `BrowserAgent.kt`. Add a `wanderCorrectionPending` flag to `LoopState`. After trip 1 (correction injected), set the flag. On the next read-only call while the flag is set, trip to `Failed` immediately. On any action call while the flag is set, clear the flag and resume normal wander logic. Trip 1 threshold stays at WANDER_THRESHOLD (20).

**Tech Stack:** Kotlin, JUnit 5, mockk, Gradle, Docker, k3s (travelmate)

**Spec:** No separate design doc — the fix is a one-file behavioral change in `BrowserAgent.kt` wander-breaker logic, constrained by existing tests in `BrowserAgentTest.kt`.

## Global Constraints

- `WANDER_THRESHOLD = 20` unchanged for trip 1
- `isReadOnlyCall(call, tightenAfterWanderTrip=true)` — after trip 1, evaluate and ALL tab calls count as read-only
- Trip 1 must still inject `WANDER_CORRECTION` and reset readStreak to 0
- Existing tests must be updated to match new trip-2-immediate behavior
- New tests must be written in TDD order (fail first, then implement)
- Image tag bumped to v8 on deploy

## Review Focus

1. **Correction ignored → immediate trip:** After WANDER_CORRECTION is sent, if the model's next tool call is read-only (snapshot/evaluate/tabs/wait_for), the attempt fails as wander breaker immediately — no second 20-read grace period.
2. **Correction heeded → normal resume:** After WANDER_CORRECTION, if the model's next tool call is an action (click/fill/upload/navigate), `wanderCorrectionPending` clears and normal 20-read threshold applies for any subsequent wander.
3. **Trip 1 still works:** 20 consecutive read-only calls (first ever) injects correction, does NOT fail — the model gets one warning.
4. **Detail text:** New trip-2 detail starts with "wander breaker:" so existing assertions on that prefix still match where appropriate.

---

### Task 1: Write failing TDD tests for trip-2-immediate behavior

**Files:**
- Test: `kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgentTest.kt`

**Interfaces:**
- Consumes: `BrowserAgent`, `ScriptedTransport`, `toolCallResponse`, `run`, `ApplyResult.Failed`
- Produces: new `@Test` methods in `BrowserAgentTest`

- [ ] **Step 1: Write test `wander trip 2 fires immediately after correction is ignored`**

```kotlin
@Test
fun `wander trip 2 fires immediately after correction is ignored`() {
    // 20 reads -> trip 1 (correction injected)
    // next call: read-only again -> trip 2 immediately (not another 20 reads)
    val transports = buildList {
        repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
        add(toolCallResponse("browser_snapshot", "{}")) // ignores correction
    }
    val result = run(agent(ScriptedTransport(transports), maxSteps = 150))
    assertThat((result as ApplyResult.Failed).detail)
        .startsWith("wander breaker:")
    val allBodies = transports.first().bodies.joinToString("\n")
    assertThat(allBodies).contains("read-only calls without acting") // correction was sent
    assertThat(allBodies).contains("ignored") // trip 2 detail mentions correction ignored
}
```

- [ ] **Step 2: Run test to verify it fails** — expect FAIL (no `wanderCorrectionPending` logic, trip 2 needs 20 reads)

- [ ] **Step 3: Write test `wander correction heeded resumes normal threshold`**

```kotlin
@Test
fun `wander correction heeded resumes normal wander threshold`() {
    // 20 reads -> trip 1 (correction)
    // action -> correction cleared
    // 20 more reads -> trip 2 (normal threshold, not immediate)
    val transports = buildList {
        repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
        add(toolCallResponse("browser_click", """{"ref":"r1"}"""))
        repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
    }
    val result = run(agent(ScriptedTransport(transports), maxSteps = 150))
    assertThat((result as ApplyResult.Failed).detail)
        .startsWith("wander breaker:")
}
```

- [ ] **Step 4: Run test to verify it fails** — expect FAIL

- [ ] **Step 5: Commit failing tests**

```bash
git add kotlin/apply-worker/src/test/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgentTest.kt
git commit -m "test: TDD failing tests for wander trip-2-immediate behavior"
```

---

### Task 2: Implement wanderCorrectionPending flag in BrowserAgent

**Files:**
- Modify: `kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgent.kt`

**Interfaces:**
- Consumes: `LoopState` (adds `wanderCorrectionPending: Boolean`), `WANDER_CORRECTION`, `WANDER_THRESHOLD`, `isReadOnlyCall`, `ApplyResult.Failed`
- Produces: modified wander-breaker block in `BrowserAgent.run()`

- [ ] **Step 1: Add `wanderCorrectionPending` field to `LoopState`** — default `false`

- [ ] **Step 2: Set `wanderCorrectionPending = true` on trip 1** alongside `wanderTrips = 1` and `readStreak = 0`

- [ ] **Step 3: Clear `wanderCorrectionPending = false` when an action resets readStreak** (in the `else` branch of readStreak reset)

- [ ] **Step 4: Add immediate trip-2 check** — after readStreak increment/reset and before threshold check, if `wanderCorrectionPending && isReadOnlyCall(call, true)`, return `Failed("wander breaker: ignored corrective shove, read-only call after correction")`

- [ ] **Step 5: Update existing test `first wander trip corrects the model, second trip fails`** — after trip 1 correction, the next read-only call trips immediately (not after another 20 reads). Update the transport to have 1 read after correction, and update assertion to match new detail text.

- [ ] **Step 6: Run all BrowserAgentTest tests** — expect all GREEN

- [ ] **Step 7: Commit implementation**

```bash
git add kotlin/apply-worker/src/main/kotlin/dev/mstaszew/campaign/worker/apply/BrowserAgent.kt
git commit -m "fix: wander trip-2 trips immediately after correction is ignored"
```

---

### Task 3: Code review via subagent

**Files:**
- Modified: `BrowserAgent.kt`, `BrowserAgentTest.kt`

- [ ] **Step 1: Dispatch code-review subagent** on the changed files with focus on wander-breaker logic, test coverage, and no regressions
- [ ] **Step 2: Address review findings** — implement any required changes
- [ ] **Step 3: Commit review fixes**

---

### Task 4: Build, deploy v8, verify on travelmate

**Files:**
- Modified: `deploy/k8s/worker.yaml` (image tag v6 → v8)

- [ ] **Step 1: Build v8 image** on travelmate: `docker build --target worker -t localhost:5000/campaign-worker:v8 kotlin/`
- [ ] **Step 2: Import to containerd** and `kubectl set image` + `kubectl apply -f worker.yaml`
- [ ] **Step 3: Verify pod running** with `WORKER_MAX_STEPS=200` and image v8
- [ ] **Step 4: Watch one job** — confirm wander trip-2 behavior in logs (or just confirm no cap-out at 150 for a read-heavy job)
- [ ] **Step 5: Commit worker.yaml image tag** and push to `main`

---

**Execution recommendation:** Native — this is a single-file change with tightly coupled tests; a subagent would need full context on the wander logic anyway. One reviewer pass at the end via code-review subagent.

Does this plan capture what you want? I can implement it natively in this session, then dispatch a code-review subagent before deploying.