package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.LocationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * Spring Data JPA repository for {@link FetchedJob} rows in the
 * {@code fetched_jobs} table.
 *
 * <p>Beyond the standard {@link JpaRepository} CRUD operations, the derived
 * queries here support the two main flows: de-duplicating jobs during fetching
 * (by returning the set of already-known job IDs) and paging over rows that
 * still need normalization.
 */
@Repository
public interface FetchedJobsRepository extends JpaRepository<FetchedJob,Long> {

    /**
     * Returns every {@link FetchedJob#getJobId() job ID} already stored for the
     * given provider.
     *
     * <p>Used by {@code Ats} implementations to skip jobs that have already been
     * ingested.
     *
     * @param atsName the ATS provider to scope the lookup to
     * @return the set of known job IDs for that provider; empty if none
     */
    @Query("select f.jobId from FetchedJob f where f.atsName = :atsName")
    Set<String> findJobIdByAtsName(@Param("atsName") AtsName atsName);

    /**
     * Returns the subset of the given job IDs that are already stored for the
     * provider.
     *
     * <p>A bounded alternative to {@link #findJobIdByAtsName(AtsName)} when only a
     * specific batch of candidate IDs needs checking.
     *
     * @param atsName the ATS provider to scope the lookup to
     * @param jobIds  the candidate job IDs to test for existence
     * @return the intersection of {@code jobIds} with the stored IDs for that
     *         provider
     */
    @Query("select f.jobId from FetchedJob f where f.atsName = :atsName and f.jobId in :jobIds")
    Set<String> findJobIdsByAtsNameAndJobIdIn(@Param("atsName") AtsName atsName, @Param("jobIds") Set<String> jobIds);

    /**
     * Finds fetched jobs by their normalization state, one page at a time.
     *
     * @param isNormalized {@code false} to retrieve jobs still awaiting
     *                     normalization, {@code true} for already-normalized ones
     * @param pageable     paging and sort specification
     * @return the matching page of fetched jobs
     */
    List<FetchedJob> findByIsNormalized(boolean isNormalized, Pageable pageable);

    /**
     * Finds fetched jobs in a location resolution state with an id above a
     * cursor, one page at a time.
     *
     * <p>The location pass works in pages so it can dedupe labels within each
     * one: a hundred jobs typically carry only a dozen distinct location strings,
     * so resolving per page rather than per job is the difference between twelve
     * model calls and a hundred.
     *
     * <p>It pages by id rather than always reading the first page because some
     * jobs stay {@link LocationStatus#PENDING} after a pass sees them. Always
     * reading from the front would hand the pass those same jobs forever once a
     * page filled up with them.
     *
     * @param locationStatus the state to retrieve, usually
     *                       {@link LocationStatus#PENDING}
     * @param afterId        only jobs with an id strictly greater than this;
     *                       {@code 0} for the start of the table
     * @param pageable       page size and sort, which should be by id
     * @return the matching page of fetched jobs
     */
    List<FetchedJob> findByLocationStatusAndIdGreaterThan(LocationStatus locationStatus, Long afterId,
                                                          Pageable pageable);

    /**
     * Counts jobs in a given location resolution state.
     *
     * @param locationStatus the state to count
     * @return how many jobs are in that state
     */
    long countByLocationStatus(LocationStatus locationStatus);

    /**
     * Finds every job with at least one location within a radius of the search
     * origin.
     *
     * <p>A job matches through either arm of the union. A row that
     * {@code follows_origin} matches outright, whatever coordinate it was stored
     * with, because it stands for "wherever the origin is". Any other row
     * matches if its great-circle distance from the origin is within the radius;
     * the bounding box in front of that check is what lets
     * {@code idx_job_locations_lat_lon} narrow the rows first. {@code UNDEFINED}
     * rows have no coordinates and never follow the origin, so they match
     * neither arm.
     *
     * <p>The distance is the haversine formula, in the {@code asin} form because
     * the {@code acos} form loses precision at short range. {@code least} keeps
     * rounding from pushing its argument past 1, where {@code asin} is
     * undefined.
     *
     * <p>{@code IN} rather than a join, so a job with several matching
     * locations is returned once.
     *
     * <p>Call through {@code RadiusSearchService}, which geocodes the origin and
     * derives the box; the parameters are only meaningful together.
     *
     * @param latitude     origin latitude, decimal degrees
     * @param longitude    origin longitude, decimal degrees
     * @param miles        search radius
     * @param minLatitude  bounding box enclosing the radius, from
     *                     {@code BoundingBox.around}
     * @param maxLatitude  bounding box
     * @param minLongitude bounding box
     * @param maxLongitude bounding box
     * @param earthRadius  {@code BoundingBox.EARTH_RADIUS_MILES}, so the box and
     *                     the distance check share one sphere
     * @return the matching jobs, ordered by id
     */
    @Query(nativeQuery = true, value = """
            SELECT fj.*
            FROM fetched_jobs fj
            WHERE fj.id IN (
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
            )
            ORDER BY fj.id
            """)
    List<FetchedJob> findWithinRadiusOfOrigin(@Param("latitude") double latitude,
                                              @Param("longitude") double longitude,
                                              @Param("miles") double miles,
                                              @Param("minLatitude") double minLatitude,
                                              @Param("maxLatitude") double maxLatitude,
                                              @Param("minLongitude") double minLongitude,
                                              @Param("maxLongitude") double maxLongitude,
                                              @Param("earthRadius") double earthRadius);
}

