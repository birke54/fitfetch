-- Location resolution pipeline: turns a job's free-text `location.name` into
-- coordinates a radius search can use.
--
-- Three tables land here:
--   1. location_interpretation -- cache: raw string -> resolved LocationInputs
--   2. geocode_cache           -- cache: query string -> coordinates
--   3. job_locations           -- durable: one row per resolved location per job
--
-- The two caches are bounded and LRU-pruned, so job_locations deliberately owns
-- its own copy of every coordinate rather than referencing geocode_cache. A
-- foreign key into a prunable table would either block the prune or null out
-- coordinates on live jobs.


---------------------------------------------------------------------------
-- 1. Interpretation cache
---------------------------------------------------------------------------
-- Populated ONLY by the LLM. Strings answered by the hand-curated lookup table
-- never reach this table, so its contents are exactly the interpretations that
-- cost something to produce.
CREATE TABLE IF NOT EXISTS location_interpretation (
    -- Normalized location.name. TEXT rather than VARCHAR(255) because real
    -- payloads exceed 255: the longest observed is a 261-character Datadog
    -- string enumerating remote eligibility state by state. Median is 22, so
    -- this is a pure tail case -- exactly the kind that survives testing.
    location_key   TEXT        NOT NULL,
    raw            TEXT        NOT NULL,
    -- List<LocationInput>: one raw string can resolve to several locations.
    outputs        JSONB       NOT NULL,
    -- Which model and prompt produced this. Without both, the prompt can never
    -- be safely changed: nothing would distinguish stale interpretations from
    -- current ones. A prompt_version bump is handled lazily -- lower-version
    -- rows are treated as cache misses on read, never bulk-deleted.
    model          VARCHAR(64) NOT NULL,
    prompt_version INT         NOT NULL,
    interpreted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_hit_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hit_count      BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_location_interpretation PRIMARY KEY (location_key)
);

-- Eviction order for the LRU prune: keep the most recently used N rows.
CREATE INDEX IF NOT EXISTS idx_location_interpretation_lru
    ON location_interpretation (last_hit_at DESC, hit_count DESC);


