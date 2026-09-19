package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.records.AshbyJobEntry;
import org.example.fitfetch.fetching.records.AshbyResponse;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;

/**
 * {@link Fetch} implementation that calls the public Ashby posting API.
 *
 * <p>Each request targets
 * {@code https://api.ashbyhq.com/posting-api/job-board/{board}?includeCompensation=true}
 * and is deserialized into an {@link AshbyResponse}, which is itself an
 * {@code AtsResponse<AshbyJobEntry>} and so is returned as-is. No
 * authentication is required.
 *
 * <p>Three things differ from {@link GreenhouseFetch} and are worth knowing
 * before changing anything here:
 *
 * <ul>
 *   <li><strong>No pagination.</strong> Ashby returns every posting on a board
 *       in a single body, so there is no loop to write. Bodies are
 *       correspondingly large &mdash; a median around 90&nbsp;KB, and 14.3&nbsp;MB
 *       on the largest board sampled.</li>
 *   <li><strong>Compression is already on, and wants leaving on.</strong>
 *       {@code RestClientConfig} builds these clients on a
 *       {@link org.springframework.http.client.JdkClientHttpRequestFactory},
 *       whose {@code compression} flag defaults to {@code true} and is never
 *       cleared, so the factory asks for gzip and decompresses the response
 *       itself. That is what keeps the bodies above affordable: the largest
 *       board crosses the wire at a fraction of its 14.3&nbsp;MB. Forcing
 *       {@code Accept-Encoding: identity} would put the full size back under
 *       {@code app.http.read-timeout}, which bounds the whole exchange rather
 *       than just the wait for headers.</li>
 *   <li><strong>The board name is case-sensitive</strong> and is passed through
 *       exactly as {@code slugs.json} stores it. An unknown or wrongly-cased
 *       board answers 404 with the plain-text body {@code Not Found}, not
 *       JSON, which surfaces here as an {@code HttpClientErrorException.NotFound}
 *       rather than a deserialization failure.</li>
 * </ul>
 *
 * @see Fetch
 */
@Service
public class AshbyFetch implements Fetch<AshbyJobEntry> {
    private static final String BASEURL = "https://api.ashbyhq.com/posting-api/job-board/";
    private final RestClient restClient;

    /**
     * @param restClients the per-ATS clients; Ashby's is rate-limited to Ashby's
     *                    own limits
     */
    @Autowired
    public AshbyFetch(AtsRestClients restClients) {
        this(restClients.forAts(AtsName.ASHBY));
    }

    /**
     * @param restClient the HTTP client used to call the Ashby API
     */
    public AshbyFetch(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches all jobs for a single Ashby board, including compensation.
     *
     * @param slug the Ashby board name (the {@code {slug}} path segment);
     *             case-sensitive, and passed through verbatim
     * @return the board's jobs; never {@code null} (an empty response is
     *         substituted when the API returns no body), though
     *         {@link AtsResponse#jobs()} may still be {@code null} if the body
     *         omits the {@code jobs} field
     * @throws org.springframework.web.client.HttpClientErrorException on a 4xx
     *         response, including the 404 an unknown board returns
     * @throws org.springframework.web.client.HttpServerErrorException on a 5xx
     *         response
     */
    @Override
    public AtsResponse<AshbyJobEntry> fetchJobs(String slug) {
        // The slug stays a template variable: the template is what the request's
        // uri metric tag holds, and concatenating the slug in would make that one
        // series per board.
        AshbyResponse response = restClient.get()
                .uri(BASEURL + "{slug}?includeCompensation=true", slug)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AshbyResponse.class);

        return response != null ? response : new AshbyResponse(Collections.emptyList(), null);
    }
}
