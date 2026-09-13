package org.example.fitfetch.location;

import java.util.Objects;

/**
 * One fully resolved location: what the posting said, what it was taken to mean,
 * and where that is on a map.
 *
 * <p>The bridge between resolution and persistence &mdash; everything a
 * {@code job_locations} row needs, with nothing about the job itself.
 *
 * @param input   the resolved location, carrying the verbatim element and the
 *                reason behind it
 * @param outcome the geocode result, or {@code null} when
 *                {@link LocationInput#geocodeQuery()} was {@code null} and there
 *                was nothing to look up
 * @param tier    which tier of the pipeline answered the label
 * @param primary whether this is the location to lead with in display
 */
public record ResolvedLocation(
        LocationInput input,
        GeocodeOutcome outcome,
        SourceTier tier,
        boolean primary
) {

    public ResolvedLocation {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(tier, "tier");
    }

    /**
     * @return {@code true} if this location has coordinates and can therefore
     *         match a radius search. A row without them is still worth storing:
     *         it keeps the job visible in the curation worklist instead of
     *         letting it disappear silently
     */
    public boolean isMatchable() {
        return outcome != null && outcome.status().hasCoordinates();
    }
}
