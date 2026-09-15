-- Job matches: how well each embedded job matches the candidate profile, and
-- why. One row per job, for the profile version, embedder and scoring rules
-- that produced it; a change to any of them makes the row stale, and the
-- matching pass scores the job again and replaces it.

CREATE SEQUENCE IF NOT EXISTS job_matches_seq
    START WITH 1
    INCREMENT BY 50; -- matches the Hibernate sequence allocation size used by fetched_jobs_seq

CREATE TABLE IF NOT EXISTS job_matches (
    id                  BIGINT       NOT NULL DEFAULT nextval('job_matches_seq'),
    normalized_job_id   BIGINT       NOT NULL,
    -- Carried for convenience: the title, company and link are on fetched_jobs.
    fetched_job_id      BIGINT       NOT NULL,

    -- 0 to 100. Computed for an excluded job too, to show what it would score.
    score               INT          NOT NULL,
    -- Whether every gate passed. Excluded jobs keep their reasons below.
    eligible            BOOLEAN      NOT NULL,
    -- ["requires a security clearance", ...]; empty when eligible.
    gate_failures       JSONB        NOT NULL,
    -- {"requirementCoverage", "skillMatch", "levelFit", "domainAdjustment"}
    parts               JSONB        NOT NULL,
    -- Per job signal: its coverage, its best bullets with their similarity, and
    -- its skills matched and missing. What a tailored resume picks bullets from.
    signals             JSONB        NOT NULL,

    profile_sha256      VARCHAR(64)  NOT NULL,
    embedder_key        VARCHAR(100) NOT NULL,
    scoring_version     INT          NOT NULL,
    scored_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_job_matches PRIMARY KEY (id),
    CONSTRAINT fk_job_matches_normalized_job
        FOREIGN KEY (normalized_job_id) REFERENCES normalized_jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_matches_fetched_job
        FOREIGN KEY (fetched_job_id) REFERENCES fetched_jobs (id) ON DELETE CASCADE,
    CONSTRAINT uq_job_matches_normalized_job UNIQUE (normalized_job_id),
    CONSTRAINT ck_job_matches_score CHECK (score BETWEEN 0 AND 100)
);

-- Serves listing the best eligible matches.
CREATE INDEX IF NOT EXISTS idx_job_matches_eligible_score
    ON job_matches (eligible, score);
