-- 1. Add the company board slug column
ALTER TABLE fetched_jobs
    ADD COLUMN slug VARCHAR(255) NOT NULL;

-- 2. Widen the uniqueness guarantee to include slug: the same (ats_name, job_id)
-- pair may only appear once per board.
ALTER TABLE fetched_jobs
    DROP CONSTRAINT uq_fetched_jobs_ats_name_job_id;

ALTER TABLE fetched_jobs
    ADD CONSTRAINT uq_fetched_jobs_ats_name_job_id_slug UNIQUE (ats_name, job_id, slug);
