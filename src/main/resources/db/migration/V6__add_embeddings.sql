-- Embeddings: vectors for job signals and profile bullets, compared to score
-- how well a job matches the candidate.
--
-- Vectors are BYTEA, four little-endian bytes per dimension, and compared in
-- the application. At this scale that is well under a second, so neither the
-- pgvector extension nor the Postgres image change it would need is required.


---------------------------------------------------------------------------
-- 1. The vector cache
---------------------------------------------------------------------------
-- Keyed by the model and the SHA-256 of the exact text embedded, prefix
-- included, not by what the text belongs to: the same text is embedded once,
-- and a restart re-embeds nothing. The text itself is not stored.
CREATE SEQUENCE IF NOT EXISTS embeddings_seq
    START WITH 1
    INCREMENT BY 50; -- matches the Hibernate sequence allocation size used by fetched_jobs_seq

CREATE TABLE IF NOT EXISTS embeddings (
    id              BIGINT      NOT NULL DEFAULT nextval('embeddings_seq'),
    model           VARCHAR(64) NOT NULL,
    input_sha256    VARCHAR(64) NOT NULL,
    dimensions      INT         NOT NULL,
    vector          BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_embeddings PRIMARY KEY (id),
    -- Also the index every lookup uses.
    CONSTRAINT uq_embeddings_model_input UNIQUE (model, input_sha256),
    CONSTRAINT ck_embeddings_vector CHECK (dimensions > 0 AND octet_length(vector) = dimensions * 4)
);


---------------------------------------------------------------------------
-- 2. Which normalized jobs have vectors
---------------------------------------------------------------------------
-- The key of the model and prefixes that embedded a job's signals; NULL until
-- then. Changing either makes every job's key stale, so each is embedded
-- again. A job normalized again gets a new row, NULL, and is embedded again.
ALTER TABLE normalized_jobs
    ADD COLUMN IF NOT EXISTS embedded_with VARCHAR(100);
