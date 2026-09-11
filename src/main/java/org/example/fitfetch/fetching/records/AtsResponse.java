package org.example.fitfetch.fetching.records;

import java.util.List;

/**
 * Provider-agnostic wrapper around the payload returned by an ATS jobs
 * endpoint.
 *
 * <p>Each integration provides one implementing record (for example
 * {@link GreenhouseResponse}) that maps its provider's response shape onto
 * {@link #jobs()}. Instances are produced by {@link org.example.fitfetch.fetching.Fetch}
 * and consumed by {@link org.example.fitfetch.ats.Ats} implementations.
 *
 * @param <T> the concrete {@link AtsJobEntry} type carried by this response
 * @see GreenhouseResponse
 */
public interface AtsResponse<T extends AtsJobEntry> {

    /**
     * @return the job entries in this response; may be {@code null} or empty
     *         when the provider returned no jobs, so callers must null-check
     */
    List<T> jobs();
}
