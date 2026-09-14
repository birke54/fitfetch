package org.example.fitfetch.location;

import java.util.Objects;

/**
 * One resolved location for a job: what was said, what it was taken to mean, and
 * what should be geocoded as a result.
 *
 * <p>This is the output contract of the whole resolution step. Every tier
 * produces it &mdash; the hand-curated table, the interpretation cache and the
 * model &mdash; and it is what {@code job_locations} rows are built from.
 *
 * <p>One {@code location.name} field yields a list of these, never a single
 * value. A job attached to several places must keep all of them, or a role with
 * an office near the user gets filtered out because a different office happened
 * to be listed first.
 *
 * @param raw          the verbatim element this came from, never the whole
 *                     {@code location.name}. {@code "Dublin, London"} yields two
 *                     inputs, one per city. Retaining it is what makes a later
 *                     rule change replayable without re-calling anything
 * @param resolution   why {@code geocodeQuery} is what it is
 * @param geocodeQuery the string to geocode; {@code null} if and only if
 *                     {@code resolution} is {@link Resolution#UNDEFINED}
 * @param regionCode   ISO 3166-1 alpha-2, or 3166-2 for a subdivision
 *                     ({@code "US"}, {@code "GB"}, {@code "US-WA"}); {@code null}
 *                     when the region is not known without geocoding. Display
 *                     and debugging only &mdash; never matching
 * @param followsOrigin whether {@code geocodeQuery} is the search origin by
 *                     policy rather than a place the posting named. Such a
 *                     location sits wherever the origin is configured to be,
 *                     so a radius search matches it without reading its stored
 *                     coordinate, and reconfiguring the origin cannot strand it
 */
public record LocationInput(
        String raw,
        Resolution resolution,
        String geocodeQuery,
        String regionCode,
        boolean followsOrigin
) {

    /** Placeholder for the configured search origin, substituted at load time. */
    public static final String ORIGIN_TOKEN = "__ORIGIN__";

    public LocationInput {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(resolution, "resolution");
        // Mirrors ck_job_locations_coords_match_resolution. Enforced here so a
        // bad mapping fails at construction rather than on insert, hours later
        // inside a scheduled pass.
        boolean hasQuery = geocodeQuery != null && !geocodeQuery.isBlank();
        if (hasQuery != resolution.hasCoordinates()) {
            throw new IllegalArgumentException(
                    "resolution " + resolution + " requires geocodeQuery to be "
                            + (resolution.hasCoordinates() ? "present" : "absent")
                            + ", but was " + (hasQuery ? "'" + geocodeQuery + "'" : "absent"));
        }
        // Mirrors ck_job_locations_follows_origin_located.
        if (followsOrigin && !resolution.hasCoordinates()) {
            throw new IllegalArgumentException("resolution " + resolution + " cannot follow the origin");
        }
    }

    /**
     * A location that geocodes as itself rather than following the origin.
     *
     * @param raw          the verbatim element
     * @param resolution   why {@code geocodeQuery} is what it is
     * @param geocodeQuery the string to geocode
     * @param regionCode   ISO 3166 region, or {@code null}
     */
    public LocationInput(String raw, Resolution resolution, String geocodeQuery, String regionCode) {
        this(raw, resolution, geocodeQuery, regionCode, false);
    }

    /**
     * @param raw the verbatim element that could not be resolved
     * @return an input recording that nothing usable could be extracted
     */
    public static LocationInput undefined(String raw) {
        return new LocationInput(raw, Resolution.UNDEFINED, null, null);
    }

    /**
     * @return {@code true} if {@link #geocodeQuery()} is still the unsubstituted
     *         {@link #ORIGIN_TOKEN}, which must never reach the geocoder
     */
    public boolean isOriginToken() {
        return ORIGIN_TOKEN.equals(geocodeQuery);
    }

    /**
     * Returns a copy with {@link #ORIGIN_TOKEN} replaced by a concrete origin.
     *
     * <p>Keeping the token in the curated resource rather than a literal address
     * is what lets the origin be reconfigured without rewriting the table or
     * invalidating cached interpretations.
     *
     * @param origin the configured search origin
     * @return this input, or a copy with the token substituted and
     *         {@link #followsOrigin()} set
     */
    public LocationInput withOrigin(String origin) {
        return isOriginToken() ? new LocationInput(raw, resolution, origin, regionCode, true) : this;
    }
}
