package org.example.fitfetch.fetching.records.AshbySubRecords;

import java.util.List;

/**
 * One tier of an Ashby posting's compensation: a complete pay package, made up
 * of {@link CompensationComponent} lines.
 *
 * <p>A posting carries several tiers when the employer publishes different
 * packages for different markets or levels &mdash; hence the list on
 * {@link Compensation} rather than a single object.
 *
 * @param id                    Ashby's identifier for this tier, a UUID string;
 *                              may be {@code null}
 * @param tierSummary           Ashby's rendering of the whole tier, e.g.
 *                              {@code "$115K \u2013 $160K \u2022 0.05% \u2013 0.15%"};
 *                              may be {@code null}
 * @param title                 the employer's label for this tier, e.g. a zone
 *                              or level name; {@code null} on single-tier
 *                              postings, which is the common case
 * @param additionalInformation free-text note the employer attached to this
 *                              tier; may be {@code null}
 * @param components            the individual pay elements; may be {@code null}
 *                              or empty
 */
public record CompensationTier(
        String id,
        String tierSummary,
        String title,
        String additionalInformation,
        List<CompensationComponent> components
) {}
