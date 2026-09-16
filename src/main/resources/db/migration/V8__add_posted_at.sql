-- Posting age: the normalization pass skips a job the ATS put up more than
-- app.normalize.max-age-days ago, so a model call is spent on jobs still worth
-- applying to.
--
-- Two changes land here:
--   1. fetched_jobs.posted_at         -- promoted out of the job_data JSON
--   2. normalize_status 'TOO_OLD'     -- the deliberate skip, like OUT_OF_RANGE


---------------------------------------------------------------------------
-- 1. posted_at
---------------------------------------------------------------------------
-- Promoted to a column rather than read from job_data on every sweep, because
-- the JSON expression cannot be indexed: casting text to timestamptz is only
-- STABLE (it can depend on the TimeZone setting) and PostgreSQL will not build
-- an index on a non-immutable expression.
--
-- Nullable, and deliberately not backfilled here. Rows already fetched are
-- filled by hand, so the one statement that reads every job_data payload in
-- the table can be run and checked when someone is watching, rather than
-- inside a deploy. AtsFetchService always sets the column, so every row
-- inserted from now on has it.
--
-- Until that backfill runs, a row with a null posted_at matches neither the
-- page read (posted_at >= cutoff) nor the age sweep (posted_at < cutoff), so
-- jobs already fetched are passed over rather than normalized or marked. They
-- come back on their own once the column is filled; nothing is lost.
--
-- The NOT NULL that belongs on this column is applied by hand after the
-- backfill, for the same reason. See the backfill script.
ALTER TABLE fetched_jobs
    ADD COLUMN IF NOT EXISTS posted_at TIMESTAMPTZ;

-- Serves the too-old sweep, which filters on normalize_status and orders
-- nothing. The existing idx_fetched_jobs_normalize_status still serves the
-- page read, which pages by id.
CREATE INDEX IF NOT EXISTS idx_fetched_jobs_normalize_status_posted_at
    ON fetched_jobs (normalize_status, posted_at);


---------------------------------------------------------------------------
-- 2. The TOO_OLD status
---------------------------------------------------------------------------
-- The check constraint from V5 lists its values, so a new one has to be named
-- here or every write of it fails at runtime.
ALTER TABLE fetched_jobs
    DROP CONSTRAINT IF EXISTS ck_fetched_jobs_normalize_status;

ALTER TABLE fetched_jobs
    ADD CONSTRAINT ck_fetched_jobs_normalize_status
        CHECK (normalize_status IN ('PENDING', 'NORMALIZED', 'OUT_OF_RANGE', 'TOO_OLD', 'FAILED'));
