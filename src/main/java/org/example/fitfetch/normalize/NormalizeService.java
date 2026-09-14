package org.example.fitfetch.normalize;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.NormalizeStatus;
import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.location.GeocodingException;
import org.example.fitfetch.location.OriginRadius;
import org.example.fitfetch.location.RadiusSearchService;
import org.example.fitfetch.utilities.JdPreProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
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
 *   <li><strong>Sweep.</strong> Every pending job whose location is resolved
 *       but outside the radius is marked {@code OUT_OF_RANGE}, in one statement.
 *       The pass only reads pending jobs, so it never looks at those again.</li>
 *   <li><strong>Normalize.</strong> The next page of pending jobs within the
 *       radius is sent to the model, one job at a time, and each is written as
 *       soon as it is answered.</li>
 * </ol>
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
    private final boolean enabled;
    private final int pageSize;
    private final double radiusMiles;

    /**
     * Id of the last job the pass looked at. Held in memory only: after a
     * restart the pass starts from the front again, which costs nothing but
     * revisiting jobs that were set aside.
     */
    private final AtomicLong cursor = new AtomicLong();

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
     * @param enabled                 {@code app.normalize.enable}
     * @param pageSize                {@code app.normalize.page-size}
     * @param radiusMiles             {@code app.normalize.radius-miles}
     */
    public NormalizeService(FetchedJobsRepository fetchedJobsRepository,
                            NormalizedJobRepository normalizedJobRepository,
                            LlmSignalExtractor extractor,
                            RadiusSearchService radiusSearch,
                            TransactionTemplate transactionTemplate,
                            Clock clock,
                            @Value("${app.normalize.enable}") boolean enabled,
                            @Value("${app.normalize.page-size}") int pageSize,
                            @Value("${app.normalize.radius-miles}") double radiusMiles) {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.normalizedJobRepository = normalizedJobRepository;
        this.extractor = extractor;
        this.radiusSearch = radiusSearch;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.pageSize = pageSize;
        this.radiusMiles = radiusMiles;
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
            normalizeOnePage();
        } catch (SignalExtractionException e) {
            LOGGER.warn("Normalization stopped early, model unavailable: {}", e.getMessage());
        } catch (GeocodingException e) {
            // Without the origin there is no radius, and no way to tell which
            // jobs are worth a model call.
            LOGGER.warn("Normalization skipped, search origin unavailable: {}", e.getMessage());
        } catch (RuntimeException e) {
            // Most likely the database. Unwritten jobs stay pending for next run.
            LOGGER.error("Normalization pass failed, will retry", e);
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
        OriginRadius radius = radiusSearch.around(radiusMiles);

        Integer excluded = transactionTemplate.execute(
                status -> fetchedJobsRepository.markOutOfRangeForNormalization(radius));
        if (excluded != null && excluded > 0) {
            LOGGER.info("{} jobs are outside the {}-mile radius and will not be normalized", excluded, radiusMiles);
        }

        List<FetchedJob> page = nextPage(radius);
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
                markFailed(job);
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
                data = extractor.extract(description);
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
                markFailed(job);
                continue;
            }

            if (data.isEmpty()) {
                LOGGER.warn("No usable normalization for job {}; marking it FAILED", job.getId());
                markFailed(job);
            } else if (write(job, data.get())) {
                normalized++;
            }
        }

        cursor.set(page.getLast().getId());
        if (setAside > 0) {
            LOGGER.info("Set aside {} repeatedly failing jobs; they stay pending", setAside);
        }
        LOGGER.info("Normalized {} of {} jobs, {} still pending", normalized, page.size(),
                fetchedJobsRepository.countByNormalizeStatus(NormalizeStatus.PENDING));
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
    private List<FetchedJob> nextPage(OriginRadius radius) {
        List<FetchedJob> page = fetchedJobsRepository.findPendingNormalizationWithinRadius(
                radius, cursor.get(), pageSize);
        if (page.isEmpty() && cursor.get() > 0) {
            cursor.set(0);
            page = fetchedJobsRepository.findPendingNormalizationWithinRadius(radius, 0, pageSize);
        }
        return page;
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
            markFailed(job);
            return false;
        }
    }

    /**
     * Marks a job {@code FAILED}. If this fails too, the database itself is the
     * problem, and the exception propagates to end the run.
     */
    private void markFailed(FetchedJob job) {
        transactionTemplate.executeWithoutResult(status -> {
            // A row from an earlier normalization would contradict FAILED.
            normalizedJobRepository.deleteByFetchedJobId(job.getId());
            fetchedJobsRepository.updateNormalizeStatus(job.getId(), NormalizeStatus.FAILED);
        });
    }
}
