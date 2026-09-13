package org.example.fitfetch.location;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.domain.JobLocation;
import org.example.fitfetch.domain.LocationStatus;
import org.example.fitfetch.fetching.FetchedJobsRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scheduled pass that resolves the location of every fetched job that still
 * needs one.
 *
 * <p>Runs separately from normalization rather than inside it. The two are
 * siblings, not stages &mdash; this reads {@code job_data->'location'} and needs
 * nothing normalization produces &mdash; and they fail in ways that call for
 * opposite responses: a normalization failure is a bug, while a model or
 * geocoder failure is transient and retrying is correct. Sharing one flag would
 * conflate the two, and sharing one pass would make a fast, local operation run
 * at the speed of a slow, networked one.
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
    private final boolean enabled;
    private final int pageSize;

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
     */
    public LocationService(FetchedJobsRepository fetchedJobsRepository,
                           JobLocationRepository jobLocationRepository,
                           LocationResolver resolver,
                           TransactionTemplate transactionTemplate,
                           Clock clock,
                           @Value("${app.location.enable}") boolean enabled,
                           @Value("${app.location.page-size}") int pageSize) {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.jobLocationRepository = jobLocationRepository;
        this.resolver = resolver;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.pageSize = pageSize;
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
            } else {
                LOGGER.warn("Location pass stopped early, will retry: {}", e.getMessage());
            }
        } catch (LocationExtractionException e) {
            LOGGER.warn("Location pass stopped early, model unavailable: {}", e.getMessage());
        }
    }

    /**
     * Resolves a single page of pending jobs.
     *
     * @return how many jobs were written
     */
    int resolveOnePage() {
        List<FetchedJob> page = fetchedJobsRepository.findByLocationStatus(
                LocationStatus.PENDING, PageRequest.of(0, pageSize, Sort.by("id")));
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

        // 2. Resolve each distinct label once. Any transport failure aborts the
        //    page: every job keeps its PENDING status and nothing is written.
        Map<String, List<ResolvedLocation>> byLabel = new HashMap<>(distinct.size());
        for (String label : distinct) {
            byLabel.put(label, resolver.resolve(label));
        }

        // 3. Write. Only now is a transaction opened.
        OffsetDateTime now = OffsetDateTime.now(clock);
        transactionTemplate.executeWithoutResult(status -> persist(page, byLabel, now));
        return page.size();
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
            for (ResolvedLocation location : resolved) {
                toSave.add(new JobLocation(job.getId(), location.input(), location.outcome(),
                        location.tier(), location.primary(), now));
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
}
