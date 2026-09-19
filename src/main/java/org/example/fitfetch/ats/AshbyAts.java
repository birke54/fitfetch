package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchLimits;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AshbyJobEntry;
import org.example.fitfetch.fetching.records.AtsJobEntry;
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
 * {@link Ats} implementation backed by the Ashby job board API.
 *
 * <p>On construction the list of company board identifiers ("slugs") is read
 * from the JSON resource pointed to by {@code app.slug-ids}, under
 * {@code ats.ashby}. Each call to {@link #fetchJobs()} then sweeps every slug
 * with a {@link SlugSweep}, which fetches several at once within Ashby's
 * limits and keeps only jobs that are new, pass the title filter and are
 * listed.
 *
 * <p><strong>Unlisted postings.</strong> Ashby publishes {@code isListed},
 * which says whether a posting belongs on the company's public board: when it
 * is false the job is reachable only by direct link. That covers confidential
 * searches, evergreen pipelines and internal reqs, none of which a candidate
 * could legitimately have found, and all of which stay live indefinitely and so
 * would age into the freshness signal as if they were fresh openings. They are
 * dropped by the {@code keep} predicate handed to the sweep. The test is
 * {@code !Boolean.FALSE.equals(...)} rather than {@code !isListed()}, so a
 * payload that omits the field keeps the posting instead of silently dropping
 * every job on the board.
 *
 * <p>The class name matters: {@link AtsName#fromBoardClass} matches an enum
 * constant whose {@link AtsName#name() name} is contained in the upper-cased
 * simple class name, so this must stay {@code AshbyAts}.
 *
 * <p>Instances are effectively immutable after construction and safe to reuse
 * across fetch cycles.
 *
 * @see Ats
 * @see AtsName#ASHBY
 */
@Component
public class AshbyAts implements Ats {

    private final FetchedJobsRepository fetchedJobsRepository;
    private final SlugSweep<AshbyJobEntry> sweep;
    private final List<String> slugs;

    /**
     * Creates the Ashby ATS with Ashby's configured limits.
     *
     * @param fetchedJobsRepository repository used to look up job IDs that have
     *                              already been seen, so they can be filtered out
     * @param fetcher               transport that retrieves the raw job list for
     *                              a single Ashby slug
     * @param metricService         sink for per-slug fetch outcome counters
     * @param slugsJson             resource ({@code app.slug-ids}) holding the
     *                              slug list under {@code ats.ashby}
     * @param readSlugsInParallel   flag ({@code app.fetch.read-slugs-list-in-parallel})
     *                              controlling whether the slug JSON array is
     *                              streamed in parallel while being parsed
     * @param limits                {@code app.fetch.limits}; sets how many slugs
     *                              are fetched at once. Ashby documents no rate
     *                              limit on its public board endpoint and did not
     *                              throttle a burst of sequential requests, so it
     *                              has no override and takes the defaults
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the {@code ats.ashby}
     *                               node
     */
    @Autowired
    public AshbyAts(FetchedJobsRepository fetchedJobsRepository,
                    Fetch<AshbyJobEntry> fetcher,
                    MetricService metricService,
                    @Value("${app.slug-ids}") Resource slugsJson,
                    @Value("${app.fetch.read-slugs-list-in-parallel}") boolean readSlugsInParallel,
                    FetchLimits limits) throws IOException, KeyException {
        this(fetchedJobsRepository, fetcher, metricService, slugsJson, readSlugsInParallel,
                limits.forAts(AtsName.ASHBY).maxConcurrent());
    }

    /**
     * Creates the Ashby ATS fetching one slug at a time.
     *
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the {@code ats.ashby}
     *                               node
     * @see #AshbyAts(FetchedJobsRepository, Fetch, MetricService, Resource, boolean, FetchLimits)
     */
    public AshbyAts(FetchedJobsRepository fetchedJobsRepository,
                    Fetch<AshbyJobEntry> fetcher,
                    MetricService metricService,
                    Resource slugsJson,
                    boolean readSlugsInParallel) throws IOException, KeyException {
        this(fetchedJobsRepository, fetcher, metricService, slugsJson, readSlugsInParallel, 1);
    }

    /**
     * Creates the Ashby ATS fetching up to {@code maxConcurrent} slugs at once.
     *
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the {@code ats.ashby}
     *                               node
     * @see #AshbyAts(FetchedJobsRepository, Fetch, MetricService, Resource, boolean, FetchLimits)
     */
    public AshbyAts(FetchedJobsRepository fetchedJobsRepository,
                    Fetch<AshbyJobEntry> fetcher,
                    MetricService metricService,
                    Resource slugsJson,
                    boolean readSlugsInParallel,
                    int maxConcurrent) throws IOException, KeyException {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.sweep = new SlugSweep<>(AtsName.ASHBY, fetcher, metricService, maxConcurrent,
                job -> !Boolean.FALSE.equals(job.isListed()));
        this.slugs = SlugFile.load(slugsJson, AtsName.ASHBY, readSlugsInParallel);
    }

    /**
     * Sweeps every configured Ashby slug and collects the jobs that are new,
     * relevant and listed; see {@link SlugSweep} for how failures, 429s and
     * pauses are handled.
     *
     * @return the new job entries across all slugs; never {@code null},
     *         possibly empty
     */
    @Override
    public List<AtsJobEntry> fetchJobs() {
        return sweep.run(slugs, fetchedJobsRepository.findJobIdByAtsName(AtsName.ASHBY));
    }

    /**
     * Returns the Ashby slugs loaded at construction time.
     *
     * @return the configured slugs in configuration order; never {@code null}
     */
    @Override
    public List<String> getSlugs() {
        return this.slugs;
    }
}
