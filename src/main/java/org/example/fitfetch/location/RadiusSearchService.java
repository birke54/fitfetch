package org.example.fitfetch.location;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finds jobs with a location within a given distance of the search origin.
 *
 * <p>The origin's coordinates are looked up on every search rather than stored
 * anywhere, so the search always runs from the origin as it is configured now.
 * The lookup goes through the caching geocoder, where the origin is one of the
 * hottest entries, so it is a primary-key read rather than a Google call.
 *
 * <p>The search is only ever centred on the origin. Rows that
 * {@link org.example.fitfetch.domain.JobLocation#followsOrigin() follow the
 * origin} match any radius, which is right around the origin and wrong around
 * anywhere else: a remote-US role is not "within 50 miles of Portland" just
 * because the user can take it from Seattle.
 *
 * @see org.example.fitfetch.fetching.RadiusQueries
 */
@Service
public class RadiusSearchService {

    private final FetchedJobsRepository fetchedJobsRepository;
    private final Geocoder geocoder;
    private final String origin;

    /**
     * @param fetchedJobsRepository where the search runs
     * @param geocoder              the caching geocoding chain
     * @param origin                {@code app.location.default-origin}
     */
    public RadiusSearchService(FetchedJobsRepository fetchedJobsRepository,
                               Geocoder geocoder,
                               @Value("${app.location.default-origin}") String origin) {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.geocoder = geocoder;
        this.origin = origin;
    }

    /**
     * @param miles the search radius; must be positive and finite
     * @return every job with at least one location within {@code miles} of the
     *         origin, including every job whose location follows the origin,
     *         ordered by id
     * @throws IllegalStateException if the configured origin does not geocode
     * @throws GeocodingException    if the origin is not cached and the lookup
     *                               fails, or lookups are disabled
     */
    public List<FetchedJob> findWithinMiles(double miles) {
        return fetchedJobsRepository.findWithinRadius(around(miles));
    }

    /**
     * @param miles the radius; must be positive and finite
     * @return a circle of that radius around the origin as configured now, for
     *         the queries in {@link org.example.fitfetch.fetching.RadiusQueries}
     * @throws IllegalStateException if the configured origin does not geocode
     * @throws GeocodingException    if the origin is not cached and the lookup
     *                               fails, or lookups are disabled
     */
    public OriginRadius around(double miles) {
        GeocodeOutcome center = geocoder.geocode(origin);
        if (!center.status().hasCoordinates()) {
            throw new IllegalStateException("The search origin '" + origin + "' does not geocode ("
                    + center.status() + "); check app.location.default-origin");
        }
        return OriginRadius.of(center.latitude(), center.longitude(), miles);
    }
}
