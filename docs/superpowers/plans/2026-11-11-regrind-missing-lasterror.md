# Regrind Missing-lastError Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FAILED rows whose `lastError` field is absent visible to the re-grind pass again, without unblocking permanent `blocked:` rows.

**Architecture:** The re-grind FAILED branch uses `Criteria.where("lastError").regex("^(?!blocked:)")`. MongoDB regex operators never match a missing field, so any FAILED row with no `lastError` at all is invisible to both the candidate query and the claim. Replace the negative-lookahead regex with `$not` plus a `^blocked:` regex in both places. `$not` matches documents where the field is absent and still excludes `blocked:`-prefixed rows.

**Tech Stack:** Kotlin, Spring Data MongoDB `Criteria`, JUnit 5, AssertJ, MockK, Testcontainers (`@DataMongoTest`), Gradle.

**Spec:** This plan implements finding 8 from the 2026-11-11 campaign monitor. Production evidence: 8 rows stamped `2026-11-06T00:19Z`, status FAILED, attempts 1 to 2, `lastError` absent. `countDocuments({status:"FAILED", lastError:{$exists:false}})` returns 8 while the exact production criteria match none of them.

## Global Constraints

- No em dashes (U+2014) in code comments, commits, or prose.
- Commit directly to `main` (repo convention), area-prefixed lowercase subject line, e.g. `common: ...`.
- Full `./gradlew build` must be green before commit.
- Push to `origin/main` and watch `kotlin-ci` to success (rule 94).
- Testcontainers ITs cannot run locally (no Docker on this machine). They run in CI. Local RED/GREEN comes from the query-contract unit test plus a live read-only Mongo proof.
- Deploy the finder only at an idle boundary (browser lease empty, no discovery window running), with `./gradlew :finder-service:bootJar`, then verify `BOOT-INF/lib/common.jar` bytes.
- Temp scripts live in `/tmp`, never in the repo.
- No VM mutation without explicit user approval.

## Review Focus

- A FAILED row with no `lastError` field is now selected and claimable (the bug).
- A FAILED row with `lastError: "blocked: ..."` stays excluded from both query and claim.
- A FAILED row with an ordinary `lastError` still behaves exactly as before.
- DEAD model-flake and IN_FLIGHT branches are untouched (no accidental widening).
- Claim path stays byte-for-byte consistent with the candidate query (defense in depth).

---

### Task 1: Pin the emitted query contract, then fix the FAILED branch

**Files:**
- Create: `kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryRegrindQueryTest.kt`
- Modify: `kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryImpl.kt:116-120` (candidate query)
- Modify: `kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryImpl.kt:180-183` (claim)

**Interfaces:**
- Consumes: `JobStateRepositoryImpl(mongo: MongoTemplate)`, `findRegrindCandidates(maxAttemptsParam: Int, olderThan: Instant, strandedAfter: Instant, limit: Int)`, `regrindClaim(id: String, maxAttempts: Int, olderThan: Instant, strandedAfter: Instant): Boolean`
- Produces: nothing new. Signatures unchanged.

- [ ] **Step 1: Write the failing unit test**

`JobStateRepositoryRegrindQueryTest.kt`, plain JUnit 5 with MockK, no Spring context:

```kotlin
package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStateDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant

class JobStateRepositoryRegrindQueryTest {

    private val mongo = mockk<MongoTemplate>()
    private val repo = JobStateRepositoryImpl(mongo)

    private fun capturedQuery(build: (JobStateRepositoryImpl) -> Unit): Document {
        val query = slot<Query>()
        every { mongo.find(capture(query), JobStateDocument::class.java) } returns emptyList()
        build(repo)
        return query.captured.queryObject
    }

    @Test
    fun `failed branch excludes blocked rows with not-regex so a missing lastError still matches`() {
        val doc = capturedQuery {
            it.findRegrindCandidates(3, Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z"), 10)
        }
        val failed = (doc["$or"] as List<*>).map { it as Document }.first { it["status"] == "FAILED" }
        // Mongo regex operators never match a missing field. $not does, so a
        // FAILED row with no lastError at all stays a candidate, while
        // "blocked:"-prefixed rows are still excluded.
        assertThat(failed["lastError"]).isEqualTo(Document("\$not", Document("\$regex", "^blocked:")))
    }

    @Test
    fun `claim mirrors the candidate guard for failed rows`() {
        val query = slot<Query>()
        every { mongo.updateFirst(capture(query), any(), JobStateDocument::class.java) } returns
            org.springframework.data.mongodb.core.query.UpdateResult.acknowledged(1, 1, null)
        repo.regrindClaim("justjoin:x", 3, Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z"))
        val branches = (query.captured.queryObject["\$or"] as List<*>).map { it as Document }
        val failed = branches.first { (it["\$and"] as List<*>).any { b -> (b as Document)["status"] == "FAILED" } }
        val lastError = (failed["\$and"] as List<*>).map { it as Document }
            .first { it.containsKey("lastError") }["lastError"]
        assertThat(lastError).isEqualTo(Document("\$not", Document("\$regex", "^blocked:")))
    }
}
```

