package org.example.fitfetch.normalize;

import org.example.fitfetch.MutableClock;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.LocationStatus;
import org.example.fitfetch.domain.NormalizeStatus;
import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.location.GeocodingDisabledException;
import org.example.fitfetch.location.OriginRadius;
import org.example.fitfetch.location.RadiusSearchService;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NormalizeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final OriginRadius RADIUS = OriginRadius.of(47.7231d, -122.2967d, 50);
    private static final String TITLE = "Backend Engineer";
    private static final NormalizedData ANSWER = new NormalizedData(Seniority.SENIOR, Track.IC,
            EmploymentType.FULL_TIME, 5,
            new HardRequirements(Degree.BACHELORS, false, Sponsorship.NO, List.of(), false, true),
            List.of("payments"),
            List.of(new Signal(SignalClassification.REQUIRED_SKILL, "Knows Java.", List.of("Java"), 3)));

    private FetchedJobsRepository fetchedJobs;
    private NormalizedJobRepository normalizedJobs;
    private LlmSignalExtractor extractor;
    private RadiusSearchService radiusSearch;
    private MetricService metricService;
    private NormalizeService service;

    private long nextId = 1L;

    @BeforeEach
    void setUp() {
        fetchedJobs = mock(FetchedJobsRepository.class);
        normalizedJobs = mock(NormalizedJobRepository.class);
        extractor = mock(LlmSignalExtractor.class);
        radiusSearch = mock(RadiusSearchService.class);
        metricService = mock(MetricService.class);
        when(radiusSearch.around(50)).thenReturn(RADIUS);
        when(extractor.model()).thenReturn("qwen2.5:14b");
        service = newService(true);
    }

    private NormalizeService newService(boolean enabled) {
        return newService(enabled, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private NormalizeService newService(boolean enabled, Clock clock) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        // Run callbacks inline so the write path is exercised without a
        // transaction manager.
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(template).executeWithoutResult(any());
        when(template.execute(any())).thenAnswer(
                invocation -> invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));

        return new NormalizeService(fetchedJobs, normalizedJobs, extractor, radiusSearch, template,
                clock, metricService, enabled, 5, 50);
    }

    private FetchedJob job(String content) {
        GreenhouseJobEntry entry = new GreenhouseJobEntry(
                "https://example.com", null, nextId, null, null, null, TITLE,
                "Co", null, "en", null, content, null, List.of(), List.of(), List.of(), "co");
        FetchedJob fetched = new FetchedJob(AtsName.GREENHOUSE, String.valueOf(nextId), "co", entry);
        fetched.setId(nextId++);
        return fetched;
    }

    private void pageContains(FetchedJob... jobs) {
        when(fetchedJobs.findPendingNormalizationWithinRadius(eq(RADIUS), anyLong(), eq(5)))
                .thenReturn(List.of(jobs));
    }

    private void pageAfter(long afterId, FetchedJob... jobs) {
        when(fetchedJobs.findPendingNormalizationWithinRadius(RADIUS, afterId, 5)).thenReturn(List.of(jobs));
    }

    private void runs(int count) {
        for (int i = 0; i < count; i++) {
            service.normalizePendingJobs();
        }
    }

    private static SignalExtractionException outage() {
        return new SignalExtractionException("Ollama unreachable", null);
    }

    // ------------------------------------------------------------- disabled

    @Test
    @DisplayName("A disabled pass touches nothing, not even the origin")
    void testDisabledIsNoOp() {
        newService(false).normalizePendingJobs();

        verifyNoInteractions(fetchedJobs, normalizedJobs, extractor, radiusSearch);
    }

    // ---------------------------------------------------------------- range

    @Test
    @DisplayName("Out-of-range jobs are swept before the page is read, with the same radius")
    void testSweepsBeforeReading() {
        pageContains();

        service.normalizeOnePage();

        InOrder order = inOrder(fetchedJobs);
        order.verify(fetchedJobs).markOutOfRangeForNormalization(RADIUS);
        order.verify(fetchedJobs).findPendingNormalizationWithinRadius(RADIUS, 0L, 5);
        verifyNoInteractions(extractor);
    }

    @Test
    @DisplayName("Without the origin there is no radius, so nothing is swept or sent to the model")
    void testOriginUnavailable() {
        when(radiusSearch.around(50)).thenThrow(new GeocodingDisabledException("origin"));

        assertDoesNotThrow(() -> service.normalizePendingJobs());

        verifyNoInteractions(fetchedJobs, extractor);
    }

    // ------------------------------------------------------------ happy path

    @Test
    @DisplayName("A job's title and plain-text description are sent, and the whole answer written with its provenance")
    void testNormalizesAndPersists() {
        FetchedJob job = job("<p>Senior engineer</p><ul><li>Knows Java</li></ul>");
        pageContains(job);
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        assertEquals(1, service.normalizeOnePage());

        verify(extractor).extract(TITLE, "Senior engineer\nKnows Java");
        ArgumentCaptor<NormalizedJob> saved = ArgumentCaptor.forClass(NormalizedJob.class);
        verify(normalizedJobs).save(saved.capture());
        assertEquals(job.getId(), saved.getValue().getFetchedJobId());
        assertEquals(Seniority.SENIOR, saved.getValue().getSeniority());
        assertEquals(Track.IC, saved.getValue().getTrack());
        assertEquals(EmploymentType.FULL_TIME, saved.getValue().getEmploymentType());
        assertEquals(5, saved.getValue().getMinYearsExperience());
        assertEquals(ANSWER.requirements(), saved.getValue().getRequirements());
        assertEquals(List.of("payments"), saved.getValue().getDomains());
        assertEquals(ANSWER.signals(), saved.getValue().getSignals());
        assertEquals("qwen2.5:14b", saved.getValue().getModel());
        assertEquals(SignalPrompt.VERSION, saved.getValue().getPromptVersion());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), saved.getValue().getNormalizedAt());
        verify(fetchedJobs).updateNormalizeStatus(job.getId(), NormalizeStatus.NORMALIZED);
    }

    @Test
    @DisplayName("A previous normalization is cleared before the new one is written")
    void testExistingRowDeletedFirst() {
        // A requeued job still has its old row, which the unique constraint
        // would otherwise reject the new one against.
        FetchedJob job = job("Knows Java");
        pageContains(job);
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        service.normalizeOnePage();

        InOrder order = inOrder(normalizedJobs);
        order.verify(normalizedJobs).deleteByFetchedJobId(job.getId());
        order.verify(normalizedJobs).save(any());
    }

    @Test
    @DisplayName("Status is set with a targeted update, never by saving the whole job")
    void testStatusUpdatedWithoutSavingJob() {
        // Saving the entity would write back the location status loaded at the
        // start of the run over anything the location pass has changed since.
        pageContains(job("Knows Java"));
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        service.normalizeOnePage();

        verify(fetchedJobs, never()).save(any());
        verify(fetchedJobs, never()).saveAll(anyList());
    }

    // --------------------------------------------------------------- FAILED

    @Test
    @DisplayName("A job the model gives no usable answer for is marked FAILED, so it is never sent again")
    void testNoAnswerMarksFailed() {
        FetchedJob job = job("Knows Java");
        pageContains(job);
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.empty());

        assertEquals(0, service.normalizeOnePage());

        verify(fetchedJobs).updateNormalizeStatus(job.getId(), NormalizeStatus.FAILED);
        verify(normalizedJobs, never()).save(any());
    }

    @Test
    @DisplayName("A job with an empty description is marked FAILED without a model call")
    void testEmptyDescriptionMarksFailed() {
        FetchedJob blank = job("<p> </p>");
        FetchedJob missing = job(null);
        pageContains(blank, missing);

        service.normalizeOnePage();

        verifyNoInteractions(extractor);
        verify(fetchedJobs).updateNormalizeStatus(blank.getId(), NormalizeStatus.FAILED);
        verify(fetchedJobs).updateNormalizeStatus(missing.getId(), NormalizeStatus.FAILED);
    }

    @Test
    @DisplayName("A bug extracting one job fails that job and the page carries on")
    void testUnexpectedExtractionErrorFailsOnlyThatJob() {
        FetchedJob broken = job("Broken");
        FetchedJob fine = job("Knows Java");
        pageContains(broken, fine);
        when(extractor.extract(TITLE, "Broken")).thenThrow(new IllegalStateException("bug"));
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));

        assertEquals(1, service.normalizeOnePage());

        verify(fetchedJobs).updateNormalizeStatus(broken.getId(), NormalizeStatus.FAILED);
        verify(fetchedJobs).updateNormalizeStatus(fine.getId(), NormalizeStatus.NORMALIZED);
    }

    @Test
    @DisplayName("A job whose answer cannot be written is marked FAILED; the rest of the page is written")
    void testWriteFailureIsolatedToOneJob() {
        FetchedJob bad = job("Bad");
        FetchedJob good = job("Good");
        pageContains(bad, good);
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.of(ANSWER));
        when(normalizedJobs.save(any())).thenAnswer(invocation -> {
            NormalizedJob row = invocation.getArgument(0);
            if (row.getFetchedJobId().equals(bad.getId())) {
                throw new DataIntegrityViolationException("constraint violated");
            }
            return row;
        });

        assertEquals(1, service.normalizeOnePage());

        verify(fetchedJobs).updateNormalizeStatus(bad.getId(), NormalizeStatus.FAILED);
        verify(fetchedJobs).updateNormalizeStatus(good.getId(), NormalizeStatus.NORMALIZED);
    }

    // -------------------------------------------------------------- outages

    @Test
    @DisplayName("A model outage stops the run: answered jobs stay written, the rest stay pending")
    void testOutageStopsRunKeepingEarlierWrites() {
        FetchedJob first = job("First");
        FetchedJob second = job("Second");
        FetchedJob third = job("Third");
        pageContains(first, second, third);
        when(extractor.extract(TITLE, "First")).thenReturn(Optional.of(ANSWER));
        when(extractor.extract(TITLE, "Second")).thenThrow(outage());

        assertDoesNotThrow(() -> service.normalizePendingJobs());

        verify(fetchedJobs).updateNormalizeStatus(first.getId(), NormalizeStatus.NORMALIZED);
        verify(fetchedJobs, never()).updateNormalizeStatus(eq(second.getId()), any());
        verify(extractor, never()).extract(TITLE, "Third");
    }

    // -------------------------------------------------------------- paging

    @Test
    @DisplayName("The pass pages past the jobs it has seen and wraps to the start at the end")
    void testCursorAdvancesAndWraps() {
        // Always reading the first page would hand the pass the same set-aside
        // jobs forever once a page filled up with them.
        FetchedJob first = job("First");
        FetchedJob second = job("Second");
        pageAfter(0L, first, second);
        pageAfter(second.getId());
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        service.normalizeOnePage();
        service.normalizeOnePage();

        InOrder order = inOrder(fetchedJobs);
        order.verify(fetchedJobs).findPendingNormalizationWithinRadius(RADIUS, 0L, 5);
        order.verify(fetchedJobs).findPendingNormalizationWithinRadius(RADIUS, second.getId(), 5);
        order.verify(fetchedJobs).findPendingNormalizationWithinRadius(RADIUS, 0L, 5);
    }

    @Test
    @DisplayName("A stopped run does not move the cursor, so the next run starts from the same place")
    void testStoppedRunRetriedFromSameCursor() {
        pageContains(job("Knows Java"));
        when(extractor.extract(anyString(), anyString())).thenThrow(outage());

        runs(2);

        verify(fetchedJobs, times(2)).findPendingNormalizationWithinRadius(RADIUS, 0L, 5);
    }

    // -------------------------------------------------------------- strikes

    @Test
    @DisplayName("A job that keeps failing is set aside after the strike limit, and the rest of the page is normalized")
    void testRepeatFailureStrikesOut() {
        // Without this, a job that fails on its own (not an outage) would stop
        // every run at the same place and nothing behind it would be normalized.
        FetchedJob poison = job("Poison");
        FetchedJob fine = job("Knows Java");
        pageContains(poison, fine);
        when(extractor.extract(TITLE, "Poison")).thenThrow(outage());
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));

        runs(NormalizeService.STRIKE_LIMIT - 1);
        verify(extractor, never()).extract(TITLE, "Knows Java");

        service.normalizePendingJobs();

        verify(fetchedJobs).updateNormalizeStatus(fine.getId(), NormalizeStatus.NORMALIZED);
        verify(fetchedJobs, never()).updateNormalizeStatus(eq(poison.getId()), any());
    }

    @Test
    @DisplayName("Only one struck-out job is retried per run, so an outage costs at most a few calls")
    void testOneStruckOutJobProbedPerRun() {
        FetchedJob first = job("First");
        FetchedJob second = job("Second");
        FetchedJob fine = job("Knows Java");
        pageContains(first, second, fine);
        when(extractor.extract(TITLE, "First")).thenThrow(outage());
        when(extractor.extract(TITLE, "Second")).thenThrow(outage());
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));

        // First strikes out on run 2; Second collects its strikes on runs 2 and
        // 3, while First is retried once per run.
        runs(2 * NormalizeService.STRIKE_LIMIT - 1);
        clearInvocations(extractor);

        service.normalizePendingJobs();

        verify(extractor).extract(TITLE, "First");
        verify(extractor, never()).extract(TITLE, "Second");
        verify(extractor).extract(TITLE, "Knows Java");
    }

    @Test
    @DisplayName("A struck-out job that gets an answer is given a clean slate")
    void testSuccessClearsStrikes() {
        FetchedJob flaky = job("Flaky");
        FetchedJob fine = job("Knows Java");
        pageContains(flaky, fine);
        when(extractor.extract(TITLE, "Flaky"))
                .thenThrow(outage())
                .thenThrow(outage())
                .thenReturn(Optional.of(ANSWER))
                .thenThrow(outage());
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));

        // Run 1 stops on Flaky, run 2 sets it aside, run 3 retries it and succeeds.
        runs(NormalizeService.STRIKE_LIMIT + 1);
        verify(fetchedJobs).updateNormalizeStatus(flaky.getId(), NormalizeStatus.NORMALIZED);
        clearInvocations(extractor);

        service.normalizePendingJobs();

        // One failure after the success stops the run again, as for any job.
        verify(extractor).extract(TITLE, "Flaky");
        verify(extractor, never()).extract(TITLE, "Knows Java");
    }

    @Test
    @DisplayName("A job with an unusable answer is failed, not struck, since retrying cannot help")
    void testNoAnswerIsNotAStrike() {
        FetchedJob job = job("Knows Java");
        pageContains(job);
        when(extractor.extract(anyString(), anyString())).thenReturn(Optional.empty());

        service.normalizeOnePage();

        verify(fetchedJobs).updateNormalizeStatus(job.getId(), NormalizeStatus.FAILED);
    }

    @Test
    @DisplayName("A database failure is caught and logged rather than escaping the scheduler")
    void testDatabaseFailureHandled() {
        when(fetchedJobs.markOutOfRangeForNormalization(any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertDoesNotThrow(() -> service.normalizePendingJobs());
        verifyNoInteractions(extractor);
    }

    // -------------------------------------------------------------- metrics

    private void verifyJobs(String result, int count) {
        verify(metricService).recordCounterByIncrement(MetricName.NORMALIZE_JOBS_COUNT,
                Map.of(TagName.RESULT, result), count);
    }

    private void verifyFailed(String reason) {
        verify(metricService).recordCounter(MetricName.NORMALIZE_JOBS_FAILED_COUNT, Map.of(TagName.REASON, reason));
    }

    private void verifyStopped(String reason) {
        verify(metricService).recordCounter(MetricName.NORMALIZE_PASS_STOPPED_COUNT, Map.of(TagName.REASON, reason));
    }

    /** The supplier the most recently built service registered for a gauge. */
    @SuppressWarnings("unchecked")
    private Supplier<Number> gauge(MetricName name, Map<TagName, String> tags) {
        ArgumentCaptor<Supplier<Number>> gauge = ArgumentCaptor.forClass(Supplier.class);
        verify(metricService, atLeastOnce()).registerGauge(eq(name), eq(tags), gauge.capture());
        return gauge.getValue();
    }

    @Test
    @DisplayName("Each job's outcome is counted, and each failure with its reason")
    void testOutcomesCounted() {
        pageContains(job("Knows Java"), job(""), job("Nothing here"));
        when(fetchedJobs.markOutOfRangeForNormalization(RADIUS)).thenReturn(3);
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));
        when(extractor.extract(TITLE, "Nothing here")).thenReturn(Optional.empty());

        service.normalizePendingJobs();

        verifyJobs("normalized", 1);
        verifyJobs("out_of_range", 3);
        verify(metricService, times(2)).recordCounterByIncrement(MetricName.NORMALIZE_JOBS_COUNT,
                Map.of(TagName.RESULT, "failed"), 1);
        verifyFailed("empty_description");
        verifyFailed("no_answer");
    }

    @Test
    @DisplayName("A bug in extraction and a failed write are counted as failures with their own reasons")
    void testErrorAndWriteFailureCounted() {
        FetchedJob broken = job("Broken");
        FetchedJob unwritable = job("Knows Java");
        pageContains(broken, unwritable);
        when(extractor.extract(TITLE, "Broken")).thenThrow(new IllegalStateException("bug"));
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));
        when(normalizedJobs.save(any())).thenThrow(new DataIntegrityViolationException("constraint"));

        service.normalizePendingJobs();

        verifyFailed("error");
        verifyFailed("write_error");
    }

    @Test
    @DisplayName("A job set aside after repeated outages is counted")
    void testSetAsideCounted() {
        pageContains(job("Poison"), job("Knows Java"));
        when(extractor.extract(TITLE, "Poison")).thenThrow(outage());
        when(extractor.extract(TITLE, "Knows Java")).thenReturn(Optional.of(ANSWER));

        runs(NormalizeService.STRIKE_LIMIT);

        verifyJobs("set_aside", 1);
        verifyStopped("model_unavailable");
    }

    @Test
    @DisplayName("A pass stopped by the origin or the database is counted with its reason")
    void testStopsCounted() {
        when(radiusSearch.around(50)).thenThrow(new GeocodingDisabledException("origin"));
        service.normalizePendingJobs();
        verifyStopped("origin_unavailable");

        reset(radiusSearch);
        when(radiusSearch.around(50)).thenReturn(RADIUS);
        when(fetchedJobs.markOutOfRangeForNormalization(any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        service.normalizePendingJobs();
        verifyStopped("error");
    }

    @Test
    @DisplayName("After a successful pass the backlog gauge holds located pending, failed and out-of-range counts")
    void testBacklogGauge() {
        pageContains();
        when(fetchedJobs.countByNormalizeStatusAndLocationStatus(NormalizeStatus.PENDING, LocationStatus.RESOLVED))
                .thenReturn(12L);
        when(fetchedJobs.countByNormalizeStatus(NormalizeStatus.FAILED)).thenReturn(2L);
        when(fetchedJobs.countByNormalizeStatus(NormalizeStatus.OUT_OF_RANGE)).thenReturn(40L);

        service.normalizePendingJobs();

        assertEquals(12L, gauge(MetricName.NORMALIZE_JOBS_BACKLOG, Map.of(TagName.STATUS, "pending")).get());
        assertEquals(2L, gauge(MetricName.NORMALIZE_JOBS_BACKLOG, Map.of(TagName.STATUS, "failed")).get());
        assertEquals(40L, gauge(MetricName.NORMALIZE_JOBS_BACKLOG, Map.of(TagName.STATUS, "out_of_range")).get());
    }

    @Test
    @DisplayName("A backlog count that fails does not count as the pass stopping")
    void testBacklogFailureIsNotAStop() {
        pageContains();
        when(fetchedJobs.countByNormalizeStatus(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertDoesNotThrow(() -> service.normalizePendingJobs());

        verify(metricService, never()).recordCounter(eq(MetricName.NORMALIZE_PASS_STOPPED_COUNT), anyMap());
    }

    @Test
    @DisplayName("The last-success gauge starts at startup, moves on a successful pass, and stays put when one stops")
    void testLastSuccessGauge() {
        MutableClock clock = new MutableClock(NOW);
        NormalizeService timed = newService(true, clock);
        Supplier<Number> lastSuccess = gauge(MetricName.NORMALIZE_PASS_LAST_SUCCESS_SECONDS, Map.of());
        assertEquals(NOW.getEpochSecond(), lastSuccess.get());

        pageContains();
        clock.advance(Duration.ofMinutes(10));
        timed.normalizePendingJobs();
        assertEquals(NOW.plus(Duration.ofMinutes(10)).getEpochSecond(), lastSuccess.get());

        when(radiusSearch.around(50)).thenThrow(new GeocodingDisabledException("origin"));
        clock.advance(Duration.ofMinutes(10));
        timed.normalizePendingJobs();
        assertEquals(NOW.plus(Duration.ofMinutes(10)).getEpochSecond(), lastSuccess.get());
    }
}
