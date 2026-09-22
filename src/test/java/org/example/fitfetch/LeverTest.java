package org.example.fitfetch;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.ats.LeverAts;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.LeverJobEntry;
import org.example.fitfetch.fetching.records.LeverSubRecords.Categories;
import org.example.fitfetch.fetching.records.LeverSubRecords.ListItem;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Board-level tests for {@link LeverAts}: slug loading, and which of the
 * entries a fetch returns survive to be stored.
 *
 * <p>Like {@link AshbyTest} and unlike {@link GreenhouseTest}, these mock the
 * {@code Fetch} and hand it entries built in code; the wire format and its
 * parsing are covered by {@link LeverFetchTest}.
 *
 * <p>What is under test here is mostly an <em>absence</em>. Lever publishes no
 * flag saying a posting should be skipped &mdash; no {@code isListed}, no
 * {@code confidential}, no {@code state} &mdash; because this endpoint is the
 * public board, so an unpublished requisition never appears in the first place.
 * {@link LeverAts} therefore uses the four-argument {@code SlugSweep} and the
 * shared checks are the whole filter. These tests pin that: everything the
 * shared checks accept is kept, including the postings a predicate invented
 * from {@code categories.commitment} would have thrown away.
 */
public class LeverTest {

    /**
     * Stands in for the provider's response record so these tests depend only on
     * the shared {@link AtsResponse} contract.
     */
    private record LeverJobs(List<LeverJobEntry> jobs) implements AtsResponse<LeverJobEntry> {
    }

    private static final Map<TagName, String> ATS_TAG = Map.of(TagName.ATS, AtsName.LEVER.stringValue());

    private Fetch<LeverJobEntry> fetcher;
    private FetchedJobsRepository fetchedJobsRepository;
    private MetricService metricService;
    private Resource defaultSlugsFile;
    private LeverAts defaultLever;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException, KeyException {
        fetcher = Mockito.mock(Fetch.class);
        fetchedJobsRepository = Mockito.mock(FetchedJobsRepository.class);
        metricService = Mockito.mock(MetricService.class);
        defaultSlugsFile = new ClassPathResource("slugs.json");
        defaultLever = new LeverAts(fetchedJobsRepository, fetcher, metricService, defaultSlugsFile, false);
    }

    private LeverJobEntry job(String id, String title) {
        return job(id, title, "Full-time");
    }

    private LeverJobEntry job(String id, String title, String commitment) {
        return new LeverJobEntry(
                id, title, 1785981069633L,
                new Categories(List.of("Remote - United States"), "Remote - United States",
                        "Platform", "Engineering", commitment, null),
                "US", "remote",
                "<div>Build things.</div>", "Build things.",
                "<div>Build things.</div>", "Build things.", "", "",
                "<div>EEO statement</div>", "EEO statement",
                List.of(new ListItem("Requirements", "<li>Java</li>")),
                "https://jobs.lever.co/cogswellcogs/" + id,
                "https://jobs.lever.co/cogswellcogs/" + id + "/apply",
                null, "", "", null);
    }

    private static LeverJobs response(LeverJobEntry... entries) {
        return new LeverJobs(Arrays.asList(entries));
    }

    private static List<String> idsOf(List<AtsJobEntry> jobs) {
        return jobs.stream().map(AtsJobEntry::jobId).toList();
    }

    private void verifyJobs(String result, int count) {
        Mockito.verify(metricService).recordCounterByIncrement(MetricName.FETCH_JOBS_COUNT,
                Map.of(TagName.ATS, AtsName.LEVER.stringValue(), TagName.RESULT, result), count);
    }

    // ------------------------------------------------------------ slug file

    @Test
    @DisplayName("Successfully read slugs file and load Lever slugs")
    void successfullyReadAndLoadsSlugs() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(defaultSlugsFile.getInputStream());
        List<String> fileSlugValues = StreamSupport.stream(root.get("ats").get("lever").spliterator(), false)
                .map(JsonNode::asString)
                .toList();

