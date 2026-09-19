package org.example.fitfetch.fetching.records.AshbySubRecords;

/**
 * An Ashby {@code address} object: a single-field wrapper around a
 * {@link PostalAddress}.
 *
 * <p>The extra level of nesting is Ashby's, mirroring schema.org's
 * {@code JobPosting.jobLocation}; it is kept rather than flattened so the
 * record round-trips the payload exactly.
 *
 * @param postalAddress the structured address; may be {@code null}
 */
public record Address(
        PostalAddress postalAddress
) {}
