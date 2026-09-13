package org.example.fitfetch.location;

import java.util.Objects;

/**
 * A cacheable geocode result: coordinates when the query resolved, and the
 * reason when it did not.
 *
 * <p>Every instance is safe to write to the cache by construction, because the
 * statuses that are not safe to cache cannot be represented here &mdash; they
 * arrive as a {@link GeocodingException} instead.
 *
 * @param status           why this outcome is what it is
 * @param latitude         decimal degrees; present if and only if
 *                         {@code status} is {@link GeocodeStatus#OK}
 * @param longitude        decimal degrees; same condition
 * @param formattedAddress Google's canonical rendering of the place, used for
 *                         display rather than matching
 * @param placeId          Google's stable identifier for the place. Worth
 *                         retaining separately from the coordinates: it is the
 *                         part of a result that does not go stale, and it is
 *                         how a coordinate refresh correlates back to the same
 *                         place
 * @param locationType     precision of the match, for example {@code ROOFTOP}
 *                         or {@code APPROXIMATE}
 * @param partialMatch     whether Google matched something other than what was
 *                         asked for, which is a signal the query was vague
 */
public record GeocodeOutcome(
        GeocodeStatus status,
        Double latitude,
        Double longitude,
        String formattedAddress,
        String placeId,
        String locationType,
        boolean partialMatch
) {

    public GeocodeOutcome {
        Objects.requireNonNull(status, "status");
        // Mirrors ck_geocode_cache_coords_match_status, so a malformed outcome
        // fails here rather than on insert.
        boolean hasCoordinates = latitude != null && longitude != null;
        if (hasCoordinates != status.hasCoordinates()) {
            throw new IllegalArgumentException(
                    "status " + status + " requires coordinates to be "
                            + (status.hasCoordinates() ? "present" : "absent"));
        }
        if (hasCoordinates && (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)) {
            // Catches a transposed pair for anything outside +/-90 longitude,
            // which includes the search origin at -122.
            throw new IllegalArgumentException(
                    "coordinates out of range: " + latitude + ", " + longitude);
        }
    }

    /**
     * @param status the non-OK reason
     * @return an outcome carrying no coordinates
     */
    public static GeocodeOutcome empty(GeocodeStatus status) {
        return new GeocodeOutcome(status, null, null, null, null, null, false);
    }
}
