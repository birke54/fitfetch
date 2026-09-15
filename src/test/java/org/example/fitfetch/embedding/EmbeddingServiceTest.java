package org.example.fitfetch.embedding;

import org.example.fitfetch.domain.NormalizedJob;
import org.example.fitfetch.normalize.EmploymentType;
import org.example.fitfetch.normalize.HardRequirements;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.NormalizedJobRepository;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.normalize.SignalClassification;
import org.example.fitfetch.normalize.Track;
import org.example.fitfetch.profile.ProfileLoader;
import org.example.fitfetch.profile.ProfileSource;
import org.example.fitfetch.skills.SkillCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbeddingServiceTest {

    private static final EmbeddingSettings SETTINGS =
            new EmbeddingSettings("nomic-embed-text", "search_query: ", "search_document: ");
    private static final String KEY = SETTINGS.key();

    private NormalizedJobRepository normalizedJobs;
    private EmbeddingCache cache;
    private ProfileEmbeddings profileEmbeddings;
    private long nextId = 1L;

    @BeforeEach
    void setUp() {
        normalizedJobs = mock(NormalizedJobRepository.class);
        cache = mock(EmbeddingCache.class);
        profileEmbeddings = new ProfileEmbeddings();
        when(cache.vectorsFor(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(text -> new float[]{text.length()}).toList();
        });
    }

    private EmbeddingService service(boolean enabled, ProfileSource profile) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(template).executeWithoutResult(any());
        return new EmbeddingService(normalizedJobs, cache, SETTINGS, profile, profileEmbeddings, template,
                enabled, 20);
    }

    private static ProfileSource noProfile() {
        return ProfileSource.load("", new ProfileLoader(SkillCanonicalizer.none()));
    }

    private static ProfileSource exampleProfile() {
        return ProfileSource.load(Path.of("profile", "profile.example.yaml").toString(),
                new ProfileLoader(SkillCanonicalizer.none()));
    }

    private NormalizedJob job(String... signals) {
        NormalizedJob job = new NormalizedJob(nextId, new NormalizedData(Seniority.SENIOR, Track.IC,
                EmploymentType.FULL_TIME, 0, HardRequirements.NONE, List.of(),
                java.util.Arrays.stream(signals)
                        .map(text -> new Signal(SignalClassification.REQUIRED_SKILL, text)).toList()),
                "qwen2.5:14b", 1, OffsetDateTime.now());
        ReflectionTestUtils.setField(job, "id", nextId++);
        return job;
    }

    private void pageContains(NormalizedJob... jobs) {
        when(normalizedJobs.findNeedingEmbedding(eq(KEY), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(jobs));
    }

    @Test
    @DisplayName("A disabled pass touches nothing")
    void testDisabled() {
        service(false, exampleProfile()).embedPending();

        verifyNoInteractions(normalizedJobs, cache);
    }

    @Test
    @DisplayName("Each job's signals are embedded as queries, and the job marked with the embedder")
    void testEmbedsAndMarks() {
        NormalizedJob job = job("Knows Kafka.", "Runs Kubernetes.");
        pageContains(job);

        assertEquals(1, service(true, noProfile()).embedOnePage());

        verify(cache).vectorsFor(List.of("search_query: Knows Kafka.", "search_query: Runs Kubernetes."));
        verify(normalizedJobs).markEmbedded(job.getId(), KEY);
    }

    @Test
    @DisplayName("An outage stops the page; jobs before it stay marked, the rest wait")
    void testOutageStopsPage() {
        NormalizedJob first = job("First.");
        NormalizedJob second = job("Second.");
        pageContains(first, second);
        when(cache.vectorsFor(List.of("search_query: Second."))).thenThrow(new EmbeddingException("down", null));

        assertThrows(EmbeddingException.class, () -> service(true, noProfile()).embedOnePage());

        verify(normalizedJobs).markEmbedded(first.getId(), KEY);
        verify(normalizedJobs, never()).markEmbedded(second.getId(), KEY);
    }

    @Test
    @DisplayName("A job that fails for another reason is passed over rather than stopping the page")
    void testOtherFailurePassedOver() {
        NormalizedJob broken = job("Broken.");
        NormalizedJob fine = job("Fine.");
        pageContains(broken, fine);
        when(cache.vectorsFor(List.of("search_query: Broken."))).thenThrow(new IllegalStateException("bad answer"));

        assertEquals(1, service(true, noProfile()).embedOnePage());

        verify(normalizedJobs, never()).markEmbedded(broken.getId(), KEY);
        verify(normalizedJobs).markEmbedded(fine.getId(), KEY);
    }

    @Test
    @DisplayName("The pass pages past the last job it saw and wraps to the start")
    void testCursor() {
        NormalizedJob first = job("First.");
        when(normalizedJobs.findNeedingEmbedding(KEY, 0L, org.springframework.data.domain.PageRequest.of(0, 20)))
                .thenReturn(List.of(first));
        EmbeddingService pass = service(true, noProfile());

        pass.embedOnePage();
        pass.embedOnePage();

        verify(normalizedJobs).findNeedingEmbedding(eq(KEY), eq(first.getId()), any(Pageable.class));
        verify(normalizedJobs, times(2)).findNeedingEmbedding(eq(KEY), eq(0L), any(Pageable.class));
    }

    @Test
    @DisplayName("The profile's bullets are embedded as documents and held by bullet id")
    void testProfileBulletsEmbedded() {
        service(true, exampleProfile()).embedProfile();

        ProfileEmbeddings.Snapshot snapshot = profileEmbeddings.current().orElseThrow();
        assertEquals(KEY, snapshot.embedderKey());
        assertEquals(5, snapshot.byBulletId().size());
        String text = "Cut p99 latency of the payments API from 900 ms to 120 ms by redesigning its caching layer.";
        assertArrayEquals(new float[]{("search_document: " + text).length()},
                snapshot.byBulletId().get("acme-payments-latency"));
    }

    @Test
    @DisplayName("Bullets already held for this profile version and embedder are not embedded again")
    void testProfileNotReembedded() {
        EmbeddingService pass = service(true, exampleProfile());

        pass.embedProfile();
        pass.embedProfile();

        verify(cache, times(1)).vectorsFor(anyList());
    }

    @Test
    @DisplayName("With no profile, jobs are still embedded")
    void testNoProfile() {
        pageContains(job("Knows Go."));

        service(true, noProfile()).embedPending();

        assertTrue(profileEmbeddings.current().isEmpty());
        verify(normalizedJobs).markEmbedded(anyLong(), eq(KEY));
    }

    @Test
    @DisplayName("An outage is caught so the scheduler is not poisoned")
    void testOutageCaught() {
        pageContains(job("Knows Go."));
        when(cache.vectorsFor(anyList())).thenThrow(new EmbeddingException("down", null));

        assertDoesNotThrow(() -> service(true, exampleProfile()).embedPending());
    }
}
