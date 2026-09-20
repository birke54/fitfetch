package org.example.fitfetch.fetching.records.AshbySubRecords;

/**
 * The {@code postalAddress} object nested inside an Ashby {@code address}:
 * schema.org {@code PostalAddress} fields, flattened.
 *
 * <p>Ashby fills these from the office record attached to the posting, not from
 * the recruiter's free-text location label, so they describe an office rather
 * than where the work may be performed. Nothing downstream resolves geography
 * from here &mdash; {@link org.example.fitfetch.fetching.records.AshbyJobEntry#locationName()}
 * is the single location input &mdash; but the fields are modelled so the
 * {@code job_data} column keeps them. Every component is nullable: on the
 * sampled boards {@code postalCode} was routinely absent, and the whole
 * enclosing {@code address} is {@code null} on roughly a fifth of postings.
 *
 * @param addressLocality the city, e.g. {@code "Paris"}; may be {@code null}
 * @param addressRegion   the state, province or region; may be {@code null}.
 *                        Ashby frequently repeats the country here for offices
 *                        it only knows at country granularity
 * @param addressCountry  the country, e.g. {@code "France"}; may be {@code null}
 * @param postalCode      the postal or ZIP code; may be {@code null}, and
 *                        usually is
 */
public record PostalAddress(
        String addressLocality,
        String addressRegion,
        String addressCountry,
        String postalCode
) {}
