package org.example.fitfetch.embedding;

import org.example.fitfetch.domain.Embedding;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbeddingCacheTest {

    private static final String MODEL = "nomic-embed-text";
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private EmbeddingRepository repository;
    private Embedder embedder;
    private MetricService metricService;

    @BeforeEach
    void setUp() {
        repository = mock(EmbeddingRepository.class);
        embedder = mock(Embedder.class);
        metricService = mock(MetricService.class);
        when(repository.findByModelAndInputSha256In(eq(MODEL), anyCollection())).thenReturn(List.of());
    }

    private EmbeddingCache cache(int batchSize) {
        return new EmbeddingCache(repository, embedder, MODEL, metricService, Clock.fixed(NOW, ZoneOffset.UTC),
                batchSize);
    }

    private static Embedding cached(String input, float... vector) {
        return new Embedding(MODEL, EmbeddingCache.sha256(input), vector, OffsetDateTime.now());
    }

    /** Answers each batch with a vector whose first value is the input's length. */
    private void embedderAnswersWithLengths() {
        when(embedder.embed(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return batch.stream().map(text -> new float[]{text.length(), 1f}).toList();
        });
    }

    @Test
    @DisplayName("Cached texts come from the table, and only the rest reach the model")
    void testOnlyMissingEmbedded() {
        when(repository.findByModelAndInputSha256In(eq(MODEL), anyCollection()))
                .thenReturn(List.of(cached("known", 9f, 9f)));
        embedderAnswersWithLengths();

        List<float[]> vectors = cache(32).vectorsFor(List.of("known", "new"));

        verify(embedder).embed(List.of("new"));
        assertArrayEquals(new float[]{9f, 9f}, vectors.get(0));
        assertArrayEquals(new float[]{3f, 1f}, vectors.get(1));
    }

    @Test
    @DisplayName("New vectors are saved, keyed by model and input hash")
    void testNewVectorsSaved() {
        embedderAnswersWithLengths();

        cache(32).vectorsFor(List.of("new"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Embedding>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        Embedding row = saved.getValue().getFirst();
        assertEquals(MODEL, row.getModel());
        assertEquals(EmbeddingCache.sha256("new"), row.getInputSha256());
        assertArrayEquals(new float[]{3f, 1f}, row.getVector());
    }

    @Test
    @DisplayName("A text asked for twice is embedded once, and both answers are in order")
    void testDuplicatesEmbeddedOnce() {
        embedderAnswersWithLengths();

        List<float[]> vectors = cache(32).vectorsFor(List.of("ab", "abc", "ab"));

        verify(embedder).embed(List.of("ab", "abc"));
        assertEquals(3, vectors.size());
        assertArrayEquals(vectors.get(0), vectors.get(2));
        assertArrayEquals(new float[]{3f, 1f}, vectors.get(1));
    }

    @Test
    @DisplayName("Missing texts go to the model in batches, each saved as it returns")
    void testBatches() {
        embedderAnswersWithLengths();

        cache(2).vectorsFor(List.of("a", "b", "c", "d", "e"));

        verify(embedder).embed(List.of("a", "b"));
        verify(embedder).embed(List.of("c", "d"));
        verify(embedder).embed(List.of("e"));
        verify(repository, times(3)).saveAll(anyList());
    }

    @Test
    @DisplayName("An outage partway keeps the batches already embedded")
    void testOutageKeepsEarlierBatches() {
        when(embedder.embed(anyList()))
                .thenReturn(List.of(new float[]{1f}, new float[]{2f}))
                .thenThrow(new EmbeddingException("down", null));

        assertThrows(EmbeddingException.class, () -> cache(2).vectorsFor(List.of("a", "b", "c")));

        verify(repository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("Cache hits and model calls are counted")
    void testCounted() {
        when(repository.findByModelAndInputSha256In(eq(MODEL), anyCollection()))
                .thenReturn(List.of(cached("known", 1f)));
        embedderAnswersWithLengths();

        cache(32).vectorsFor(List.of("known", "new", "newer"));

        verify(metricService).recordCounterByIncrement(MetricName.EMBEDDING_INPUTS_COUNT,
                Map.of(TagName.RESULT, "cached"), 1);
        verify(metricService).recordCounterByIncrement(MetricName.EMBEDDING_INPUTS_COUNT,
                Map.of(TagName.RESULT, "embedded"), 2);
    }

    @Test
    @DisplayName("Everything cached makes no model call and saves nothing")
    void testAllCached() {
        when(repository.findByModelAndInputSha256In(eq(MODEL), anyCollection()))
                .thenReturn(List.of(cached("known", 1f)));

        cache(32).vectorsFor(List.of("known"));

        verifyNoInteractions(embedder);
        verify(repository, never()).saveAll(anyList());
    }
}
