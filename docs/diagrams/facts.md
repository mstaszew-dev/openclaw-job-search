# Live facts for the diagrams (collected 2026-10-11)

Evidence commands are in the plan (Task 2). Secrets are redacted: MongoDB auth comes from
secret `campaign-mongo`; no password, keyfile, or provider key appears in this file.

## Access

| What | Value |
|---|---|
| Laptop tailnet | `100.94.153.31` (`mstro-travelmate-p215-52`), MagicDNS `mstro-travelmate-p215-52.taila0a683.ts.net` |
| Laptop LAN | `192.168.18.13` |
| SSH (host) | `ssh -p 2222 mstro@100.94.153.31`, password `{ in local vault }`, passwordless sudo |
| SSH (pod `lubuntu-agent`) | `ssh -p 30022 agent@100.94.153.31`, password `{ in local vault }` |
| Mac dev | `michaels-macbook-air` `100.125.162.30`, runs opencode + Playwright MCP |

Host sshd does not auto-start after a reboot (per `deploy/docs/KOTLIN_DEPLOY.md`).

## Host

| Fact | Value |
|---|---|
| OS | Ubuntu 22.04.5 LTS, kernel 6.8.0-138-generic (amd64) |
| CPU / RAM / disk | 4 vCPU / 7759 MB / 234 GB (81% used, 43 GB free) |
| Container runtime | containerd 2.3.4-k3s1.36, docker (host) for image builds |
| k3s | `v1.36.5+k3s1`, single node, control-plane role, uptime 8d |
| Registry | none: `docker build` -> `docker save | k3s ctr images import`, tags `:v1` / `:k3s-headless-v1` |

## k3s namespaces and workloads

Namespace `campaign`:

| Kind | Name | Image | Notes |
|---|---|---|---|
| Deployment | `finder` | `localhost:5000/campaign-finder:v1` | discovery + dedupe + eligibility + CV scoring + produce |
| Deployment | `apply-worker` | `localhost:5000/campaign-worker:v1` | Kafka listener, agent loop, verifier, recorder |
| Deployment | `campaign-api` | `localhost:5000/campaign-api:v1` | REST: stats, jobs, applications |
| Deployment | `msrouter` | `localhost:5000/msrouter:k3s-headless-v1` | **PAUSED: 0/0 replicas since 2026-10-08** |
| StatefulSet | `kafka` | `apache/kafka:3.7.1` | KRaft, single broker, heap 512 MB |
| StatefulSet | `mongo` | `mongo:7` | replica set `rs0`, auth, WiredTiger cache 256 MB |

Namespace `default`:

| Kind | Name | Notes |
|---|---|---|
| Pod | `lubuntu-agent` | Chrome (Xvfb :99) + VNC + CDP + sshd + own tailscaled (`NeedsLogin`) |
| Service | `lubuntu-agent` | ClusterIP: 22, 3000, 5900, 8790, 9222 |
| Service | `lubuntu-agent-nodeport` | NodePort: 22:30022, 3000:30300, 5900:30590, 9222:30922 |

## Services and endpoints (ns `campaign`)

| Service | Type | ClusterIP | Port | Endpoint |
|---|---|---|---|---|
| `campaign-api` | NodePort | 10.43.133.150 | 8080:30080 | 10.42.0.101:8080 |
| `finder` | ClusterIP | 10.43.113.127 | 8081 | 10.42.0.105:8081 |
| `kafka` | ClusterIP | 10.43.155.28 | 9092 | 10.42.0.96:9092 |
| `mongo` | ClusterIP | 10.43.33.113 | 27017 | 10.42.0.97:27017 |
| `msrouter` | ClusterIP | 10.43.192.13 | 3000 | none (paused) |
| `postgres` | ClusterIP | 10.43.48.53 | 5432 | none (retired) |

Host data (hostPath, owned by host user `mstro`): `/home/mstro/k3s-pod-data/{mongodb,kafka,agent-home,agent-state,playwright-output,tailscale,usr-local}`.

## Kafka

