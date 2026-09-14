package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchLimits;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.metrics.MetricService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.KeyException;
import java.util.List;

/**
 * {@link Ats} implementation backed by the Greenhouse job board API.
 *
 * <p>On construction the list of company board identifiers ("slugs") is read
 * from the JSON resource pointed to by {@code app.slug-ids}, under
 * {@code ats.greenhouse}. Each call to {@link #fetchJobs()} then sweeps every
 * slug with a {@link SlugSweep}, which fetches several at once within
 * Greenhouse's limits and keeps only jobs that are new and pass the title
 * filter.
 *
 * <p>Instances are effectively immutable after construction and safe to reuse
 * across fetch cycles.
 *
 * @see Ats
 * @see AtsName#GREENHOUSE
 */
@Component
public class GreenhouseAts implements Ats {

    private final FetchedJobsRepository fetchedJobsRepository;
    private final SlugSweep<GreenhouseJobEntry> sweep;
    private final List<String> slugs;

    /**
     * Creates the Greenhouse ATS with Greenhouse's configured limits.
     *
     * @param fetchedJobsRepository repository used to look up job IDs that have
     *                              already been seen, so they can be filtered out
     * @param fetcher               transport that retrieves the raw job list for
     *                              a single Greenhouse slug
     * @param metricService         sink for per-slug fetch outcome counters
     * @param slugsJson             resource ({@code app.slug-ids}) holding the
     *                              slug list under {@code ats.greenhouse}
     * @param readSlugsInParallel   flag ({@code app.fetch.read-slugs-list-in-parallel})
     *                              controlling whether the slug JSON array is
     *                              streamed in parallel while being parsed
     * @param limits                {@code app.fetch.limits}; sets how many slugs
     *                              are fetched at once
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the
     *                               {@code ats.greenhouse} node
     */
    @Autowired
    public GreenhouseAts(FetchedJobsRepository fetchedJobsRepository,
                         Fetch<GreenhouseJobEntry> fetcher,
                         MetricService metricService,
                         @Value("${app.slug-ids}") Resource slugsJson,
                         @Value("${app.fetch.read-slugs-list-in-parallel}") boolean readSlugsInParallel,
                         FetchLimits limits) throws IOException, KeyException {
        this(fetchedJobsRepository, fetcher, metricService, slugsJson, readSlugsInParallel,
                limits.forAts(AtsName.GREENHOUSE).maxConcurrent());
    }

    /**
     * Creates the Greenhouse ATS fetching one slug at a time.
     *
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the
     *                               {@code ats.greenhouse} node
     * @see #GreenhouseAts(FetchedJobsRepository, Fetch, MetricService, Resource, boolean, FetchLimits)
     */
    public GreenhouseAts(FetchedJobsRepository fetchedJobsRepository,
                         Fetch<GreenhouseJobEntry> fetcher,
                         MetricService metricService,
                         Resource slugsJson,
                         boolean readSlugsInParallel) throws IOException, KeyException {
        this(fetchedJobsRepository, fetcher, metricService, slugsJson, readSlugsInParallel, 1);
    }

    /**
     * Creates the Greenhouse ATS fetching up to {@code maxConcurrent} slugs at
     * once.
     *
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the
     *                               {@code ats.greenhouse} node
     * @see #GreenhouseAts(FetchedJobsRepository, Fetch, MetricService, Resource, boolean, FetchLimits)
     */
    public GreenhouseAts(FetchedJobsRepository fetchedJobsRepository,
                         Fetch<GreenhouseJobEntry> fetcher,
                         MetricService metricService,
                         Resource slugsJson,
                         boolean readSlugsInParallel,
                         int maxConcurrent) throws IOException, KeyException {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.sweep = new SlugSweep<>(AtsName.GREENHOUSE, fetcher, metricService, maxConcurrent);
        this.slugs = SlugFile.load(slugsJson, AtsName.GREENHOUSE, readSlugsInParallel);
    }

    /**
     * Sweeps every configured Greenhouse slug and collects the jobs that are
     * new and relevant; see {@link SlugSweep} for how failures, 429s and pauses
     * are handled.
     *
     * @return the new job entries across all slugs; never {@code null},
     *         possibly empty
     */
    @Override
    public List<AtsJobEntry> fetchJobs() {
        return sweep.run(slugs, fetchedJobsRepository.findJobIdByAtsName(AtsName.GREENHOUSE));
    }

    /**
     * Returns the Greenhouse slugs loaded at construction time.
     *
     * @return the configured slugs in configuration order; never {@code null}
     */
    @Override
    public List<String> getSlugs() {
        return this.slugs;
    }
}
