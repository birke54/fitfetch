package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.AtsThrottledException;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.example.fitfetch.utilities.TitleFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches every slug of one ATS provider, several at a time, and collects the
 * jobs that are new and relevant.
 *
 * <p>This is the part every {@link Ats} shares; an implementation supplies only
 * its {@link Fetch}, its slugs and the jobs already stored.
 *
 * <p><strong>Concurrency.</strong> Each slug runs on its own virtual thread, and
 * at most {@code max-concurrent} are fetched at once. The rate of requests is
 * not decided here: every request goes through the provider's rate-limited
 * client, which spaces them out however many are in flight. Concurrency only
 * lets slow responses overlap, so that rate is actually reached. It is capped
 * per slug rather than per request because a response has not been read when
 * the request completes, and bodies with full job content are large.
 *
 * <p><strong>Failures.</strong> A slug that fails &mdash; an HTTP error, a
 * timeout, an unreadable body &mdash; is logged, counted once and skipped. A
 * slug answered 429 is retried once, after the pause the 429 triggered. If the
 * provider asks for a pause longer than {@code max-wait}, no further slug is
 * started: the sweep returns what it has, since every remaining slug would wait
 * just as long.
 *
 * <p>Slugs start in the order listed, so with {@code max-concurrent: 1} the
 * sweep is strictly sequential, and results come back in slug order whatever
 * order the fetches finish in. Instances are immutable and safe to reuse across
 * fetch cycles.
 *
 * @param <T> the provider's job entry type
 */