---------------------------------------------------------------------------
-- 2. Geocode cache
---------------------------------------------------------------------------
-- Flat by design. The curated lookup table collapses many spellings onto one
-- query before anything reaches here (`New York, New York, USA`, `New York, NY`,
-- `New York` and `NYC` all become one key), so the place-level de-duplication a
-- second table would provide has already happened upstream.
CREATE TABLE IF NOT EXISTS geocode_cache (
    -- Post-split single locations only; comfortably inside 255.
    query_key         VARCHAR(255)     NOT NULL,
    -- NULL unless status = 'OK'.
    latitude          DOUBLE PRECISION,
    longitude         DOUBLE PRECISION,
    formatted_address TEXT,
    -- Google's stable place identifier, retained for refresh correlation.
    place_id          VARCHAR(255),
    location_type     VARCHAR(32),
    partial_match     BOOLEAN,
    -- Only authoritative answers are ever cached. ZERO_RESULTS and
    -- INVALID_REQUEST are real answers and get stored as negative entries;
    -- OVER_QUERY_LIMIT, UNKNOWN_ERROR and REQUEST_DENIED are transient or fatal
    -- and must NOT be, or a quota blip becomes a permanent "no such place".
    status            VARCHAR(24)      NOT NULL,
    resolved_at       TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Coordinates are refreshed on access once older than the configured TTL.
    refreshed_at      TIMESTAMPTZ,
    last_hit_at       TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hit_count         BIGINT           NOT NULL DEFAULT 0,

    CONSTRAINT pk_geocode_cache PRIMARY KEY (query_key),
    CONSTRAINT ck_geocode_cache_status
        CHECK (status IN ('OK', 'ZERO_RESULTS', 'INVALID_REQUEST')),
    -- A successful lookup must carry coordinates; a negative one must not.
    CONSTRAINT ck_geocode_cache_coords_match_status
        CHECK ((status = 'OK') = (latitude IS NOT NULL AND longitude IS NOT NULL)),
    CONSTRAINT ck_geocode_cache_lat_range
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_geocode_cache_lon_range
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE INDEX IF NOT EXISTS idx_geocode_cache_lru
    ON geocode_cache (last_hit_at DESC, hit_count DESC);

-- Negative entries are pruned on their own short TTL, ahead of the LRU trim:
-- they are cheap to rebuild and should not consume the LRU budget.
CREATE INDEX IF NOT EXISTS idx_geocode_cache_negative
    ON geocode_cache (status, resolved_at)
    WHERE status <> 'OK';


---------------------------------------------------------------------------
-- 3. Resolved job locations
---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS job_locations_seq
    START WITH 1
    INCREMENT BY 50; -- matches the Hibernate sequence allocation size used by fetched_jobs_seq

CREATE TABLE IF NOT EXISTS job_locations (
    id                BIGINT       NOT NULL DEFAULT nextval('job_locations_seq'),

    -- References fetched_jobs(id), NOT the (ats_name, job_id) pair. The comment
    -- in V1 predates V2, which dropped that two-column unique constraint and
    -- replaced it with (ats_name, job_id, slug); the surrogate key is the stable
    -- target. V1 is left untouched so its Flyway checksum stays valid.
    fetched_job_id    BIGINT       NOT NULL,

    -- The verbatim ELEMENT this row came from, not the whole location.name.
    -- "Dublin, London" yields two rows, raw='Dublin' and raw='London'. Keeping
    -- it means a rule change can be replayed over stored rows without
    -- re-calling either the LLM or Google.
    raw               TEXT         NOT NULL,

    -- Why this row has the coordinate it has. Several resolutions share the
    -- same coordinate (the search origin), so the coordinate alone cannot tell
    -- a remote-US job from a genuine local one or from a parse failure.
    resolution        VARCHAR(24)  NOT NULL,

    -- What was actually sent to the geocoder; NULL only for UNDEFINED.
    geocode_query     VARCHAR(255),

    -- NULL only for UNDEFINED, which never matches a radius search.
    latitude          DOUBLE PRECISION,
    longitude         DOUBLE PRECISION,
    formatted_address TEXT,
    place_id          VARCHAR(255),
    -- ISO 3166-1 alpha-2, or 3166-2 for a subdivision (e.g. 'US', 'GB', 'US-WA').
    region_code       VARCHAR(8),

    -- Drives display ordering only ("Seattle, WA + 2 more"), never filtering.
    is_primary        BOOLEAN      NOT NULL DEFAULT false,

    -- Provenance: whether this row came from the hand-curated table, a cached
    -- interpretation, or a fresh model call. Cheap, and the first thing worth
    -- knowing when auditing a surprising match.
    source_tier       VARCHAR(16)  NOT NULL,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_job_locations PRIMARY KEY (id),
    CONSTRAINT fk_job_locations_fetched_job
        FOREIGN KEY (fetched_job_id) REFERENCES fetched_jobs (id) ON DELETE CASCADE,
    CONSTRAINT uq_job_locations_job_raw_resolution
        UNIQUE (fetched_job_id, raw, resolution),

    CONSTRAINT ck_job_locations_resolution CHECK (resolution IN (
        'PLACE',
        'REMOTE_BARE',
        'REMOTE_IN_US',
        'REMOTE_ELSEWHERE',
        'REMOTE_REGION',
        'COUNTRY_US',
        'COUNTRY_OTHER',
        'STATE_OTHER',
        'EMPTY_DEFAULT',
        'UNDEFINED'
    )),
    CONSTRAINT ck_job_locations_source_tier
        CHECK (source_tier IN ('CURATED', 'INTERPRETATION', 'LLM')),
    -- Only UNDEFINED may lack a coordinate, and it must lack one.
    CONSTRAINT ck_job_locations_coords_match_resolution
        CHECK ((resolution <> 'UNDEFINED')
               = (latitude IS NOT NULL AND longitude IS NOT NULL)),
    -- Also catches transposed lat/lon for any coordinate whose longitude falls
    -- outside +/-90, which includes the search origin at -122.
    CONSTRAINT ck_job_locations_lat_range
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_job_locations_lon_range
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

-- Loading every location for a job, and the foreign key's own lookups.
CREATE INDEX IF NOT EXISTS idx_job_locations_fetched_job
    ON job_locations (fetched_job_id);

-- Bounding-box prefilter for radius search. Partial, because UNDEFINED rows can
-- never match and would only bloat it.
CREATE INDEX IF NOT EXISTS idx_job_locations_lat_lon
    ON job_locations (latitude, longitude)
    WHERE latitude IS NOT NULL;

-- Supports the curation worklist: which raw strings are still unresolved.
CREATE INDEX IF NOT EXISTS idx_job_locations_resolution
    ON job_locations (resolution);


---------------------------------------------------------------------------
-- 4. Pipeline state on fetched_jobs
---------------------------------------------------------------------------
-- Location resolution runs as its own scheduled pass, separate from
-- normalization: it depends on two external services (a local LLM and the
-- Google Geocoding API) whose failures are transient and retryable, whereas a
-- normalization failure is a bug. One is_normalized flag cannot express both,
-- and the two passes are siblings rather than stages -- location resolution
-- reads job_data->'location' and needs nothing normalization produces.
ALTER TABLE fetched_jobs
    ADD COLUMN IF NOT EXISTS location_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE fetched_jobs
    ADD CONSTRAINT ck_fetched_jobs_location_status
        CHECK (location_status IN ('PENDING', 'RESOLVED', 'FAILED', 'SKIPPED'));

CREATE INDEX IF NOT EXISTS idx_fetched_jobs_location_status
    ON fetched_jobs (location_status);
