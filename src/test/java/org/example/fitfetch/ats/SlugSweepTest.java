package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.AtsThrottledException;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseResponse;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlugSweepTest {

    private static final Map<TagName, String> ATS_TAG = Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue());

    private final AtomicLong nextId = new AtomicLong(1);
    private MetricService metricService;
    private Fetch<GreenhouseJobEntry> fetcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        metricService = mock(MetricService.class);
        fetcher = mock(Fetch.class);
    }

    private SlugSweep<GreenhouseJobEntry> sweep(int maxConcurrent) {
        return new SlugSweep<>(AtsName.GREENHOUSE, fetcher, metricService, maxConcurrent);
    }

    private SlugSweep<GreenhouseJobEntry> sweep(Fetch<GreenhouseJobEntry> fetch, int maxConcurrent) {
        return new SlugSweep<>(AtsName.GREENHOUSE, fetch, metricService, maxConcurrent);
    }

    private SlugSweep<GreenhouseJobEntry> sweep(int maxConcurrent, Predicate<GreenhouseJobEntry> keep) {
        return new SlugSweep<>(AtsName.GREENHOUSE, fetcher, metricService, maxConcurrent, keep);
    }

    private GreenhouseJobEntry job(Long id, String title) {
        return new GreenhouseJobEntry("https://example.com", null, id, null, null, null, title,
                "Co", null, "en", null, "jd", null, List.of(), List.of(), List.of(), null);
    }

    private GreenhouseResponse jobs(GreenhouseJobEntry... entries) {
        return new GreenhouseResponse(Arrays.asList(entries));
    }

    private GreenhouseResponse oneJob() {
        return jobs(job(nextId.getAndIncrement(), "Backend Engineer"));
    }

    private static HttpClientErrorException tooManyRequests() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    }

    private static AtsThrottledException longPause() {
        return new AtsThrottledException(AtsName.GREENHOUSE, Duration.ofMinutes(10));
    }

    private static List<String> slugsOf(List<AtsJobEntry> jobs) {
        return jobs.stream().map(AtsJobEntry::slug).toList();
    }

    // ------------------------------------------------------------ filtering

    @Test
    @DisplayName("Only new jobs that pass the title filter are kept, each tagged with its slug")
    void testFiltersAndTags() {
        when(fetcher.fetchJobs("alpha")).thenReturn(jobs(
                job(1L, "Backend Engineer"),
                job(2L, "Backend Engineer"),        // already stored
                job(3L, "Account Executive"),       // filtered by title
                job(4L, null),                      // no title
                job(null, "Backend Engineer"),      // no id
                null));

        List<AtsJobEntry> kept = sweep(1).run(List.of("alpha"), Set.of("2"));

        assertEquals(List.of("1"), kept.stream().map(AtsJobEntry::jobId).toList());
        assertEquals(List.of("alpha"), slugsOf(kept));
        verify(metricService).recordCounter(MetricName.SLUG_FETCH_SUCCESS_COUNT,
                ATS_TAG);
        verifyJobs("new", 1);
        verifyJobs("known", 1);
        verifyJobs("filtered_title", 1);
        verifyJobs("invalid", 3);
    }

    private void verifyJobs(String result, int count) {
        verify(metricService).recordCounterByIncrement(MetricName.FETCH_JOBS_COUNT,
                Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue(), TagName.RESULT, result), count);
    }

    @Test
    @DisplayName("Fetch counters are tagged by ATS only, never by slug")
    void testCountersNotTaggedBySlug() {
        // A slug tag would make each counter one series per board, thousands of them.
        when(fetcher.fetchJobs(anyString())).thenAnswer(invocation -> oneJob());

        sweep(1).run(List.of("alpha", "beta"), Set.of());

        verify(metricService, times(2)).recordCounter(MetricName.SLUG_FETCH_SUCCESS_COUNT, ATS_TAG);
    }

    @Test
    @DisplayName("An empty response is counted and skipped")
    void testNullResponse() {
        when(fetcher.fetchJobs("alpha")).thenReturn(new GreenhouseResponse(null));

        assertTrue(sweep(1).run(List.of("alpha"), Set.of()).isEmpty());
        verify(metricService).recordCounter(MetricName.SLUG_FETCH_NULL_RESPONSE_COUNT,
                ATS_TAG);
    }

    // ---------------------------------------------------------- concurrency

    @Test
    @DisplayName("Results come back in slug order, whatever order the fetches finish in")
    void testResultsInSlugOrder() {
        CountDownLatch gammaDone = new CountDownLatch(1);
        Fetch<GreenhouseJobEntry> outOfOrder = slug -> {
            if (slug.equals("alpha")) {
                await(gammaDone);
            }
            GreenhouseResponse response = oneJob();
            if (slug.equals("gamma")) {
                gammaDone.countDown();
            }
            return response;
        };

        List<AtsJobEntry> jobs = sweep(outOfOrder, 3).run(List.of("alpha", "beta", "gamma"), Set.of());

        assertEquals(List.of("alpha", "beta", "gamma"), slugsOf(jobs));
    }

    @Test
    @DisplayName("With max-concurrent 1, slugs are fetched strictly one after another in the order listed")
    void testSequentialWhenConcurrencyIsOne() {
        List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostInFlight = new AtomicInteger();
        Fetch<GreenhouseJobEntry> recording = slug -> {
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            calls.add(slug);
            inFlight.decrementAndGet();
            return oneJob();
        };
        List<String> slugs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            slugs.add("slug-" + i);
        }

        sweep(recording, 1).run(slugs, Set.of());

        assertEquals(slugs, calls);
        assertEquals(1, mostInFlight.get());
    }

    @Test
    @DisplayName("Slugs are fetched concurrently, up to max-concurrent and never more")
    void testConcurrencyCapped() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostInFlight = new AtomicInteger();
        // Each fetch waits for a partner, so the sweep must run two at once to
        // finish at all; a third running alongside would show in mostInFlight.
        CyclicBarrier pair = new CyclicBarrier(2);
        Fetch<GreenhouseJobEntry> paired = slug -> {
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                pair.await(5, TimeUnit.SECONDS);
                return oneJob();
            } catch (Exception e) {
                throw new IllegalStateException("no partner arrived", e);
            } finally {
                inFlight.decrementAndGet();
            }
        };

        List<AtsJobEntry> jobs = sweep(paired, 2).run(List.of("a", "b", "c", "d", "e", "f"), Set.of());

        assertEquals(6, jobs.size());
        assertEquals(2, mostInFlight.get());
    }

    // --------------------------------------------------------------- errors

    @Test
    @DisplayName("A failing slug is counted once and skipped; the rest are kept")
    void testErrorSkipsOnlyThatSlug() {
        when(fetcher.fetchJobs("alpha")).thenReturn(oneJob());
        when(fetcher.fetchJobs("beta")).thenThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                        new byte[0], StandardCharsets.UTF_8));
        when(fetcher.fetchJobs("gamma")).thenReturn(oneJob());

        List<AtsJobEntry> jobs = sweep(2).run(List.of("alpha", "beta", "gamma"), Set.of());

        assertEquals(List.of("alpha", "gamma"), slugsOf(jobs));
        verify(metricService).recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT,
                ATS_TAG);
    }

    @Test
    @DisplayName("An unexpected failure costs only its slug")
    void testUnexpectedFailureSkipsOnlyThatSlug() {
        when(fetcher.fetchJobs("alpha")).thenThrow(new IllegalStateException("bug"));
        when(fetcher.fetchJobs("beta")).thenReturn(oneJob());

        List<AtsJobEntry> jobs = sweep(2).run(List.of("alpha", "beta"), Set.of());

        assertEquals(List.of("beta"), slugsOf(jobs));
        verify(metricService).recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT,
                ATS_TAG);
    }

    @Test
    @DisplayName("A slug whose board is gone is counted as missing, not as an error")
    void testNotFoundCountedApartFromErrors() {
        when(fetcher.fetchJobs("alpha")).thenReturn(oneJob());
        when(fetcher.fetchJobs("beta")).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(),
                        new byte[0], StandardCharsets.UTF_8));
        when(fetcher.fetchJobs("gamma")).thenReturn(oneJob());

        List<AtsJobEntry> jobs = sweep(2).run(List.of("alpha", "beta", "gamma"), Set.of());

        assertEquals(List.of("alpha", "gamma"), slugsOf(jobs));
        verify(metricService).recordCounter(MetricName.SLUG_FETCH_MISSING_COUNT, ATS_TAG);
        // The point of the split: a dead board must not move the counter that a
        // real failure is alerted on.
        verify(metricService, never()).recordCounter(eq(MetricName.SLUG_FETCH_ERROR_COUNT), anyMap());
    }

    @Test
    @DisplayName("A client error that is not a 404 is still an error")
    void testOtherClientErrorStillCountedAsError() {
        when(fetcher.fetchJobs("alpha")).thenThrow(
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                        new byte[0], StandardCharsets.UTF_8));

        sweep(1).run(List.of("alpha"), Set.of());

        verify(metricService).recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT, ATS_TAG);
        verify(metricService, never()).recordCounter(eq(MetricName.SLUG_FETCH_MISSING_COUNT), anyMap());
    }

    @Test
    @DisplayName("A job the board's own predicate rejects is left out and counted as filtered")
    void testKeepPredicateRejects() {
        when(fetcher.fetchJobs("alpha")).thenReturn(jobs(
                job(1L, "Backend Engineer"),
                job(2L, "Backend Engineer"),        // rejected by the board's rule
                job(3L, "Backend Engineer")));

        // Typed on the provider's own record, so the rule reads its own fields.
        List<AtsJobEntry> kept = sweep(1, entry -> entry.id() != 2L).run(List.of("alpha"), Set.of());

        assertEquals(List.of("1", "3"), kept.stream().map(AtsJobEntry::jobId).toList());
        verifyJobs("new", 2);
        // Counted with the title rejections: both mean fetched, new and not wanted.
        verifyJobs("filtered_title", 1);
        verifyJobs("known", 0);
        verifyJobs("invalid", 0);
    }

    @Test
    @DisplayName("The predicate is offered only entries that are usable and new")
    void testKeepPredicateSeesOnlyUsableNewJobs() {
        // It runs last, so it may read the entry's fields without null-checking
        // them and is never asked about a job that was going to be dropped anyway.
        when(fetcher.fetchJobs("alpha")).thenReturn(jobs(
                job(1L, "Backend Engineer"),
                job(2L, "Backend Engineer"),        // already stored
                job(3L, "Account Executive"),       // filtered by title
                job(4L, null),                      // no title
                job(null, "Backend Engineer"),      // no id
                null));
        List<Long> offered = java.util.Collections.synchronizedList(new ArrayList<>());

        List<AtsJobEntry> kept = sweep(1, entry -> {
            offered.add(entry.id());
            return true;
        }).run(List.of("alpha"), Set.of("2"));

        assertEquals(List.of(1L), offered);
        assertEquals(List.of("1"), kept.stream().map(AtsJobEntry::jobId).toList());
        verifyJobs("invalid", 3);
    }

    @Test
    @DisplayName("Without a predicate every job passing the shared checks is kept")
    void testNoKeepPredicateKeepsEverything() {
        when(fetcher.fetchJobs("alpha")).thenReturn(jobs(
                job(1L, "Backend Engineer"),
                job(2L, "Backend Engineer")));

        List<AtsJobEntry> kept = sweep(1).run(List.of("alpha"), Set.of());

        assertEquals(List.of("1", "2"), kept.stream().map(AtsJobEntry::jobId).toList());
        verifyJobs("new", 2);
        verifyJobs("filtered_title", 0);
    }

    @Test
    @DisplayName("A null predicate is rejected")
    void testNullKeepPredicate() {
        assertThrows(NullPointerException.class, () -> sweep(1, null));
    }

    // ------------------------------------------------------------ throttling

    @Test
    @DisplayName("A slug answered 429 is retried once, and its jobs are kept")
    void testThrottledSlugRetriedOnce() {
        when(fetcher.fetchJobs("alpha")).thenReturn(oneJob());
        when(fetcher.fetchJobs("beta")).thenThrow(tooManyRequests()).thenReturn(oneJob());

        List<AtsJobEntry> jobs = sweep(1).run(List.of("alpha", "beta"), Set.of());

        assertEquals(List.of("alpha", "beta"), slugsOf(jobs));
        verify(fetcher, times(2)).fetchJobs("beta");
    }

    @Test
    @DisplayName("A second 429 is an error for that slug only")
    void testSecondThrottleSkipsOnlyThatSlug() {
        when(fetcher.fetchJobs("alpha")).thenThrow(tooManyRequests(), tooManyRequests());
        when(fetcher.fetchJobs("beta")).thenReturn(oneJob());

        List<AtsJobEntry> jobs = sweep(1).run(List.of("alpha", "beta"), Set.of());

        assertEquals(List.of("beta"), slugsOf(jobs));
        verify(fetcher, times(2)).fetchJobs("alpha");
        verify(metricService).recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT,
                ATS_TAG);
    }

    @Test
    @DisplayName("A pause longer than max-wait stops the sweep, keeping the jobs already fetched")
    void testLongPauseStopsSweep() {
        when(fetcher.fetchJobs("alpha")).thenReturn(oneJob());
        when(fetcher.fetchJobs("beta")).thenThrow(longPause());

        List<AtsJobEntry> jobs = sweep(1).run(List.of("alpha", "beta", "gamma"), Set.of());

        assertEquals(List.of("alpha"), slugsOf(jobs));
        verify(fetcher, never()).fetchJobs("gamma");
        verify(metricService).recordCounter(MetricName.FETCH_STOPPED_COUNT, ATS_TAG);
        verify(metricService, never()).recordCounter(eq(MetricName.SLUG_FETCH_ERROR_COUNT), anyMap());
    }

    @Test
    @DisplayName("A pause too long for the retry also stops the sweep")
    void testLongPauseOnRetryStopsSweep() {
        when(fetcher.fetchJobs("alpha")).thenThrow(tooManyRequests()).thenThrow(longPause());

        assertTrue(sweep(1).run(List.of("alpha", "beta"), Set.of()).isEmpty());
        verify(fetcher, never()).fetchJobs("beta");
    }

    @Test
    @DisplayName("Many slugs hitting the same long pause at once stop the sweep once")
    void testConcurrentStopsCountedOnce() {
        when(fetcher.fetchJobs(anyString())).thenThrow(longPause());

        List<String> slugs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            slugs.add("slug-" + i);
        }
        assertTrue(sweep(4).run(slugs, Set.of()).isEmpty());

        verify(metricService, times(1)).recordCounter(MetricName.FETCH_STOPPED_COUNT, ATS_TAG);
        // Slugs that had not started when the pause was found are never sent.
        verify(fetcher, atMost(4)).fetchJobs(anyString());
    }

    @Test
    @DisplayName("max-concurrent below 1 is rejected")
    void testInvalidConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> sweep(0));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
