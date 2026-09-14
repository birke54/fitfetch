package org.example.fitfetch.normalize;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.NormalizeStatus;
import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.location.GeocodingDisabledException;
import org.example.fitfetch.location.OriginRadius;
import org.example.fitfetch.location.RadiusSearchService;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NormalizeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final OriginRadius RADIUS = OriginRadius.of(47.7231d, -122.2967d, 50);
    private static final NormalizedData ANSWER = new NormalizedData(Seniority.SENIOR,
            List.of(new Signal(SignalClassification.REQUIRED_SKILL, "Knows Java.")));

    private FetchedJobsRepository fetchedJobs;
    private NormalizedJobRepository normalizedJobs;
    private LlmSignalExtractor extractor;
    private RadiusSearchService radiusSearch;
    private NormalizeService service;

    private long nextId = 1L;

    @BeforeEach
    void setUp() {
        fetchedJobs = mock(FetchedJobsRepository.class);
        normalizedJobs = mock(NormalizedJobRepository.class);
        extractor = mock(LlmSignalExtractor.class);
        radiusSearch = mock(RadiusSearchService.class);
        when(radiusSearch.around(50)).thenReturn(RADIUS);
        when(extractor.model()).thenReturn("qwen2.5:14b");
        service = newService(true);
    }

    @SuppressWarnings("unchecked")
    private NormalizeService newService(boolean enabled) {
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
                Clock.fixed(NOW, ZoneOffset.UTC), enabled, 5, 50);
    }

    private FetchedJob job(String content) {
        GreenhouseJobEntry entry = new GreenhouseJobEntry(
                "https://example.com", null, nextId, null, null, null, "Backend Engineer",
                "Co", null, "en", null, content, null, List.of(), List.of(), List.of(), "co");
        FetchedJob fetched = new FetchedJob(AtsName.GREENHOUSE, String.valueOf(nextId), "co", entry);
        fetched.setId(nextId++);
        return fetched;
    }

    private void pageContains(FetchedJob... jobs) {
        when(fetchedJobs.findPendingNormalizationWithinRadius(RADIUS, 5)).thenReturn(List.of(jobs));
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
        order.verify(fetchedJobs).findPendingNormalizationWithinRadius(RADIUS, 5);
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
    @DisplayName("A job's description is sent as plain text, and the answer written with its provenance")
    void testNormalizesAndPersists() {
        FetchedJob job = job("<p>Senior engineer</p><ul><li>Knows Java</li></ul>");
        pageContains(job);
        when(extractor.extract(anyString())).thenReturn(Optional.of(ANSWER));

        assertEquals(1, service.normalizeOnePage());

        verify(extractor).extract("Senior engineer\nKnows Java");
        ArgumentCaptor<NormalizedJob> saved = ArgumentCaptor.forClass(NormalizedJob.class);
        verify(normalizedJobs).save(saved.capture());
        assertEquals(job.getId(), saved.getValue().getFetchedJobId());
        assertEquals(Seniority.SENIOR, saved.getValue().getSeniority());
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
        when(extractor.extract(anyString())).thenReturn(Optional.of(ANSWER));

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
        when(extractor.extract(anyString())).thenReturn(Optional.of(ANSWER));

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
        when(extractor.extract(anyString())).thenReturn(Optional.empty());

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
        when(extractor.extract("Broken")).thenThrow(new IllegalStateException("bug"));
        when(extractor.extract("Knows Java")).thenReturn(Optional.of(ANSWER));

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
        when(extractor.extract(anyString())).thenReturn(Optional.of(ANSWER));
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
        when(extractor.extract("First")).thenReturn(Optional.of(ANSWER));
        when(extractor.extract("Second")).thenThrow(new SignalExtractionException("Ollama unreachable", null));

        assertDoesNotThrow(() -> service.normalizePendingJobs());

        verify(fetchedJobs).updateNormalizeStatus(first.getId(), NormalizeStatus.NORMALIZED);
        verify(fetchedJobs, never()).updateNormalizeStatus(eq(second.getId()), any());
        verify(extractor, never()).extract("Third");
    }

    @Test
    @DisplayName("A database failure is caught and logged rather than escaping the scheduler")
    void testDatabaseFailureHandled() {
        when(fetchedJobs.markOutOfRangeForNormalization(any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertDoesNotThrow(() -> service.normalizePendingJobs());
        verifyNoInteractions(extractor);
    }
}
