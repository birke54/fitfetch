package org.example.fitfetch.normalize;

import org.example.fitfetch.domain.NormalizedJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository for normalized jobs.
 *
 * @see NormalizedJob
 * @see NormalizeService
 */
@Repository
public interface NormalizedJobRepository extends JpaRepository<NormalizedJob, Long> {

    /**
     * @param fetchedJobId the job to look up
     * @return its normalization, if it has one
     */
    Optional<NormalizedJob> findByFetchedJobId(Long fetchedJobId);

    /**
     * Deletes a job's normalization.
     *
     * <p>Run immediately before writing a new one. A job reaches the pass again
     * only after being requeued, and {@code uq_normalized_jobs_fetched_job}
     * would otherwise reject its new row.
     *
     * @param fetchedJobId the job whose normalization should be cleared
     * @return how many rows were deleted
     */
    @Modifying
    @Query("delete from NormalizedJob n where n.fetchedJobId = :id")
    int deleteByFetchedJobId(@Param("id") Long fetchedJobId);
}
