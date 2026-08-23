# IL-Only Targeting + Unrestricted Seniority/Pay Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Narrow the job search campaign to Israel-only, remove pay/seniority restrictions, expand expertise scope to include TDD, code reviews, and freelance.

**Architecture:** Update the single-source-of-truth prompt in `prompt.py`, the runtime docs (`AGENT_TICK.md`, `PORTALS.md`, `CONTEXT.md`), and the workspace `AGENTS.md`. No code logic changes - just policy text.

**Tech Stack:** Python (prompt.py), Markdown (docs), bats (existing tests verify prompt doesn't break).

**Spec:** User request 2026-08-23: "focus on IL, don't apply on Upwork or Polish sites, don't restrict by pay or seniority as long as in my area of expertise, include even freelance, TDD, reviews etc."

---

## File Map

| File | Change |
|------|--------|
| `campaign_agent/src/campaign_agent/prompt.py` | Rewrite RULES section: IL-only, no salary floor, all seniority, expanded expertise |
| `Downloads/job-search/job-apply/AGENT_TICK.md` | Remove EU/PL work order, update rules |
| `Downloads/job-search/job-apply/PORTALS.md` | Remove EU section entirely, keep IL boards only |
| `Downloads/job-search/job-apply/CONTEXT.md` | Update salary line, remove EU B2B note |
| `Downloads/job-search/job-apply/IL_BOARDS.md` | Already IL-only, no change needed |
| `~/.campaign-agent/director-prompt-overrides.md` | Clean up (remove stale "test" entries, add new directive) |
| `openclaw-job-search/AGENTS.md` | Update job-search specifics section |

---

### Task 1: Update prompt.py (single source of truth)

**Files:**
- Modify: `campaign_agent/src/campaign_agent/prompt.py:18-51`

**Interfaces:**
- Consumes: Config dataclass (unchanged)
- Produces: Updated USER_PROMPT_TEMPLATE string used by build_user_prompt()

- [ ] **Step 1: Update the RULES section in USER_PROMPT_TEMPLATE**

Replace lines 29-50 with:

```python
- Targets: Java/Kotlin/Spring, PHP/Laravel, Node/React. Include TDD, code reviews, \
test-driven development, CI/CD, and related engineering practices. \
Skip: ABAP, Salesforce, C/C++, .NET, ML/data, DevOps/SRE-only.
- Seniority: ALL levels accepted (junior through senior). Skip only: \
team-lead/manager/architect/director/head/VP.
- IL only: remote/hybrid/onsite ALL OK (central Israel for onsite; remote anywhere in IL). \
Do NOT apply to Polish sites, Upwork, or EU/PL portals.
- Freelance: include freelance, contract, part-time, and fixed-term B2B in IL.
- Record submissions ONLY via exec: update_tracker.py submitted '<json>'. Never \
edit tracker.json directly. Record immediately after browser confirmation.
- Dedupe: rag_search_apps + Gmail (60d). One company once. Do NOT call automation \
scripts (no score_candidate.py, no check_dupe.py).
- Browser: existing Chrome at http://127.0.0.1:9222. Do NOT launch/close Chrome.
- CV to upload: {cv_path} (absolute path; it is a regular file).
- Playwright page snapshots are saved under {playwright_output_dir} (absolute \
path, NOT relative to the campaign dir); read them from there if needed.
- Never ask permission. No stop tokens. After recording a submission, end your turn.
- Temp scripts go in /tmp/, not the campaign dir.
- The exec tool's working directory is {campaign_dir}; use relative paths there.

Work order: IL only (all modes). Stop after one confirmed submission.
```

- [ ] **Step 2: Run existing tests to verify prompt doesn't break**

Run: `cd /Users/mst/ZCodeProject/openclaw-job-search/campaign_agent && python -m pytest tests/test_prompt.py -v`
Expected: All tests pass (prompt structure unchanged, only string content updated).

- [ ] **Step 3: Commit**

```bash
git add campaign_agent/src/campaign_agent/prompt.py
git commit -m "feat: narrow targeting to IL-only, remove pay/seniority restrictions, expand expertise"
```

---

### Task 2: Update AGENT_TICK.md (runtime runbook)

**Files:**
- Modify: `Downloads/job-search/job-apply/AGENT_TICK.md`

**Interfaces:**
- Consumes: None (standalone doc)
- Produces: Updated runbook read by agent each tick

- [ ] **Step 1: Update Section 1 (Pick next) and Section 3 (Apply)**

Replace work order and rules:

Line 17: `Order: **IL remote/hybrid -> EU/PL full remote -> other EU full remote**.`
-> `Order: **IL only** (remote/hybrid/onsite all OK).`

Lines 20-21 (EU portals): Remove entirely. Replace with:
```
- **Portals (PORTALS.md, IL_BOARDS.md):** IL = AllJobs/Drushim/JobMaster/Jobnet/DevJobs/Janglo/Secret Tel Aviv/LinkedIn IL. Remote, hybrid, or onsite OK.
```

Line 24: `Junior/entry-level/graduate roles ARE allowed`
-> `ALL seniority levels accepted (junior through senior). Only skip: team-lead/manager/architect/director/head/VP.`

Line 29: `EU/GLOBAL = full remote only, B2B >=15k PLN/month when salary listed.`
-> Remove. Replace with: `IL = all modes (remote/hybrid/onsite), no salary floor.`

Line 30-31: Remove EU/PL salary constraints entirely.

Line 36: Update salary field guidance:
`Salary fields: 15000 PLN (EU) or 15000 ILS (IL) for monthly full-remote expectations.`
-> `Salary fields: use market rate for IL. No minimum floor.`

Line 72: Remove `plB2bNote` for PL/EU roles. Simplify cover note.

Line 106-107: Remove EU/PL constraints from "Do NOT" section.

- [ ] **Step 2: Add TDD/reviews expertise note**

After the "Apply" section, add:
```
- **Expertise scope:** Include roles involving TDD, code reviews, test-driven development, \
pair programming, CI/CD, and engineering best practices. These are core competencies, \
not exclusions.
```

- [ ] **Step 3: Commit**

```bash
git add /Users/mst/Downloads/job-search/job-apply/AGENT_TICK.md
git commit -m "docs: update AGENT_TICK.md for IL-only targeting"
```

---

### Task 3: Update PORTALS.md

**Files:**
- Modify: `Downloads/job-search/job-apply/PORTALS.md`

**Interfaces:**
- Consumes: None (standalone doc)
- Produces: Updated portal catalog

- [ ] **Step 1: Remove EU section, keep IL only**

Remove the entire "Europe (EU)" section (lines 29-37).

Update the header/summary to reflect IL-only:
```markdown
# Portals by region

Validated with live Google searches. **IL only** - all work modes (remote/hybrid/onsite).

## Target Seniority and Role Constraints
- **Allowed Seniority**: ALL levels (junior through senior). Strictly no team leaders, tech leads, architects, managers, directors, or heads.
- **Region Policy**: Israel ONLY. Do NOT apply to Polish sites, Upwork, or EU/PL portals.
- **Expertise**: Java/Kotlin/Spring, PHP/Laravel, Node/React. Include TDD, code reviews, CI/CD.
```

- [ ] **Step 2: Update rules of thumb**

Replace line 42-46 with:
```markdown
## Rules of thumb
- IL only: remote, hybrid, or onsite OK in central Israel; remote OK anywhere.
- Every candidate must pass CV alignment reasoning (no score_candidate.py script).
- One company once - dedupe is an **agent task** following `DEDUPE.md`.
- All seniority levels accepted. Skip only lead/architect/manager/director.
```

- [ ] **Step 3: Commit**

```bash
git add /Users/mst/Downloads/job-search/job-apply/PORTALS.md
git commit -m "docs: update PORTALS.md to IL-only, remove EU portals"
```

---

### Task 4: Update CONTEXT.md

**Files:**
- Modify: `Downloads/job-search/job-apply/CONTEXT.md`

**Interfaces:**
- Consumes: None (standalone doc)
- Produces: Updated campaign context

- [ ] **Step 1: Update salary and targeting lines**

Line 4: `1200 submitted applications (raised 2026-07-05 from 1000; junior/entry roles now included)`
-> `1200 submitted applications. ALL seniority levels. IL only.`

Line 12: Remove `Score against: stack (Java/Kotlin/Spring, PHP/Laravel, Node/React), seniority (mid-to-senior preferred; junior/entry also allowed per widened policy. Skip lead/architect/manager/director/head/vp), region (IL remote/hybrid/on-site; EU full remote only), salary (EU min 15k PLN B2B when listed).`
-> `Score against: stack (Java/Kotlin/Spring, PHP/Laravel, Node/React + TDD/reviews/CI/CD). All seniority. IL only (remote/hybrid/onsite). No salary floor.`

Line 32: `Salary line: 15000 PLN net+VAT/month (EU B2B, full remote only) or 15000 ILS/month (IL).`
-> `Salary line: market rate for IL. No minimum floor.`

- [ ] **Step 2: Commit**

```bash
git add /Users/mst/Downloads/job-search/job-apply/CONTEXT.md
git commit -m "docs: update CONTEXT.md for IL-only, remove salary floor"
```

---

### Task 5: Update workspace AGENTS.md

**Files:**
- Modify: `openclaw-job-search/AGENTS.md`

**Interfaces:**
- Consumes: None (standalone doc)
- Produces: Updated workspace instructions

- [ ] **Step 1: Update job-search specifics**

Line 15-16: Replace:
```
- Target mid-to-senior Java/Kotlin/Spring, PHP/Laravel, Node/React. IL:
  remote/hybrid/onsite (central). EU/PL: full remote, B2B >= 15000 PLN.
```
With:
```
- Target Java/Kotlin/Spring, PHP/Laravel, Node/React + TDD, code reviews, CI/CD. \
IL only (remote/hybrid/onsite). All seniority. Include freelance. \
No salary floor. No Polish sites, no Upwork, no EU/PL portals.
```

- [ ] **Step 2: Commit**

```bash
git add openclaw-job-search/AGENTS.md
git commit -m "docs: update AGENTS.md for IL-only targeting"
```

---

### Task 6: Clean up director-prompt-overrides.md

**Files:**
- Modify: `~/.campaign-agent/director-prompt-overrides.md`

**Interfaces:**
- Consumes: None (standalone file)
- Produces: Updated director overrides injected into prompt

- [ ] **Step 1: Replace stale content**

Remove all the `test` entries. Replace with:
```markdown
IL ONLY: Do NOT apply to Polish sites, Upwork, or EU/PL portals. Israel only (remote/hybrid/onsite).
No salary floor. All seniority levels accepted (junior through senior). Skip only: team-lead/manager/architect/director/head/VP.
Include TDD, code reviews, CI/CD, and engineering best practices roles.
Include freelance, contract, and part-time engagements.
```

- [ ] **Step 2: Commit**

```bash
git add ~/.campaign-agent/director-prompt-overrides.md
git commit -m "docs: update director overrides for IL-only targeting"
```

---

### Task 7: Verify all changes

- [ ] **Step 1: Run full test suite**

Run: `cd /Users/mst/ZCodeProject/openclaw-job-search/campaign_agent && python -m pytest tests/ -v`
Expected: All tests pass.

- [ ] **Step 2: Verify prompt renders correctly**

Run: `cd /Users/mst/ZCodeProject/openclaw-job-search/campaign_agent && python -c "from campaign_agent.prompt import build_user_prompt; from campaign_agent.config import Config; print(build_user_prompt(Config()))"`
Expected: Output shows IL-only rules, no EU/PL, no salary floor, TDD/reviews included.

- [ ] **Step 3: Final commit**

```bash
git add -A && git commit -m "chore: verify IL-only targeting changes compile and pass tests"
```
