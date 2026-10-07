-- Campaign pipeline schema v1.
-- Ports the tracker.json / events.jsonl model to Postgres:
--   job_listings  : discovered listings (normalized, dedup-indexed)
--   apply_tasks   : pipeline queue (SKIP LOCKED claims, leases, retries)
--   applications  : submitted/attempted applications (tracker.applications[])
--   skips         : duplicate/salary/filter skips (tracker.skipped[])
--   blockers      : manual blocks, captcha etc. (tracker.blockers[])
--   events        : append-only ledger (events.jsonl)

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE job_listings (
    id              BIGSERIAL PRIMARY KEY,
    source          TEXT        NOT NULL,
    source_job_id   TEXT        NOT NULL,
    company         TEXT        NOT NULL,
    company_key     TEXT        NOT NULL,
    role_title      TEXT        NOT NULL,
    role_key        TEXT,
    url             TEXT        NOT NULL,
    region          TEXT        NOT NULL DEFAULT 'PL',
    remote_policy   TEXT,
    salary_min      NUMERIC(12, 2),
    salary_max      NUMERIC(12, 2),
    salary_currency TEXT,
    salary_basis    TEXT,
    stack           TEXT[],
    raw             JSONB,
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_listings_source_job UNIQUE (source, source_job_id)
);

CREATE INDEX idx_job_listings_company_key ON job_listings (company_key);
CREATE INDEX idx_job_listings_url         ON job_listings (url);

CREATE TABLE apply_tasks (
    id               BIGSERIAL PRIMARY KEY,
    listing_id       BIGINT      NOT NULL REFERENCES job_listings (id),
    state            TEXT        NOT NULL,
    priority         INTEGER     NOT NULL DEFAULT 0,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    max_attempts     INTEGER     NOT NULL DEFAULT 3,
    claimed_by       TEXT,
    claim_expires_at TIMESTAMPTZ,
    score            INTEGER,
    score_reason     TEXT,
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    state_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- At most one open task per listing.
CREATE UNIQUE INDEX uq_apply_tasks_active
    ON apply_tasks (listing_id)
    WHERE state IN ('DISCOVERED', 'QUEUED', 'CLAIMED');

CREATE INDEX idx_apply_tasks_state_priority ON apply_tasks (state, priority DESC, id);
CREATE INDEX idx_apply_tasks_reaper ON apply_tasks (claim_expires_at) WHERE state = 'CLAIMED';

CREATE TABLE applications (
    id               TEXT PRIMARY KEY,   -- 'source:sourceJobId'
    source           TEXT        NOT NULL,
    source_job_id    TEXT        NOT NULL,
    company          TEXT        NOT NULL,
    company_key      TEXT        NOT NULL,
    role_title       TEXT        NOT NULL,
    role_key         TEXT,
    url              TEXT,
    region           TEXT        NOT NULL DEFAULT 'PL',
    remote_policy    TEXT,
    salary           JSONB,
    stack            TEXT[],
    apply_method     TEXT,
    ats              TEXT,
    status           TEXT        NOT NULL,  -- SUBMITTED | ATTEMPTED (EnumType.STRING)
    confirmation_url TEXT,
    confirmation_text TEXT,
    evidence         JSONB,
    applied_at       TIMESTAMPTZ,
    follow_ups       JSONB,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_applications_company_key ON applications (company_key);
CREATE INDEX idx_applications_url         ON applications (url);
CREATE INDEX idx_applications_applied_at  ON applications (applied_at);
CREATE INDEX idx_applications_company_trgm ON applications USING gin (company_key gin_trgm_ops);

CREATE TABLE skips (
    id             BIGSERIAL PRIMARY KEY,
    reason         TEXT        NOT NULL,  -- DUPLICATE | SALARY | FILTER (EnumType.STRING)
    listing_id     BIGINT      REFERENCES job_listings (id),
    source         TEXT,
    source_job_id  TEXT,
    company        TEXT,
    company_key    TEXT,
    role_title     TEXT,
    role_key       TEXT,
    url            TEXT,
    region         TEXT,
    remote_policy  TEXT,
    salary         JSONB,
    stack          TEXT[],
    detail         TEXT,
    blocked_repeat BOOLEAN     NOT NULL DEFAULT FALSE,
    block_count    INTEGER,
    at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_skips_company_key ON skips (company_key);
CREATE INDEX idx_skips_url         ON skips (url);

CREATE TABLE blockers (
    id            BIGSERIAL PRIMARY KEY,
    source        TEXT,
    source_job_id TEXT,
    company       TEXT,
    company_key   TEXT,
    role_title    TEXT,
    url           TEXT,
    reason        TEXT NOT NULL,     -- captcha | portal-block | ...
    resolved      BOOLEAN NOT NULL DEFAULT FALSE,
    detail        TEXT,
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_blockers_company_key ON blockers (company_key);

CREATE TABLE events (
    seq    BIGSERIAL PRIMARY KEY,
    at     TIMESTAMPTZ NOT NULL,
    action TEXT        NOT NULL,
    record JSONB       NOT NULL
);

CREATE INDEX idx_events_action_at ON events (action, at);
