package org.example.fitfetch;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.ats.GreenhouseAts;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.GreenhouseFetch;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.security.KeyException;
import java.util.List;
import java.util.stream.StreamSupport;

public class GreenhouseTest {
    private Fetch<GreenhouseJobEntry> fetcher;
    private FetchedJobsRepository fetchedJobsRepository;
    private MetricService metricService;
    private MockRestServiceServer mockServer;
    private Resource defaultSlugsFile;
    private GreenhouseAts defaultGreenhouse;

    @BeforeEach
    void setUp() throws IOException, KeyException {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        fetcher = new GreenhouseFetch(restClient);
        fetchedJobsRepository = Mockito.mock(FetchedJobsRepository.class);
        metricService = Mockito.mock(MetricService.class);
        defaultSlugsFile = new ClassPathResource("slugs.json");
        defaultGreenhouse = new GreenhouseAts(fetchedJobsRepository, fetcher, metricService, defaultSlugsFile, false);
    }

    @Test
    @DisplayName("Successfully read slugs file and load Greenhouse slugs")
    void successfullyReadAndLoadsSlugs() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(defaultSlugsFile.getInputStream());
        List<String> fileSlugValues = StreamSupport.stream(root.get("ats").get("greenhouse").spliterator(), false)
                .map(JsonNode::asString)
                .toList();

