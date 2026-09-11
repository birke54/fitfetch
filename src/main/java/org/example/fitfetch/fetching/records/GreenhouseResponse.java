package org.example.fitfetch.fetching.records;

import java.util.List;

/**
 * {@link AtsResponse} implementation for the Greenhouse job-board API.
 *
 * <p>Mirrors the top-level {@code { "jobs": [ ... ] }} envelope returned by
 * {@code /v1/boards/{slug}/jobs}. Deserialized by
 * {@link org.example.fitfetch.fetching.GreenhouseFetch} and passed straight
 * through as the {@code AtsResponse<GreenhouseJobEntry>} its callers expect.
 *
 * @param jobs the job entries in the response; may be {@code null} or empty
 * @see GreenhouseJobEntry
 */
public record GreenhouseResponse(
        List<GreenhouseJobEntry> jobs
) implements AtsResponse<GreenhouseJobEntry> {}