| Topic | Partitions | Key | Purpose |
|---|---|---|---|
| `campaign.jobs` | 3 | `companyKey` | the work queue |
| `campaign.jobs.dlq` | 1 | `companyKey` | poison messages past `maxAttempts` |
| `__consumer_offsets` | 50 | - | broker-internal, compacted |

Consumer group `apply-workers`, lag observed **0** on 2026-10-11:
partition 0 offset 6531, partition 1 no committed offset (empty), partition 2 offset 12492.

## MongoDB (replica set `rs0`, database `campaign`)

`db.hello().isWritablePrimary` = **true**; `rs.status()` members: `mongo.campaign.svc.cluster.local:27017` = PRIMARY.

| Collection | Count (2026-10-11) | Indexes (name: keys, unique) |
|---|---|---|
| `applications` | 1845 | `_id_` (`_id`); `idx_applications_company_key` (`companyKey`); `idx_applications_url` (`url`); `idx_applications_applied_at` (`appliedAt`); `idx_applications_company_role` (`companyKey`,`roleKey`) |
| `job_listings` | 507 | `_id_`; `uq_job_listings_source_job` (`source`,`sourceJobId`) **unique**; `idx_job_listings_company_key`; `idx_job_listings_url` |
| `skips` | 654 | `_id_`; `idx_skips_company_key`; `idx_skips_url`; `idx_skips_reason_company` (`reason`,`companyKey`); `idx_skips_reason_url` (`reason`,`url`); `uq_skips_dedupe` (`reason`,`source`,`sourceJobId`,`companyKey`,`url`,`at`) |
| `blockers` | 102 | `_id_`; `idx_blockers_company_key`; `uq_blockers_dedupe` (`companyKey`,`reason`,`at`) |
| `events` | 40724 | `_id_`; `idx_events_at` (`at`); `idx_events_action_at` (`action`,`at`) |
| `job_state` | 94 | `_id_`; `idx_job_state_status` (`status`) |
| `import_runs` | 1 | `_id_` (importer bookkeeping; not in the Obsidian note) |

`_id` of `applications` is the assigned String `source:sourceJobId` (uniqueness carried by `_id_`).
Indexes named `uq_*` are not flagged `unique` in the live metadata except `uq_job_listings_source_job`.

## msrouter providers

`EXTRA_PROVIDER_ORDER` (src/providers/extras.ts:22) = `unorouter, groq, sambanova, mistral, gemini, cline, cloudflare`.
`Gemini` is the GCP slot: `GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai`,
`GEMINI_MODEL` default `gemini-2.5-flash-lite`, env `GEMINI_API_KEY` (free tier, added 2026-10-11).

Pause switch (must move together): `msrouter.yaml` `spec.replicas: 0` and `finder.yaml`
`FINDER_SCORING_ENABLED=false` -> `CvScorer` returns fallback score 70, the `score >= 60` CV filter
stops rejecting, `APPLY_MODE` stays `shadow`.

## Reference flow (from the user's diagram) and its mapping

```
External Sources (job boards, aggregators, company sites)
   -> Discovery & Deduplication Agent   [normalize schema, similarity/hash dedupe]
   -> KAFKA topic `validated_job_offers`
   -> Worker agents: skill/tech-stack extractor, salary/benefit estimator, company/culture profiler
   -> Job Search DB / UI
```

| Reference element | Deployed element |
|---|---|
| Discovery & Deduplication Agent | `finder` (collect, dedupe, eligibility, CV scoring, produce) |
| Kafka topic `validated_job_offers` | `campaign.jobs` (3 partitions, key `companyKey`) |
| Worker agents (3 specialists) | `apply-worker` pool, one consumer group `apply-workers` (parallelism is by partition, not by specialty) |
| Job Search DB / UI | `mongo-0` (7 collections) + `campaign-api` (`/stats /jobs /applications`, NodePort 30080) |
| External sources | NoFluffJobs / JustJoin.it / theProtocol.it (PL only, fully remote, B2B >= 15 000 PLN net+VAT) |
| Poison-message path | `campaign.jobs.dlq` -> `job_state.status=DEAD` |
| Browser action (not in the reference flow) | Chrome in `lubuntu-agent` reached over CDP at node IP `:30922` |
