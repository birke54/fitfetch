package org.example.fitfetch.location;

import org.example.fitfetch.domain.LocationInterpretation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for cached model extractions.
 *
 * <p>The batch methods exist because the location pass works a page of jobs at a
 * time, and a page of a hundred jobs typically holds only a dozen distinct
 * labels. Looking them up one at a time would spend a round trip per job to
 * learn what one query answers for all of them.
 *
 * @see LocationInterpretation
 * @see CachingLocationExtractor
 */
@Repository
public interface LocationInterpretationRepository extends JpaRepository<LocationInterpretation, String> {

    /**
     * Fetches every cached extraction for a batch of labels.
     *
     * @param locationKeys the normalized labels to look up
     * @return the subset that is cached; never {@code null}
     */
    @Query("select i from LocationInterpretation i where i.locationKey in :keys")
    List<LocationInterpretation> findAllByKeys(@Param("keys") Collection<String> locationKeys);

    /**
     * Records reads against a batch of rows in one statement.
     *
     * <p>Read statistics drive eviction order, which means every cache
     * <em>read</em> implies a <em>write</em>. Batching keeps that to one
     * statement per page rather than one per lookup; the caller additionally
     * coarsens it, only touching rows whose last read is already old.
     *
     * @param locationKeys the rows that were read
     * @param now          the time of the read
     * @return how many rows were updated
     */
    @Modifying
    @Query("""
            update LocationInterpretation i
               set i.hitCount = i.hitCount + 1, i.lastHitAt = :now
             where i.locationKey in :keys
            """)
    int recordHits(@Param("keys") Collection<String> locationKeys, @Param("now") OffsetDateTime now);
}
