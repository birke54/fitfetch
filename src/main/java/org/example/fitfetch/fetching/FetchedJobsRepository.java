package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
    Set<String> findJobIdByAtsName(@Param("ats_name") AtsName atsName);

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
    Set<String> findJobIdsByAtsNameAndJobIdIn(AtsName atsName, Set<String> jobIds);

    /**
     * Finds fetched jobs by their normalization state, one page at a time.
     *
     * @param isNormalized {@code false} to retrieve jobs still awaiting
     *                     normalization, {@code true} for already-normalized ones
     * @param pageable     paging and sort specification
     * @return the matching page of fetched jobs
     */
    List<FetchedJob> findByIsNormalized(boolean isNormalized, Pageable pageable);
}

