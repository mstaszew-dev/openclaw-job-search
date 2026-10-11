# StarUML k3s + Mongo + Google + WhatsApp MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce five StarUML models (with SVG exports) of the laptop/Tailscale/k3s job pipeline, document the live MongoDB tracking schema, install the WhatsApp MCP bridge and wire it into opencode, and add a global opencode rule that makes `~/ZCodeProject` the default work dir.

**Architecture:** Diagram and document artifacts are authored in the repo `~/ZCodeProject/openclaw-job-search-kotlin/docs/` and committed on branch `zcode/kotlin-distributed`. The laptop cluster is read-only for this work (no rollout, no scale, no delete). OpenCode config changes land in `~/.config/opencode/opencode.json` (MCP servers) and `~/.config/opencode/AGENTS.md` (global rule).

**Tech Stack:** StarUML 6.3.4 (brew cask), `.mdj` JSON projects, MongoDB 7 (in k3s, read via `kubectl exec mongo-0 -- mongosh`), Go (bridge), Python/uv (MCP server), OpenCode V2 config.

**Spec:** this plan is the spec (short in-chat design approved 2026-10-11).

## Global Constraints

- Verified access: laptop `ssh -p 2222 mstro@100.94.153.31` password `1851`, passwordless sudo, `k3s` + `kubectl` + `docker` present. Host sshd does not auto-start after reboot.
- k3s facts that must be exact in diagrams: ns `campaign` = `finder`, `apply-worker`, `campaign-api`, `kafka-0` (KRaft), `mongo-0` (replica set `rs0`, auth), `msrouter` (pinned `replicas: 0`); ns `default` = `lubuntu-agent` (Chrome/Xvfb/VNC/CDP). Services: `campaign-api` NodePort 30080, `finder` 8081 ClusterIP, `kafka` 9092, `mongo` 27017, `msrouter` 3000, `postgres` 5432 (retired). Host NodePorts: 30022 ssh, 30080 api, 30590 vnc, 30922 cdp. Data: `/home/mstro/k3s-pod-data/{mongodb,kafka,agent-home,agent-state,playwright-output,tailscale}`.
- Topics: `campaign.jobs` (3 partitions, key `companyKey`), `campaign.jobs.dlq` (1). Collections: `job_listings`, `applications` (`_id = source:sourceJobId`), `skips`, `blockers`, `events`, `job_state`.
- GCP means the msrouter Google Gemini provider slot: `https://generativelanguage.googleapis.com/v1beta/openai`, env `GEMINI_API_KEY`/`GEMINI_MODEL`, default `gemini-2.5-flash-lite`, position 5 in `EXTRA_PROVIDER_ORDER` (`unorouter, groq, sambanova, mistral, gemini, cline, cloudflare`, then cloudflare last).
- OpenCode is V2: servers go under `mcp.servers` (`mcp.<name>` is V1 and not read by V2). Preserve unrelated `opencode.json` settings.
- NO SECRETS in any artifact: no `campaign-mongo` password/keyfile, no `msrouter.env` provider keys, no `GEMINI_API_KEY`, no bridge session files. MongoDB credentials appear only as `{ from secret campaign-mongo }`.
- Typography: no em dash (U+2014) anywhere.
- Chat/artifacts in English. Diagrams show the user's reference flow (Discovery+Dedupe -> Kafka -> worker fleet -> DB/UI) annotated with the real deployed component names.
- Scope: laptop is read-only (probes, `kubectl get/exec mongosh` reads). Mongo stays where it is; no new laptop installs.

## Review Focus

Highest-risk failure modes for a person using these artifacts, each pinned to a test/verification step below:

1. StarUML CLI may not export SVG in 6.3.4 -> Task 1 records the real capability; Task 3 falls back to `mermaid-cli` and labels the SVGs as rendered, not app-exported.
2. Hand-written `.mdj` JSON can be schema-invalid and the app refuses to open it -> Task 3 load-tests every file and reports failures instead of claiming success.
3. `opencode.json` currently has `mcp.playwright` in the V1 shape -> Task 5 moves it to `mcp.servers` unchanged and proves Playwright still lists; if moving it breaks it, revert and keep the server in both shapes.
4. WhatsApp pairing needs the user's phone -> Task 5 prints the QR/link and stops; no send is attempted by me.
5. A secret can leak into a diagram or doc (mongo password, provider keys) -> Task 4 greps all new artifacts for base64 key material before commit.
6. Diagram claims can drift from the cluster (paused gateway, retired postgres) -> Task 2 and Task 3 assert the paused/retired state is labeled in the diagrams.

