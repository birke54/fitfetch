package org.example.fitfetch.embedding;

import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.normalize.NormalizedJobRepository;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.profile.CandidateProfile;
import org.example.fitfetch.profile.LoadedProfile;
import org.example.fitfetch.profile.ProfileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled pass that gives job signals and profile bullets their vectors, so
 * scoring can compare them.
 *
 * <p>Each run first makes sure the profile's bullets are embedded, which after
 * the first run is a cache lookup, then embeds one page of normalized jobs not
 * yet embedded with the current settings, and marks each done.
 *
 * <p>Failures follow the other passes. The model being down stops the run and
 * leaves the page for the next one; jobs finished before that stay done, and
 * their vectors are cached. A job that fails for any other reason is logged and
 * passed over, and paging by id with a cursor keeps it from blocking the rest.
 */
@Service
public class EmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    private final NormalizedJobRepository normalizedJobRepository;
    private final EmbeddingCache cache;
    private final EmbeddingSettings settings;
    private final ProfileSource profileSource;
    private final ProfileEmbeddings profileEmbeddings;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int pageSize;

    /** Id of the last job the pass looked at; in memory only, like the other passes. */
    private final AtomicLong cursor = new AtomicLong();

    /**
     * @param normalizedJobRepository source of jobs to embed, and where they are
     *                                marked done
     * @param cache                   vectors, cached or from the model
     * @param settings                the model and prefixes in force
     * @param profileSource           the profile whose bullets are embedded
     * @param profileEmbeddings       where the bullet vectors are held
     * @param transactionTemplate     scopes the mark on each job
     * @param enabled                 {@code app.embedding.enable}
     * @param pageSize                {@code app.embedding.page-size}
     */
    public EmbeddingService(NormalizedJobRepository normalizedJobRepository,
                            EmbeddingCache cache,
                            EmbeddingSettings settings,
                            ProfileSource profileSource,
                            ProfileEmbeddings profileEmbeddings,
                            TransactionTemplate transactionTemplate,
                            @Value("${app.embedding.enable}") boolean enabled,
                            @Value("${app.embedding.page-size}") int pageSize) {
        this.normalizedJobRepository = normalizedJobRepository;
        this.cache = cache;
        this.settings = settings;
        this.profileSource = profileSource;
        this.profileEmbeddings = profileEmbeddings;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.pageSize = pageSize;
    }

    /** Cron entry point (schedule from {@code app.embedding.schedule}). */
    @Scheduled(cron = "${app.embedding.schedule}")
    public void embedPending() {
        if (!enabled) {
            LOGGER.info("EmbeddingService is disabled");
            return;
        }
        try {
            embedProfile();
            int embedded = embedOnePage();
            if (embedded > 0) {
                LOGGER.info("Embedded the signals of {} jobs", embedded);
            }
        } catch (EmbeddingException e) {
            LOGGER.warn("Embedding pass stopped early, model unavailable: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Embedding pass failed, will retry", e);
        }
    }

    /**
     * Embeds the profile's bullets unless the vectors held are already for this
     * profile version and these settings.
     */
    void embedProfile() {
        LoadedProfile loaded = profileSource.current().orElse(null);
        if (loaded == null || profileEmbeddings.isCurrent(loaded.sha256(), settings.key())) {
            return;
        }
        List<String> ids = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        for (CandidateProfile.Role role : loaded.profile().roles()) {
            for (CandidateProfile.Bullet bullet : role.bullets()) {
                ids.add(bullet.id());
                inputs.add(settings.document(bullet.text()));
            }
        }
        List<float[]> vectors = cache.vectorsFor(inputs);
        Map<String, float[]> byBulletId = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            byBulletId.put(ids.get(i), vectors.get(i));
        }
        profileEmbeddings.set(new ProfileEmbeddings.Snapshot(loaded.sha256(), settings.key(), byBulletId));
        LOGGER.info("Embedded {} profile bullets", byBulletId.size());
    }

    /**
     * Embeds the signals of one page of jobs not yet embedded with the current
     * settings.
     *
     * @return how many jobs were embedded and marked
     * @throws EmbeddingException if the model becomes unreachable; jobs marked
     *                            before that stay marked, and the cursor stays
     *                            put so the next run starts from the same place
     */
    int embedOnePage() {
        String key = settings.key();
        List<NormalizedJob> page = nextPage(key);
        if (page.isEmpty()) {
            return 0;
        }
        int embedded = 0;
        for (NormalizedJob job : page) {
            List<String> inputs = job.getSignals().stream().map(Signal::text).map(settings::query).toList();
            try {
                cache.vectorsFor(inputs);
            } catch (EmbeddingException e) {
                throw e;
            } catch (RuntimeException e) {
                // Not an outage, so it would fail the same way next run; passing
                // over it keeps it from stopping every run at the same place.
                LOGGER.error("Could not embed the signals of normalized job {}; passing over it", job.getId(), e);
                continue;
            }
            transactionTemplate.executeWithoutResult(
                    status -> normalizedJobRepository.markEmbedded(job.getId(), key));
            embedded++;
        }
        cursor.set(page.getLast().getId());
        return embedded;
    }

    /** The next page after the cursor, wrapping to the start once the end is reached. */
    private List<NormalizedJob> nextPage(String key) {
        List<NormalizedJob> page = pendingAfter(key, cursor.get());
        if (page.isEmpty() && cursor.get() > 0) {
            cursor.set(0);
            page = pendingAfter(key, 0);
        }
        return page;
    }

    private List<NormalizedJob> pendingAfter(String key, long afterId) {
        return normalizedJobRepository.findNeedingEmbedding(key, afterId, PageRequest.of(0, pageSize));
    }
}
