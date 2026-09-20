package org.example.fitfetch.fetching.records;

import java.util.List;

/**
 * {@link AtsResponse} implementation for the Lever postings API.
 *
 * <p>There is no envelope to mirror. Lever answers a board with a <em>bare JSON
 * array</em> of postings &mdash; on 227 of 227 boards sampled, with no
 * {@code jobs} wrapper and no {@code apiVersion} marker, and with the two-byte
 * body {@code []} for a board with nothing open. So unlike
 * {@link AshbyResponse}, this record is not a map of the payload: it is the
 * adapter that gives that array the shape {@link AtsResponse} requires.
 *
 * <p><strong>Never deserialized.</strong>
 * {@link org.example.fitfetch.fetching.LeverFetch} reads the body as a
 * {@code LeverJobEntry[]} and constructs this itself, which is why there is no
 * {@code @JsonTypeInfo(Id.NONE)} marker on {@code jobs} as there is on
 * {@link AshbyResponse}: that marker only helps when Jackson walks into the
 * list through the enclosing record, and here it never does. How the array
 * itself is read past the inherited type discriminator is documented on
 * {@code LeverFetch}.
 *
 * <p>There is no pagination to unwrap either: Lever returns every posting on a
 * board in this one body however large it gets, and although {@code skip} and
 * {@code limit} exist there is no default cap, so there is no paging loop to
 * write.
 *
 * @param jobs the postings in the response; empty for a board with no open
 *             postings. Declared as possibly {@code null} per the
 *             {@link AtsResponse} contract, though {@code LeverFetch}
 *             substitutes an empty list rather than ever passing one
 * @see LeverJobEntry
 */
public record LeverResponse(
        List<LeverJobEntry> jobs
) implements AtsResponse<LeverJobEntry> {}
