package org.example.fitfetch.fetching;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.location.OriginRadius;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The {@link FetchedJobsRepository} queries that ask whether a job is within the
 * search radius.
 *
 * <p>A fragment rather than {@code @Query} methods because all three share one
 * piece of SQL, the radius match, and each needs the same eight parameters
 * bound from an {@link OriginRadius}. Keeping them together keeps that SQL in
 * one place, so the search and the normalization pass can never disagree about
 * which jobs are in range.
 *
 * @see RadiusQueriesImpl
 */
public interface RadiusQueries {

    /**
     * @param radius the circle around the origin
     * @return every job with at least one location within it, ordered by id
     */
    List<FetchedJob> findWithinRadius(OriginRadius radius);

    /**
     * The next jobs for the normalization pass: pending, located, and within
     * the radius.
     *
     * <p>The radius check is repeated here rather than left to
     * {@link #markOutOfRangeForNormalization}. A job the location pass resolves
     * between that sweep and this read would otherwise be normalized without
     * ever being checked.
     *
     * <p>Pages by id, like the location pass, because a job that keeps failing
     * is set aside and stays pending. Always reading from the front would hand
     * the pass those same jobs forever once a page filled up with them.
     *
     * <p>The age check is repeated here for the same reason, and with more cause:
     * the radius only moves when its configuration does, while the cutoff moves
     * with the clock. A job that crossed it since the sweep would otherwise be
     * normalized on the strength of a sweep that ran when it was still young
     * enough.
     *
     * @param radius  the circle around the origin
     * @param postedAfter only jobs the ATS posted at or after this
     * @param afterId only jobs with an id strictly greater than this; {@code 0}
     *                for the start of the table
     * @param limit   page size
     * @return up to {@code limit} jobs, ordered by id
     */
    List<FetchedJob> findPendingNormalizationWithinRadius(OriginRadius radius, OffsetDateTime postedAfter,
                                                          long afterId, int limit);

    /**
     * Marks every pending, located job with no location within the radius as
     * {@code OUT_OF_RANGE}, so the normalization pass never considers it again.
     *
     * <p>Only {@code RESOLVED} jobs are swept. A job whose location is still
     * pending, or failed and awaiting curation, has no answer yet to judge.
     * Must run inside a transaction.
     *
     * @param radius the circle around the origin
     * @return how many jobs were marked
     */
    int markOutOfRangeForNormalization(OriginRadius radius);
}
