package org.example.fitfetch.normalize;

import org.example.fitfetch.domain.NormalizedJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /**
     * Finds normalized jobs whose signals have not been embedded with the
     * current embedder, after a cursor, one page at a time.
     *
     * @param embedderKey the current {@code EmbeddingSettings.key()}
     * @param afterId     only jobs with a greater id
     * @param pageable    the page size; the order is by id
     * @return the page
     */
    @Query("""
            select n from NormalizedJob n
            where (n.embeddedWith is null or n.embeddedWith <> :key) and n.id > :afterId
            order by n.id""")
    List<NormalizedJob> findNeedingEmbedding(@Param("key") String embedderKey, @Param("afterId") long afterId,
                                             Pageable pageable);

    /**
     * Records that a job's signals have vectors, and nothing else about the row.
     *
     * @param id          the normalized job
     * @param embedderKey what made the vectors
     * @return how many rows were updated
     */
    @Modifying
    @Query("update NormalizedJob n set n.embeddedWith = :key where n.id = :id")
    int markEmbedded(@Param("id") Long id, @Param("key") String embedderKey);
}