public final class SlugSweep<T extends AtsJobEntry> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlugSweep.class);

    private final AtsName ats;
    private final Fetch<T> fetcher;
    private final MetricService metricService;
    private final int maxConcurrent;

    /**
     * @param ats           the provider
     * @param fetcher       its transport, which must be safe to call from
     *                      several threads at once
     * @param metricService sink for per-slug outcome counters
     * @param maxConcurrent how many slugs may be fetched at once; at least 1
     */
    public SlugSweep(AtsName ats, Fetch<T> fetcher, MetricService metricService, int maxConcurrent) {
        this.ats = Objects.requireNonNull(ats, "ats");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.metricService = Objects.requireNonNull(metricService, "metricService");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException(ats + ": max-concurrent must be at least 1, but was " + maxConcurrent);
        }
        this.maxConcurrent = maxConcurrent;
    }

    /** What became of one slug. */
    private record Outcome(List<AtsJobEntry> jobs, boolean fetched) {
        static final Outcome NOT_FETCHED = new Outcome(List.of(), false);
        static final Outcome NOTHING_NEW = new Outcome(List.of(), true);
    }

    /**
     * @param slugs       the boards to fetch
     * @param knownJobIds ids of this provider's jobs already stored, which are
     *                    dropped
     * @return the new, relevant jobs across all slugs, each tagged with its
     *         slug; never {@code null}
     */
    public List<AtsJobEntry> run(List<String> slugs, Set<String> knownJobIds) {
        Semaphore inFlight = new Semaphore(maxConcurrent);
        AtomicReference<AtsThrottledException> stoppedBy = new AtomicReference<>();

        List<Future<Outcome>> futures = new ArrayList<>(slugs.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String slug : slugs) {
                // Taken here rather than in the task, so slugs start in the order
                // listed and no thread exists for a slug until it can start.
                inFlight.acquire();
                if (stoppedBy.get() != null) {
                    inFlight.release();
                    break;
                }
                futures.add(executor.submit(() -> {
                    try {
                        return fetchSlug(slug, knownJobIds, stoppedBy);
                    } finally {
                        inFlight.release();
                    }
                }));
            }
        } catch (InterruptedException e) {
            // Slugs already started have finished; the rest are not started.
            Thread.currentThread().interrupt();
        }

        List<AtsJobEntry> jobs = new ArrayList<>();
        int notFetched = slugs.size() - futures.size();
        for (int i = 0; i < futures.size(); i++) {
            try {
                Outcome outcome = futures.get(i).get();
                jobs.addAll(outcome.jobs());
                if (!outcome.fetched()) {
                    notFetched++;
                }
            } catch (ExecutionException e) {
                // Not one of the failures fetchSlug handles, so most likely a bug.
                // It cost this slug only.
                LOGGER.error("Fetching {} from {} failed unexpectedly", slugs.get(i), ats.stringValue(), e.getCause());
                recordError();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notFetched += futures.size() - i;
                break;
            }
        }

        AtsThrottledException stop = stoppedBy.get();
        if (stop != null) {
            LOGGER.warn("Ended this {} fetch early, {} of {} slugs unfetched: {}",
                    ats.stringValue(), notFetched, slugs.size(), stop.getMessage());
            metricService.recordCounter(MetricName.FETCH_STOPPED_COUNT, Map.of(TagName.ATS, ats.stringValue()));
        }
        return jobs;
    }

    private Outcome fetchSlug(String slug, Set<String> knownJobIds,
                              AtomicReference<AtsThrottledException> stoppedBy) {
        if (stoppedBy.get() != null) {
            // Another slug found the long pause after this one was started.
            return Outcome.NOT_FETCHED;
        }

        AtsResponse<T> response;
        try {
            response = fetchRetryingOnce(slug);
        } catch (AtsThrottledException e) {
            stoppedBy.compareAndSet(null, e);
            return Outcome.NOT_FETCHED;
        } catch (HttpStatusCodeException e) {
            LOGGER.error("{} answered {} for {}", ats.stringValue(), e.getStatusCode(), slug);
            recordError();
            return Outcome.NOTHING_NEW;
        } catch (RestClientException e) {
            // Timeouts, refused connections and unreadable bodies.
            LOGGER.error("Could not fetch jobs for {} from {}: {}", slug, ats.stringValue(), e.getMessage());
            recordError();
            return Outcome.NOTHING_NEW;
        }

        if (response == null || response.jobs() == null) {
            metricService.recordCounter(MetricName.SLUG_FETCH_NULL_RESPONSE_COUNT, tags());
            return Outcome.NOTHING_NEW;
        }
        List<AtsJobEntry> fresh = new ArrayList<>();
        int invalid = 0;
        int known = 0;
        int filtered = 0;
        for (AtsJobEntry job : response.jobs()) {
            if (job == null || job.id() == null || job.title() == null) {
                invalid++;
            } else if (knownJobIds.contains(job.id().toString())) {
                known++;
            } else if (!TitleFilter.keep(job.title())) {
                filtered++;
            } else {
                fresh.add(job.withSlug(slug));
            }
        }
        LOGGER.debug("Found {} new jobs for {} from {}; dropped {} known or filtered by title",
                fresh.size(), slug, ats.stringValue(), response.jobs().size() - fresh.size());
        recordJobs("new", fresh.size());
        recordJobs("known", known);
        recordJobs("filtered_title", filtered);
        recordJobs("invalid", invalid);
        metricService.recordCounter(MetricName.SLUG_FETCH_SUCCESS_COUNT, tags());
        return new Outcome(fresh, true);
    }

    private void recordJobs(String result, int count) {
        metricService.recordCounterByIncrement(MetricName.FETCH_JOBS_COUNT,
                Map.of(TagName.ATS, ats.stringValue(), TagName.RESULT, result), count);
    }

    /**
     * Fetches one slug, retrying once if the provider answers 429.
     *
     * <p>The 429 has already paused every request to the provider, so the retry
     * waits that out in the rate limiter rather than being sent straight back
     * into the throttle. A second 429 is handled like any other client error.
     */
    private AtsResponse<T> fetchRetryingOnce(String slug) {
        try {
            return fetcher.fetchJobs(slug);
        } catch (HttpClientErrorException.TooManyRequests e) {
            LOGGER.info("{} throttled {}; retrying once after the pause", ats.stringValue(), slug);
            return fetcher.fetchJobs(slug);
        }
    }

    private void recordError() {
        metricService.recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT, tags());
    }

    /**
     * Tagged by ATS only. The slug is in the log line beside each counter; as a
     * tag it would make these counters one series per board.
     */
    private Map<TagName, String> tags() {
        return Map.of(TagName.ATS, ats.stringValue());
    }
}
