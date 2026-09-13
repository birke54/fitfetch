package org.example.fitfetch.location;

import org.example.fitfetch.domain.LocationInterpretation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CachingLocationExtractorTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");
    private static final int PROMPT_VERSION = 3;
    private static final String MODEL = "llama3.1:8b";
    private static final Duration DAY = Duration.ofDays(1);

    private LocationExtractor delegate;
    private LocationInterpretationRepository repository;
    private CachingLocationExtractor extractor;

    private static final ExtractedLocation CANADA = new ExtractedLocation(
            "Remote, Canada", LocationKind.REMOTE_SPECIFIER, "Canada", SpecifierType.COUNTRY);

    @BeforeEach
    void setUp() {
        delegate = mock(LocationExtractor.class);
        repository = mock(LocationInterpretationRepository.class);
        extractor = newExtractor(PROMPT_VERSION, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CachingLocationExtractor newExtractor(int promptVersion, Clock clock) {
        return new CachingLocationExtractor(delegate, repository, MODEL, promptVersion, DAY, clock);
    }

    private LocationInterpretation cachedEntry(String key, String raw, int promptVersion, Instant hitAt) {
        LocationInterpretation entry = new LocationInterpretation(
                key, raw, List.of(CANADA), MODEL, promptVersion,
                OffsetDateTime.ofInstant(hitAt, ZoneOffset.UTC));
        entry.setLastHitAt(OffsetDateTime.ofInstant(hitAt, ZoneOffset.UTC));
        return entry;
    }

    // ------------------------------------------------------------------ miss

    @Test
    @DisplayName("A miss calls the model and records the answer")
    void testMissCallsDelegateAndCaches() {
        when(repository.findById("remote, canada")).thenReturn(Optional.empty());
        when(delegate.extract("Remote, Canada")).thenReturn(ExtractionResult.of(List.of(CANADA)));

        ExtractionResult result = extractor.extract("Remote, Canada");

        assertEquals(1, result.locations().size());
        ArgumentCaptor<LocationInterpretation> saved = ArgumentCaptor.forClass(LocationInterpretation.class);
        verify(repository).save(saved.capture());
        assertEquals("remote, canada", saved.getValue().getLocationKey());
        assertEquals("Remote, Canada", saved.getValue().getRaw(), "the verbatim label is kept for replay");
        assertEquals(MODEL, saved.getValue().getModel());
        assertEquals(PROMPT_VERSION, saved.getValue().getPromptVersion());
    }

    @Test
    @DisplayName("What is cached is the pre-policy extraction, not a resolved location")
    void testCachesPrePolicyShape() {
        // Caching post-policy output would bake the search origin into every row,
        // so reconfiguring it would invalidate the whole cache.
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.extract(anyString())).thenReturn(ExtractionResult.of(List.of(CANADA)));

        extractor.extract("Remote, Canada");

        ArgumentCaptor<LocationInterpretation> saved = ArgumentCaptor.forClass(LocationInterpretation.class);
        verify(repository).save(saved.capture());
        ExtractedLocation stored = saved.getValue().getOutputs().getFirst();
        assertEquals(LocationKind.REMOTE_SPECIFIER, stored.kind());
        assertEquals("Canada", stored.specifier());
    }

    // ------------------------------------------------------------------- hit

    @Test
    @DisplayName("A hit never reaches the model")
    void testHitSkipsDelegate() {
        when(repository.findById("remote, canada"))
                .thenReturn(Optional.of(cachedEntry("remote, canada", "Remote, Canada", PROMPT_VERSION, NOW)));

        ExtractionResult result = extractor.extract("Remote, Canada");

        assertEquals("Canada", result.locations().getFirst().specifier());
        assertTrue(result.cacheable());
        verify(delegate, never()).extract(anyString());
    }

    @Test
    @DisplayName("Case and padding variants hit the same cached row")
    void testLookupIsNormalized() {
        when(repository.findById("remote, canada"))
                .thenReturn(Optional.of(cachedEntry("remote, canada", "Remote, Canada", PROMPT_VERSION, NOW)));

        extractor.extract("  REMOTE,   Canada  ");

        verify(repository).findById("remote, canada");
        verify(delegate, never()).extract(anyString());
    }

    // ------------------------------------------------------- write behaviour

    @Test
    @DisplayName("A row read again the same day is not rewritten")
    void testHitWithinGranularityDoesNotWrite() {
        // Read statistics drive eviction, so every read implies a write unless
        // it is coarsened. Without this a read-heavy cache becomes write-heavy.
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(cachedEntry("remote, canada", "Remote, Canada",
                        PROMPT_VERSION, NOW.minus(Duration.ofHours(2)))));

        extractor.extract("Remote, Canada");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A row not read for longer than the granularity is updated")
    void testHitOutsideGranularityWrites() {
        LocationInterpretation stale = cachedEntry("remote, canada", "Remote, Canada",
                PROMPT_VERSION, NOW.minus(Duration.ofDays(3)));
        when(repository.findById(anyString())).thenReturn(Optional.of(stale));

        extractor.extract("Remote, Canada");

        verify(repository).save(stale);
        assertEquals(1L, stale.getHitCount());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), stale.getLastHitAt());
    }

    // -------------------------------------------------------- prompt version

    @Test
    @DisplayName("A row from an older prompt is treated as a miss and re-extracted")
    void testStalePromptVersionReExtracts() {
        LocationInterpretation old = cachedEntry("remote, canada", "Remote, Canada", PROMPT_VERSION - 1, NOW);
        when(repository.findById(anyString())).thenReturn(Optional.of(old));
        when(delegate.extract(anyString())).thenReturn(ExtractionResult.of(List.of(CANADA)));

        extractor.extract("Remote, Canada");

        verify(delegate).extract("Remote, Canada");
        verify(repository).save(old);
        assertEquals(PROMPT_VERSION, old.getPromptVersion(), "the row is upgraded in place");
    }

    @Test
    @DisplayName("A row from a newer prompt is still used, so a rollback does not thrash")
    void testNewerPromptVersionIsHonoured() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(cachedEntry("remote, canada", "Remote, Canada", PROMPT_VERSION + 1, NOW)));

        extractor.extract("Remote, Canada");

        verify(delegate, never()).extract(anyString());
    }

    // ----------------------------------------------------------- cacheability

    @Test
    @DisplayName("Untrusted output is returned but deliberately not remembered")
    void testUntrustedResultNotCached() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.extract(anyString())).thenReturn(ExtractionResult.untrusted("Somewhere odd"));

        ExtractionResult result = extractor.extract("Somewhere odd");

        assertFalse(result.cacheable());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A well-formed 'nothing found' is cached, since asking again changes nothing")
    void testUnparseableIsCached() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.extract(anyString())).thenReturn(ExtractionResult.unparseable("!!!"));

        extractor.extract("!!!");

        verify(repository).save(any());
    }

    // ---------------------------------------------------------- propagation

    @Test
    @DisplayName("A model outage propagates and writes nothing")
    void testExtractionFailurePropagates() {
        // Caching here would bake a transient outage into durable data; the
        // caller has to be able to leave the job pending.
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.extract(anyString()))
                .thenThrow(new LocationExtractionException("Ollama unreachable"));

        assertThrows(LocationExtractionException.class, () -> extractor.extract("Remote, Canada"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A blank label is answered without touching the cache or the model")
    void testBlankLabelShortCircuits() {
        ExtractionResult result = extractor.extract("   ");

        assertEquals(LocationKind.UNPARSEABLE, result.locations().getFirst().kind());
        verify(repository, never()).findById(anyString());
        verify(delegate, never()).extract(anyString());
    }

    @Test
    @DisplayName("Null input is rejected")
    void testNullRejected() {
        assertThrows(NullPointerException.class, () -> extractor.extract(null));
    }
}
