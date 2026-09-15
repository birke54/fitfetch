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
import org.example.fitfetch.normalize.SignalClassification;
import org.example.fitfetch.profile.LoadedProfile;
import org.example.fitfetch.profile.ProfileLoader;
import org.example.fitfetch.profile.ProfileSource;
import org.example.fitfetch.skills.SkillCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MatchServiceTest {

    private static final EmbeddingSettings SETTINGS =
            new EmbeddingSettings("nomic-embed-text", "search_query: ", "search_document: ");
    private static final String KEY = SETTINGS.key();
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private JobMatchRepository matches;
    private EmbeddingCache cache;
    private EmbeddingService embeddingService;
    private ProfileEmbeddings profileEmbeddings;
    private MetricService metricService;
    private ProfileSource profile;
    private long nextId = 1L;

    @BeforeEach
    void setUp() {
        matches = mock(JobMatchRepository.class);
        cache = mock(EmbeddingCache.class);
        embeddingService = mock(EmbeddingService.class);
        profileEmbeddings = new ProfileEmbeddings();
        metricService = mock(MetricService.class);
        profile = ProfileSource.load(Path.of("profile", "profile.example.yaml").toString(),
                new ProfileLoader(SkillCanonicalizer.none()));
        when(cache.vectorsFor(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).stream().map(text -> MatchFixtures.SIGNAL).toList());
    }

    private LoadedProfile loaded() {
        return profile.current().orElseThrow();
    }

    /** Holds bullet vectors for the example profile, as the embedding pass would. */
    private void bulletsEmbedded() throws Exception {
        Map<String, float[]> byId = new HashMap<>();
        loaded().profile().roles().forEach(role -> role.bullets()
                .forEach(bullet -> byId.put(bullet.id(), MatchFixtures.similarity(0.9))));
        Method set = ProfileEmbeddings.class.getDeclaredMethod("set", ProfileEmbeddings.Snapshot.class);
        set.setAccessible(true);
        set.invoke(profileEmbeddings, new ProfileEmbeddings.Snapshot(loaded().sha256(), KEY, byId));
    }

    private MatchService service(boolean enabled, ProfileSource source) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(template).executeWithoutResult(any());
        return new MatchService(matches, cache, SETTINGS, embeddingService, source, profileEmbeddings,
                new MatchScorer(SkillCanonicalizer.none()), template, metricService,
                Clock.fixed(NOW, ZoneOffset.UTC), enabled, 100);
    }

    private NormalizedJob job(String... signals) {
        NormalizedJob job = new NormalizedJob(nextId, MatchFixtures.job(java.util.Arrays.stream(signals)
                .map(text -> new Signal(SignalClassification.REQUIRED_SKILL, text)).toArray(Signal[]::new)),
                "qwen2.5:14b", 1, OffsetDateTime.now());
        ReflectionTestUtils.setField(job, "id", nextId++);
        return job;
    }

    private void pageContains(NormalizedJob... jobs) {
        when(matches.findJobsNeedingScore(eq(KEY), eq(loaded().sha256()), eq(MatchScorer.VERSION), anyLong(),
                any(Pageable.class))).thenReturn(List.of(jobs));
    }

    @Test
    @DisplayName("A disabled pass touches nothing")
    void testDisabled() {
        service(false, profile).scorePending();

        verifyNoInteractions(matches, cache, embeddingService);
    }

    @Test
    @DisplayName("With no profile there is nothing to score against")
    void testNoProfile() {
        ProfileSource none = ProfileSource.load("", new ProfileLoader(SkillCanonicalizer.none()));

        assertEquals(0, service(true, none).scoreOnePage());
        verifyNoInteractions(matches, cache, embeddingService);
    }

    @Test
    @DisplayName("Each job is scored and its match replaced, with the versions that produced it")
    void testScoresAndReplaces() throws Exception {
        bulletsEmbedded();
        NormalizedJob job = job("Operates Kafka.");
        pageContains(job);

        assertEquals(1, service(true, profile).scoreOnePage());

        verify(cache).vectorsFor(List.of("search_query: Operates Kafka."));
        InOrder order = inOrder(matches);
        order.verify(matches).deleteByNormalizedJobId(job.getId());
        ArgumentCaptor<JobMatch> saved = ArgumentCaptor.forClass(JobMatch.class);
        order.verify(matches).save(saved.capture());
        JobMatch match = saved.getValue();
        assertEquals(job.getId(), match.getNormalizedJobId());
        assertEquals(job.getFetchedJobId(), match.getFetchedJobId());
        assertEquals(loaded().sha256(), match.getProfileSha256());
        assertEquals(KEY, match.getEmbedderKey());
        assertEquals(MatchScorer.VERSION, match.getScoringVersion());
        assertTrue(match.isEligible());
        assertEquals(1, match.getSignals().size());
        verify(metricService).recordCounter(MetricName.MATCH_JOBS_SCORED_COUNT, Map.of(TagName.RESULT, "eligible"));
    }

    @Test
    @DisplayName("The profile's bullets are embedded first, if they are not already")
    void testEmbedsProfileFirst() throws Exception {
        bulletsEmbedded();
        pageContains();

        service(true, profile).scoreOnePage();

        verify(embeddingService).embedProfile();
    }

    @Test
    @DisplayName("Bullets without vectors for this profile version stop the page rather than scoring against nothing")
    void testBulletsMissing() {
        pageContains(job("Operates Kafka."));

        assertThrows(IllegalStateException.class, () -> service(true, profile).scoreOnePage());
        verify(matches, never()).save(any());
    }

    @Test
    @DisplayName("An embedding outage stops the page; jobs scored before it keep their matches")
    void testOutage() throws Exception {
        bulletsEmbedded();
        NormalizedJob first = job("First.");
        NormalizedJob second = job("Second.");
        pageContains(first, second);
        when(cache.vectorsFor(List.of("search_query: Second."))).thenThrow(new EmbeddingException("down", null));

        assertThrows(EmbeddingException.class, () -> service(true, profile).scoreOnePage());

        verify(matches).deleteByNormalizedJobId(first.getId());
        verify(matches, never()).deleteByNormalizedJobId(second.getId());
    }

    @Test
    @DisplayName("An outage is caught so the scheduler is not poisoned")
    void testOutageCaught() throws Exception {
        bulletsEmbedded();
        pageContains(job("A."));
        when(cache.vectorsFor(anyList())).thenThrow(new EmbeddingException("down", null));

        assertDoesNotThrow(() -> service(true, profile).scorePending());
    }
}
