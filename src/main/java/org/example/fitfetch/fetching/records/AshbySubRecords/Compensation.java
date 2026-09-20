package org.example.fitfetch.fetching.records.AshbySubRecords;

import java.util.List;

/**
 * The {@code compensation} object on an Ashby job, returned only when the
 * request carries {@code includeCompensation=true}.
 *
 * <p>Present on every posting the sampled boards returned, but frequently
 * hollow: a board that publishes no pay data still answers with all four
 * components set to {@code null} or an empty list. Read
 * {@link org.example.fitfetch.fetching.records.AshbyJobEntry#shouldDisplayCompensationOnJobPostings()}
 * before showing any of it &mdash; that flag is the employer's own answer to
 * whether the figures may be displayed, and it is {@code false} on postings
 * that nonetheless carry full tiers.
 *
 * <p>Nothing in the pipeline reads these fields yet. They are modelled because
 * the record is what gets serialized into the {@code job_data} column, and a
 * field left unmodelled is dropped at fetch time and cannot be recovered
 * without re-fetching a posting that may by then be gone.
 *
 * @param compensationTierSummary            Ashby's rendering of the whole
 *                                           package across tiers, e.g.
 *                                           {@code "$115K \u2013 $160K \u2022 0.05% \u2013 0.15%"};
 *                                           may be {@code null}
 * @param scrapeableCompensationSalarySummary the salary range alone, in the
 *                                           plain form Ashby publishes for
 *                                           aggregators, e.g.
 *                                           {@code "$115K - $160K"}; may be
 *                                           {@code null}
 * @param compensationTiers                  the full packages; may be
 *                                           {@code null} or empty
 * @param summaryComponents                  the pay elements rolled up across
 *                                           tiers, carried as the same
 *                                           {@link CompensationComponent} shape
 *                                           with {@code id} and {@code summary}
 *                                           absent; may be {@code null} or empty
 */
public record Compensation(
        String compensationTierSummary,
        String scrapeableCompensationSalarySummary,
        List<CompensationTier> compensationTiers,
        List<CompensationComponent> summaryComponents
) {}
