-- Normalization: the LLM pass that reads a job description and extracts its
-- seniority and its requirement signals.
--
-- Two changes land here:
--   1. fetched_jobs.normalize_status -- replaces is_normalized
--   2. normalized_jobs              -- durable: one row per normalized job
--
-- Normalization costs a model call per job, so it runs only for jobs within the
-- search radius. A job outside it is marked OUT_OF_RANGE once and never looked
-- at again; is_normalized could not express that, since "not yet" and "never"
-- were both false, and every false row was a candidate on every run.


---------------------------------------------------------------------------
-- 1. Pipeline state on fetched_jobs
---------------------------------------------------------------------------
ALTER TABLE fetched_jobs
    ADD COLUMN IF NOT EXISTS normalize_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE fetched_jobs
    ADD CONSTRAINT ck_fetched_jobs_normalize_status
        CHECK (normalize_status IN ('PENDING', 'NORMALIZED', 'OUT_OF_RANGE', 'FAILED'));

-- is_normalized is dropped rather than carried over. Nothing ever set it: no
-- normalization pass shipped, and a true value would name a job with no
-- normalized_jobs row behind it anyway. Every job starts PENDING.
DROP INDEX IF EXISTS idx_is_normalized;
ALTER TABLE fetched_jobs DROP COLUMN IF EXISTS is_normalized;

-- Serves the pass's page query (pending, located, in id order) and the
-- out-of-range sweep, which filters on the same two columns.
CREATE INDEX IF NOT EXISTS idx_fetched_jobs_normalize_status
    ON fetched_jobs (normalize_status, location_status, id);


---------------------------------------------------------------------------
-- 2. Normalized jobs
---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS normalized_jobs_seq
    START WITH 1
    INCREMENT BY 50; -- matches the Hibernate sequence allocation size used by fetched_jobs_seq

CREATE TABLE IF NOT EXISTS normalized_jobs (
    id              BIGINT      NOT NULL DEFAULT nextval('normalized_jobs_seq'),

    -- The surrogate key, as job_locations uses: (ats_name, job_id) stopped
    -- being unique in V2.
    fetched_job_id  BIGINT      NOT NULL,

    -- Its own column rather than a field in the JSON, so a search can filter
    -- on it with an index.
    seniority       VARCHAR(16) NOT NULL,

    -- List<Signal>: {"classification": ..., "text": ...} per requirement.
    signals         JSONB       NOT NULL,

    -- Which model and prompt produced this. Without both, the prompt could
    -- never be safely changed: nothing would tell old answers from new ones.
    model           VARCHAR(64) NOT NULL,
    prompt_version  INT         NOT NULL,

    normalized_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_normalized_jobs PRIMARY KEY (id),
    CONSTRAINT fk_normalized_jobs_fetched_job
        FOREIGN KEY (fetched_job_id) REFERENCES fetched_jobs (id) ON DELETE CASCADE,
    -- One current answer per job; normalizing again replaces it.
    CONSTRAINT uq_normalized_jobs_fetched_job UNIQUE (fetched_job_id),
    CONSTRAINT ck_normalized_jobs_seniority CHECK (seniority IN (
        'JUNIOR', 'MIDLEVEL', 'SENIOR', 'STAFF', 'PRINCIPAL', 'DISTINGUISHED'
    ))
);

CREATE INDEX IF NOT EXISTS idx_normalized_jobs_seniority
    ON normalized_jobs (seniority);
