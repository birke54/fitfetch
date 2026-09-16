package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.LocationStatus;
import org.example.fitfetch.domain.NormalizeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Spring Data JPA repository for {@link FetchedJob} rows in the
 * {@code fetched_jobs} table.
 *
 * <p>Beyond the standard {@link JpaRepository} CRUD operations, the queries
 * here support de-duplicating jobs during fetching (by returning the set of
 * already-known job IDs) and paging the location pass over pending jobs. The
 * radius queries, which the search and the normalization pass share, come from
 * {@link RadiusQueries}.
 */
@Repository
public interface FetchedJobsRepository extends JpaRepository<FetchedJob,Long>, RadiusQueries {

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
     * Counts jobs in a given normalization state and location resolution state.
     *
     * <p>Counting {@code PENDING} normalization with {@code RESOLVED} locations
     * leaves out jobs still waiting on the location pass, including the
     * {@code FAILED} ones that wait until they are curated.
     *
     * @param normalizeStatus the normalization state to count
     * @param locationStatus  the location resolution state to count
     * @return how many jobs are in both states
     */
    long countByNormalizeStatusAndLocationStatus(NormalizeStatus normalizeStatus, LocationStatus locationStatus);

    /**
     * Counts jobs in a given normalization state, whatever their location state.
     *
     * @param normalizeStatus the state to count
     * @return how many jobs are in that state
     */
    long countByNormalizeStatus(NormalizeStatus normalizeStatus);

    /**
     * Sets one job's normalization state, and nothing else.
     *
     * <p>An update rather than saving the entity: saving writes every column
     * from the copy loaded at the start of the run, which would put back a
     * location state the location pass had changed since.
     *
     * @param id              the job to update
     * @param normalizeStatus the state to set
     * @return how many rows were updated
     */
    @Modifying
    @Query("update FetchedJob f set f.normalizeStatus = :status where f.id = :id")
    int updateNormalizeStatus(@Param("id") Long id, @Param("status") NormalizeStatus normalizeStatus);

    /**
     * Marks every pending job the ATS posted before the cutoff as
     * {@code TOO_OLD}, so the normalization pass never considers it again.
     *
     * <p>Unlike the out-of-range sweep this ignores {@link LocationStatus}: a
     * job's age does not depend on where it is, so there is nothing to wait for
     * the location pass to answer. It runs first for the same reason, and marks
     * jobs the radius sweep would otherwise have to resolve a location for.
     *
     * <p>Not in {@link RadiusQueries}, which exists to keep one copy of the
     * radius SQL; this needs none of it. Must run inside a transaction.
     *
     * @param cutoff jobs posted strictly before this are marked
     * @return how many jobs were marked
     */
    @Modifying
    @Query("update FetchedJob f set f.normalizeStatus = org.example.fitfetch.domain.NormalizeStatus.TOO_OLD "
            + "where f.normalizeStatus = org.example.fitfetch.domain.NormalizeStatus.PENDING "
            + "and f.postedAt < :cutoff")
    int markTooOldForNormalization(@Param("cutoff") OffsetDateTime cutoff);
}

