package org.example.fitfetch;

import org.example.fitfetch.ats.AshbyAts;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AshbyJobEntry;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Board-level tests for {@link AshbyAts}: slug loading, and which of the
 * entries a fetch returns survive to be stored.
 *
 * <p>Unlike {@link GreenhouseTest}, which drives a real {@code Fetch} against a
 * mock HTTP server, these mock the {@code Fetch} and hand it entries built in
 * code. What is under test here is the board's own rule about {@code isListed},
 * not the wire format; the JSON fixtures and their parsing are covered by the
 * Ashby fetch's own tests.
 */
public class AshbyTest {

    /**
     * Stands in for the provider's response record so these tests depend only on
     * the shared {@link AtsResponse} contract.
     */
    private record AshbyJobs(List<AshbyJobEntry> jobs) implements AtsResponse<AshbyJobEntry> {
    }

    private static final Map<TagName, String> ATS_TAG = Map.of(TagName.ATS, AtsName.ASHBY.stringValue());

    private Fetch<AshbyJobEntry> fetcher;
    private FetchedJobsRepository fetchedJobsRepository;
    private MetricService metricService;
    private Resource defaultSlugsFile;
    private AshbyAts defaultAshby;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException, KeyException {
        fetcher = Mockito.mock(Fetch.class);
        fetchedJobsRepository = Mockito.mock(FetchedJobsRepository.class);
        metricService = Mockito.mock(MetricService.class);
        defaultSlugsFile = new ClassPathResource("slugs.json");
        defaultAshby = new AshbyAts(fetchedJobsRepository, fetcher, metricService, defaultSlugsFile, false);
    }

    private AshbyJobEntry job(String id, String title, Boolean isListed) {
        return new AshbyJobEntry(
                id, title, "Engineering", "Platform", "FullTime",
                "Remote - United States", List.of(), null,
                true, "Remote",
                "<p>Build things.</p>", "Build things.",
                OffsetDateTime.parse("2026-09-01T12:00:00Z"),
                "https://jobs.ashbyhq.com/acmerobotics/" + id,
                "https://jobs.ashbyhq.com/acmerobotics/" + id + "/application",
                isListed, null,
                Boolean.FALSE,
                null);
    }

    private static AshbyJobs response(AshbyJobEntry... entries) {
        return new AshbyJobs(Arrays.asList(entries));
    }

    private static List<String> idsOf(List<AtsJobEntry> jobs) {
        return jobs.stream().map(AtsJobEntry::jobId).toList();
    }

    private void verifyJobs(String result, int count) {
        Mockito.verify(metricService).recordCounterByIncrement(MetricName.FETCH_JOBS_COUNT,
                Map.of(TagName.ATS, AtsName.ASHBY.stringValue(), TagName.RESULT, result), count);
    }

    // ------------------------------------------------------------ slug file

    @Test
    @DisplayName("Successfully read slugs file and load Ashby slugs")
    void successfullyReadAndLoadsSlugs() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(defaultSlugsFile.getInputStream());
        List<String> fileSlugValues = StreamSupport.stream(root.get("ats").get("ashby").spliterator(), false)
                .map(JsonNode::asString)
                .toList();

