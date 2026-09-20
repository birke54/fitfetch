package org.example.fitfetch.fetching.records.LeverSubRecords;

import java.math.BigDecimal;

/**
 * The {@code salaryRange} object on a Lever posting: the structured pay range
 * the employer published, present on 36.4% of sampled postings and absent as a
 * key entirely on the rest.
 *
 * <p>Nothing in the pipeline reads these fields yet. They are modelled because
 * this record is what gets serialized into the {@code job_data} column, and a
 * field left unmodelled is dropped at fetch time and cannot be recovered
 * without re-fetching a posting that may by then be gone.
 *
 * @param min      bottom of the range. <strong>{@link BigDecimal}, not a
 *                 {@code Long}</strong>: Lever sends this as a JSON integer on
 *                 most postings but as a float on some &mdash; 1,232 of the
 *                 8,908 min/max values sampled arrived as floats &mdash; and
 *                 binding a float to an integral type either fails or
 *                 truncates. May be {@code null}
 * @param max      top of the range, on the same terms as {@code min}
 * @param currency ISO 4217 code, e.g. {@code "USD"}, {@code "CAD"},
 *                 {@code "RON"}; may be {@code null}
 * @param interval the period the figures cover, e.g.
 *                 {@code "per-year-salary"}; may be {@code null}
 */
public record SalaryRange(
        BigDecimal min,
        BigDecimal max,
        String currency,
        String interval
) {}
