package org.example.fitfetch;

import org.example.fitfetch.fetching.Fetch;

import org.example.fitfetch.fetching.GreenhouseFetch;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class GreenhouseFetchTest {

    private Fetch<GreenhouseJobEntry> fetcher;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        // 1. Create a real RestClient.Builder
        RestClient.Builder builder = RestClient.builder();

        // 2. Bind the MockServer to this builder instance
        // This allows Spring to intercept the actual HTTP calls under the hood
        mockServer = MockRestServiceServer.bindTo(builder).build();

        // 3. Build a REAL RestClient out of the bound builder
        RestClient restClient = builder.build();

        // 4. Pass the real client into your production code
        fetcher = new GreenhouseFetch(restClient);
    }

    @Test
    @DisplayName("Valid slug returns JSON response")
    void testHappyPath() throws IOException {
        // 1. Read JSON file content safely from resources
        InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("greenhouse_single_valid_response.json");
        assertNotNull(inputStream, "Could not find file in test resources");
        String jsonPayload = new String(inputStream.readAllBytes());

        // 2. Instruct the mock server to look out for the exact URI called inside production code
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/launchdarkly/jobs?content=true"))
                .andRespond(withSuccess(jsonPayload, MediaType.APPLICATION_JSON));

        // 3. Execute target operation
        AtsResponse<GreenhouseJobEntry> result = fetcher.fetchJobs("launchdarkly");

        assertNotNull(result);
        assertEquals(1, result.jobs().size());
        assertEquals(Long.valueOf("7767461003"), (result.jobs().getFirst()).id());

        // Verifies all expected mock server requests actually occurred
        mockServer.verify();
    }

    @Test
    @DisplayName("Valid slug returns 2 job entries in JSON response")
    void testHappyPathWithMultipleJobs() throws IOException {
        // 1. Read JSON file content safely from resources
        InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("greenhouse_two_valid_responses.json");
        assertNotNull(inputStream, "Could not find file in test resources");
        String jsonPayload = new String(inputStream.readAllBytes());

        // 2. Instruct the mock server to look out for the exact URI called inside production code
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/launchdarkly/jobs?content=true"))
                .andRespond(withSuccess(jsonPayload, MediaType.APPLICATION_JSON));

        // 3. Execute target operation
        AtsResponse<GreenhouseJobEntry> result = fetcher.fetchJobs("launchdarkly");

        assertNotNull(result);
        assertEquals(2, result.jobs().size());
        System.out.println(result);
        assertEquals(Long.valueOf("7767461003"), result.jobs().get(0).id());
        assertEquals(Long.valueOf("7861151003"), result.jobs().get(1).id());

        // Verifies all expected mock server requests actually occurred
        mockServer.verify();
    }

    @Test
    @DisplayName("Invalid slug passed into fetchJobs")
    void testInvalidSlugPassedIntoFetchJobs() {
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/invalid_slug/jobs?content=true"))
                .andRespond(withResourceNotFound());

        assertThrows(HttpClientErrorException.class, () -> fetcher.fetchJobs("invalid_slug"));
    }
}