package org.example.fitfetch.match;

import org.example.fitfetch.domain.JobMatch;
import org.example.fitfetch.domain.NormalizedJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Spring Data JPA repository for {@link JobMatch} rows. */
@Repository
public interface JobMatchRepository extends JpaRepository<JobMatch, Long> {

    /**
     * Finds jobs embedded with the current embedder that have no match scored
     * against the current profile version, embedder and scoring rules, after a
     * cursor, one page at a time.
     *
     * @param embedderKey    the current {@code EmbeddingSettings.key()}
     * @param profileSha256  the current profile version
     * @param scoringVersion the current {@link MatchScorer#VERSION}
     * @param afterId        only jobs with a greater id
     * @param pageable       the page size; the order is by id
     * @return the page
     */
    @Query("""
            select n from NormalizedJob n
            where n.embeddedWith = :key and n.id > :afterId
              and not exists (
                  select m.id from JobMatch m
                  where m.normalizedJobId = n.id
                    and m.profileSha256 = :sha
                    and m.embedderKey = :key
                    and m.scoringVersion = :version)
            order by n.id""")
    List<NormalizedJob> findJobsNeedingScore(@Param("key") String embedderKey,
                                             @Param("sha") String profileSha256,
                                             @Param("version") int scoringVersion,
                                             @Param("afterId") long afterId,
                                             Pageable pageable);

    /**
     * Deletes a job's match, run before writing its new one.
     *
     * @param normalizedJobId the job
     * @return how many rows were deleted
     */
    @Modifying
    @Query("delete from JobMatch m where m.normalizedJobId = :id")
    int deleteByNormalizedJobId(@Param("id") Long normalizedJobId);
}
