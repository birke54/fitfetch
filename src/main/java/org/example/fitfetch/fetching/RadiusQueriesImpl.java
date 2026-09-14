package org.example.fitfetch.fetching;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.location.BoundingBox;
import org.example.fitfetch.location.OriginRadius;

import java.util.List;

/**
 * Native SQL for {@link RadiusQueries}; picked up by Spring Data by its name.
 */
class RadiusQueriesImpl implements RadiusQueries {

    /**
     * The ids of every job with a location within the radius.
     *
     * <p>A row that {@code follows_origin} matches outright, whatever coordinate
     * it was stored with, because it stands for "wherever the origin is". Any
     * other row matches if its great-circle distance from the origin is within
     * the radius; the bounding box in front of that check is what lets
     * {@code idx_job_locations_lat_lon} narrow the rows first. {@code UNDEFINED}
     * rows have no coordinates and never follow the origin, so they match
     * neither arm.
     *
     * <p>The distance is the haversine formula, in the {@code asin} form because
     * the {@code acos} form loses precision at short range. {@code least} keeps
     * rounding from pushing its argument past 1, where {@code asin} is undefined.
     *
     * <p>Used with {@code IN} and {@code NOT IN} rather than as a join, so a job
     * with several matching locations counts once. {@code NOT IN} is safe here
     * because {@code fetched_job_id} is never null.
     */
    static final String WITHIN_RADIUS = """
            SELECT jl.fetched_job_id
            FROM job_locations jl
            WHERE jl.follows_origin
            UNION ALL
            SELECT jl.fetched_job_id
            FROM job_locations jl
            WHERE NOT jl.follows_origin
              AND jl.latitude BETWEEN :minLatitude AND :maxLatitude
              AND jl.longitude BETWEEN :minLongitude AND :maxLongitude
              AND 2 * :earthRadius * asin(least(1, sqrt(
                      power(sin(radians(jl.latitude - :latitude) / 2), 2)
                      + cos(radians(:latitude)) * cos(radians(jl.latitude))
                        * power(sin(radians(jl.longitude - :longitude) / 2), 2)
                  ))) <= :miles
            """;

    static final String FIND_WITHIN_RADIUS = """
            SELECT fj.*
            FROM fetched_jobs fj
            WHERE fj.id IN (%s)
            ORDER BY fj.id
            """.formatted(WITHIN_RADIUS);

    static final String FIND_PENDING_NORMALIZATION = """
            SELECT fj.*
            FROM fetched_jobs fj
            WHERE fj.normalize_status = 'PENDING'
              AND fj.location_status = 'RESOLVED'
              AND fj.id IN (%s)
            ORDER BY fj.id
            LIMIT :pageSize
            """.formatted(WITHIN_RADIUS);

    static final String MARK_OUT_OF_RANGE = """
            UPDATE fetched_jobs
            SET normalize_status = 'OUT_OF_RANGE'
            WHERE normalize_status = 'PENDING'
              AND location_status = 'RESOLVED'
              AND id NOT IN (%s)
            """.formatted(WITHIN_RADIUS);

    @PersistenceContext
    private EntityManager entityManager;

    /** For Spring Data, which injects the entity manager. */
    RadiusQueriesImpl() {
    }

    /** @param entityManager the entity manager to query through */
    RadiusQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<FetchedJob> findWithinRadius(OriginRadius radius) {
        Query query = entityManager.createNativeQuery(FIND_WITHIN_RADIUS, FetchedJob.class);
        return bind(query, radius).getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<FetchedJob> findPendingNormalizationWithinRadius(OriginRadius radius, int limit) {
        Query query = entityManager.createNativeQuery(FIND_PENDING_NORMALIZATION, FetchedJob.class);
        return bind(query, radius).setParameter("pageSize", limit).getResultList();
    }

    @Override
    public int markOutOfRangeForNormalization(OriginRadius radius) {
        return bind(entityManager.createNativeQuery(MARK_OUT_OF_RANGE), radius).executeUpdate();
    }

    private static Query bind(Query query, OriginRadius radius) {
        BoundingBox box = radius.box();
        return query
                .setParameter("latitude", radius.latitude())
                .setParameter("longitude", radius.longitude())
                .setParameter("miles", radius.miles())
                .setParameter("minLatitude", box.minLatitude())
                .setParameter("maxLatitude", box.maxLatitude())
                .setParameter("minLongitude", box.minLongitude())
                .setParameter("maxLongitude", box.maxLongitude())
                .setParameter("earthRadius", BoundingBox.EARTH_RADIUS_MILES);
    }
}
