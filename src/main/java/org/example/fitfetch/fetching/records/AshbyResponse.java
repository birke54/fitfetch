package org.example.fitfetch.fetching.records;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * {@link AtsResponse} implementation for the Ashby posting API.
 *
 * <p>Mirrors the whole top-level envelope, which has exactly two keys:
 * {@code { "jobs": [ ... ], "apiVersion": "1" }}. Deserialized by
 * {@link org.example.fitfetch.fetching.AshbyFetch} and passed straight through
 * as the {@code AtsResponse<AshbyJobEntry>} its callers expect.
 *
 * <p>There is no pagination to unwrap: Ashby returns every posting on a board
 * in this one body, however large it gets, and an empty board answers with an
 * empty {@code jobs} array rather than a null or an absent key.
 *
 * @param jobs       the job entries in the response; empty for a board with no
 *                   open postings. Declared as possibly {@code null} per the
 *                   {@link AtsResponse} contract, though Ashby has not been
 *                   observed to omit the key
 * @param apiVersion the envelope's version marker, {@code "1"} at time of
 *                   writing. Modelled so the field is not silently dropped;
 *                   nothing reads it yet, but a bump is the signal that the
 *                   payload shape may have moved
 * @see AshbyJobEntry
 */
public record AshbyResponse(
        // Ashby's payload carries no `type` discriminator, and the one inherited
        // from AtsJobEntry defaults to GreenhouseJobEntry when absent -- which is
        // not an AshbyJobEntry, so the default would fail the whole read with an
        // InvalidTypeIdException. Id.NONE switches polymorphic handling off for
        // this property only: the element type is already known statically here.
        // GreenhouseResponse needs no such marker only because it happens to name
        // the very type that defaultImpl points at.
        @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
        List<AshbyJobEntry> jobs,
        String apiVersion
) implements AtsResponse<AshbyJobEntry> {}
