-- 1. Create the sequence for ID generation
CREATE SEQUENCE IF NOT EXISTS fetched_jobs_seq
    START WITH 1
    INCREMENT BY 50; -- Default Hibernate sequence allocation size

-- 2. Create the main table
CREATE TABLE IF NOT EXISTS fetched_jobs (
    id BIGINT NOT NULL DEFAULT nextval('fetched_jobs_seq'),
    ats_name VARCHAR(50) NOT NULL,
    job_id VARCHAR(255) NOT NULL,
    job_data JSONB NOT NULL,
    is_normalized BOOLEAN NOT NULL DEFAULT false,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_fetched_jobs PRIMARY KEY (id),

    -- Unique *constraint* (not just an index) on the compound (ats_name, job_id)
    -- pair, so it can be the target of job_locations' foreign key. PostgreSQL
    -- only lets a foreign key reference columns backed by a unique or primary
    -- key constraint.
    CONSTRAINT uq_fetched_jobs_ats_name_job_id UNIQUE (ats_name, job_id)
    );

-- 3. Create the performance optimization index for processing status
CREATE INDEX IF NOT EXISTS idx_is_normalized
    ON fetched_jobs (is_normalized);