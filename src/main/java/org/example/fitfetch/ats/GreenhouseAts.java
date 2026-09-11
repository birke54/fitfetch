package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.example.fitfetch.utilities.TitleFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * {@link Ats} implementation backed by the Greenhouse job board API.
 *
 * <p>On construction the list of company board identifiers ("slugs") is read
 * from the JSON resource pointed to by {@code app.slug-ids}, expecting the
 * structure {@code { "ats": { "greenhouse": [ ... ] } }}. Each call to
 * {@link #fetchJobs()} then polls every slug, discards jobs that have already
 * been persisted or whose title is filtered out by {@link TitleFilter}, and
 * emits counters through {@link MetricService} for per-slug success, transport
 * errors and empty responses.
 *
 * <p>Instances are effectively immutable after construction and safe to reuse
 * across fetch cycles.
 *
 * @see Ats
 * @see AtsName#GREENHOUSE
 */
@Component
public class GreenhouseAts implements Ats {
    private static final Logger LOGGER = LoggerFactory.getLogger(GreenhouseAts.class);
    private final FetchedJobsRepository fetchedJobsRepository;
    private final Fetch<GreenhouseJobEntry> fetcher;
    private final MetricService metricService;
    private final boolean readSlugsInParallel;
    private final List<String> slugs;

    /**
     * Creates a Greenhouse ATS client and eagerly loads its configured slugs.
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
     * @throws FileNotFoundException if {@code slugsJson} does not exist
     * @throws IOException           if the slugs resource cannot be read
     * @throws KeyException          if the JSON is missing the
     *                               {@code ats.greenhouse} node
     */
    public GreenhouseAts(FetchedJobsRepository fetchedJobsRepository,
                         Fetch<GreenhouseJobEntry> fetcher,
                         MetricService metricService,
                         @Value("${app.slug-ids}") Resource slugsJson,
                         @Value("${app.fetch.read-slugs-list-in-parallel}") boolean readSlugsInParallel) throws IOException, KeyException {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.fetcher = fetcher;
        this.metricService = metricService;
        this.readSlugsInParallel = readSlugsInParallel;
        this.slugs = loadSlugs(readSlugs(slugsJson));
    }

    /**
     * Reads the raw bytes of the slugs resource.
     *
     * @param slugsResource the configured slugs resource
     * @return the full contents of the resource
     * @throws FileNotFoundException if the resource does not exist
     * @throws IOException           if the resource cannot be read
     */
    private byte[] readSlugs(Resource slugsResource) throws IOException {
        if(!slugsResource.exists()) {
            throw new FileNotFoundException("Slugs file not found: " + slugsResource.getFilename());
        }
        try (InputStream inputStream = slugsResource.getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    /**
     * Parses the slug identifiers from the raw JSON document.
     *
     * <p>The document must contain a string array at {@code ats.greenhouse}.
     *
     * @param fileBytes the raw JSON content of the slugs resource
     * @return the list of Greenhouse slugs in document order
     * @throws KeyException if the {@code ats.greenhouse} node is absent
     */
    private List<String> loadSlugs(byte[] fileBytes) throws KeyException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(fileBytes);

        if (rootNode == null || !rootNode.has("ats") || !rootNode.get("ats").has("greenhouse")) {
            throw new KeyException("Invalid slugs JSON schema structure. Missing 'ats.greenhouse' node.");
        }

        JsonNode slugsList = rootNode.get("ats").get("greenhouse");
        LOGGER.info("Loaded {} slugs for the Greenhouse ATS", slugsList.size());
        return StreamSupport.stream(rootNode.get("ats").get("greenhouse").spliterator(), readSlugsInParallel)
                .map(JsonNode::asString)
                .toList();
    }

    /**
     * Polls every configured Greenhouse slug and collects the jobs that are new
     * and relevant.
     *
     * <p>For each slug the raw job list is fetched and then filtered to drop
     * entries whose ID is already recorded in {@link FetchedJobsRepository} and
     * entries rejected by {@link TitleFilter#keep(String)}. Client and server
     * HTTP errors for a single slug are logged, recorded as
     * {@link org.example.fitfetch.metrics.MetricName#SLUG_FETCH_ERROR_COUNT} and
     * skipped without aborting the remaining slugs; a {@code null} or empty
     * response is recorded as
     * {@link org.example.fitfetch.metrics.MetricName#SLUG_FETCH_NULL_RESPONSE_COUNT}
     * and skipped. Successful slugs are recorded as
     * {@link org.example.fitfetch.metrics.MetricName#SLUG_FETCH_SUCCESS_COUNT}.
     *
     * @return the aggregated new job entries across all slugs; never
     *         {@code null}, possibly empty
     */
    @Override
    public List<AtsJobEntry> fetchJobs() {
        Set<String> jobIds = fetchedJobsRepository.findJobIdByAtsName(AtsName.GREENHOUSE);
        List<AtsJobEntry> newJobEntries = new ArrayList<>();
        for (String slug : slugs) {
            AtsResponse<GreenhouseJobEntry> jobs = null;
            try {
                jobs = fetcher.fetchJobs(slug);
            } catch (HttpClientErrorException e) {
                LOGGER.error("Client error requesting jobs for {} from Greenhouse: {}", slug, e.getMessage());
                metricService.recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT, Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue(), TagName.SLUG, slug));
            } catch (HttpServerErrorException e) {
                LOGGER.error("Server error requesting jobs for {} from Greenhouse: {}", slug, e.getMessage());
                metricService.recordCounter(MetricName.SLUG_FETCH_ERROR_COUNT, Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue(), TagName.SLUG, slug));
            }
            if (jobs == null || jobs.jobs() == null) {
                metricService.recordCounter(MetricName.SLUG_FETCH_NULL_RESPONSE_COUNT, Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue(), TagName.SLUG, slug));
                continue;
            }
            List<GreenhouseJobEntry> filteredJobs = jobs.jobs()
                    .stream()
                    .filter(job -> job instanceof GreenhouseJobEntry ghJob
                            && !jobIds.contains(ghJob.id().toString()) && TitleFilter.keep(ghJob.title()))
                    .toList();

            LOGGER.debug("Found {} new jobs for {} from Greenhouse; filtered out {} existing jobs", jobs.jobs().size() - filteredJobs.size(), slug, filteredJobs.size());
            metricService.recordCounter(MetricName.SLUG_FETCH_SUCCESS_COUNT, Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue(), TagName.SLUG, slug));
            newJobEntries.addAll(filteredJobs);
        }
        return newJobEntries;
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
