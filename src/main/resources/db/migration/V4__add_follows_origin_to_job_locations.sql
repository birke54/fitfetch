-- Marks the job_locations rows whose coordinate is the search origin by policy
-- rather than by geography.
--
-- Several resolutions deliberately geocode to the configured origin: bare
-- "Remote", remote within the US or the home state, a bare US or home-state
-- label, and a posting with no location at all. Their stored coordinate is
-- whatever the origin was when the row was written, so if the origin were
-- reconfigured they would stay behind at the old address and fall out of every
-- radius search. The flag lets the search treat such a row as sitting wherever
-- the origin is now, and measure distance only for the rows that name a real
-- place. The coordinate is kept anyway: V3 requires it on every row but
-- UNDEFINED, and it still shows where the row was resolved to.
ALTER TABLE job_locations
    ADD COLUMN IF NOT EXISTS follows_origin BOOLEAN NOT NULL DEFAULT false;

-- An UNDEFINED row matches nothing; the flag must not let one back in.
ALTER TABLE job_locations
    ADD CONSTRAINT ck_job_locations_follows_origin_located
        CHECK (NOT follows_origin OR latitude IS NOT NULL);

-- Backfill rows written before the flag existed; a no-op on an empty table.
-- The four resolutions below always use the origin, in LocationPolicy and in
-- the curated table alike. A bare home-state label is stored as PLACE, so it is
-- found instead by sharing their geocode query, which avoids hard-coding the
-- origin address here.
UPDATE job_locations
SET follows_origin = true
WHERE latitude IS NOT NULL
  AND (resolution IN ('REMOTE_BARE', 'REMOTE_IN_US', 'COUNTRY_US', 'EMPTY_DEFAULT')
       OR geocode_query IN (SELECT geocode_query
                            FROM job_locations
                            WHERE resolution IN ('REMOTE_BARE', 'REMOTE_IN_US',
                                                 'COUNTRY_US', 'EMPTY_DEFAULT')));
