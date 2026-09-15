package org.example.fitfetch.location;

import org.example.fitfetch.domain.GeocodeCacheEntry;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CachingGeocoderTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);
    private static final Duration DAY = Duration.ofDays(1);

    private static final GeocodeOutcome SEATTLE = new GeocodeOutcome(
            GeocodeStatus.OK, 47.7231d, -122.2967d,
            "3001 NE 130th St, Seattle, WA 98125, USA", "ChIJseattle", "ROOFTOP", false);

    private Geocoder delegate;
    private GeocodeCacheRepository repository;
    private MetricService metricService;
    private CachingGeocoder geocoder;

    @BeforeEach
    void setUp() {
        delegate = mock(Geocoder.class);
        repository = mock(GeocodeCacheRepository.class);
        metricService = mock(MetricService.class);
        geocoder = newGeocoder(true, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CachingGeocoder newGeocoder(boolean lookupEnabled, Clock clock) {
        return new CachingGeocoder(delegate, repository, TTL, DAY, lookupEnabled, clock, metricService);
    }

    private void verifyResult(String result) {
        verify(metricService).recordCounter(
                MetricName.LOCATION_GEOCODE_CACHE_COUNT, Map.of(TagName.RESULT, result));
    }

    private GeocodeCacheEntry entry(GeocodeOutcome outcome, Instant at) {
        GeocodeCacheEntry cached = new GeocodeCacheEntry(
                "seattle, wa", outcome, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        cached.setLastHitAt(OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        return cached;
    }

    // ------------------------------------------------------------------ miss

    @Test
    @DisplayName("A miss calls the geocoder and records the result")
    void testMissCallsDelegateAndCaches() {
        when(repository.findById("seattle, wa")).thenReturn(Optional.empty());
        when(delegate.geocode("Seattle, WA")).thenReturn(SEATTLE);

        GeocodeOutcome outcome = geocoder.geocode("Seattle, WA");

        assertEquals(47.7231d, outcome.latitude());
        ArgumentCaptor<GeocodeCacheEntry> saved = ArgumentCaptor.forClass(GeocodeCacheEntry.class);
        verify(repository).save(saved.capture());
        assertEquals("seattle, wa", saved.getValue().getQueryKey());
        assertEquals("ChIJseattle", saved.getValue().getPlaceId());
        verifyResult("miss");
    }

    @Test
    @DisplayName("A hit never spends a call")
    void testHitSkipsDelegate() {
        when(repository.findById("seattle, wa")).thenReturn(Optional.of(entry(SEATTLE, NOW)));

        GeocodeOutcome outcome = geocoder.geocode("Seattle, WA");

        assertEquals(GeocodeStatus.OK, outcome.status());
        assertEquals(-122.2967d, outcome.longitude());
        verify(delegate, never()).geocode(anyString());
        verifyResult("hit");
    }

    @Test
    @DisplayName("Case and padding variants hit the same cached row")
    void testLookupIsNormalized() {
        when(repository.findById("seattle, wa")).thenReturn(Optional.of(entry(SEATTLE, NOW)));

        geocoder.geocode("  Seattle,   WA ");

        verify(repository).findById("seattle, wa");
        verify(delegate, never()).geocode(anyString());
    }

    // -------------------------------------------------------- negative caching

    @Test
    @DisplayName("A negative result is cached, so a dead string costs one call ever")
    void testNegativeResultIsCached() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.geocode(anyString())).thenReturn(GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS));

        geocoder.geocode("Atlantis");

        ArgumentCaptor<GeocodeCacheEntry> saved = ArgumentCaptor.forClass(GeocodeCacheEntry.class);
        verify(repository).save(saved.capture());
        assertEquals(GeocodeStatus.ZERO_RESULTS, saved.getValue().getStatus());
        assertNull(saved.getValue().getLatitude());
    }

    @Test
    @DisplayName("A cached negative is served without a call")
    void testCachedNegativeServed() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(entry(GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS), NOW)));

        assertEquals(GeocodeStatus.ZERO_RESULTS, geocoder.geocode("Atlantis").status());
        verify(delegate, never()).geocode(anyString());
    }

    @Test
    @DisplayName("Negative entries never expire, since a dead string does not revive")
    void testNegativeEntriesDoNotGoStale() {
        // Refreshing these would spend a call to re-learn nothing; they are
        // pruned on their own short schedule instead.
        GeocodeCacheEntry ancient = entry(GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS),
                NOW.minus(Duration.ofDays(400)));
        when(repository.findById(anyString())).thenReturn(Optional.of(ancient));

        geocoder.geocode("Atlantis");

        verify(delegate, never()).geocode(anyString());
    }

    // ------------------------------------------------------------ refreshing

    @Test
    @DisplayName("Coordinates past the TTL are refreshed on access")
    void testStaleCoordinatesRefreshed() {
        GeocodeCacheEntry stale = entry(SEATTLE, NOW.minus(Duration.ofDays(45)));
        when(repository.findById(anyString())).thenReturn(Optional.of(stale));
        when(delegate.geocode("Seattle, WA")).thenReturn(SEATTLE);

        geocoder.geocode("Seattle, WA");

        verify(delegate).geocode("Seattle, WA");
        verify(repository).save(stale);
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), stale.getRefreshedAt());
        verifyResult("stale_refresh");
    }

    @Test
    @DisplayName("Coordinates inside the TTL are used as-is")
    void testFreshCoordinatesNotRefreshed() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(entry(SEATTLE, NOW.minus(Duration.ofDays(29)))));

        geocoder.geocode("Seattle, WA");

        verify(delegate, never()).geocode(anyString());
    }

    @Test
    @DisplayName("A refresh preserves read statistics rather than resetting them")
    void testRefreshKeepsHitStatistics() {
        GeocodeCacheEntry stale = entry(SEATTLE, NOW.minus(Duration.ofDays(45)));
        stale.setHitCount(17L);
        when(repository.findById(anyString())).thenReturn(Optional.of(stale));
        when(delegate.geocode(anyString())).thenReturn(SEATTLE);

        geocoder.geocode("Seattle, WA");

        assertEquals(17L, stale.getHitCount(), "a refresh must not look like a brand new row to eviction");
    }

    // ------------------------------------------------------- write behaviour

    @Test
    @DisplayName("A row read again the same day is not rewritten")
    void testHitWithinGranularityDoesNotWrite() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(entry(SEATTLE, NOW.minus(Duration.ofHours(2)))));

        geocoder.geocode("Seattle, WA");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A row not read for longer than the granularity is updated")
    void testHitOutsideGranularityWrites() {
        GeocodeCacheEntry cold = entry(SEATTLE, NOW.minus(Duration.ofDays(3)));
        when(repository.findById(anyString())).thenReturn(Optional.of(cold));

        geocoder.geocode("Seattle, WA");

        verify(repository).save(cold);
        assertEquals(1L, cold.getHitCount());
    }

    // ----------------------------------------------------------- cache-only

    @Test
    @DisplayName("With lookups disabled a miss spends nothing, writes nothing, and is not reported as ZERO_RESULTS")
    void testCacheOnlyModeOnMiss() {
        CachingGeocoder cacheOnly = newGeocoder(false, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findById(anyString())).thenReturn(Optional.empty());

        // ZERO_RESULTS would mark the job unresolvable when it only needs lookups
        // switched on, so a miss is signalled separately and is retryable.
        GeocodingDisabledException e = assertThrows(GeocodingDisabledException.class,
                () -> cacheOnly.geocode("Seattle, WA"));

        assertTrue(e.isRetryable());
        verify(delegate, never()).geocode(anyString());
        // Critically, nothing is written: enabling lookups later must resolve
        // this properly rather than find a poisoned negative entry.
        verify(repository, never()).save(any());
        verifyResult("disabled_miss");
    }

    @Test
    @DisplayName("With lookups disabled stale coordinates are served rather than treated as a miss")
    void testCacheOnlyModeServesStaleCoordinates() {
        // They cannot be refreshed, and a place that has not moved beats no answer.
        CachingGeocoder cacheOnly = newGeocoder(false, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(entry(SEATTLE, NOW.minus(Duration.ofDays(45)))));

        assertEquals(GeocodeStatus.OK, cacheOnly.geocode("Seattle, WA").status());
        verify(delegate, never()).geocode(anyString());
        verifyResult("hit");
    }

    @Test
    @DisplayName("With lookups disabled a hit is still served from the cache")
    void testCacheOnlyModeStillServesHits() {
        CachingGeocoder cacheOnly = newGeocoder(false, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findById(anyString())).thenReturn(Optional.of(entry(SEATTLE, NOW)));

        assertEquals(GeocodeStatus.OK, cacheOnly.geocode("Seattle, WA").status());
    }

    // ---------------------------------------------------------- propagation

    @Test
    @DisplayName("A quota breach propagates and writes nothing")
    void testQuotaFailurePropagates() {
        // This is the failure that would poison the cache if it were caught and
        // stored: every job at this location would be dropped from then on.
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.geocode(anyString()))
                .thenThrow(new GeocodingException("quota exhausted", false));

        GeocodingException error = assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA"));

        assertTrue(error.isRetryable());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A denied key propagates as fatal so the pass can stop")
    void testFatalFailurePropagates() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(delegate.geocode(anyString()))
                .thenThrow(new GeocodingException("request denied", true));

        assertTrue(assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA")).isFatal());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A refresh that fails leaves the existing entry intact")
    void testFailedRefreshLeavesEntryAlone() {
        GeocodeCacheEntry stale = entry(SEATTLE, NOW.minus(Duration.ofDays(45)));
        when(repository.findById(anyString())).thenReturn(Optional.of(stale));
        when(delegate.geocode(anyString())).thenThrow(new GeocodingException("quota exhausted", false));

        assertThrows(GeocodingException.class, () -> geocoder.geocode("Seattle, WA"));
        verify(repository, never()).save(any());
        assertEquals(GeocodeStatus.OK, stale.getStatus(), "the usable old value survives the failure");
    }

    @Test
    @DisplayName("Blank queries are rejected")
    void testBlankRejected() {
        assertThrows(IllegalArgumentException.class, () -> geocoder.geocode(" "));
        assertThrows(IllegalArgumentException.class, () -> geocoder.geocode(null));
    }
}
