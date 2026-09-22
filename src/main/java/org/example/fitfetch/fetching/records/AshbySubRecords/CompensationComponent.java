package org.example.fitfetch.fetching.records.AshbySubRecords;

import java.math.BigDecimal;

/**
 * One line of a {@link CompensationTier}: a single pay element such as base
 * salary, a bonus, or an equity grant.
 *
 * <p>Values are carried as {@link BigDecimal} rather than {@code double}
 * because the same field holds both a six-figure salary and an equity
 * percentage like {@code 0.05}; binary floating point would round both, and
 * this record is what gets written into the {@code job_data} JSON column.
 *
 * @param compensationType what this element pays, e.g. {@code "Salary"},
 *                         {@code "EquityPercentage"}, {@code "Bonus"}; may be
 *                         {@code null}
 * @param interval         the period the value covers, e.g. {@code "1 YEAR"},
 *                         or {@code "NONE"} for one-off and equity elements;
 *                         may be {@code null}
 * @param currencyCode     ISO 4217 code, e.g. {@code "USD"}; {@code null} for
 *                         elements with no currency, such as equity percentages
 * @param minValue         bottom of the published range; may be {@code null}
 *                         when the employer published a summary only
 * @param maxValue         top of the published range; may be {@code null} on
 *                         the same terms as {@code minValue}
 * @param id               Ashby's identifier for this element, a UUID string;
 *                         {@code null} on the copies carried in
 *                         {@link Compensation#summaryComponents()}, which omit
 *                         it
 * @param summary          Ashby's own rendering of the range, e.g.
 *                         {@code "$115K \u2013 $160K"}; {@code null} on the
 *                         {@code summaryComponents} copies
 */
public record CompensationComponent(
        String compensationType,
        String interval,
        String currencyCode,
        BigDecimal minValue,
        BigDecimal maxValue,
        String id,
        String summary
) {}