        assertNotNull(defaultGreenhouse.getSlugs());
        assertNotNull(fileSlugValues);
        assertFalse(fileSlugValues.isEmpty());
        assertEquals(defaultGreenhouse.getSlugs().size(), fileSlugValues.size());
    }

    @Test
    @DisplayName("readSlugs throws FileNotFoundException when missing_ats_slugs.json is missing")
    void testReadSlugsThrowsWhenFileMissing() {
        Resource nonExistentSlugsFile = new ClassPathResource("this-file-does-not-exist.json");
        assertThrowsExactly(FileNotFoundException.class, () -> new GreenhouseAts(fetchedJobsRepository, fetcher, metricService, nonExistentSlugsFile, false));
    }

    @Test
    @DisplayName("missing_ats_slugs.json contents does not have `greenhouse` nested key")
    void slugsJsonContentsDoesNotHaveGreenhouseKey() {
        Resource missingAtsSlugsFile = new ClassPathResource("missing_ats_slugs.json");
        assertThrowsExactly(KeyException.class, () -> new GreenhouseAts(fetchedJobsRepository, fetcher, metricService, missingAtsSlugsFile, false));
    }

    @Test
    @DisplayName("successfully returned jobs for subset of slugs")
    void successfullyReturnedJobsForSubsetOfSlugs() throws Exception {
        InputStream oneUpHealthInputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("1uphealthValidResponse.json");
        assertNotNull(oneUpHealthInputStream, "Could not find file in test resources");
        String oneUpHealthJsonPayload = new String(oneUpHealthInputStream.readAllBytes());

        InputStream warpInputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("warpValidResponse.json");
        assertNotNull(warpInputStream, "Could not find file in test resources");
        String warpJsonPayload = new String(warpInputStream.readAllBytes());

        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withSuccess(oneUpHealthJsonPayload, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(warpJsonPayload, MediaType.APPLICATION_JSON));

        List<AtsJobEntry> results = defaultGreenhouse.fetchJobs();
        // Some jobs are filtered out by `TitleFilter`
        assertEquals(4,  results.size());

        mockServer.verify();
    }

    @Test
    @DisplayName("404 Not found returned for a slug")
    void ErrorCode404Returned() throws Exception {
        InputStream warpInputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("warpValidResponse.json");
        assertNotNull(warpInputStream, "Could not find file in test resources");
        String warpJsonPayload = new String(warpInputStream.readAllBytes());

        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withResourceNotFound());
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(warpJsonPayload, MediaType.APPLICATION_JSON));

        List<AtsJobEntry> results = defaultGreenhouse.fetchJobs();
        // Some jobs are filtered out by `TitleFilter`
        assertEquals(3, results.size());

        mockServer.verify();
    }

    @Test
    @DisplayName("500 error returned for a slug")
    void ErrorCode500Returned() throws Exception {
        InputStream warpInputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("warpValidResponse.json");
        assertNotNull(warpInputStream, "Could not find file in test resources");
        String warpJsonPayload = new String(warpInputStream.readAllBytes());

        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withServerError());
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(warpJsonPayload, MediaType.APPLICATION_JSON));

        List<AtsJobEntry> results = defaultGreenhouse.fetchJobs();
        assertEquals(3, results.size());

        mockServer.verify();
    }

    @Test
    @DisplayName("An HTTP error is counted once, as an error and not also as an empty response")
    void httpErrorCountedOnce() throws Exception {
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withServerError());
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(resource("warpValidResponse.json"), MediaType.APPLICATION_JSON));

        defaultGreenhouse.fetchJobs();

        Mockito.verify(metricService).recordCounter(Mockito.eq(MetricName.SLUG_FETCH_ERROR_COUNT), Mockito.anyMap());
        Mockito.verify(metricService, Mockito.never())
                .recordCounter(Mockito.eq(MetricName.SLUG_FETCH_NULL_RESPONSE_COUNT), Mockito.anyMap());
    }

    @Test
    @DisplayName("A timeout on one slug skips that slug and keeps the others")
    void timeoutSkipsOnlyThatSlug() throws Exception {
        // Previously uncaught: it failed the whole board and discarded every slug.
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(resource("warpValidResponse.json"), MediaType.APPLICATION_JSON));

        List<AtsJobEntry> results = defaultGreenhouse.fetchJobs();

        assertEquals(3, results.size(), "warp's jobs survive the other slug's timeout");
        Mockito.verify(metricService).recordCounter(Mockito.eq(MetricName.SLUG_FETCH_ERROR_COUNT), Mockito.anyMap());
        mockServer.verify();
    }

    @Test
    @DisplayName("An unreadable body on one slug skips that slug and keeps the others")
    void unreadableBodySkipsOnlyThatSlug() throws Exception {
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(resource("warpValidResponse.json"), MediaType.APPLICATION_JSON));

        List<AtsJobEntry> results = defaultGreenhouse.fetchJobs();

        assertEquals(3, results.size());
        mockServer.verify();
    }

    private String resource(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Could not find " + name + " in test resources");
            return new String(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("should filter out jobs that already exist in the database")
    void shouldFilterOutExistingDatabaseJobs() throws Exception {
        // 1. Arrange the Mock DB response
        // Assume "4166249004" is an ID present inside your 1uphealthValidResponse.json payload
        java.util.Set<String> existingDbJobIds = java.util.Set.of("5995721004");

        // Use Mockito to tell your existing mock repository what to return
        Mockito.when(fetchedJobsRepository.findJobIdByAtsName(AtsName.GREENHOUSE))
                .thenReturn(existingDbJobIds);

        // 2. Arrange the Mock Server payloads (using your existing file setup)
        InputStream oneUpHealthInputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("1uphealthValidResponse.json");
        assertNotNull(oneUpHealthInputStream, "Could not find file in test resources");
        String oneUpHealthJsonPayload = new String(oneUpHealthInputStream.readAllBytes());

        InputStream warpInputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("warpValidResponse.json");
        assertNotNull(warpInputStream, "Could not find file in test resources");
        String warpJsonPayload = new String(warpInputStream.readAllBytes());

        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/1uphealth/jobs?content=true"))
                .andRespond(withSuccess(oneUpHealthJsonPayload, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/warp/jobs?content=true"))
                .andRespond(withSuccess(warpJsonPayload, MediaType.APPLICATION_JSON));

        // 3. Act
        List<AtsJobEntry> results = defaultGreenhouse.fetchJobs();

        // 4. Assert: Expecting 5 instead of 6 because one job ID was filtered out by the DB mock
        assertEquals(4, results.size(), "Result size should be 2 less because 1 job was filtered out by database IDs and 1" +
                " was filtered out by the `TitleFilter");

        mockServer.verify();
    }
}
