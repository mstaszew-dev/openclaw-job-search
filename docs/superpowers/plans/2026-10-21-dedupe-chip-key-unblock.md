# Dedupe Chip-Key Unblock Implementation Plan

**Goal:** Unblock the stalled campaign (1990/2100, 0 enqueues since Oct 21 07:12Z). Root cause: board card extractors pick UI badge/chip text ("new", "viewed", "B2B", "Backend") as the company name; dedupe then mass-blocks every listing sharing that garbage companyKey the moment one chip-keyed application exists (7 such applications now). Fix: sentinel-key expansion (functional unblock, drift-proof), extractor noise-list fix (data quality), per-window outcome summary (observability).

**Architecture:** All three changes are small and localized: `CHIP_KEYS` in common `CompanyKeyNormalizer` (added to `SENTINEL_KEYS`; the dedupe company-branch already skips sentinels, wrapping BOTH the application and duplicate-skip lookups - so no Mongo repair is needed; old chip-keyed applications become inert); the `CARD_SCRIPT` chipNoise regex in `McpListingCollector` built from `CHIP_KEYS` (whole-line anchored, so the real company line below the badges wins); a `WindowSummary` accumulator logged once per finder run (a collapse like today's becomes one visible line instead of 17 silent hours).

**Evidence (2026-10-21):** 545/568 theprotocol listings in 48h keyed "new"; nofluffjobs: backend=70, fullstack=54, b2b=33...; 7 applications carry chip keys (new, viewed, backend, frontend, fullstack, b2b, uop) - each silently blocks all same-keyed listings at `DedupService.check` (COMPANY match, no log/counter). Enqueues collapsed 2079/day (Oct 20) -> 7 (Oct 21) -> 0; submissions stuck at 1990/2100.

## Global Constraints

- English only; no em dashes; no secret values.
- TDD for all Kotlin changes; fresh-context review subagent before push.
- The sentinel expansion must NOT change normalization output (golden tests pin `normalize`); only `SENTINEL_KEYS`/`isSentinelKey` grow.
- uiNoise additions are whole-line anchored matches only (a real company like "B2B Group" on its own line will be skipped - accepted tradeoff; the salary/stack/LLM gates still guard quality).
- Deploy contract: image `:v1`, rebuild + `k3s ctr images import` + rollout restart; common module changed -> rebuild BOTH finder-service and apply-worker images.
- Worker is idle (lag 0) -> restarts are safe.

## Review Outcome (2026-10-21)

Review subagent: APPROVE after fixes. Applied: BLOCKER 1 (the `ai/ml` branch must be slash-escaped `ai\/ml` or the emitted JS regex literal dies with a SyntaxError and ALL extraction fails - caught by executing the emitted line in a JS engine), SHOULD 1 (JVM-regex semantics test for the alternation + escaped-form script assertion), SHOULD 2 (`ml`, `hybrid` added to CHIP_KEYS), SHOULD 3 (`remote` added to the extractor filter only - still excluded from sentinels because Remote.com is a real employer), SHOULD 4 (`WindowSummary.WINDOW_SCAN_TAG` used by the producer), SHOULD 5 (round-trip normalize pins for scraped chip text), NICE 1/2/3/4 (ID+URL summary cases, accepted-risk KDoc sentence, this doc update, per-source collected-count log line).

## Task 1: Sentinel keys (common, TDD) - DONE

**Files:**
- `kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/dedupe/CompanyKeyNormalizerTest.kt`
- `kotlin/common/src/main/kotlin/dev/mstaszew/campaign/common/dedupe/CompanyKeyNormalizer.kt`
- `kotlin/common/src/test/kotlin/dev/mstaszew/campaign/common/dedupe/DedupServiceTest.kt`

- [x] Red: `isSentinelKey` true for the chip vocabulary (new, viewed, start-asap, quick-apply, easy-apply, b2b, uop, uod, backend, frontend, fullstack, devops, testing, qa, mobile, data, ai-ml, ml, architecture, hybrid, sprawdź-wynagrodzenie, sprawdzisz-zarobki, nowa, nowe, polecamy); false for ryb-tech, link-group, and deliberately remote (Remote.com). DedupServiceTest: a candidate with companyKey "new" and an existing application on "new" is NOT a company duplicate; both company lookups verified never called.
- [x] Implement: `CHIP_KEYS` set + `SENTINEL_KEYS = legacy + CHIP_KEYS`; KDoc records the outage and the accepted generic-word collision risk.
- [x] Green: full common suite (normalize golden cases untouched).

## Task 2: Extractor chip filtering (finder, TDD) - DONE

**Files:**
- `kotlin/finder-service/src/test/kotlin/dev/mstaszew/campaign/finder/collect/McpListingCollectorTest.kt`
- `kotlin/finder-service/src/main/kotlin/dev/mstaszew/campaign/finder/collect/McpListingCollector.kt`

- [x] `chipBadgeAlternation()` builds the whole-line filter from `CHIP_KEYS` (hyphens to spaces) plus extractor-only extras `remote` and slash-escaped `ai\/ml`.
- [x] Tests: alternation contains every observed chip; JVM-regex semantics check (chip lines match, "B2B Group"/"Link Group"/"DataArt"/"Nowe horyzonty" do not); emitted script carries the anchored `chipNoise` regex, the JS-escaped `ai\/ml` form, and never the raw `ai/ml`.
- [x] Note: the JS itself is not executable in unit tests; the production verification is the first live window after deploy (finder log `collected <board>: N listings` + window summary).

## Task 3: Window summary (finder, TDD) - DONE

**Files:**
- `kotlin/finder-service/src/test/kotlin/dev/mstaszew/campaign/finder/WindowSummaryTest.kt`
- `kotlin/finder-service/src/main/kotlin/dev/mstaszew/campaign/finder/WindowSummary.kt`
- `kotlin/finder-service/src/main/kotlin/dev/mstaszew/campaign/finder/FinderPipeline.kt`

- [x] `WindowSummary` counts seen/dupWindow/deduped/ineligible/lowScore/enqueued/failed; renders `window summary: seen=N dupWindow=N deduped=N ineligible=N lowScore=N enqueued=N failed=N`.
- [x] `run()` records every outcome (and failures), logs one summary line per window; the summary is a method-local single-writer accumulator.
- [x] Fresh-listing-mass-block signal: `upsert` returns `(listing, created)`; a dedupe hit on a freshly created listing logs `fresh listing ... deduped on ...; check for a mass-block`.
- [x] Per-source visibility: `collected <board>: N listings` logged per board (an empty batch from selector drift is now visible).
- [x] Green: finder suite + full `./gradlew build`.

## Task 4: Review, commit, push - DONE

- [x] Fresh-context review subagent (code-review agent): 1 BLOCKER + 5 SHOULD + 4 NICE, all addressed (see Review Outcome).
- [x] Commit + push origin main.

## Task 5: Deploy + end-to-end verification

- [x] Laptop: bundle -> ff-merge -> build BOTH images (`--target worker`, `--target finder`) -> import -> rollout restart finder + worker. (Done 13:42:58Z; images at `361ab45`.)
- [x] Watch the next window (<=15 min): finder log shows `collected` lines + `window summary` with enqueued >= 1; worker consumes; submissions resume; Slack posts carry the stats footer. (Window 13:43Z: enqueued=7, first in 17h, zero mass-block lines. Window 15:01Z: seen=365 deduped=50 ineligible=74 lowScore=0 enqueued=22 failed=0 - backlog draining; new listings carry real keys, e.g. `torpol`, `phz-sm-lacpol`.)
- [x] Report to user: root cause, fix, proof of resumed progress; justjoin collector yields 0 listings ever (selector drift) - logged as follow-up, not in this scope.

## Review Outcome (post-implementation)

All BLOCKER/SHOULD/NICE findings addressed and shipped in `361ab45`: `ai\/ml` slash-escaping pinned by tests, JVM-regex semantics test, ml+hybrid sentinels, remote extractor-only, `WindowSummary.WINDOW_SCAN_TAG`, round-trip normalize pins, ID/URL summary cases.

## Post-deploy operations log (2026-10-21, same session)

Three operational blockers were diagnosed and cleared while verifying the funnel; none are regressions of this change:

1. **Stale Chrome lease** (cleared 14:26:50Z): the pre-fix worker pod died at 14:18 holding `browser_leases` (`_id: "chrome"`, 25-min TTL). The restarted worker's first claim waited silently on the lease. Deleted the doc; new owner acquired in seconds. Future hardening candidate: on worker boot, steal leases whose owner pod name no longer exists.
2. **Playwright init wedge** (cleared ~14:33Z): every `connectOverCDP` (worker MCP, fresh pod, laptop host) timed out in `initializeServer` while raw CDP answered in 5-20 ms from everywhere. Root cause: a nofluffjobs tab poisoned by a recaptcha challenge (OOPIF iframes + unclosable `recaptcha/api2/webworker.js` target) stalled Playwright's auto-attach. `Target.closeTarget` on that one page fixed it; Playwright connected in 138 ms. The apply that hit the captcha recorded the open blocker (`blockersOpen=1`, upvanta listing).
3. **Queue drain confirmed**: consumer lag 7 -> 0 by 15:00, then 22 fresh enqueues queued at 15:10. Worker observed actively form-filling on live portals (msrouter ~38 step calls / 7 min).

Pre-existing frictions observed, logged as follow-ups (not in this scope):

- **Dead listings burn the step cap**: `ERR_HTTP_RESPONSE_CODE_FAILURE` on navigate (removed posting) retried ~50x to the 60-step cap (~10 min wasted). Candidate: early-abort after N consecutive navigate HTTP failures.
- **Free-model flakiness**: `model produced no tool call` (prose instead of tool call) killed 2 of ~6 attempts; invented selectors waste further steps. Economics of the `mst/free` pool; consider a fallback model or retry-on-babble step policy.
- **nofluffjobs CV upload friction**: `browser_file_upload` modal-state refusals fall back to CDP; works but burns steps.
- **justjoin collector**: 0 listings ever (selector drift) - unchanged follow-up.
- **Chip-residue cost, accepted**: one wasted attempt on an already-submitted job (darize; listing key frozen as `b2b` vs app key `darize` missed company dedupe; URL normalize also missed). Bounded by worker-side dedupe + Gmail 60-day net.
