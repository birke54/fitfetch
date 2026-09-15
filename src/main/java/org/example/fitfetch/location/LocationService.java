package org.example.fitfetch.location;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.JobLocation;
import org.example.fitfetch.domain.LocationStatus;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled pass that resolves the location of every fetched job that still
 * needs one.
 *
 * <p><strong>Network calls happen outside the transaction.</strong> Resolving a
 * page can involve a model call and a geocode lookup per distinct label; holding
 * a database transaction open across those would tie up a connection for minutes
 * and hold locks the whole time. So each page resolves first and writes second,
 * with the transaction opened only around the write.
 *
 * <p>Labels are deduplicated within a page before anything is resolved. A page of
 * a hundred jobs typically carries a dozen distinct location strings, so this is
 * the difference between twelve model calls and a hundred.
 *
 * @see LocationResolver
 */
@Service
public class LocationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationService.class);

    private final FetchedJobsRepository fetchedJobsRepository;
    private final JobLocationRepository jobLocationRepository;
    private final LocationResolver resolver;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final MetricService metricService;
    private final boolean enabled;
    private final int pageSize;

    /**
     * Warm-up mode. Every label is resolved exactly as in a normal run, so the
     * interpretation cache and (with geocoding enabled) the geocode cache fill
     * up, but nothing is written to {@code job_locations} and every job stays
     * {@code PENDING}. That lets the model answers, the geocode queries and
     * their cost be checked in the two cache tables before any location is
     * recorded against a job. Switching it off then resolves the waiting jobs
     * from the warm caches.
     */
    private final boolean warmCachesOnly;

    /**
     * Id of the last job the pass looked at. Held in memory only: after a
     * restart the pass starts from the front again, which costs nothing but
     * revisiting deferred jobs.
     */
    private final AtomicLong cursor = new AtomicLong();

    /**
     * How many times a label may abort the page before it is deferred instead.
     * Two runs is 20 minutes at the default schedule: long enough to ride out
     * a restart, short enough that one bad label cannot hold the queue for long.
     */
    static final int STRIKE_LIMIT = 2;

    /**
     * Consecutive aborting failures per label, cleared when the label resolves.
     * In memory only, like the cursor; a restart gives every label a fresh
     * start. Holds only labels that are currently failing, so it stays small.
     */
    private final Map<String, Integer> strikes = new ConcurrentHashMap<>();

    /**
     * @param fetchedJobsRepository source of pending jobs, and where status is
     *                              recorded
     * @param jobLocationRepository destination for resolved locations
     * @param resolver              the resolution chain
     * @param transactionTemplate   used to scope the write explicitly. Preferred
     *                              over an annotation because the boundary is the
     *                              point here, and because a self-invoked
     *                              {@code @Transactional} method silently does
     *                              nothing through Spring's proxy
     * @param clock                 time source; injectable so tests are not
     *                              timing-dependent
     * @param enabled               {@code app.location.enable}
     * @param pageSize              {@code app.location.page-size}
     * @param warmCachesOnly        {@code app.location.warm-caches-only}: resolve
     *                              labels to fill both caches, but write no
     *                              locations and change no job's status
     */
    public LocationService(FetchedJobsRepository fetchedJobsRepository,
                           JobLocationRepository jobLocationRepository,
                           LocationResolver resolver,
                           TransactionTemplate transactionTemplate,
                           Clock clock,
                           MetricService metricService,
                           @Value("${app.location.enable}") boolean enabled,
                           @Value("${app.location.page-size}") int pageSize,
                           @Value("${app.location.warm-caches-only}") boolean warmCachesOnly) {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.jobLocationRepository = jobLocationRepository;
        this.resolver = resolver;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.metricService = metricService;
        this.enabled = enabled;
        this.pageSize = pageSize;
        this.warmCachesOnly = warmCachesOnly;
    }

    /**
     * Cron entry point (schedule from {@code app.location.schedule}) that
     * resolves one page of pending jobs.
     *
     * <p>Deliberately one page per run rather than draining the queue. A backlog
     * then clears steadily across runs instead of one invocation holding the
     * model and the geocoding budget for as long as it takes, and a fatal
     * geocoding failure costs at most a page.
     */
    @Scheduled(cron = "${app.location.schedule}")
    public void resolvePendingLocations() {
        if (!enabled) {
            LOGGER.info("LocationService is disabled");
            return;
        }
        try {
            int resolved = resolveOnePage();
            if (resolved > 0) {
                long remaining = fetchedJobsRepository.countByLocationStatus(LocationStatus.PENDING);
                LOGGER.info("Resolved locations for {} jobs, {} still pending", resolved, remaining);
            }
        } catch (GeocodingException e) {
            if (e.isFatal()) {
                // A denied key or disabled billing. Retrying would burn whatever
                // budget remains against a call that cannot succeed.
                LOGGER.error("Halting location pass: {}", e.getMessage());
                recordStopped("geocoding_fatal");
            } else {
                LOGGER.warn("Location pass stopped early, will retry: {}", e.getMessage());
                recordStopped("geocoding_retryable");
            }
        } catch (LocationExtractionException e) {
            LOGGER.warn("Location pass stopped early, model unavailable: {}", e.getMessage());
            recordStopped("model_unavailable");
        } catch (RuntimeException e) {
            // Most likely the database itself. The page stays pending and the
            // cursor does not move, so the next run retries it.
            LOGGER.error("Location pass failed, will retry", e);
            recordStopped("error");
        }
    }

    private void recordStopped(String reason) {
        metricService.recordCounter(MetricName.LOCATION_PASS_STOPPED_COUNT, Map.of(TagName.REASON, reason));
    }

    /**
     * Resolves a single page of pending jobs.
     *
     * <p>Two kinds of label are deferred: their jobs are not written and stay
     * {@code PENDING}, while every other label on the page is written as
     * normal.
     *
     * <ul>
     *   <li>A label whose query is not cached while geocoding is switched off,
     *       so it resolves properly once lookups are enabled.</li>
     *   <li>A label that has failed {@link #STRIKE_LIMIT} times in a row with
     *       an error that would otherwise abort the page. An outage fails every
     *       label, but a label that only fails on its own (an input the model
     *       always errors on, say) would abort the same page on every run,
     *       and the cursor would never move past it.</li>
     * </ul>
     *
     * <p>In warm-up mode ({@link #warmCachesOnly}) the labels are resolved the
     * same way, and failures abort or defer the same way, but nothing is written
     * at all. A label deferred because geocoding is off has still been through
     * the model, so its answer is cached; only its geocode lookups are not. A
     * struck-out label is not warmed at all and is left for a later sweep.
     *
     * @return how many jobs were written; always 0 in warm-up mode
     */
    int resolveOnePage() {
        List<FetchedJob> page = nextPage();
        if (page.isEmpty()) {
            return 0;
        }

        // 1. Deduplicate labels across the page, preserving order so failures are
        //    deterministic and the cheapest labels tend to resolve first.
        Set<String> distinct = new LinkedHashSet<>();
        for (FetchedJob job : page) {
            distinct.add(labelOf(job));
        }
        LOGGER.debug("Resolving {} distinct labels for {} jobs", distinct.size(), page.size());

        // 2. Resolve each distinct label once. A transport failure aborts the
        //    page: every job keeps its PENDING status and nothing is written.
        //    The exception is a label that keeps failing (see STRIKE_LIMIT).
        Map<String, List<ResolvedLocation>> byLabel = new HashMap<>(distinct.size());
        Set<String> deferred = new HashSet<>();
        // Labels that got past the model step and stopped only at geocoding.
        int geocodingOff = 0;
        boolean probed = false;
        for (String label : distinct) {
            if (strikes.getOrDefault(label, 0) >= STRIKE_LIMIT) {
                // Retry at most one struck-out label per run. During an outage
                // they all fail, so trying each would stretch a run by a timeout
                // per label; the rest wait for a later run.
                if (probed) {
                    deferred.add(label);
                    continue;
                }
                probed = true;
            }
            try {
                byLabel.put(label, resolver.resolve(label));
                strikes.remove(label);
            } catch (GeocodingDisabledException e) {
                deferred.add(label);
                geocodingOff++;
            } catch (LocationExtractionException | GeocodingException e) {
                if (e instanceof GeocodingException geocoding && geocoding.isFatal()) {
                    // A denied key fails every label alike; it says nothing
                    // about this one.
                    throw e;
                }
                int count = strikes.merge(label, 1, Integer::sum);
                if (count < STRIKE_LIMIT) {
                    throw e;
                }
                LOGGER.warn("'{}' has failed {} times in a row ({}); leaving its jobs pending "
                        + "so the rest of the page can proceed", label, count, e.getMessage());
                deferred.add(label);
            } catch (RuntimeException e) {
                // A bug rather than an outage, so it will fail the same way every
                // time. Resolving the label to nothing marks its jobs FAILED, where
                // they can be found; aborting instead would retry this page forever.
                LOGGER.error("Could not resolve '{}'{}", label,
                        warmCachesOnly ? "" : "; marking its jobs FAILED", e);
                byLabel.put(label, List.of());
            }
        }

        if (warmCachesOnly) {
            // Resolving has already filled the caches; that was the point. Write
            // nothing, so every job is still PENDING when this mode is switched off.
            // A label stopped by geocoding being off still got its answer first,
            // so with geocoding off "answered" is the number that shows progress.
            long resolved = byLabel.values().stream().filter(locations -> !locations.isEmpty()).count();
            LOGGER.info("Warm-up: {} of {} labels on {} jobs answered (curated table, cache or model), "
                            + "{} also geocoded; nothing written",
                    resolved + geocodingOff, distinct.size(), page.size(), resolved);
            cursor.set(page.getLast().getId());
            return 0;
        }

        List<FetchedJob> toWrite = page.stream()
                .filter(job -> !deferred.contains(labelOf(job)))
                .toList();
        if (toWrite.size() < page.size()) {
            LOGGER.info("Deferred {} jobs across {} labels; they stay pending",
                    page.size() - toWrite.size(), deferred.size());
        }

        // 3. Write. Only now is a transaction opened.
        write(toWrite, byLabel, OffsetDateTime.now(clock));
        cursor.set(page.getLast().getId());
        return toWrite.size();
    }

    /**
     * Reads the next page of pending jobs after the cursor, wrapping to the start
     * of the table once the end is reached.
     *
     * <p>Paging by id rather than always reading the first page is what lets the
     * pass move past deferred jobs, which stay {@code PENDING}. Wrapping is what
     * brings them, and anything requeued behind the cursor, round again.
     */
    private List<FetchedJob> nextPage() {
        List<FetchedJob> page = pendingAfter(cursor.get());
        if (page.isEmpty() && cursor.get() > 0) {
            if (warmCachesOnly) {
                // Every job stays PENDING in warm-up, so the pass loops forever;
                // later sweeps only re-read the caches. This is the point to switch.
                LOGGER.info("Warm-up sweep complete: every pending job has been through the "
                        + "resolution chain once. Set app.location.warm-caches-only to false "
                        + "to start writing locations.");
            }
            cursor.set(0);
            page = pendingAfter(0);
        }
        return page;
    }

    private List<FetchedJob> pendingAfter(long afterId) {
        return fetchedJobsRepository.findByLocationStatusAndIdGreaterThan(
                LocationStatus.PENDING, afterId, PageRequest.of(0, pageSize, Sort.by("id")));
    }

    /**
     * Writes a page in one transaction, falling back to one transaction per job
     * if that fails.
     *
     * <p>A constraint violation on one job's rows would otherwise roll back the
     * whole page on every run. The fallback isolates it: every other job is
     * written, and a job that cannot be written even on its own is marked
     * {@code FAILED} with no rows. If marking it fails as well, the database
     * itself is the problem, and the exception propagates so the page is retried.
     */
    private void write(List<FetchedJob> jobs, Map<String, List<ResolvedLocation>> byLabel,
                       OffsetDateTime now) {
        if (jobs.isEmpty()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> persist(jobs, byLabel, now));
            return;
        } catch (RuntimeException e) {
            if (jobs.size() == 1) {
                markFailed(jobs.getFirst(), e);
                return;
            }
            LOGGER.warn("Writing {} jobs failed, retrying one at a time: {}", jobs.size(), e.getMessage());
        }
        for (FetchedJob job : jobs) {
            try {
                transactionTemplate.executeWithoutResult(status -> persist(List.of(job), byLabel, now));
            } catch (RuntimeException e) {
                markFailed(job, e);
            }
        }
    }

    private void markFailed(FetchedJob job, RuntimeException cause) {
        LOGGER.error("Could not write locations for job {}; marking it FAILED", job.getId(), cause);
        job.setLocationStatus(LocationStatus.FAILED);
        transactionTemplate.executeWithoutResult(status -> {
            // Rows from an earlier resolution would contradict the FAILED status.
            jobLocationRepository.deleteByFetchedJobIds(List.of(job.getId()));
            fetchedJobsRepository.save(job);
        });
    }

    private void persist(List<FetchedJob> page, Map<String, List<ResolvedLocation>> byLabel,
                         OffsetDateTime now) {
        List<Long> jobIds = page.stream().map(FetchedJob::getId).toList();

        // Clear first. The unique constraint prevents duplicates but not stale
        // rows: after a prompt change or a curated-table correction, an old row
        // would otherwise survive beside its replacement and the job would carry
        // both the wrong location and the right one.
        jobLocationRepository.deleteByFetchedJobIds(jobIds);

        List<JobLocation> toSave = new ArrayList<>();
        for (FetchedJob job : page) {
            List<ResolvedLocation> resolved = byLabel.get(labelOf(job));
            // uq_job_locations_job_raw_resolution allows one row per element and
            // resolution. A model can name the same element twice, and an
            // unlocatable one is stored as UNDEFINED, so two rows can collapse
            // onto one key; the first is kept, which preserves the primary.
            Set<RowKey> seen = new HashSet<>();
            for (ResolvedLocation location : resolved) {
                JobLocation row = new JobLocation(job.getId(), location.input(), location.outcome(),
                        location.tier(), location.primary(), now);
                if (seen.add(new RowKey(row.getRaw(), row.getResolution()))) {
                    toSave.add(row);
                } else {
                    LOGGER.debug("Dropping duplicate location '{}' ({}) for job {}",
                            row.getRaw(), row.getResolution(), job.getId());
                }
            }
            // A job whose every location failed to resolve is marked FAILED, not
            // PENDING. Retrying would produce the same answer, and FAILED is what
            // makes it show up in the curation worklist rather than cycling
            // through the pass forever.
            boolean anyMatchable = resolved.stream().anyMatch(ResolvedLocation::isMatchable);
            job.setLocationStatus(anyMatchable ? LocationStatus.RESOLVED : LocationStatus.FAILED);
        }

        jobLocationRepository.saveAll(toSave);
        fetchedJobsRepository.saveAll(page);
        LOGGER.debug("Wrote {} locations for {} jobs", toSave.size(), page.size());
    }

    /**
     * @param job the job to read
     * @return its raw location label, or an empty string when the payload
     *         carried none. Empty is a resolvable case rather than an error: it
     *         resolves to the search origin under rule 4
     */
    private static String labelOf(FetchedJob job) {
        String label = job.getJobData() == null ? null : job.getJobData().locationName();
        return label == null ? "" : label;
    }

    /** The columns {@code uq_job_locations_job_raw_resolution} is unique on, within one job. */
    private record RowKey(String raw, Resolution resolution) {
    }
}
