package org.example.fitfetch.location;

/**
 * Resolves a location query to coordinates.
 *
 * <p>Implementations own one lookup and nothing else: no caching, no batching,
 * no rate limiting. Those belong to the layer above, which is what lets the
 * transport be swapped or mocked without touching the policy that decides when a
 * lookup is worth making.
 *
 * @see GeocodeOutcome
 * @see GeocodingException
 */
public interface Geocoder {

    /**
     * Looks up a single query.
     *
     * @param query the location to resolve; must not be {@code null} or blank
     * @return a cacheable outcome, whether or not the query resolved
     * @throws GeocodingException if the lookup failed for reasons unrelated to
     *         the query, in which case nothing may be cached
     */
    GeocodeOutcome geocode(String query);
}
