# Mongo import: semantics, verification queries, and reconciliation

The `tracker-import` Job mirrors the live Python tracker (`$DATA/tracker.json`)
into MongoDB (`campaign.applications` / `.skips` / `.blockers`). This doc
records the import contract and the queries to reconcile Mongo against the
tracker, with outputs (condensed for readability) captured from the live k3s
deployment on 2026-10-20 (after commit `f4a0c8f`, tracker snapshot: 2185
entries, 2161 distinct).

## Import semantics

- **Canonical id** is `source:sourceJobId`; the legacy slug `id` field is a
  fallback only. Duplicate `applications[]` entries that canonicalize to the
  same id collapse deterministically before anything reaches Mongo:
  1. `SUBMITTED` outranks `ATTEMPTED` (a confirmed submission beats a later
     unconfirmed re-apply),
  2. then the later `appliedAt` wins (a re-apply supersedes the first try),
  3. then the earlier file position wins.
  The live tracker had 24 such duplicates; without the collapse, the
  unordered `bulkWrite` let an ATTEMPTED variant race in ahead of its
  SUBMITTED sibling on first insert.
- **Applications upsert repairs.** `applications` is tracker-owned state, so
  matched rows get `$set` on every tracker field; only `_id`, `createdAt`,
  `updatedAt` are `$setOnInsert`. A re-import of an unchanged tracker writes
  nothing (`updated=0`); an advanced tracker repairs stale rows
  (ATTEMPTED -> SUBMITTED, null -> real `appliedAt`).
- **Evidence is one-directional.** `confirmationUrl`, `confirmationText`,
  `evidence` are only written when the incoming value is non-null: a tracker
  variant without confirmation data never erases what an earlier import or
  the worker recorded.
- **Skips and blockers are insert-only** (append-only ledgers).
- **Status mapping:** `submitted` -> SUBMITTED; `attempted`,
  `attempted_no_confirmation`, missing, or unknown -> ATTEMPTED (warn on
  missing/unknown only). The pre-fix default of SUBMITTED for anything
  unrecognized inflated the KPI.
- **`appliedAt` parsing:** ISO-8601 with offset/Z as-is; zone-less datetimes
  and date-only strings read as UTC (the tracker writer emits aware-UTC
  everywhere else). Genuinely malformed strings (`+00001` offsets,
  `09-51-00Z`, `$(date ...)` shell mistakes) stay NULL with a warning.
- **KPI note.** `tracker.stats.submitted` (2000) counts submission *events*,
  including re-applies of the same job. Mongo counts *distinct jobs*. Both
  are correct for what they measure; `/api/v1/stats` reports distinct jobs.

## Re-run the import

```bash
bash deploy/rerun-import.sh        # delete + re-apply the tracker-import Job
kubectl -n campaign logs job/tracker-import | grep -A3 "import report"
```

First run after the fix (repairs applied), and the identical re-run
(idempotency proof):

```text
import report:
  applications: 0 inserted, 60 updated, 2101 unchanged (tracker applications[]: 2161 distinct, 24 duplicate entries collapsed)
  skips:        0 inserted, 494 already present (tracker skipped[]: 494)
  blockers:     0 inserted, 112 already present (tracker blockers[]: 112)

# second, identical run:
  applications: 0 inserted, 0 updated, 2161 unchanged (tracker applications[]: 2161 distinct, 24 duplicate entries collapsed)
```

Pipe the queries below into mongosh on the laptop (env vars come from the
mongo container):

```bash
kubectl -n campaign exec -i mongo-0 -c mongo -- sh -c \
  'mongosh -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
   --authenticationDatabase admin --quiet campaign' <<'JS'
// queries here
JS
```

## Query 1: status breakdown (tracker reconciliation)

```javascript
db.applications.aggregate([
  { $group: { _id: "$status", n: { $sum: 1 } } },
  { $sort: { _id: 1 } }
])
```

```text
ATTEMPTED: 179
SUBMITTED: 1983
```

1982 SUBMITTED come from the tracker's distinct best-per-id; +1 is a legacy
orphan (`justjoin.it:soflab-technology-java-developer-k-m-warszawa`, from a
pre-canonical id naming) that no tracker entry maps to any more. Imports
never delete, so it stays. Tracker events-based `stats.submitted=2000` is
higher by design (re-applies).

## Query 2: NULL appliedAt (malformed / missing in the tracker itself)

