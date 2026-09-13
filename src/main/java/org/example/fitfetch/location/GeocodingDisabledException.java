package org.example.fitfetch.location;

/**
 * Signals a query that was not looked up because outbound geocoding is switched
 * off and the cache had no answer.
 *
 * <p>Deliberately not a {@link GeocodeStatus}. {@code ZERO_RESULTS} means the
 * geocoder was asked and knows the query names nowhere; this means nobody asked.
 * Reporting the two the same way would mark a job as unresolvable when it only
 * needs geocoding switched on, and it would stay that way after it was.
 *
 * <p>Retryable, so a caller that does not handle it specifically treats it like
 * any other transient failure and leaves the job pending.
 *
 * @see CachingGeocoder
 */
public class GeocodingDisabledException extends GeocodingException {

    /**
     * @param query the query that was not looked up
     */
    public GeocodingDisabledException(String query) {
        super("Geocoding is disabled and '" + query + "' is not cached", false);
    }
}
