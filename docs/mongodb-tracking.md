# MongoDB tracking store (jobsearcher campaign, k3s on the lubuntu laptop)

Documented 2026-10-11 from the live cluster, not from memory. Facts and raw evidence: `facts.md`.
Auth comes from secret `campaign-mongo`; no credential value is written in this file.

## What it is

`mongo-0` is the bookkeeping store for the Kotlin job pipeline. One line rules the design:

> **Kafka owns delivery, Mongo owns bookkeeping.**

The broker decides what work exists and who gets it (`campaign.jobs`, consumer group
`apply-workers`). Mongo records what happened after the fact and is never read to decide delivery:
no claim, no lease, no heartbeat, no reaper. Nothing in the ledger can strand a job.

## Deployment

| Fact | Value |
|---|---|
| Workload | StatefulSet `mongo` in namespace `campaign`, image `mongo:7` |
| Replica set | `rs0`, single member `mongo.campaign.svc.cluster.local:27017` = PRIMARY |
| Auth | keyfile + user `campaign`, secret `campaign-mongo` (`keyfile`, `password`) |
| Data | hostPath `/home/mstro/k3s-pod-data/mongodb` (host user `mstro`) |
| Memory | WiredTiger cache 256 MB |
| Service | ClusterIP `mongo:27017` (no NodePort; reachable only inside the cluster or via port-forward) |
| Readiness | exec `db.hello().isWritablePrimary`, not a port check |
| Init | `rs.initiate()` runs once from a Job; `mongo-init` |

Why a replica set on one node: multi-document transactions require it, and change streams stay
available for later work.

## Collections (live, 2026-10-11)

| Collection | Docs | Job | Keys and indexes |
|---|---|---|---|
| `job_listings` | 507 | discovered jobs | unique `uq_job_listings_source_job` (`source`,`sourceJobId`); `idx_job_listings_company_key`; `idx_job_listings_url` |
| `applications` | 1845 | submitted/attempted applications | `_id` = `source:sourceJobId` (uniqueness via `_id_`); `idx_applications_company_key`; `idx_applications_url`; `idx_applications_applied_at`; `idx_applications_company_role` (`companyKey`,`roleKey`) |
| `skips` | 654 | duplicate / salary / filter skips | `idx_skips_company_key`; `idx_skips_url`; `idx_skips_reason_company` (`reason`,`companyKey`); `idx_skips_reason_url`; `uq_skips_dedupe` (`reason`,`source`,`sourceJobId`,`companyKey`,`url`,`at`) |
| `blockers` | 102 | captcha and hard portal blocks | `idx_blockers_company_key`; `uq_blockers_dedupe` (`companyKey`,`reason`,`at`) |
| `events` | 40724 | append-only ledger, mirrors `events.jsonl` | `idx_events_at` (`at`); `idx_events_action_at` (`action`,`at`) |
| `job_state` | 94 | per-message bookkeeping: attempts, outcome, last error | `idx_job_state_status` (`status`) |
| `import_runs` | 1 | importer bookkeeping (one row per `tracker.json` import) | `_id_` |

Notes the schema does not advertise:

- Index names prefixed `uq_` are **not** flagged unique in live metadata except
  `uq_job_listings_source_job`. Uniqueness for the packed dedupe tuples is enforced by the write path
  (insert-if-absent), not by a MongoDB constraint. Do not assume a `uq_*` name means a unique index.
- `salary`, `evidence`, `followUps`, `raw`, `record` are real nested BSON documents, so `salary.min` is
  a queryable path. The tracker's `salarySeen` is either a bare string or an object; the converter maps both.
- `IndexBootstrap` creates every index at startup as data (an integration test asserts the exact list);
  there is no Flyway or `ddl-auto`.

## Transaction boundaries

Transactions exist only for bookkeeping; single-document writes stay outside on purpose.

| Boundary | Writes |
|---|---|
| `Recorder.recordSubmitted` | application insert-if-absent + event + `job_state` |
| `Recorder.recordSkippedDuplicate` / `recordBlocked` | skip or blocker + event + `job_state` |
| `WorkerLoop.markTerminal` / `failJob` | `job_state` outcome |