---

### Task 1: Install StarUML and record its CLI capability

**Files:**
- Create: `docs/diagrams/README.md` (capability notes only; grows in Task 3)

**Interfaces:**
- Produces: the exact export command (or the documented absence of one) that Task 3 uses.

- [ ] **Step 1: Install the app**

Run: `brew install --cask staruml`
Expected: `StarUML.app` present. Verify: `ls -d /Applications/StarUML.app`.

- [ ] **Step 2: Probe the CLI**

Run: `/Applications/StarUML.app/Contents/MacOS/StarUML --help 2>&1 | head -40`
Expected: either a flag list containing an export/`--export` style option, or an "unknown option" style error. Record the raw output verbatim in `docs/diagrams/README.md` under "StarUML CLI".

- [ ] **Step 3: Find a reference `.mdj` to copy the schema from**

Run: `find /Applications/StarUML.app -name '*.mdj' 2>/dev/null | head -20`
Expected: sample/template `.mdj` files, or nothing. If nothing, download the upstream sample project from `github.com/staruml/staruml-file-format` (or the StarUML docs example) into `docs/diagrams/_schema/` and record which source was used.

- [ ] **Step 4: Commit**

```bash
git add docs/diagrams/README.md docs/diagrams/_schema 2>/dev/null
git commit -m "docs(diagrams): record StarUML 6.3.4 CLI capability and mdj schema source"
```

### Task 2: Collect the live facts for the diagrams

**Files:**
- Create: `docs/diagrams/facts.md` (raw evidence, no secrets)

**Interfaces:**
- Produces: the exact strings (names, ports, indexes, provider order) that Tasks 3 and 4 transcribe. Later tasks do not re-derive them.

- [ ] **Step 1: Snapshot cluster state**

Run (from the Mac):
`sshpass -p 1851 ssh -p 2222 mstro@100.94.153.31 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; sudo -E k3s kubectl get nodes -o wide; sudo -E k3s kubectl -n campaign get deploy,sts,svc,ep; sudo -E k3s kubectl -n default get pods,svc -o wide'`
Expected: node + backing pod except `msrouter` list, all services and endpoints as listed in Global Constraints.

- [ ] **Step 2: Snapshot Mongo schema**

Run: `sshpass -p 1851 ssh -p 2222 mstro@100.94.153.31 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; P=$(sudo -E k3s kubectl -n campaign get pod -l app=mongo -o jsonpath="{.items[0].metadata.name}"); sudo -E k3s kubectl -n campaign exec $P -- mongosh "mongodb://campaign:$(sudo -E k3s kubectl -n campaign get secret campaign-mongo -o jsonpath="{.data.password}" | base64 -d)@localhost:27017/campaign?authSource=admin" --quiet --eval "db.getCollectionNames(); db.getCollectionNames().forEach(c => print(c, JSON.stringify(db.getCollection(c).getIndexes())))"'`
Expected: the seven collections and their index lists. Redact the password before writing `facts.md` (write `{ from secret campaign-mongo }`).

- [ ] **Step 3: Snapshot provider order and Gemini slot**

Run: `rg -n -A8 'EXTRA_PROVIDER_ORDER = \[' ~/ZCodeProject/msrouter/src/providers/extras.ts; rg -n 'GEMINI_BASE_URL|GEMINI_MODEL' ~/ZCodeProject/msrouter/src/providers/gemini.spec.ts | head -5`
Expected: the order line and the two Gemini constants. Record output.

- [ ] **Step 4: Record the reference flow from the user's diagram**

