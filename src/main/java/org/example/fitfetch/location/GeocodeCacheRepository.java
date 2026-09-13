package org.example.fitfetch.location;

import org.example.fitfetch.domain.GeocodeCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for cached geocode lookups.
 *
 * <p>Every row here cost either money or a call against a daily budget, which is
 * why the batch lookup matters: the whole curated table collapses to 57 distinct
 * queries, so a page of jobs resolves against a handful of rows rather than one
 * per job.
 *
 * @see GeocodeCacheEntry
 * @see CachingGeocoder
 */
@Repository
public interface GeocodeCacheRepository extends JpaRepository<GeocodeCacheEntry, String> {

    /**
     * Fetches every cached lookup for a batch of queries.
     *
     * @param queryKeys the normalized queries to look up
     * @return the subset that is cached; never {@code null}
     */
    @Query("select g from GeocodeCacheEntry g where g.queryKey in :keys")
    List<GeocodeCacheEntry> findAllByKeys(@Param("keys") Collection<String> queryKeys);

    /**
     * Records reads against a batch of rows in one statement.
     *
     * @param queryKeys the rows that were read
     * @param now       the time of the read
     * @return how many rows were updated
     */
    @Modifying
    @Query("""
            update GeocodeCacheEntry g
               set g.hitCount = g.hitCount + 1, g.lastHitAt = :now
             where g.queryKey in :keys
            """)
    int recordHits(@Param("keys") Collection<String> queryKeys, @Param("now") OffsetDateTime now);
}
