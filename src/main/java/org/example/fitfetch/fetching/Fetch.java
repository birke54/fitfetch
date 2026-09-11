package org.example.fitfetch.fetching;

import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;

/**
 * Transport abstraction for retrieving the raw job list of a single company
 * board ("slug") from an ATS provider's API.
 *
 * <p>Implementations own the provider-specific HTTP call and deserialization;
 * higher-level filtering and persistence are the caller's responsibility
 * (see {@link org.example.fitfetch.ats.Ats}).
 *
 * @param <T> the concrete {@link AtsJobEntry} type returned by this provider
 */
public interface Fetch<T extends AtsJobEntry> {

    /**
     * Fetches the current job postings for the given slug.
     *
     * @param slug the provider-specific board identifier to query
     * @return the provider response wrapping the job list; the response, or its
     *         job list, may be {@code null} when the provider returns nothing
     * @throws org.springframework.web.client.HttpClientErrorException on a 4xx
     *         response
     * @throws org.springframework.web.client.HttpServerErrorException on a 5xx
     *         response
     */
    AtsResponse<T> fetchJobs(String slug);
}