Append to `facts.md` the reference pipeline (External sources -> Discovery & Deduplication Agent -> Kafka -> worker agents -> Job Search DB/UI) and the mapping table: Discovery&Dedupe = `finder`; Kafka topic = `campaign.jobs` (reference name `validated_job_offers` kept as an alias in the diagram); worker fleet = `apply-worker`; Job Search DB = `mongo`; UI/API = `campaign-api`. Note the reference flow shows three specialist workers while the deployment runs one consumer group `apply-workers`.

- [ ] **Step 5: Commit**

```bash
git add docs/diagrams/facts.md
git commit -m "docs(diagrams): snapshot live k3s, mongo, and msrouter provider facts"
```

### Task 3: Author the five StarUML models and export SVG

**Files:**
- Create: `docs/diagrams/01-uml-deployment.mdj`, `02-arch-job-pipeline.mdj`, `03-mongo-data-model.mdj`, `04-gcp-msrouter-providers.mdj`, `05-wa-mcp-opencode.mdj`
- Create: `docs/diagrams/export/*.svg`
- Modify: `docs/diagrams/README.md`

**Interfaces:**
- Consumes: Task 1 export command, Task 2 `facts.md` strings.
- Produces: the five `.mdj` paths and their SVG exports.

- [ ] **Step 1: Write `01-uml-deployment.mdj`**

Content: UML deployment with nodes Mac dev (opencode, Playwright MCP), Tailscale tailnet (`100.94.153.31`, MagicDNS name), laptop node (Ubuntu 22.04, k3s, docker), artifacts inside the node: ns `campaign` (finder, apply-worker, campaign-api, kafka-0, mongo-0 rs0, msrouter replicas 0), ns `default` (lubuntu-agent: Chrome, Xvfb, VNC, CDP), NodePorts 30022/30080/30590/30922, hostPath volumes under `/home/mstro/k3s-pod-data/`, and the image path `docker build -> docker save | k3s ctr images import` (no registry).

- [ ] **Step 2: Write `02-arch-job-pipeline.mdj`**

Content: UML component diagram of the reference flow mapped to reality (table in Task 2 Step 4): sources -> finder -> `campaign.jobs` (3 partitions, key companyKey) -> apply-worker (manual ack AFTER the Mongo write, `max.poll.records=1`) -> mongo -> campaign-api (`/stats /jobs /applications`), plus `campaign.jobs.dlq`, plus the CDP path from finder/worker to Chrome in `lubuntu-agent` on node IP `:30922`. Label `APPLY_MODE=shadow` and `msrouter PAUSED (replicas=0)`.

- [ ] **Step 3: Write `03-mongo-data-model.mdj`**

Content: UML class diagram of the seven collections with the fields and index names from Task 2 Step 2. Mark `applications._id` unique (`source:sourceJobId`), `job_listings` unique `(source, sourceJobId)`, and the three transaction boundaries (`recordSubmitted`, `recordSkippedDuplicate`/`recordBlocked`, `markTerminal`/`failJob`). Note `job_state` is a ledger only and never schedules.

- [ ] **Step 4: Write `04-gcp-msrouter-providers.mdj`**

Content: component diagram: clients (agent loop, CvScorer, apply-worker) -> msrouter (`mst/free`) -> provider chain in `EXTRA_PROVIDER_ORDER` order with the Google Gemini (GCP) slot highlighted (`generativelanguage.googleapis.com/v1beta/openai`, `gemini-2.5-flash-lite`, free tier), plus the other slots and the pause switch (`replicas: 0`, `FINDER_SCORING_ENABLED=false`, fallback score 70, `score >= 60` CV filter off while paused).

- [ ] **Step 5: Write `05-wa-mcp-opencode.mdj`**

Content: component/deployment diagram of opencode-only WhatsApp MCP: opencode (Mac, stdio) -> `whatsapp-mcp-server` (Python/uv, repo `~/ZCodeProject/tools/whatsapp-mcp/whatsapp-mcp-server`) -> `whatsapp-bridge` (Go, whatsmeow, WhatsApp Web multi-device) -> SQLite `store/messages.db`; tools listed: search_contacts, list_messages, list_chats, get_chat, get_message_context, send_message, send_file, send_audio_message, download_media. Mark "opencode only, not wired into the pipeline".

- [ ] **Step 6: Export SVGs**