Note on the second test: `regrindClaim` builds `_id` + `attempts` + `$or` of three `$and` branches. If the rendered shape differs from the assertion, read the actual `queryObject` in the failure output and adjust the traversal only, keeping the `$not` expectation unchanged.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew :common:test --tests "dev.mstaszew.campaign.common.repo.JobStateRepositoryRegrindQueryTest"`
Expected: FAIL. `failed["lastError"]` is `{"$regex": "^(?!blocked:)"}`, not `{"$not": {"$regex": "^blocked:"}}`. Both tests fail on the same missing `$not`.

- [ ] **Step 3: Fix the candidate query**

In `findRegrindCandidates`, replace the comment and criteria at lines 116-119 with:

```kotlin
            // "blocked:"-prefixed lastErrors are permanent portal blocks;
            // grinding those again burns an attempt for a known dead end.
            // `$not` with a regex, not a negative-lookahead regex: Mongo
            // regex operators never match a missing field, and 8 rows from
            // the Nov 6 00:19 bulk failure were left FAILED with no
            // lastError at all, invisible to this pass forever (2026-11-11).
            Criteria.where("lastError").not().regex("""^blocked:"""),
```

- [ ] **Step 4: Fix the claim**

In `regrindClaim`, replace `Criteria.where("lastError").regex("""^(?!blocked:)""")` (line 182) with `Criteria.where("lastError").not().regex("""^blocked:""")`. Keep the surrounding `andOperator` unchanged.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew :common:test --tests "dev.mstaszew.campaign.common.repo.JobStateRepositoryRegrindQueryTest"`
Expected: PASS, 2 tests, 0 skipped.

- [ ] **Step 6: Commit**

```bash
git add kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryRegrindQueryTest.kt \
        kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryImpl.kt
git commit -m "common: regrind FAILED rows that carry no lastError field"
```

---

### Task 2: Pin the behavior against real Mongo

**Files:**
- Modify: `kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryRegrindIT.kt` (append one test before `strandedBound()`)

**Interfaces:**
- Consumes: `failedRow(...)`, `mongo: MongoTemplate`, `rgrind: JobStateRepositoryImpl`, `strandedBound()` from the existing IT.
- Produces: nothing new.

- [ ] **Step 1: Write the failing IT**

