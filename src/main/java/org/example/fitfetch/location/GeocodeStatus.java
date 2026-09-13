package org.example.fitfetch.location;

/**
 * The outcomes of a geocode lookup that are worth remembering.
 *
 * <p>Deliberately narrower than the set of statuses Google returns. Only these
 * three say something durable about the query itself; everything else Google can
 * report &mdash; quota exhaustion, an unknown error, a rejected key &mdash; says
 * something about the moment the call was made, and is surfaced as a
 * {@link GeocodingException} rather than a status.
 *
 * <p>That narrowing is the point. Making a transient failure unrepresentable as
 * a status is what stops a quota blip from being written to the cache as "this
 * place does not exist", which would silently drop every job at that location
 * from then on.
 *
 * <p>These constants are mirrored by the {@code ck_geocode_cache_status} check
 * constraint.
 */
public enum GeocodeStatus {

    /** The query resolved to a place; coordinates are present. */
    OK,

    /**
     * The query was well-formed but names no place Google knows. A real answer,
     * cached negatively so the same dead string is not looked up every cycle.
     */
    ZERO_RESULTS,

    /**
     * The query was malformed. Also a real answer: a query that is not valid
     * today will not become valid on its own.
     */
    INVALID_REQUEST;

    /** @return {@code true} only for {@link #OK}, which is the only status carrying coordinates */
    public boolean hasCoordinates() {
        return this == OK;
    }
}