Run the Task 1 export command per file into `docs/diagrams/export/`. If no CLI export exists, run:
`npx -y @mermaid-js/mermaid-cli -i <src.mmd> -o docs/diagrams/export/<name>.svg` on equivalent sources kept in `docs/diagrams/src/`, and state in `README.md` that those SVGs are rendered from source, not StarUML exports.

- [ ] **Step 7: Load-test every `.mdj`**

For each file: run the StarUML CLI export again against it (a schema-invalid project fails here) or, when no CLI exists, validate the JSON parses and report that in-app opening is unverified. Record per-file status in `README.md`. Do not claim "opens in StarUML" without that evidence.

- [ ] **Step 8: Commit**

```bash
git add docs/diagrams
git commit -m "docs(diagrams): five StarUML models plus SVG exports for k3s pipeline, mongo, gemini, whatsapp mcp"
```

### Task 4: Document the MongoDB tracking store

**Files:**
- Create: `docs/mongodb-tracking.md`

**Interfaces:**
- Consumes: Task 2 Step 2 schema snapshot.

- [ ] **Step 1: Prove the store is live and writable-primary**

Run: `sshpass -p 1851 ssh -p 2222 mstro@100.94.153.31 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; P=$(sudo -E k3s kubectl -n campaign get pod -l app=mongo -o jsonpath="{.items[0].metadata.name}"); sudo -E k3s kubectl -n campaign exec $P -- mongosh "mongodb://campaign:$(sudo -E k3s kubectl -n campaign get secret campaign-mongo -o jsonpath="{.data.password}" | base64 -d)@localhost:27017/campaign?authSource=admin" --quiet --eval "db.hello().isWritablePrimary; db.events.countDocuments({}); db.applications.countDocuments({})"'`
Expected: `true` and two counts. Record counts in the doc with the date.

- [ ] **Step 2: Write the doc**

Sections: deployment (mongo-0, replica set `rs0`, auth via secret `campaign-mongo`, keyfile, data at `/home/mstro/k3s-pod-data/mongodb`, WiredTiger cache 256MB, probes use `db.hello().isWritablePrimary`); collection reference table (name, job, key fields, indexes); the one-line rule "Kafka owns delivery, Mongo owns bookkeeping"; the three transaction boundaries; dedupe semantics (id -> company -> url, sentinel keys, blank URL); copy-paste tracking queries for `/stats /jobs /applications` equivalents; ops (health check, why a Ready pod can still serve nobody, `rs.initiate` Job); and the importer (`import-tracker` one-shot, `tracker.json` + `events.jsonl`).

- [ ] **Step 3: Secret sweep**

Run: `rg -n 'base64|password|API_KEY|keyfile' docs/mongodb-tracking.md docs/diagrams/*.mdj docs/diagrams/facts.md docs/diagrams/README.md`
Expected: only `{ from secret campaign-mongo }` style placeholders and env-var names such as `GEMINI_API_KEY`, never a value.

- [ ] **Step 4: Commit**

```bash
git add docs/mongodb-tracking.md
git commit -m "docs: document the MongoDB tracking store, schema, and transaction boundaries"
```

### Task 5: Install the WhatsApp MCP bridge and wire it into opencode

**Files:**
- Modify: `~/.config/opencode/opencode.json` (add `mcp.servers.whatsapp`, move `playwright` under `mcp.servers`)
- Create: `~/.config/opencode/opencode.json.bak-pre-whatsapp-mcp-20261011`
- Create: `docs/whatsapp-mcp.md` (install + config record, no session data)

**Interfaces:**
- Produces: `opencode mcp list` showing `playwright` and `whatsapp`.

- [ ] **Step 1: Install Go and clone the server**

Run: `brew install go && mkdir -p ~/ZCodeProject/tools && git clone https://github.com/lharries/whatsapp-mcp.git ~/ZCodeProject/tools/whatsapp-mcp`
Expected: `go version` prints a version; the repo exists with `whatsapp-bridge/` and `whatsapp-mcp-server/`.

- [ ] **Step 2: Build the bridge**