```kotlin
    /**
     * 2026-11-11 production evidence: 8 rows left FAILED by the Nov 6 00:19
     * bulk failure carry no lastError field at all. Mongo's $regex never
     * matches a missing field, so the old negative-lookahead guard hid them
     * from this pass permanently. A blocked row must stay excluded.
     */
    @Test
    fun `failed rows with no lastError field are candidates and claimable, blocked rows are not`() {
        val cutoff = Instant.now().minus(java.time.Duration.ofHours(1))
        val strandedAfter = strandedBound()
        failedRow("justjoin:no-error", attempts = 1)
        mongo.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`("justjoin:no-error"),
            ),
            org.springframework.data.mongodb.core.query.Update().unset("lastError"),
            JobStateDocument::class.java,
        )
        failedRow("justjoin:blocked", attempts = 1, lastError = "blocked: portal-block")

        val candidates = rgrind.findRegrindCandidates(
            maxAttemptsParam = 3,
            olderThan = cutoff,
            strandedAfter = strandedAfter,
            limit = 10,
        )

        assertThat(candidates.map { it.id }).containsExactly("justjoin:no-error")
        assertThat(rgrind.regrindClaim("justjoin:no-error", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isTrue()
        assertThat(rgrind.regrindClaim("justjoin:blocked", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isFalse()
        mqttAssertStatus("justjoin:blocked", JobStatus.FAILED)
    }
```

- [ ] **Step 2: Run it locally and confirm the documented skip**

Run: `./gradlew :common:test --tests "dev.mstaszew.campaign.common.repo.JobStateRepositoryRegrindIT"`
Expected: SKIPPED (no Docker on this machine). Record this in the final report. The behavioral RED/GREEN for this test is observed in CI, which runs Testcontainers.

- [ ] **Step 3: Commit**

```bash
git add kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/repo/JobStateRepositoryRegrindIT.kt
git commit -m "common: pin regrind recovery of failed rows without lastError"
```

---

### Task 3: Prove it against production data (read-only)

**Files:** none (temporary scripts in `/tmp`).

- [ ] **Step 1: Count with the old guard and the new guard on the live VM**

Run a read-only `mongosh` comparison of `{lastError: {$regex: "^(?!blocked:)"}}` (expect 0 of the 8) against `{lastError: {$not: {$regex: "^blocked:"}}}` (expect 8). Both restricted to `status: FAILED`, `attempts < 3`, `updatedAt` older than 12h.

- [ ] **Step 2: Record the numbers in the final report**

---

### Task 4: Review, verify, push, watch CI

**Files:** none.

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, no new failures in any module.

- [ ] **Step 2: Fresh-context code review (rule 60)**

Dispatch a review subagent with the full diff and the Java/Spring checklist. Fix every BLOCKER and every SHOULD, or justify each SHOULD in writing.

- [ ] **Step 3: Push and watch CI**

```bash
git push origin main
```
Watch `kotlin-ci` for the pushed SHA until `conclusion=success`. The Testcontainers ITs run there, including Task 2's new test.

---

### Task 5: Deploy the finder at an idle boundary

**Files:** none.

- [ ] **Step 1: Build the fat jar**

Run: `./gradlew :finder-service:bootJar`. Confirm `finder-service/build/libs/finder-service.jar` mtime is new.

- [ ] **Step 2: Wait for idle**

Poll `db.browser_leases.countDocuments({})` until 0, and confirm no discovery window is running (no fresh `collected ... listings` line in the finder log). Do not restart mid-window.

- [ ] **Step 3: scp, verify, restart**

Back up the current jar, `scp -P 2222` the new jar, verify the full-jar SHA-256 with `openssl dgst -sha256` (sha256sum misbehaves on that VM), verify `BOOT-INF/lib/common.jar` contains the 4-arg `regrindClaim` signature, then `sudo systemctl start campaign-finder` (stop first if it is running). Leave the worker untouched.

- [ ] **Step 4: Confirm the fix lands**

Within 15 minutes the re-grind pass should log `re-grind pass: N candidate(s)` and claim up to `regrindBatchPerWindow` (4) of the 8 previously invisible rows. Confirm the FAILED count with a missing `lastError` drops.

---

### Task 6: Watchdog rewire (design plus approval gate, no mutation yet)

**Files:** none in the repo. Target is the VM unit `/opt/campaign/bin/chrome-probe.sh` and `campaign-chrome-watchdog.timer`.

- [ ] **Step 1: Capture the real Chrome command line and environment**

Read `/proc/4303/cmdline` and `/proc/4303/environ` (filtered to `DISPLAY`, `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, `DBUS_SESSION_BUS_ADDRESS`) plus the GNOME autostart entry. This is read-only.

- [ ] **Step 2: Draft the replacement failure path**

On WEDGED: log, kill the process listening on 9222, relaunch with the captured argv and env as user `ubuntu`, wait, then run the CDP probe once as a second chance. No `systemctl restart campaign-chrome`.

- [ ] **Step 3: Stop and ask for explicit approval**

Present the exact script diff and the relaunch command line. Do not install or restart anything without a clear go.

---

## Self-Review

1. **Spec coverage:** finding 8 (missing `lastError` invisibility) is covered by Task 1 (contract), Task 2 (behavior), Task 3 (production proof), Task 5 (deploy). Watchdog decision covered by Task 6. Mac-side watchdog is explicitly out of scope per the user.
2. **Step scan:** every step names a file, a command, or an exact assertion. No TBD.
3. **Type consistency:** `findRegrindCandidates` and `regrindClaim` signatures are unchanged across tasks; the IT uses the same 4-arg calls already in the file.
4. **Review Focus:** each of the five lines has a test or assertion in Task 1 or Task 2, except "DEAD and IN_FLIGHT untouched", which the existing IT tests (`dead model-flake rows...`, `stranded in-flight rows...`) already pin and which Task 4's full build re-runs.
5. **Proportion:** three small code edits, two tests, one deploy, one approval gate. No task is a transcript of the program.
