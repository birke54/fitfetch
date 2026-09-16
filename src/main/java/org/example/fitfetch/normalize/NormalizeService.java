package org.example.fitfetch.normalize;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.LocationStatus;
import org.example.fitfetch.domain.NormalizeStatus;
import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.location.GeocodingException;
import org.example.fitfetch.location.OriginRadius;
import org.example.fitfetch.location.RadiusSearchService;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.example.fitfetch.utilities.JdPreProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled pass that extracts seniority and requirement signals from the
 * description of every job located within the search radius.
 *
 * <p>Each run does two things:
 *
 * <ol>
 *   <li><strong>Age sweep.</strong> Every pending job the ATS posted longer ago
 *       than {@code app.normalize.max-age-days} is marked {@code TOO_OLD}, in
 *       one statement. It runs first because a job's age is known whatever its
 *       location, so marking it here spares the radius sweep the work.</li>
 *   <li><strong>Sweep.</strong> Every pending job whose location is resolved
 *       but outside the radius is marked {@code OUT_OF_RANGE}, in one statement.
 *       The pass only reads pending jobs, so it never looks at those again.</li>
 *   <li><strong>Normalize.</strong> The next page of pending jobs within the
 *       radius is sent to the model, one job at a time, and each is written as
 *       soon as it is answered.</li>
 * </ol>
 *
 * <p>A job ages while it waits, so one held up behind a backlog can cross the
 * limit before the model ever sees it. That is the page size to turn up, not a
 * reason to date a job by when it was fetched.
 *
 * <p>So every job reaches the model at most once. A job whose location is still
 * pending, or failed and awaiting curation, is left alone until the location
 * pass resolves it.
 *
 * <p>Failures are split the same way as in the location pass. A model outage
 * stops the run and leaves the remaining jobs pending for next time; jobs
 * already answered in that run stay written. The exception is a job that keeps
 * failing (see {@link #STRIKE_LIMIT}). An answer the model cannot give &mdash;
 * an empty description, unusable output, nothing extracted &mdash; marks the job
 * {@code FAILED}, since deterministic sampling would give the same answer on
 * every retry.
 *
 * <p>The model call happens outside any transaction, for the same reason as in
 * the location pass: a call can take minutes, and a transaction held across it
 * would hold a connection and its locks the whole time.
 *
 * @see NormalizeStatus
 */
@Service
public class NormalizeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizeService.class);

    /**
     * How many times a job may stop the run before it is set aside instead.
     *
     * <p>A model outage fails every job, so stopping the run is right: the
     * next run tries again. But a job that fails on its own &mdash; a
     * description the model always times out on, say &mdash; would stop every
     * run at the same place, and nothing behind it would ever be normalized.
     * Two runs is long enough to ride out a restart, short enough that one bad
     * job cannot hold the queue for long.
     */
    static final int STRIKE_LIMIT = 2;

    private final FetchedJobsRepository fetchedJobsRepository;
    private final NormalizedJobRepository normalizedJobRepository;
    private final LlmSignalExtractor extractor;
    private final RadiusSearchService radiusSearch;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final MetricService metricService;
    private final boolean enabled;
    private final int pageSize;
    private final double radiusMiles;
    private final int maxAgeDays;

    /**
     * Id of the last job the pass looked at. Held in memory only: after a
     * restart the pass starts from the front again, which costs nothing but
     * revisiting jobs that were set aside.
     */
    private final AtomicLong cursor = new AtomicLong();

    /** Jobs by status as of the last successful pass, for the backlog gauge. */
    private final AtomicLong pendingJobs = new AtomicLong();
    private final AtomicLong failedJobs = new AtomicLong();
    private final AtomicLong outOfRangeJobs = new AtomicLong();
    private final AtomicLong tooOldJobs = new AtomicLong();

    /** When the pass last ran without stopping, in epoch seconds, for its gauge. */
    private final AtomicLong lastSuccess = new AtomicLong();

    /**
     * Consecutive run-stopping failures per job, cleared when the job gets an
     * answer. In memory only, like the cursor; a restart gives every job a
     * fresh start. Holds only jobs that are currently failing, so it stays
     * small.
     */
    private final Map<Long, Integer> strikes = new ConcurrentHashMap<>();

    /**
     * @param fetchedJobsRepository   source of pending jobs, and where status is
     *                                recorded
     * @param normalizedJobRepository destination for normalizations
     * @param extractor               the model
     * @param radiusSearch            supplies the circle around the origin as
     *                                configured now
     * @param transactionTemplate     scopes each write explicitly
     * @param clock                   time source
     * @param metricService           where outcomes, stops and the backlog are
     *                                recorded
     * @param enabled                 {@code app.normalize.enable}
     * @param pageSize                {@code app.normalize.page-size}
     * @param radiusMiles             {@code app.normalize.radius-miles}
     * @param maxAgeDays              {@code app.normalize.max-age-days}; zero or
     *                                less to normalize a job however old it is
     */
    public NormalizeService(FetchedJobsRepository fetchedJobsRepository,
                            NormalizedJobRepository normalizedJobRepository,
                            LlmSignalExtractor extractor,
                            RadiusSearchService radiusSearch,
                            TransactionTemplate transactionTemplate,
                            Clock clock,
                            MetricService metricService,
                            @Value("${app.normalize.enable}") boolean enabled,
                            @Value("${app.normalize.page-size}") int pageSize,
                            @Value("${app.normalize.radius-miles}") double radiusMiles,
                            @Value("${app.normalize.max-age-days}") int maxAgeDays) {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.normalizedJobRepository = normalizedJobRepository;
        this.extractor = extractor;
        this.radiusSearch = radiusSearch;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.metricService = metricService;
        this.enabled = enabled;
        this.pageSize = pageSize;
        this.radiusMiles = radiusMiles;
        this.maxAgeDays = maxAgeDays;

        // Starts at startup rather than zero, so the gauge's age is how long the
        // pass has gone without a successful run, not how long since 1970.
        lastSuccess.set(clock.instant().getEpochSecond());
        metricService.registerGauge(MetricName.NORMALIZE_PASS_LAST_SUCCESS_SECONDS, Map.of(), lastSuccess::get);
        metricService.registerGauge(MetricName.NORMALIZE_JOBS_BACKLOG,
                Map.of(TagName.STATUS, "pending"), pendingJobs::get);
        metricService.registerGauge(MetricName.NORMALIZE_JOBS_BACKLOG,
                Map.of(TagName.STATUS, "failed"), failedJobs::get);
        metricService.registerGauge(MetricName.NORMALIZE_JOBS_BACKLOG,
                Map.of(TagName.STATUS, "out_of_range"), outOfRangeJobs::get);
        metricService.registerGauge(MetricName.NORMALIZE_JOBS_BACKLOG,
                Map.of(TagName.STATUS, "too_old"), tooOldJobs::get);
    }

    /**
     * Cron entry point (schedule from {@code app.normalize.schedule}) that
     * sweeps out-of-range jobs and normalizes one page of the rest.
     */
    @Scheduled(cron = "${app.normalize.schedule}")
    public void normalizePendingJobs() {
        if (!enabled) {
            LOGGER.info("NormalizeService is disabled");
            return;
        }
        try {
            int normalized = normalizeOnePage();
            lastSuccess.set(clock.instant().getEpochSecond());
            refreshBacklog();
            if (normalized > 0) {
                LOGGER.info("Normalized {} jobs, {} still pending", normalized, pendingJobs.get());
            }
        } catch (SignalExtractionException e) {
            LOGGER.warn("Normalization stopped early, model unavailable: {}", e.getMessage());
            recordStopped("model_unavailable");
        } catch (GeocodingException e) {
            // Without the origin there is no radius, and no way to tell which
            // jobs are worth a model call.
            LOGGER.warn("Normalization skipped, search origin unavailable: {}", e.getMessage());
            recordStopped("origin_unavailable");
        } catch (RuntimeException e) {
            // Most likely the database. Unwritten jobs stay pending for next run.
            LOGGER.error("Normalization pass failed, will retry", e);
            recordStopped("error");
        }
    }

    /**
     * Updates the backlog gauge. A failure here leaves the gauge as it was: the
     * page is already written, so it must not count as the pass stopping.
     * {@code pending} counts only located jobs, which are the ones waiting for
     * the model rather than for the location pass.
     */
    private void refreshBacklog() {
        try {
            pendingJobs.set(fetchedJobsRepository.countByNormalizeStatusAndLocationStatus(
                    NormalizeStatus.PENDING, LocationStatus.RESOLVED));
            failedJobs.set(fetchedJobsRepository.countByNormalizeStatus(NormalizeStatus.FAILED));
            outOfRangeJobs.set(fetchedJobsRepository.countByNormalizeStatus(NormalizeStatus.OUT_OF_RANGE));
            tooOldJobs.set(fetchedJobsRepository.countByNormalizeStatus(NormalizeStatus.TOO_OLD));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not count the normalization backlog: {}", e.getMessage());
        }
    }

    private void recordStopped(String reason) {
        metricService.recordCounter(MetricName.NORMALIZE_PASS_STOPPED_COUNT, Map.of(TagName.REASON, reason));
    }

    private void recordJobs(String result, int count) {
        if (count > 0) {
            metricService.recordCounterByIncrement(MetricName.NORMALIZE_JOBS_COUNT,
                    Map.of(TagName.RESULT, result), count);
        }
    }

    /**
     * Sweeps out-of-range jobs, then normalizes the next page of pending jobs
     * within the radius.
     *
     * <p>A job that has stopped the run {@link #STRIKE_LIMIT} times in a row is
     * set aside rather than allowed to stop it again: it stays {@code PENDING},
     * since the failures may still be an outage, and the rest of the page goes
     * ahead. At most one such job is retried per run.
     *
     * @return how many jobs were written as {@code NORMALIZED}
     * @throws SignalExtractionException if the model becomes unreachable; jobs
     *                                   answered before that are already written,
     *                                   and the cursor stays put so the next run
     *                                   starts from the same place
     */
    int normalizeOnePage() {
        // The origin is read first even though the age sweep does not need it:
        // without it the run stops, and a pass that stops must not have written
        // anything, or the backlog gauges describe a run that never finished.
        OriginRadius radius = radiusSearch.around(radiusMiles);
        OffsetDateTime postedAfter = postedAfter();

        // Before the radius sweep, which has to resolve a location to judge a
        // job: an old job is skipped whatever its location, so marking it here
        // saves that work.
        Integer stale = transactionTemplate.execute(
                status -> fetchedJobsRepository.markTooOldForNormalization(postedAfter));
        if (stale != null && stale > 0) {
            LOGGER.info("{} jobs were posted more than {} days ago and will not be normalized", stale, maxAgeDays);
            recordJobs("too_old", stale);
        }

        Integer excluded = transactionTemplate.execute(
                status -> fetchedJobsRepository.markOutOfRangeForNormalization(radius));
        if (excluded != null && excluded > 0) {
            LOGGER.info("{} jobs are outside the {}-mile radius and will not be normalized", excluded, radiusMiles);
            recordJobs("out_of_range", excluded);
        }

        List<FetchedJob> page = nextPage(radius, postedAfter);
        if (page.isEmpty()) {
            return 0;
        }

        int normalized = 0;
        int setAside = 0;
        boolean probed = false;
        for (FetchedJob job : page) {
            String description = JdPreProcess.toPlainText(
                    job.getJobData() == null ? null : job.getJobData().content());
            if (description.isEmpty()) {
                LOGGER.warn("Job {} has an empty description; marking it FAILED", job.getId());
                markFailed(job, "empty_description");
                continue;
            }

            if (strikes.getOrDefault(job.getId(), 0) >= STRIKE_LIMIT) {
                // Retry at most one struck-out job per run. During an outage they
                // all fail, so trying each would stretch a run by a timeout per
                // job; the rest wait for the cursor to come round again.
                if (probed) {
                    setAside++;
                    continue;
                }
                probed = true;
            }

            Optional<NormalizedData> data;
            try {
                data = extractor.extract(job.getJobData().title(), description);
                strikes.remove(job.getId());
            } catch (SignalExtractionException e) {
                int count = strikes.merge(job.getId(), 1, Integer::sum);
                if (count < STRIKE_LIMIT) {
                    throw e;
                }
                LOGGER.warn("Job {} has failed {} times in a row ({}); leaving it pending "
                        + "so the rest of the page can proceed", job.getId(), count, e.getMessage());
                setAside++;
                continue;
            } catch (RuntimeException e) {
                // A bug rather than an outage, so it will fail the same way every
                // time. Failing the job keeps it from blocking the queue.
                strikes.remove(job.getId());
                LOGGER.error("Could not normalize job {}; marking it FAILED", job.getId(), e);
                markFailed(job, "error");
                continue;
            }

            if (data.isEmpty()) {
                LOGGER.warn("No usable normalization for job {}; marking it FAILED", job.getId());
                markFailed(job, "no_answer");
            } else if (write(job, data.get())) {
                normalized++;
            }
        }

        cursor.set(page.getLast().getId());
        if (setAside > 0) {
            LOGGER.info("Set aside {} repeatedly failing jobs; they stay pending", setAside);
        }
        recordJobs("normalized", normalized);
        recordJobs("set_aside", setAside);
        return normalized;
    }

    /**
     * Reads the next page of pending jobs after the cursor, wrapping to the start
     * once the end is reached.
     *
     * <p>Paging by id is what lets the pass move past jobs set aside after
     * repeated failures, which stay {@code PENDING}. Wrapping is what brings them
     * round again.
     */
    private List<FetchedJob> nextPage(OriginRadius radius, OffsetDateTime postedAfter) {
        List<FetchedJob> page = fetchedJobsRepository.findPendingNormalizationWithinRadius(
                radius, postedAfter, cursor.get(), pageSize);
        if (page.isEmpty() && cursor.get() > 0) {
            cursor.set(0);
            page = fetchedJobsRepository.findPendingNormalizationWithinRadius(radius, postedAfter, 0, pageSize);
        }
        return page;
    }

    /**
     * @return the oldest posting date still worth a model call, or the epoch if
     *         {@code app.normalize.max-age-days} is zero or less, which lets
     *         every job through however old it is
     */
    private OffsetDateTime postedAfter() {
        return maxAgeDays <= 0
                ? OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)
                : OffsetDateTime.now(clock).minusDays(maxAgeDays);
    }

    /**
     * Writes a job's normalization, or marks it {@code FAILED} if that cannot
     * be done.
     *
     * @return {@code true} if the job was written as {@code NORMALIZED}
     */
    private boolean write(FetchedJob job, NormalizedData data) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // A requeued job still has its previous row.
                normalizedJobRepository.deleteByFetchedJobId(job.getId());
                normalizedJobRepository.save(new NormalizedJob(job.getId(), data, extractor.model(),
                        SignalPrompt.VERSION, OffsetDateTime.now(clock)));
                fetchedJobsRepository.updateNormalizeStatus(job.getId(), NormalizeStatus.NORMALIZED);
            });
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("Could not write normalization for job {}; marking it FAILED", job.getId(), e);
            markFailed(job, "write_error");
            return false;
        }
    }

    /**
     * Marks a job {@code FAILED}, and counts it once that is written. If this
     * fails too, the database itself is the problem, and the exception
     * propagates to end the run.
     *
     * @param reason why, as the failed-jobs counter's {@code reason}
     */
    private void markFailed(FetchedJob job, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            // A row from an earlier normalization would contradict FAILED.
            normalizedJobRepository.deleteByFetchedJobId(job.getId());
            fetchedJobsRepository.updateNormalizeStatus(job.getId(), NormalizeStatus.FAILED);
        });
        recordJobs("failed", 1);
        metricService.recordCounter(MetricName.NORMALIZE_JOBS_FAILED_COUNT, Map.of(TagName.REASON, reason));
    }
}
