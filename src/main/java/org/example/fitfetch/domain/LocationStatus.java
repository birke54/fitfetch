package org.example.fitfetch.domain;

/**
 * How far a fetched job has got through location resolution.
 *
 * <p>Separate from {@link NormalizeStatus} because the two passes fail in different
 * ways. Normalization is pure and local, so its failures are bugs that retrying
 * cannot fix. Location resolution depends on a local model and an external
 * geocoding API, whose failures are transient and where retrying is exactly the
 * right response. A single flag could not express both without conflating "needs
 * a code fix" with "try again in fifteen minutes".
 *
 * <p>These constants are mirrored by the {@code ck_fetched_jobs_location_status}
 * check constraint.
 */
public enum LocationStatus {

    /** Not yet resolved, or left to be retried after a transient failure. */
    PENDING,

    /** Resolved; {@code job_locations} holds at least one usable row. */
    RESOLVED,

    /**
     * Resolved to nothing usable, and retrying will not help.
     *
     * <p>Distinct from {@link #PENDING} on purpose: these rows are the curation
     * worklist. Grouping them by raw label, ordered by how many jobs sit behind
     * each, is what tells you which entry to add to the curated table next.
     */
    FAILED,

    /** Deliberately not resolved, for a provider that publishes no location. */
    SKIPPED
}
