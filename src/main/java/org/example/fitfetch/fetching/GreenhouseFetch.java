package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseResponse;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link Fetch} implementation that calls the public Greenhouse job-board API.
 *
 * <p>Each request targets
 * {@code https://boards-api.greenhouse.io/v1/boards/{slug}/jobs?content=true}
 * and is deserialized into a {@link GreenhouseResponse}, which is itself an
 * {@code AtsResponse<GreenhouseJobEntry>} and so is returned as-is. No
 * authentication is required.
 *
 * @see Fetch
 * @see org.example.fitfetch.ats.GreenhouseAts
 */
@Service
public class GreenhouseFetch implements Fetch<GreenhouseJobEntry> {
    private static final Logger logger = LoggerFactory.getLogger(GreenhouseFetch.class);
    private static final String BASEURL = "https://boards-api.greenhouse.io/v1/boards/";
    private final RestClient restClient;

    /**
     * @param restClients the per-ATS clients; Greenhouse's is rate-limited to
     *                    Greenhouse's own limits
     */
    @Autowired
    public GreenhouseFetch(AtsRestClients restClients) {
        this(restClients.forAts(AtsName.GREENHOUSE));
    }

    /**
     * @param restClient the HTTP client used to call the Greenhouse API
     */
    public GreenhouseFetch(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches all jobs for a single Greenhouse board, including full job
     * content.
     *
     * @param slug the Greenhouse board identifier (the {@code {slug}} path
     *             segment)
     * @return the board's jobs; never {@code null} (an empty response is
     *         substituted when the API returns no body), though
     *         {@link AtsResponse#jobs()} may still be {@code null} if the body
     *         omits the {@code jobs} field
     * @throws org.springframework.web.client.HttpClientErrorException on a 4xx
     *         response
     * @throws org.springframework.web.client.HttpServerErrorException on a 5xx
     *         response
     */
    @Override
    public AtsResponse<GreenhouseJobEntry> fetchJobs(String slug) {
        String endpoint = BASEURL + slug + "/jobs?content=true";

        // 1. Fetch cleanly into the concrete DTO layer
        GreenhouseResponse response = restClient.get()
                .uri(endpoint)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(GreenhouseResponse.class);


        return response != null ? response : new GreenhouseResponse(java.util.Collections.emptyList());
    }
}
