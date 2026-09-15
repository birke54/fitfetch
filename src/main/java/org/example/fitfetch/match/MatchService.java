package org.example.fitfetch.match;

import org.example.fitfetch.domain.JobMatch;
import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.embedding.EmbeddingCache;
import org.example.fitfetch.embedding.EmbeddingException;
import org.example.fitfetch.embedding.EmbeddingService;
import org.example.fitfetch.embedding.EmbeddingSettings;
import org.example.fitfetch.embedding.ProfileEmbeddings;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.profile.LoadedProfile;
import org.example.fitfetch.profile.ProfileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled pass that scores embedded jobs against the candidate profile and
 * stores each result with its reasons.
 *
 * <p>A job is scored once per profile version, embedder and scoring rules: a
 * change to any of them makes its match stale, and it is scored again. Nothing
 * is scored without a profile, or before its bullets have vectors.
 *
 * <p>Scoring itself is local arithmetic. The only call out is for the job's
 * signal vectors, which the embedding pass has already cached, so an outage
 * here is rare; when one happens the run stops and the page is left for the
 * next.
 */
@Service
public class MatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchService.class);

    private final JobMatchRepository jobMatchRepository;
    private final EmbeddingCache cache;
    private final EmbeddingSettings settings;
    private final EmbeddingService embeddingService;
    private final ProfileSource profileSource;
    private final ProfileEmbeddings profileEmbeddings;
    private final MatchScorer scorer;
    private final TransactionTemplate transactionTemplate;
    private final MetricService metricService;
    private final Clock clock;
    private final boolean enabled;
    private final int pageSize;

    /** Id of the last job the pass looked at; in memory only, like the other passes. */
    private final AtomicLong cursor = new AtomicLong();

    /**
     * @param jobMatchRepository  finds jobs to score and stores matches
     * @param cache               the signals' cached vectors
     * @param settings            the embedder in force
     * @param embeddingService    embeds the profile's bullets if they are not yet
     * @param profileSource       the candidate profile
     * @param profileEmbeddings   the bullets' vectors
     * @param scorer              the scoring rules
     * @param transactionTemplate scopes each replace of a match
     * @param metricService       where scored jobs are counted
     * @param clock               time source
     * @param enabled             {@code app.match.enable}
     * @param pageSize            {@code app.match.page-size}
     */
    public MatchService(JobMatchRepository jobMatchRepository,
                        EmbeddingCache cache,
                        EmbeddingSettings settings,
                        EmbeddingService embeddingService,
                        ProfileSource profileSource,
                        ProfileEmbeddings profileEmbeddings,
                        MatchScorer scorer,
                        TransactionTemplate transactionTemplate,
                        MetricService metricService,
                        Clock clock,
                        @Value("${app.match.enable}") boolean enabled,
                        @Value("${app.match.page-size}") int pageSize) {
        this.jobMatchRepository = jobMatchRepository;
        this.cache = cache;
        this.settings = settings;
        this.embeddingService = embeddingService;
        this.profileSource = profileSource;
        this.profileEmbeddings = profileEmbeddings;
        this.scorer = scorer;
        this.transactionTemplate = transactionTemplate;
        this.metricService = metricService;
        this.clock = clock;
        this.enabled = enabled;
        this.pageSize = pageSize;
    }

    /** Cron entry point (schedule from {@code app.match.schedule}). */
    @Scheduled(cron = "${app.match.schedule}")
    public void scorePending() {
        if (!enabled) {
            LOGGER.info("MatchService is disabled");
            return;
        }
        try {
            int scored = scoreOnePage();
            if (scored > 0) {
                LOGGER.info("Scored {} jobs against the profile", scored);
            }
        } catch (EmbeddingException e) {
            LOGGER.warn("Matching pass stopped early, embedding model unavailable: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Matching pass failed, will retry", e);
        }
    }

    /**
     * Scores one page of jobs whose match is missing or stale.
     *
     * @return how many jobs were scored
     */
    int scoreOnePage() {
        LoadedProfile loaded = profileSource.current().orElse(null);
        if (loaded == null) {
            LOGGER.info("No candidate profile; nothing to score against");
            return 0;
        }
        embeddingService.embedProfile();
        String key = settings.key();
        Map<String, float[]> bulletVectors = profileEmbeddings.current()
                .filter(snapshot -> profileEmbeddings.isCurrent(loaded.sha256(), key))
                .map(ProfileEmbeddings.Snapshot::byBulletId)
                .orElseThrow(() -> new IllegalStateException("The profile's bullets have no vectors"));

        List<NormalizedJob> page = nextPage(key, loaded.sha256());
        if (page.isEmpty()) {
            return 0;
        }
        int scored = 0;
        for (NormalizedJob job : page) {
            List<String> inputs = job.getSignals().stream().map(Signal::text).map(settings::query).toList();
            MatchResult result;
            try {
                result = scorer.score(job.toData(), cache.vectorsFor(inputs), loaded.profile(), bulletVectors);
            } catch (EmbeddingException e) {
                throw e;
            } catch (RuntimeException e) {
                // Would fail the same way next run; passing over it keeps it from
                // stopping every run at the same place.
                LOGGER.error("Could not score normalized job {}; passing over it", job.getId(), e);
                continue;
            }
            JobMatch match = new JobMatch(job, result, loaded.sha256(), key, MatchScorer.VERSION,
                    OffsetDateTime.now(clock));
            transactionTemplate.executeWithoutResult(status -> {
                jobMatchRepository.deleteByNormalizedJobId(job.getId());
                jobMatchRepository.save(match);
            });
            metricService.recordCounter(MetricName.MATCH_JOBS_SCORED_COUNT,
                    Map.of(TagName.RESULT, result.eligible() ? "eligible" : "excluded"));
            scored++;
        }
        cursor.set(page.getLast().getId());
        return scored;
    }

    /** The next page after the cursor, wrapping to the start once the end is reached. */
    private List<NormalizedJob> nextPage(String key, String profileSha256) {
        List<NormalizedJob> page = pendingAfter(key, profileSha256, cursor.get());
        if (page.isEmpty() && cursor.get() > 0) {
            cursor.set(0);
            page = pendingAfter(key, profileSha256, 0);
        }
        return page;
    }

    private List<NormalizedJob> pendingAfter(String key, String profileSha256, long afterId) {
        return jobMatchRepository.findJobsNeedingScore(key, profileSha256, MatchScorer.VERSION, afterId,
                PageRequest.of(0, pageSize));
    }
}
