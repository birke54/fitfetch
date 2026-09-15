package org.example.fitfetch;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.fetching.Fetch;

import org.example.fitfetch.fetching.GreenhouseFetch;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

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
    @DisplayName("Payload fields the record used to drop are now deserialized")
    void testPreviouslyDroppedFieldsAreDeserialized() throws IOException {
        InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("greenhouse_single_valid_response.json");
        assertNotNull(inputStream, "Could not find file in test resources");
        String jsonPayload = new String(inputStream.readAllBytes());

        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/launchdarkly/jobs?content=true"))
                .andRespond(withSuccess(jsonPayload, MediaType.APPLICATION_JSON));

        GreenhouseJobEntry job = fetcher.fetchJobs("launchdarkly").jobs().getFirst();

        // `location` is what the location pipeline is built on. It was silently
        // discarded on every fetch before this component existed.
        assertNotNull(job.location(), "location object should be deserialized");
        assertEquals("Remote - US", job.location().name());
        assertEquals("Remote - US", job.locationName());

        assertEquals(Long.valueOf("5778872003"), job.internalJobId());
        assertEquals(2, job.metadata().size());
        assertEquals("Other Employment Types", job.metadata().getFirst().name());
        assertEquals(1, job.dataCompliance().size());
        assertEquals("gdpr", job.dataCompliance().getFirst().type());

        mockServer.verify();
    }

    @Test
    @DisplayName("locationName is null-safe for payloads carrying no location")
    void testLocationNameIsNullSafeWhenLocationAbsent() {
        // Mirrors a job_data payload persisted before the location component existed.
        GreenhouseJobEntry noLocation = new GreenhouseJobEntry(
                "https://example.com", null, 1L, null, null, null, "Backend Engineer", null, null,
                null, null, "JD body", null, List.of(), List.of(), List.of(), "some-slug");

        assertNull(noLocation.location());
        assertNull(noLocation.locationName());
    }

    @Test
    @DisplayName("withSlug preserves every other component")
    void testWithSlugPreservesAllOtherComponents() {
        GreenhouseJobEntry original = new GreenhouseJobEntry(
                "https://example.com", "Bachelors", 1L, 101L, null, "REQ-1", "Backend Engineer",
                "Company A", null, "en", null, "JD body", new Location("Remote - US"),
                List.of(), List.of(), List.of(), null);

        GreenhouseJobEntry tagged = original.withSlug("company-a");

        assertEquals("company-a", tagged.slug());
        // Round-trip: clearing the slug again must reproduce the original exactly,
        // which fails if withSlug drops a component it should be threading through.
        assertEquals(original, tagged.withSlug(null));
    }

    @Test
    @DisplayName("Requests for different slugs share one http.client.requests series, tagged by template")
    void testRequestMetricNotTaggedBySlug() {
        // Concatenating the slug into the URI would make the uri tag one series
        // per board; the template keeps it to one.
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        RestClient.Builder builder = RestClient.builder().observationRegistry(observations);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Fetch<GreenhouseJobEntry> observed = new GreenhouseFetch(builder.build());
        for (String slug : List.of("alpha", "beta")) {
            server.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs?content=true"))
                    .andRespond(withSuccess("{\"jobs\":[]}", MediaType.APPLICATION_JSON));
        }

        observed.fetchJobs("alpha");
        observed.fetchJobs("beta");

        server.verify();
        Collection<Timer> timers = meters.get("http.client.requests").timers();
        assertEquals(1, timers.size(), "one series whatever the slug");
        Timer timer = timers.iterator().next();
        assertEquals(2, timer.count());
        String uri = timer.getId().getTag("uri");
        assertTrue(uri.contains("{slug}"), "tagged with the template, but was " + uri);
    }

    @Test
    @DisplayName("Invalid slug passed into fetchJobs")
    void testInvalidSlugPassedIntoFetchJobs() {
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/invalid_slug/jobs?content=true"))
                .andRespond(withResourceNotFound());

        assertThrows(HttpClientErrorException.class, () -> fetcher.fetchJobs("invalid_slug"));
    }
}