        assertFalse(fileSlugValues.isEmpty());
        assertEquals(fileSlugValues, defaultAshby.getSlugs());
    }

    @Test
    @DisplayName("readSlugs throws FileNotFoundException when the slugs file is missing")
    void testReadSlugsThrowsWhenFileMissing() {
        Resource nonExistentSlugsFile = new ClassPathResource("this-file-does-not-exist.json");
        assertThrowsExactly(FileNotFoundException.class,
                () -> new AshbyAts(fetchedJobsRepository, fetcher, metricService, nonExistentSlugsFile, false));
    }

    @Test
    @DisplayName("missing_ats_slugs.json contents does not have an `ashby` nested key")
    void slugsJsonContentsDoesNotHaveAshbyKey() {
        Resource missingAtsSlugsFile = new ClassPathResource("missing_ats_slugs.json");
        assertThrowsExactly(KeyException.class,
                () -> new AshbyAts(fetchedJobsRepository, fetcher, metricService, missingAtsSlugsFile, false));
    }

    // ------------------------------------------------------------- fetching

    @Test
    @DisplayName("Jobs are collected across every slug and tagged with the slug they came from")
    void successfullyReturnedJobsForEverySlug() {
        Mockito.when(fetcher.fetchJobs("acmerobotics"))
                .thenReturn(response(job("a1", "Backend Engineer", true)));
        Mockito.when(fetcher.fetchJobs("northwind"))
                .thenReturn(response(job("n1", "Platform Engineer", true)));

        List<AtsJobEntry> results = defaultAshby.fetchJobs();

        assertEquals(List.of("a1", "n1"), idsOf(results));
        assertEquals(List.of("acmerobotics", "northwind"), results.stream().map(AtsJobEntry::slug).toList());
    }

    @Test
    @DisplayName("A posting the company chose not to list is dropped")
    void unlistedJobIsDropped() {
        // isListed false means the posting is off the public board and reachable
        // only by direct link: a confidential search, an evergreen pipeline or an
        // internal req, none of which the user could legitimately have found.
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenReturn(response(
                job("a1", "Backend Engineer", true),
                job("a2", "Backend Engineer", false)));
        Mockito.when(fetcher.fetchJobs("northwind")).thenReturn(response());

        List<AtsJobEntry> results = defaultAshby.fetchJobs();

        assertEquals(List.of("a1"), idsOf(results));
    }

    @Test
    @DisplayName("A posting with no isListed field at all is kept")
    void missingIsListedIsKept() {
        // Boolean.FALSE.equals rather than !isListed(): if Ashby ever stops
        // sending the field, the board keeps its jobs instead of silently
        // dropping every one of them.
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenReturn(response(
                job("a1", "Backend Engineer", null)));
        Mockito.when(fetcher.fetchJobs("northwind")).thenReturn(response());

        List<AtsJobEntry> results = defaultAshby.fetchJobs();

        assertEquals(List.of("a1"), idsOf(results));
    }

    @Test
    @DisplayName("An unlisted posting is counted as filtered, not as new")
    void unlistedJobIsCountedAsFiltered() {
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenReturn(response(
                job("a1", "Backend Engineer", true),
                job("a2", "Backend Engineer", false)));
        Mockito.when(fetcher.fetchJobs("northwind")).thenReturn(response());

        defaultAshby.fetchJobs();

        verifyJobs("new", 1);
        verifyJobs("filtered_title", 1);
        Mockito.verify(metricService, Mockito.times(2))
                .recordCounter(MetricName.SLUG_FETCH_SUCCESS_COUNT, ATS_TAG);
    }

    @Test
    @DisplayName("A board whose postings are all unlisted yields nothing and is still a success")
    void everyPostingUnlisted() {
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenReturn(response(
                job("a1", "Backend Engineer", false),
                job("a2", "Platform Engineer", false)));
        Mockito.when(fetcher.fetchJobs("northwind")).thenReturn(response());

        assertTrue(defaultAshby.fetchJobs().isEmpty());
        Mockito.verify(metricService, Mockito.never())
                .recordCounter(Mockito.eq(MetricName.SLUG_FETCH_ERROR_COUNT), Mockito.anyMap());
    }

    @Test
    @DisplayName("The shared checks still apply: unusable, stored and off-title jobs are dropped")
    void sharedFilteringStillApplies() {
        Mockito.when(fetchedJobsRepository.findJobIdByAtsName(AtsName.ASHBY)).thenReturn(Set.of("a2"));
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenReturn(response(
                job("a1", "Backend Engineer", true),
                job("a2", "Backend Engineer", true),      // already stored
                job("a3", "Account Executive", true),     // filtered by title
                job("a4", null, true),                    // no title
                job(null, "Backend Engineer", true),      // no id
                job("a5", "Backend Engineer", false)));   // unlisted
        Mockito.when(fetcher.fetchJobs("northwind")).thenReturn(response());

        List<AtsJobEntry> results = defaultAshby.fetchJobs();

        assertEquals(List.of("a1"), idsOf(results));
        verifyJobs("new", 1);
        verifyJobs("known", 1);
        verifyJobs("invalid", 2);
        // The title rejection and the unlisted one share the series.
        verifyJobs("filtered_title", 2);
    }

    @Test
    @DisplayName("A failing slug is counted once and skipped; the other slug's jobs survive")
    void errorSkipsOnlyThatSlug() {
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                        new byte[0], StandardCharsets.UTF_8));
        Mockito.when(fetcher.fetchJobs("northwind"))
                .thenReturn(response(job("n1", "Backend Engineer", true)));

        List<AtsJobEntry> results = defaultAshby.fetchJobs();

        assertEquals(List.of("n1"), idsOf(results));
        Mockito.verify(metricService).recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT, ATS_TAG);
    }

    @Test
    @DisplayName("A response with no job list is counted and skipped")
    void nullJobListIsSkipped() {
        Mockito.when(fetcher.fetchJobs("acmerobotics")).thenReturn(new AshbyJobs(null));
        Mockito.when(fetcher.fetchJobs("northwind")).thenReturn(response());

        assertTrue(defaultAshby.fetchJobs().isEmpty());
        Mockito.verify(metricService).recordCounter(MetricName.SLUG_FETCH_NULL_RESPONSE_COUNT, ATS_TAG);
    }

    @Test
    @DisplayName("The board resolves to the Ashby ATS by its class name")
    void resolvesToAshbyByClassName() {
        // AtsName.fromBoardClass matches on the upper-cased simple name, so
        // renaming this class would silently unhook it from its limits and slugs.
        assertEquals(AtsName.ASHBY, AtsName.fromBoardClass(AshbyAts.class));
    }
}