```javascript
db.applications.find(
  { appliedAt: null },
  { _id: 1, status: 1 }
).sort({ _id: 1 })
```

```text
Ideamotive:ideamotive-senior-java-developer-k-m-energetyka-wroclaw-java   [ATTEMPTED]
justjoinit:senior-java-developer-addepto-platform-modernization-2026-10-11 [SUBMITTED]
linkedin:4454240047                                                        [SUBMITTED]
nofluffjobs:senior-java-consultant-gft-poland-krakow-3                     [ATTEMPTED]
```

All four are NULL in the tracker too: one entry has no `appliedAt` at all,
three carry unparsable values (`'2026-10-11T17:07:00+00001'`,
`'2026-08-21T09-51-00Z'`, and the literal string `'2026-10-10T...'` - dots
included; the writer stored a truncation placeholder). They stay NULL by
contract; before the fix this bucket was 35 rows (parse failures plus stale
rows the `$setOnInsert`-only upsert could not repair - the re-import
repaired 31).

## Query 3: verify a repaired row (was stuck ATTEMPTED, tracker says SUBMITTED)

```javascript
db.applications.find(
  { _id: "nofluffjobs:senior-java-developer-addepto-remote" },
  { _id: 1, status: 1, appliedAt: 1, createdAt: 1, updatedAt: 1 }
)
```

```javascript
{
  _id:        'nofluffjobs:senior-java-developer-addepto-remote',
  status:     'SUBMITTED',
  appliedAt:  ISODate('2026-10-11T08:35:00.000Z'),
  createdAt:  ISODate('2026-10-18T20:37:24.793Z'),   // first import - untouched
  updatedAt:  ISODate('2026-10-18T20:37:24.793Z')    // Mongo-owned - untouched by repairs
}
```

Four rows were stuck this way (tracker advanced them to SUBMITTED after an
earlier import had inserted an ATTEMPTED variant); all four now mirror the
tracker.

## Query 4: why a duplicate pair resolved the way it did

```javascript
// two tracker entries share the canonical id nofluffjobs:senior-java-developer-ework-group-remote-5:
//   variant A: appliedAt = '$(date -u +%Y-%m-%dT%H:%M:%S.%6NZ)'   (shell mistake, unparsable)
//   variant B: appliedAt = '2026-10-06T04:11:00+00:00'            (real, later)
db.applications.find(
  { _id: "nofluffjobs:senior-java-developer-ework-group-remote-5" },
  { _id: 1, status: 1, appliedAt: 1, confirmationUrl: 1 }
)
```

```javascript
{
  _id:        'nofluffjobs:senior-java-developer-ework-group-remote-5',
  status:     'SUBMITTED',
  appliedAt:  ISODate('2026-10-06T04:11:00.000Z'),
  confirmationUrl: 'https://nofluffjobs.com/pl/job/senior-java-developer-ework-group-remote-5/success'
}
```

Both variants are SUBMITTED, so the later parsable `appliedAt` wins; the
unparsable sibling is dropped during collapse, not stored. (If the variants
had differed in status, SUBMITTED would have won regardless of timestamps.)

## Query 5: evidence survived the re-import

```javascript
db.applications.countDocuments({ confirmationUrl: { $nin: [null] } })  // 1040
db.applications.countDocuments({ evidence: { $exists: true, $ne: null } })  // 519
```

Counts are unchanged from before the repair-import: one-directional evidence
means an incoming null (e.g. from a collapsed variant without confirmation
data) never erases recorded proof.

## Query 6: totals

```javascript
db.applications.countDocuments({})                            // 2162
db.applications.countDocuments({ appliedAt: { $ne: null } })  // 2158
```

2162 = 2161 tracker-distinct + 1 legacy orphan. 2158 with a timestamp = the
4 rows whose tracker values are missing/malformed.

## Verification history

- 2026-10-20: reconciliation above captured on the live k3s deployment;
  `/api/v1/stats` reads `{"submitted":1983,"attempted":179,"target":2100,
  "remaining":117,...}`, matching the tracker-best view exactly.
- Test coverage: `TrackerParserTest` (17 tests) pins collapse order, status
  mapping, and timestamp parsing; `ImportWriterIT` (6 Testcontainers tests,
  needs Docker) pins repair, evidence protection, and rerun idempotency
  against a real MongoDB.