        assertFalse(fileSlugValues.isEmpty());
        assertEquals(fileSlugValues, defaultLever.getSlugs());
    }

    @Test
    @DisplayName("Slugs are passed through verbatim, because Lever slugs are case-sensitive")
    void slugsAreNotNormalized() {
        // A wrongly-cased slug is a 404, not a redirect, so nothing may
        // lower-case these on the way through.
        assertEquals(List.of("cogswellcogs", "vandelayindustries"), defaultLever.getSlugs());
    }

    @Test
    @DisplayName("readSlugs throws FileNotFoundException when the slugs file is missing")
    void testReadSlugsThrowsWhenFileMissing() {
        Resource nonExistentSlugsFile = new ClassPathResource("this-file-does-not-exist.json");
        assertThrowsExactly(FileNotFoundException.class,
                () -> new LeverAts(fetchedJobsRepository, fetcher, metricService, nonExistentSlugsFile, false));
    }

    @Test
    @DisplayName("missing_ats_slugs.json contents does not have a `lever` nested key")
    void slugsJsonContentsDoesNotHaveLeverKey() {
        Resource missingAtsSlugsFile = new ClassPathResource("missing_ats_slugs.json");
        assertThrowsExactly(KeyException.class,
                () -> new LeverAts(fetchedJobsRepository, fetcher, metricService, missingAtsSlugsFile, false));
    }

    // ------------------------------------------------------------- fetching

    @Test
    @DisplayName("Jobs are collected across every slug and tagged with the slug they came from")
    void successfullyReturnedJobsForEverySlug() {
        Mockito.when(fetcher.fetchJobs("cogswellcogs"))
                .thenReturn(response(job("c1", "Backend Engineer")));
        Mockito.when(fetcher.fetchJobs("vandelayindustries"))
                .thenReturn(response(job("v1", "Platform Engineer")));

        List<AtsJobEntry> results = defaultLever.fetchJobs();

        assertEquals(List.of("c1", "v1"), idsOf(results));
        assertEquals(List.of("cogswellcogs", "vandelayindustries"),
                results.stream().map(AtsJobEntry::slug).toList());
    }

    @Test
    @DisplayName("Nothing is dropped for a provider-specific reason: there is no keep predicate")
    void noProviderRuleDropsAnything() {
        // Lever has no isListed analogue, so every posting the endpoint returns
        // is one the company published. The only reasons to drop one are the
        // shared ones.
        Mockito.when(fetcher.fetchJobs("cogswellcogs")).thenReturn(response(
                job("c1", "Backend Engineer"),
                job("c2", "Backend Engineer", "Full-Time"),
                job("c3", "Backend Engineer", "Full-time, Permanent"),
                job("c4", "Backend Engineer", null)));
        Mockito.when(fetcher.fetchJobs("vandelayindustries")).thenReturn(response());

        List<AtsJobEntry> results = defaultLever.fetchJobs();

        // categories.commitment is uncontrolled free text -- the same thing is
        // spelled three ways here and omitted once -- so any rule built on it
        // would silently drop jobs on the boards that phrase it differently.
        assertEquals(List.of("c1", "c2", "c3", "c4"), idsOf(results));
        verifyJobs("new", 4);
        Mockito.verify(metricService, Mockito.never()).recordCounterByIncrement(
                Mockito.eq(MetricName.FETCH_JOBS_COUNT),
                Mockito.eq(Map.of(TagName.ATS, AtsName.LEVER.stringValue(), TagName.RESULT, "filtered_title")),
                Mockito.intThat(count -> count > 0));
    }

    @Test
    @DisplayName("The shared checks still apply: unusable, stored and off-title jobs are dropped")
    void sharedFilteringStillApplies() {
        Mockito.when(fetchedJobsRepository.findJobIdByAtsName(AtsName.LEVER)).thenReturn(Set.of("c2"));
        Mockito.when(fetcher.fetchJobs("cogswellcogs")).thenReturn(response(
                job("c1", "Backend Engineer"),
                job("c2", "Backend Engineer"),          // already stored
                job("c3", "Account Executive"),         // filtered by title
                job("c4", null),                        // no title
                job(null, "Backend Engineer")));        // no id
        Mockito.when(fetcher.fetchJobs("vandelayindustries")).thenReturn(response());

        List<AtsJobEntry> results = defaultLever.fetchJobs();

        assertEquals(List.of("c1"), idsOf(results));
        verifyJobs("new", 1);
        verifyJobs("known", 1);
        verifyJobs("invalid", 2);
        verifyJobs("filtered_title", 1);
    }

    @Test
    @DisplayName("An empty board is a success that yields nothing, not an error")
    void emptyBoardIsASuccess() {
        // Lever answers a board with nothing open with the two-byte body [].
        Mockito.when(fetcher.fetchJobs("cogswellcogs")).thenReturn(response());
        Mockito.when(fetcher.fetchJobs("vandelayindustries")).thenReturn(response());

        assertTrue(defaultLever.fetchJobs().isEmpty());
        Mockito.verify(metricService, Mockito.times(2))
                .recordCounter(MetricName.SLUG_FETCH_SUCCESS_COUNT, ATS_TAG);
        Mockito.verify(metricService, Mockito.never())
                .recordCounter(Mockito.eq(MetricName.SLUG_FETCH_ERROR_COUNT), Mockito.anyMap());
    }

    @Test
    @DisplayName("A failing slug is counted once and skipped; the other slug's jobs survive")
    void errorSkipsOnlyThatSlug() {
        Mockito.when(fetcher.fetchJobs("cogswellcogs")).thenThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                        new byte[0], StandardCharsets.UTF_8));
        Mockito.when(fetcher.fetchJobs("vandelayindustries"))
                .thenReturn(response(job("v1", "Backend Engineer")));

        List<AtsJobEntry> results = defaultLever.fetchJobs();

        assertEquals(List.of("v1"), idsOf(results));
        Mockito.verify(metricService).recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT, ATS_TAG);
    }

    @Test
    @DisplayName("A dead slug's 404 is counted as a missing board, not as an error")
    void deadSlugIsCountedAsMissing() {
        // Roughly 42% of the harvested Lever slug list is dead at any time, so
        // this is the common case rather than the exceptional one: it must not
        // land in fetching.error.count and drown the real failures.
        Mockito.when(fetcher.fetchJobs("cogswellcogs")).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(),
                        "{\"ok\":false,\"error\":\"Document not found\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
        Mockito.when(fetcher.fetchJobs("vandelayindustries"))
                .thenReturn(response(job("v1", "Backend Engineer")));

        List<AtsJobEntry> results = defaultLever.fetchJobs();

        assertEquals(List.of("v1"), idsOf(results));
        Mockito.verify(metricService).recordCounter(MetricName.SLUG_FETCH_MISSING_COUNT, ATS_TAG);
        Mockito.verify(metricService, Mockito.never())
                .recordCounter(Mockito.eq(MetricName.SLUG_FETCH_ERROR_COUNT), Mockito.anyMap());
    }

    @Test
    @DisplayName("A response with no job list is counted and skipped")
    void nullJobListIsSkipped() {
        Mockito.when(fetcher.fetchJobs("cogswellcogs")).thenReturn(new LeverJobs(null));
        Mockito.when(fetcher.fetchJobs("vandelayindustries")).thenReturn(response());

        assertTrue(defaultLever.fetchJobs().isEmpty());
        Mockito.verify(metricService).recordCounter(MetricName.SLUG_FETCH_NULL_RESPONSE_COUNT, ATS_TAG);
    }

    @Test
    @DisplayName("The board resolves to the Lever ATS by its class name")
    void resolvesToLeverByClassName() {
        // AtsName.fromBoardClass matches on the upper-cased simple name, so
        // renaming this class would silently unhook it from its limits and slugs.
        assertEquals(AtsName.LEVER, AtsName.fromBoardClass(LeverAts.class));
    }
}
