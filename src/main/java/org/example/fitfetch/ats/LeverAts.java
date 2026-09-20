package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchLimits;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.LeverJobEntry;
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
 * {@link Ats} implementation backed by the Lever postings API.
 *
 * <p>On construction the list of company board identifiers ("slugs") is read
 * from the JSON resource pointed to by {@code app.slug-ids}, under
 * {@code ats.lever}. Each call to {@link #fetchJobs()} then sweeps every slug
 * with a {@link SlugSweep}, which fetches several at once and keeps only jobs
 * that are new and pass the title filter.
 *
 * <p><strong>No keep predicate, and that is a finding rather than an
 * omission.</strong> {@link AshbyAts} drops unlisted postings because Ashby
 * publishes an {@code isListed} flag saying a posting is off the public board.
 * Lever has no analogue: no {@code isListed}, no {@code confidential}, no
 * {@code state}. It does not need one, because this endpoint <em>is</em> the
 * public board &mdash; a requisition the company has not published simply does
 * not appear in the response. So the four-argument {@link SlugSweep}
 * constructor is used and the shared checks are the whole filter.
 *
 * <p>The one field that might tempt a predicate is
 * {@code categories.commitment}. It must not be used: it is uncontrolled free
 * text typed by the recruiter. Across 12,231 sampled postings one working week
 * is spelled {@code "Full-Time"} (1,556 postings), {@code "Full-time"} (1,513),
 * {@code "Full Time"} (1,499), {@code "Full time"} (384),
 * {@code "Full-time Employment"} (819), {@code "Full Time Hybrid"} (922) and
 * {@code "Full Time On-Site"} (386), with 409 postings omitting the key
 * entirely. Any rule built on it would silently drop jobs on the boards that
 * happen to phrase it differently.
 *
 * <p><strong>No rate-limit override.</strong> Lever documents none on this
 * endpoint and did not throttle a burst of 40 requests per second &mdash; zero
 * 429s &mdash; so it takes {@code app.fetch.limits.defaults} like every other
 * provider without an entry.
 *
 * <p>The class name matters: {@link AtsName#fromBoardClass} matches an enum
 * constant whose {@link AtsName#name() name} is contained in the upper-cased
 * simple class name, so this must stay {@code LeverAts}.
 *
 * <p>Instances are effectively immutable after construction and safe to reuse
 * across fetch cycles.
 *
 * @see Ats
 * @see AtsName#LEVER
 */
@Component
public class LeverAts implements Ats {

    private final FetchedJobsRepository fetchedJobsRepository;
    private final SlugSweep<LeverJobEntry> sweep;
    private final List<String> slugs;

    /**
     * Creates the Lever ATS with Lever's configured limits.
     *
     * @param fetchedJobsRepository repository used to look up job IDs that have
     *                              already been seen, so they can be filtered out
     * @param fetcher               transport that retrieves the raw job list for
     *                              a single Lever slug
     * @param metricService         sink for per-slug fetch outcome counters
     * @param slugsJson             resource ({@code app.slug-ids}) holding the
     *                              slug list under {@code ats.lever}
     * @param readSlugsInParallel   flag ({@code app.fetch.read-slugs-list-in-parallel})
     *                              controlling whether the slug JSON array is
     *                              streamed in parallel while being parsed
     * @param limits                {@code app.fetch.limits}; sets how many slugs
     *                              are fetched at once. Lever documents no rate
     *                              limit on its postings endpoint and did not
     *                              throttle 40 requests per second, so it has no
     *                              override and takes the defaults
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the {@code ats.lever}
     *                               node
     */
    @Autowired
    public LeverAts(FetchedJobsRepository fetchedJobsRepository,
                    Fetch<LeverJobEntry> fetcher,
                    MetricService metricService,
                    @Value("${app.slug-ids}") Resource slugsJson,
                    @Value("${app.fetch.read-slugs-list-in-parallel}") boolean readSlugsInParallel,
                    FetchLimits limits) throws IOException, KeyException {
        this(fetchedJobsRepository, fetcher, metricService, slugsJson, readSlugsInParallel,
                limits.forAts(AtsName.LEVER).maxConcurrent());
    }

    /**
     * Creates the Lever ATS fetching one slug at a time.
     *
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the {@code ats.lever}
     *                               node
     * @see #LeverAts(FetchedJobsRepository, Fetch, MetricService, Resource, boolean, FetchLimits)
     */
    public LeverAts(FetchedJobsRepository fetchedJobsRepository,
                    Fetch<LeverJobEntry> fetcher,
                    MetricService metricService,
                    Resource slugsJson,
                    boolean readSlugsInParallel) throws IOException, KeyException {
        this(fetchedJobsRepository, fetcher, metricService, slugsJson, readSlugsInParallel, 1);
    }

    /**
     * Creates the Lever ATS fetching up to {@code maxConcurrent} slugs at once.
     *
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the {@code ats.lever}
     *                               node
     * @see #LeverAts(FetchedJobsRepository, Fetch, MetricService, Resource, boolean, FetchLimits)
     */
    public LeverAts(FetchedJobsRepository fetchedJobsRepository,
                    Fetch<LeverJobEntry> fetcher,
                    MetricService metricService,
                    Resource slugsJson,
                    boolean readSlugsInParallel,
                    int maxConcurrent) throws IOException, KeyException {
        this.fetchedJobsRepository = fetchedJobsRepository;
        // Four arguments, not five: Lever publishes nothing that says a posting
        // should be skipped, because an unpublished one never reaches this
        // endpoint in the first place.
        this.sweep = new SlugSweep<>(AtsName.LEVER, fetcher, metricService, maxConcurrent);
        this.slugs = SlugFile.load(slugsJson, AtsName.LEVER, readSlugsInParallel);
    }

    /**
     * Sweeps every configured Lever slug and collects the jobs that are new and
     * relevant; see {@link SlugSweep} for how failures, 429s and pauses are
     * handled.
     *
     * <p>Expect a large share of the slugs to answer 404: probing 600 slugs from
     * the shipped list answered 375 boards and 225 404s, so better than a third
     * of the harvested list is dead at any time. Those are counted as missing
     * boards rather than errors and cost the sweep nothing but the request.
     *
     * @return the new job entries across all slugs; never {@code null},
     *         possibly empty
     */
    @Override
    public List<AtsJobEntry> fetchJobs() {
        return sweep.run(slugs, fetchedJobsRepository.findJobIdByAtsName(AtsName.LEVER));
    }

    /**
     * Returns the Lever slugs loaded at construction time.
     *
     * @return the configured slugs in configuration order; never {@code null}
     */
    @Override
    public List<String> getSlugs() {
        return this.slugs;
    }
}