Run: `cd ~/ZCodeProject/tools/whatsapp-mcp/whatsapp-bridge && go build -o whatsapp-bridge .`
Expected: a `whatsapp-bridge` binary, no CGO error (macOS has a C compiler). Fallback if cgo fails: `CGO_ENABLED=1 go build -o whatsapp-bridge .`.

- [ ] **Step 3: Back up and edit the opencode config**

Run: `cp ~/.config/opencode/opencode.json ~/.config/opencode/opencode.json.bak-pre-whatsapp-mcp-20261011`
Then add to `~/.config/opencode/opencode.json`:

```jsonc
"mcp": {
  "servers": {
    "playwright": { "type": "local", "command": ["/opt/homebrew/bin/playwright-mcp", "--browser", "chrome", "--user-data-dir", "/Users/mst/.playwright-mcp"] },
    "whatsapp": { "type": "local", "command": ["/Users/mst/.local/bin/uv", "--directory", "/Users/mst/ZCodeProject/tools/whatsapp-mcp/whatsapp-mcp-server", "run", "main.py"] }
  }
}
```

Use the real `which uv` output for the command path. Leave every other key in the file untouched.

- [ ] **Step 4: Verify the servers list**

Run: `opencode mcp list`
Expected: `playwright` and `whatsapp` present. If `playwright` broke after the move, restore the V1 `mcp.playwright` entry alongside `mcp.servers` and re-verify.

- [ ] **Step 5: Start the bridge and hand over pairing**

Run: `cd ~/ZCodeProject/tools/whatsapp-mcp/whatsapp-bridge && ./whatsapp-bridge` (background, log to `~/ZCodeProject/tools/whatsapp-mcp/bridge.log`)
Expected: a QR code or pairing link in the log. Show it to the user and stop: pairing needs their phone (WhatsApp > Linked devices). Report "configured, awaiting sign-in" until a bridge log line confirms authentication.

- [ ] **Step 6: Write `docs/whatsapp-mcp.md` and commit**

Include: repo path, install commands, config snippet, the tools exposed, the re-link window (~20 days), the local SQLite store path, and an explicit warning that pairing and any send action are user-driven.

```bash
git add docs/whatsapp-mcp.md
git commit -m "docs: record WhatsApp MCP install and opencode wiring"
```

### Task 6: Add the global opencode rule for the projects dir

**Files:**
- Modify: `~/.config/opencode/AGENTS.md`
- Create: `~/.config/opencode/AGENTS.md.bak-pre-workdir-rule-20261011`

**Interfaces:**
- Produces: the rule text that makes `~/ZCodeProject` the default work dir for opencode sessions.

- [ ] **Step 1: Back up and append**

Run: `cp ~/.config/opencode/AGENTS.md ~/.config/opencode/AGENTS.md.bak-pre-workdir-rule-20261011`
Append a section titled `## Work directory: ~/ZCodeProject` stating: this dir is what the user calls "zcodeprojects" (`~/zcodeprojects` does not exist); default new projects, scratch scripts, and generated artifacts to `~/ZCodeProject/...`; use absolute paths; keep the existing pointer to `~/.zcode/AGENTS.md` intact.

- [ ] **Step 2: Verify**

Run: `sed -n '1,60p' ~/.config/opencode/AGENTS.md`
Expected: original pointer plus the new section, no other content changed (`diff` against the backup shows only additions).

- [ ] **Step 3: Commit (not a repo file)**

The file is user config outside the repo; no commit. Record the change in the final summary.

### Task 7: Final verification and report

- [ ] **Step 1: Re-run every verification command**

StarUML app present; five `.mdj` files parse/export; SVGs exist in `docs/diagrams/export/`; `opencode mcp list` lists both servers; mongo read returns the documented collections; the opencode rule is readable.

- [ ] **Step 2: Confirm the repo state**

Run: `cd ~/ZCodeProject/openclaw-job-search-kotlin && git log --oneline -6 && git status --porcelain`
Expected: the task commits, clean tree (exclude unrelated pre-existing files if any).

- [ ] **Step 3: Report**

List each deliverable with its path, the exact evidence command and result, plus what is still pending user action (WhatsApp phone pairing, StarUML license nag, any unverified `.mdj` opening).
