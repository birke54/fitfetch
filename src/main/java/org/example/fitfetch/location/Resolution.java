package org.example.fitfetch.location;

/**
 * Why a {@link LocationInput} carries the coordinate it does.
 *
 * <p>Several resolutions deliberately share the same coordinate &mdash; the
 * configured search origin &mdash; because a job the user is eligible for
 * should always fall inside the search radius regardless of how the posting
 * phrased it. That makes the coordinate alone useless for telling a remote-US
 * role from a genuine local one, or either from a posting with no location data
 * at all. This enum is what preserves that distinction, and it is what display
 * code must read rather than the coordinate.
 *
 * <p>The constants are mirrored by the {@code ck_job_locations_resolution}
 * check constraint; adding one requires a migration.
 */
public enum Resolution {

    /** A genuine address or city. Rule 1. */
    PLACE,

    /** Bare {@code "Remote"} with no geographic qualifier. Rule 2. */
    REMOTE_BARE,

    /**
     * Remote within the United States, or within the configured home state.
     * Rules 3 (US branch) and 7 (home-state branch).
     */
    REMOTE_IN_US,

    /**
     * Remote somewhere the user is not: a non-US country, a non-home US state,
     * or a specific foreign city. Rules 3 and 7.
     */
    REMOTE_ELSEWHERE,

    /** Remote across a multi-country macro-region such as EMEA. Rule 3b. */
    REMOTE_REGION,

    /** A bare US country label with no remote marker. Rule 6. */
    COUNTRY_US,

    /** A bare non-US country label with no remote marker. Rule 6. */
    COUNTRY_OTHER,

    /** A bare non-home US state label with no remote marker. Rule 6b. */
    STATE_OTHER,

    /** Empty, {@code "N/A"}, or an unfilled template placeholder. Rule 4. */
    EMPTY_DEFAULT,

    /**
     * Nothing usable could be extracted, or the gazetteer rejected what was.
     * Rule 5. The only resolution without coordinates, and the only one that
     * never matches a radius search.
     */
    UNDEFINED;

    /**
     * Whether a row with this resolution carries coordinates.
     *
     * <p>Mirrors the {@code ck_job_locations_coords_match_resolution} check
     * constraint: every resolution but {@link #UNDEFINED} must have a
     * coordinate, and {@link #UNDEFINED} must not.
     *
     * @return {@code true} for every resolution except {@link #UNDEFINED}
     */
    public boolean hasCoordinates() {
        return this != UNDEFINED;
    }
}
