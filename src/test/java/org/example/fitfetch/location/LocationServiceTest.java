package org.example.fitfetch.location;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.JobLocation;
import org.example.fitfetch.domain.LocationStatus;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");
    private static final String ORIGIN = "3001 NE 130th St, Seattle, WA 98125";

    private static final GeocodeOutcome POINT = new GeocodeOutcome(
            GeocodeStatus.OK, 47.7231d, -122.2967d, "Seattle", "p", "ROOFTOP", false);

    private FetchedJobsRepository fetchedJobs;
    private JobLocationRepository jobLocations;
    private LocationResolver resolver;
    private LocationService service;

    private long nextId = 1L;

    @BeforeEach
    void setUp() {
        fetchedJobs = mock(FetchedJobsRepository.class);
        jobLocations = mock(JobLocationRepository.class);
        resolver = mock(LocationResolver.class);
        service = newService(true);
    }

    private LocationService newService(boolean enabled) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        // Run the callback inline so the write path is exercised without a
        // transaction manager.
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(null);
            return null;
        }).when(template).executeWithoutResult(any());

        return new LocationService(fetchedJobs, jobLocations, resolver, template,
                Clock.fixed(NOW, ZoneOffset.UTC), enabled, 200);
    }

    private FetchedJob job(String locationName) {
        GreenhouseJobEntry entry = new GreenhouseJobEntry(
                "https://example.com", null, nextId, null, null, null, "Backend Engineer",
                "Co", null, "en", null, "jd",
                locationName == null ? null : new Location(locationName),
                List.of(), List.of(), List.of(), "co");
        FetchedJob fetched = new FetchedJob(AtsName.GREENHOUSE, String.valueOf(nextId), "co", entry, false);
        fetched.setId(nextId++);
        return fetched;
    }

    private ResolvedLocation resolved(String raw, Resolution resolution, GeocodeOutcome outcome) {
        return new ResolvedLocation(
                new LocationInput(raw, resolution,
                        resolution.hasCoordinates() ? ORIGIN : null, null),
                outcome, SourceTier.CURATED, true);
    }

    private void pageContains(FetchedJob... jobs) {
        when(fetchedJobs.findByLocationStatusAndIdGreaterThan(
                eq(LocationStatus.PENDING), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(jobs));
    }

    private void pageAfter(long afterId, FetchedJob... jobs) {
        when(fetchedJobs.findByLocationStatusAndIdGreaterThan(
                eq(LocationStatus.PENDING), eq(afterId), any(Pageable.class)))
                .thenReturn(List.of(jobs));
    }

    private List<JobLocation> savedRows() {
        ArgumentCaptor<List<JobLocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(jobLocations).saveAll(saved.capture());
        return saved.getValue();
    }

    // ------------------------------------------------------------- disabled

    @Test
    @DisplayName("A disabled pass touches nothing")
    void testDisabledIsNoOp() {
        newService(false).resolvePendingLocations();

        verifyNoInteractions(fetchedJobs, jobLocations, resolver);
    }

    @Test
    @DisplayName("An empty queue writes nothing")
    void testEmptyQueue() {
        pageContains();

        assertEquals(0, service.resolveOnePage());
        verifyNoInteractions(resolver, jobLocations);
    }

    // ------------------------------------------------------------ happy path

    @Test
    @DisplayName("Each job's locations are written and the job marked resolved")
    void testResolvesAndPersists() {
        FetchedJob job = job("Remote US");
        pageContains(job);
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        assertEquals(1, service.resolveOnePage());

        ArgumentCaptor<List<JobLocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(jobLocations).saveAll(saved.capture());
        assertEquals(1, saved.getValue().size());
        assertEquals(job.getId(), saved.getValue().getFirst().getFetchedJobId());
        assertEquals(47.7231d, saved.getValue().getFirst().getLatitude());
        assertEquals(LocationStatus.RESOLVED, job.getLocationStatus());
    }

    @Test
    @DisplayName("A job with several locations keeps all of them")
    void testMultipleLocationsAllPersisted() {
        // Collapsing to one would filter out a job with an office near the user
        // because a different office happened to be listed first.
        FetchedJob job = job("Boston; New York");
        pageContains(job);
        when(resolver.resolve("Boston; New York")).thenReturn(List.of(
                resolved("Boston", Resolution.PLACE, POINT),
                resolved("New York", Resolution.PLACE, POINT)));

        service.resolveOnePage();

        ArgumentCaptor<List<JobLocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(jobLocations).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size());
    }

    // ----------------------------------------------------------- dedup

    @Test
    @DisplayName("A repeated label is resolved once for the whole page")
    void testLabelsAreDeduplicatedAcrossThePage() {
        // The economics of the pass: a hundred jobs typically carry a dozen
        // distinct labels, so this is twelve model calls rather than a hundred.
        pageContains(job("Remote US"), job("Remote US"), job("Remote US"));
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        assertEquals(3, service.resolveOnePage());

        verify(resolver, times(1)).resolve("Remote US");
    }

    @Test
    @DisplayName("A missing location object is resolved as an empty label, not skipped")
    void testMissingLocationResolvesAsEmpty() {
        // job_data rows persisted before the location field existed deserialize
        // with a null location; they must still get a row.
        pageContains(job(null));
        when(resolver.resolve("")).thenReturn(
                List.of(resolved("", Resolution.EMPTY_DEFAULT, POINT)));

        assertEquals(1, service.resolveOnePage());
        verify(resolver).resolve("");
    }

    // -------------------------------------------------------- stale rows

    @Test
    @DisplayName("Existing rows are cleared before re-inserting")
    void testExistingRowsDeletedFirst() {
        // The unique constraint stops duplicates but not leftovers: after a
        // prompt change an old row would otherwise survive beside its
        // replacement and the job would carry both.
        FetchedJob job = job("Remote US");
        pageContains(job);
        when(resolver.resolve(anyString()))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        service.resolveOnePage();

        InOrder order = inOrder(jobLocations);
        order.verify(jobLocations).deleteByFetchedJobIds(List.of(job.getId()));
        order.verify(jobLocations).saveAll(anyList());
    }

    // ------------------------------------------------------------- FAILED

    @Test
    @DisplayName("A job resolving to nothing matchable is marked FAILED, not left pending")
    void testUnmatchableJobMarkedFailed() {
        // FAILED is what puts it in the curation worklist. Leaving it PENDING
        // would cycle it through the pass forever for the same answer.
        FetchedJob job = job("???");
        pageContains(job);
        when(resolver.resolve("???"))
                .thenReturn(List.of(resolved("???", Resolution.UNDEFINED, null)));

        service.resolveOnePage();

        assertEquals(LocationStatus.FAILED, job.getLocationStatus());
        ArgumentCaptor<List<JobLocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(jobLocations).saveAll(saved.capture());
        assertEquals(1, saved.getValue().size(), "the row is still kept for the worklist");
        assertFalse(saved.getValue().getFirst().isMatchable());
    }

    @Test
    @DisplayName("One matchable location among several is enough to count as resolved")
    void testPartiallyMatchableJobIsResolved() {
        FetchedJob job = job("Atlantis; Remote US");
        pageContains(job);
        when(resolver.resolve(anyString())).thenReturn(List.of(
                resolved("Atlantis", Resolution.UNDEFINED, null),
                resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        service.resolveOnePage();

        assertEquals(LocationStatus.RESOLVED, job.getLocationStatus());
    }

    // --------------------------------------------------- row constraints

    @Test
    @DisplayName("A place the geocoder cannot find is stored as UNDEFINED, keeping its query")
    void testUnlocatablePlaceStoredAsUndefined() {
        // ck_job_locations_coords_match_resolution lets only UNDEFINED rows lack
        // a coordinate, so a PLACE row with none would fail the whole page.
        FetchedJob job = job("Atlantis, GA");
        pageContains(job);
        when(resolver.resolve(anyString())).thenReturn(List.of(new ResolvedLocation(
                new LocationInput("Atlantis, GA", Resolution.PLACE, "Atlantis, GA", null),
                GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS), SourceTier.LLM, true)));

        service.resolveOnePage();

        JobLocation row = savedRows().getFirst();
        assertEquals(Resolution.UNDEFINED, row.getResolution());
        assertEquals("Atlantis, GA", row.getGeocodeQuery(), "the worklist shows what was looked up");
        assertNull(row.getLatitude());
        assertEquals(LocationStatus.FAILED, job.getLocationStatus());
    }

    @Test
    @DisplayName("Locations colliding on element and resolution are written once")
    void testDuplicateRowsCollapsed() {
        // uq_job_locations_job_raw_resolution would otherwise fail the page.
        FetchedJob job = job("Boston, Boston");
        pageContains(job);
        when(resolver.resolve(anyString())).thenReturn(List.of(
                resolved("Boston", Resolution.PLACE, POINT),
                resolved("Boston", Resolution.PLACE, POINT),
                resolved("???", Resolution.UNDEFINED, null)));

        service.resolveOnePage();

        assertEquals(2, savedRows().size());
    }

    @Test
    @DisplayName("Two unlocatable places with the same element collapse once both are UNDEFINED")
    void testRowsCollidingAfterDowngradeCollapsed() {
        FetchedJob job = job("Atlantis");
        pageContains(job);
        when(resolver.resolve(anyString())).thenReturn(List.of(
                resolved("Atlantis", Resolution.PLACE, GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS)),
                resolved("Atlantis", Resolution.UNDEFINED, null)));

        service.resolveOnePage();

        assertEquals(1, savedRows().size());
    }

    // ------------------------------------------------------------ deferral

    @Test
    @DisplayName("A label not cached while geocoding is off is deferred; the rest of the page is written")
    void testCacheOnlyMissDefersOnlyThatLabel() {
        FetchedJob deferred = job("Atlantis");
        FetchedJob written = job("Remote US");
        pageContains(deferred, written);
        when(resolver.resolve("Atlantis")).thenThrow(new GeocodingDisabledException("Atlantis"));
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        assertEquals(1, service.resolveOnePage());

        assertEquals(LocationStatus.PENDING, deferred.getLocationStatus(),
                "it resolves properly once lookups are enabled");
        assertEquals(LocationStatus.RESOLVED, written.getLocationStatus());
        verify(jobLocations).deleteByFetchedJobIds(List.of(written.getId()));
        verify(fetchedJobs).saveAll(List.of(written));
    }

    @Test
    @DisplayName("The pass pages past deferred jobs and wraps to the start at the end")
    void testCursorAdvancesAndWraps() {
        // Always reading the first page would hand the pass the same deferred
        // jobs forever once a page filled up with them.
        FetchedJob first = job("Atlantis");
        FetchedJob second = job("Atlantis");
        pageAfter(0L, first, second);
        pageAfter(second.getId());
        when(resolver.resolve(anyString())).thenThrow(new GeocodingDisabledException("Atlantis"));

        service.resolveOnePage();
        service.resolveOnePage();

        InOrder order = inOrder(fetchedJobs);
        order.verify(fetchedJobs).findByLocationStatusAndIdGreaterThan(
                eq(LocationStatus.PENDING), eq(0L), any(Pageable.class));
        order.verify(fetchedJobs).findByLocationStatusAndIdGreaterThan(
                eq(LocationStatus.PENDING), eq(second.getId()), any(Pageable.class));
        order.verify(fetchedJobs).findByLocationStatusAndIdGreaterThan(
                eq(LocationStatus.PENDING), eq(0L), any(Pageable.class));
    }

    @Test
    @DisplayName("An aborted page does not move the cursor, so the same page is retried")
    void testAbortedPageRetriedFromSameCursor() {
        FetchedJob job = job("Remote US");
        pageContains(job);
        when(resolver.resolve(anyString()))
                .thenThrow(new LocationExtractionException("Ollama unreachable"));

        service.resolvePendingLocations();
        service.resolvePendingLocations();

        verify(fetchedJobs, times(2)).findByLocationStatusAndIdGreaterThan(
                eq(LocationStatus.PENDING), eq(0L), any(Pageable.class));
    }

    // ------------------------------------------------------ write failures

    @Test
    @DisplayName("A job whose rows cannot be written is marked FAILED; the rest of the page is written")
    void testWriteFailureIsolatedToOneJob() {
        FetchedJob good = job("Remote US");
        FetchedJob bad = job("Poison");
        pageContains(good, bad);
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));
        when(resolver.resolve("Poison"))
                .thenReturn(List.of(resolved("Poison", Resolution.REMOTE_IN_US, POINT)));
        doAnswer(invocation -> {
            List<JobLocation> rows = invocation.getArgument(0);
            if (rows.stream().anyMatch(row -> row.getFetchedJobId().equals(bad.getId()))) {
                throw new DataIntegrityViolationException("constraint violated");
            }
            return rows;
        }).when(jobLocations).saveAll(anyList());

        service.resolveOnePage();

        assertEquals(LocationStatus.RESOLVED, good.getLocationStatus());
        assertEquals(LocationStatus.FAILED, bad.getLocationStatus());
        verify(fetchedJobs).saveAll(List.of(good));
        verify(fetchedJobs).save(bad);
    }

    @Test
    @DisplayName("A bug resolving one label marks its jobs FAILED rather than stalling the queue")
    void testUnexpectedResolutionFailureFailsThatLabelOnly() {
        FetchedJob broken = job("Broken");
        FetchedJob fine = job("Remote US");
        pageContains(broken, fine);
        when(resolver.resolve("Broken")).thenThrow(new IllegalStateException("bug"));
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        assertEquals(2, service.resolveOnePage());

        assertEquals(LocationStatus.FAILED, broken.getLocationStatus());
        assertEquals(LocationStatus.RESOLVED, fine.getLocationStatus());
        assertEquals(1, savedRows().size());
    }

    @Test
    @DisplayName("A database failure is caught and logged rather than escaping the scheduler")
    void testDatabaseFailureHandled() {
        pageContains(job("Remote US"));
        when(resolver.resolve(anyString()))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));
        when(jobLocations.saveAll(anyList())).thenThrow(new DataAccessResourceFailureException("down"));
        when(fetchedJobs.save(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertDoesNotThrow(() -> service.resolvePendingLocations());
    }

    // ---------------------------------------------------------- failures

    @Test
    @DisplayName("A model outage aborts the page and writes nothing")
    void testExtractionFailureAbortsPageWithoutWriting() {
        // Every job keeps PENDING, so the next run retries the whole page.
        FetchedJob job = job("Remote US");
        pageContains(job);
        when(resolver.resolve(anyString()))
                .thenThrow(new LocationExtractionException("Ollama unreachable"));

        service.resolvePendingLocations();

        verify(jobLocations, never()).saveAll(anyList());
        verify(fetchedJobs, never()).saveAll(anyList());
        assertEquals(LocationStatus.PENDING, job.getLocationStatus());
    }

    @Test
    @DisplayName("A retryable geocoding failure aborts the page without writing")
    void testRetryableGeocodingFailureAborts() {
        pageContains(job("Remote US"));
        when(resolver.resolve(anyString()))
                .thenThrow(new GeocodingException("quota exhausted", false));

        service.resolvePendingLocations();

        verify(jobLocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("A fatal geocoding failure is caught so the scheduler is not poisoned")
    void testFatalGeocodingFailureHandled() {
        // An escaping exception would stop the scheduled task from running again.
        pageContains(job("Remote US"));
        when(resolver.resolve(anyString()))
                .thenThrow(new GeocodingException("request denied", true));

        assertDoesNotThrow(() -> service.resolvePendingLocations());
        verify(jobLocations, never()).saveAll(anyList());
    }

    // ------------------------------------------------------------- strikes

    private void runs(int count) {
        for (int i = 0; i < count; i++) {
            service.resolvePendingLocations();
        }
    }

    @Test
    @DisplayName("A label that keeps failing is deferred after the strike limit, and the rest of the page is written")
    void testRepeatFailureStrikesOut() {
        // Without this, a label that fails on its own (not an outage) would abort
        // the same page on every run and the cursor would never move past it.
        FetchedJob poison = job("Poison");
        FetchedJob fine = job("Remote US");
        pageContains(poison, fine);
        when(resolver.resolve("Poison")).thenThrow(new LocationExtractionException("HTTP 500"));
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        runs(LocationService.STRIKE_LIMIT - 1);
        verify(jobLocations, never()).saveAll(anyList());

        service.resolvePendingLocations();

        verify(fetchedJobs).saveAll(List.of(fine));
        assertEquals(LocationStatus.RESOLVED, fine.getLocationStatus());
        assertEquals(LocationStatus.PENDING, poison.getLocationStatus(), "deferred, not failed: it may be an outage");
    }

    @Test
    @DisplayName("Only one struck-out label is retried per run, so an outage costs at most a few calls")
    void testOneStruckOutLabelProbedPerRun() {
        FetchedJob first = job("First");
        FetchedJob second = job("Second");
        FetchedJob fine = job("Remote US");
        pageContains(first, second, fine);
        when(resolver.resolve("First")).thenThrow(new LocationExtractionException("HTTP 500"));
        when(resolver.resolve("Second")).thenThrow(new LocationExtractionException("HTTP 500"));
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));

        // First strikes out on run 3; Second then collects its three strikes on
        // runs 3 to 5, while First is retried once per run.
        runs(2 * LocationService.STRIKE_LIMIT - 1);
        clearInvocations(resolver);

        service.resolvePendingLocations();

        verify(resolver).resolve("First");
        verify(resolver, never()).resolve("Second");
        verify(resolver).resolve("Remote US");
    }

    @Test
    @DisplayName("A struck-out label that resolves again is given a clean slate")
    void testSuccessClearsStrikes() {
        FetchedJob flaky = job("Flaky");
        pageContains(flaky);
        when(resolver.resolve("Flaky"))
                .thenThrow(new LocationExtractionException("HTTP 500"))
                .thenThrow(new LocationExtractionException("HTTP 500"))
                .thenThrow(new LocationExtractionException("HTTP 500"))
                .thenReturn(List.of(resolved("Flaky", Resolution.PLACE, POINT)))
                .thenThrow(new LocationExtractionException("HTTP 500"));

        runs(LocationService.STRIKE_LIMIT + 1);
        verify(jobLocations).saveAll(anyList());
        clearInvocations(jobLocations);

        service.resolvePendingLocations();

        // One failure after the success aborts the page again, as for any label.
        verify(jobLocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("A denied geocoding key never strikes a label out, since it fails every label")
    void testFatalGeocodingFailureNeverStrikesOut() {
        FetchedJob job = job("Remote US");
        pageContains(job);
        when(resolver.resolve(anyString())).thenThrow(new GeocodingException("request denied", true));

        runs(LocationService.STRIKE_LIMIT + 2);

        verify(fetchedJobs, never()).saveAll(anyList());
        verify(resolver, times(LocationService.STRIKE_LIMIT + 2)).resolve("Remote US");
    }

    @Test
    @DisplayName("Resolution failure on one label abandons the page, including labels already done")
    void testOneFailureAbandonsWholePage() {
        // Atomic per page: a partial write would leave some jobs resolved
        // against a half-finished pass and complicate the retry.
        pageContains(job("Remote US"), job("Broken"));
        when(resolver.resolve("Remote US"))
                .thenReturn(List.of(resolved("Remote US", Resolution.REMOTE_IN_US, POINT)));
        when(resolver.resolve("Broken"))
                .thenThrow(new LocationExtractionException("boom"));

        service.resolvePendingLocations();

        verify(jobLocations, never()).saveAll(anyList());
    }
}