Double-apply protection, in order:

1. `applications._id` is the assigned `source:sourceJobId`.
2. The worker re-checks dedupe after consuming, **before** opening the browser: a redelivered message
   declines the action, not just the bookkeeping.
3. Writes are insert-if-absent (`$setOnInsert` upsert); `upsertedId != null` means "I created it", so a
   redelivery never overwrites the first outcome.
4. The Kafka offset commits only after the Mongo writes succeed.

Dedupe order: id, then company, then url, against `applications` plus duplicate `skips`. Sentinel keys
(`confidential`, `anonymous`, `חברה-חסויה`) never count as a company match. A blank url contributes no
branch (it would match every url-less application ever stored). `blockedRepeat` rows count as
duplicates; sentinel-key blocks are listing-scoped.

## Tracking queries

Run from the Mac (password is read from the secret on the laptop, never stored):

```bash
sshpass -p "$LAPTOP_PW" ssh -p 2222 mstro@100.94.153.31 '
  PW=$(sudo k3s kubectl -n campaign get secret campaign-mongo -o jsonpath="{.data.password}" | base64 -d)
  sudo k3s kubectl -n campaign exec mongo-0 -- mongosh \
    "mongodb://campaign:$PW@localhost:27017/campaign?authSource=admin" --quiet --eval "<JS>"
'
```

| Question | JS |
|---|---|
| health | `db.hello().isWritablePrimary` |
| stats | `printjson({submitted: db.applications.countDocuments(), skips: db.skips.countDocuments(), blockers: db.blockers.countDocuments(), events: db.events.countDocuments(), dead: db.job_state.countDocuments({status:"DEAD"})})` |
| in-flight | `db.job_state.countDocuments({status:"IN_FLIGHT"})` |
| recent attempts | `db.job_state.find({}).sort({attempts:-1}).limit(5).toArray()` |
| recent applications | `db.applications.find({}, {companyKey:1, roleKey:1, appliedAt:1}).sort({appliedAt:-1}).limit(10).toArray()` |
| stage counts | `db.job_state.aggregate([{$group:{_id:"$status",n:{$sum:1}}}]).toArray()` |
| event tail | `db.events.find({}).sort({at:-1}).limit(10).toArray()` |

HTTP equivalents on the node (NodePort 30080): `/api/v1/stats`, `/api/v1/jobs`, `/api/v1/applications`.

## Ops

- A `mongod` pod that is Ready but serves writes to nobody usually means the replica set never got a
  primary: check `job/mongo-init` logs (`rs.initiate` + a `db.hello()` wait). A URI handed back at
  INITIATING state is a documented failure mode.
- "Port 27017 is open" is not readiness; use `db.hello().isWritablePrimary`.
- Import the live Python tracker (idempotent): `deploy/rerun-import.sh`, or delete/re-apply
  `job tracker-import` with `deploy/k8s/tracker-import.yaml`. Result rows land in `import_runs`.
- Postgres data in `/home/mstro/k3s-pod-data/postgres` is retired; delete only after the M8 baseline
  verification passes.
- No backup job exists in the cluster. A logical dump for safekeeping:

```bash
sudo k3s kubectl -n campaign exec mongo-0 -- mongodump \
  --uri="mongodb://campaign:$PW@localhost:27017/campaign?authSource=admin" \
  --archive=/tmp/campaign-$(date +%F).archive.gz --gzip
sudo k3s kubectl -n campaign cp mongo-0:/tmp/campaign-$(date +%F).archive.gz ./campaign-dump.archive.gz
```

## Values observed (baseline)

2026-10-11: `applications` 1845, `skips` 654, `blockers` 102, `events` 40724, `job_listings` 507,
`job_state` 94, `import_runs` 1. Kafka `apply-workers` lag 0 (offsets 6531 / none / 12492 on the three
partitions). One-shot import reference from bring-up (2026-10-08): 1859 applications counting
in-tracker id collisions, 273 unique skips, 102 blockers, 2691 events.
