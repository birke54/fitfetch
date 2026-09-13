package org.example.fitfetch.location;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocationResolverTest {

    private static final String ORIGIN = "3001 NE 130th St, Seattle, WA 98125";

    private static final GeocodeOutcome ANY_POINT = new GeocodeOutcome(
            GeocodeStatus.OK, 47.7231d, -122.2967d, "somewhere", "p", "ROOFTOP", false);

    private LocationExtractor extractor;
    private Geocoder geocoder;
    private LocationResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        extractor = mock(LocationExtractor.class);
        geocoder = mock(Geocoder.class);
        when(geocoder.geocode(anyString())).thenReturn(ANY_POINT);
        resolver = newResolver(25);
    }

    private LocationResolver newResolver(int cap) throws IOException {
        return new LocationResolver(
                new CuratedLocations(new ClassPathResource("location_table.json"), ORIGIN),
                extractor,
                new LocationPolicy(ORIGIN, "WA"),
                geocoder,
                cap);
    }

    private void extractorReturns(ExtractedLocation... locations) {
        when(extractor.extract(anyString())).thenReturn(ExtractionResult.of(List.of(locations)));
    }

    // ------------------------------------------------------------ tier one

    @Test
    @DisplayName("A curated label short-circuits the model entirely")
    void testCuratedShortCircuits() {
        List<ResolvedLocation> resolved = resolver.resolve("Remote US");

        assertEquals(1, resolved.size());
        assertEquals(SourceTier.CURATED, resolved.getFirst().tier());
        assertEquals(Resolution.REMOTE_IN_US, resolved.getFirst().input().resolution());
        assertEquals(ORIGIN, resolved.getFirst().input().geocodeQuery());
        verifyNoInteractions(extractor);
    }

    @Test
    @DisplayName("A curated multi-location label yields every location")
    void testCuratedMultiLocation() {
        List<ResolvedLocation> resolved =
                resolver.resolve("Boston, Massachusetts, USA; New York, New York, USA");

        assertEquals(2, resolved.size());
        assertTrue(resolved.getFirst().primary(), "the first location named leads in display");
        assertFalse(resolved.get(1).primary());
        verifyNoInteractions(extractor);
    }

    // ---------------------------------------------------------- tiers two/three

    @Test
    @DisplayName("An uncurated label goes to the extractor and through the policy switch")
    void testUncuratedUsesExtractorAndPolicy() {
        extractorReturns(new ExtractedLocation("Remote, Faroe Islands",
                LocationKind.REMOTE_SPECIFIER, "Faroe Islands", SpecifierType.COUNTRY));

        List<ResolvedLocation> resolved = resolver.resolve("Remote, Faroe Islands");

        assertEquals(Resolution.REMOTE_ELSEWHERE, resolved.getFirst().input().resolution());
        assertEquals("Faroe Islands", resolved.getFirst().input().geocodeQuery());
        assertEquals("FO", resolved.getFirst().input().regionCode());
        verify(extractor).extract("Remote, Faroe Islands");
    }

    @Test
    @DisplayName("The tier reported by the extractor is carried through")
    void testTierIsPropagated() {
        // A cache hit must be distinguishable from a fresh model call, since the
        // ratio is the signal that the curated table needs extending.
        when(extractor.extract(anyString())).thenReturn(
                ExtractionResult.of(List.of(new ExtractedLocation("Oslo, Norway",
                                LocationKind.PLACE, "Oslo, Norway", SpecifierType.CITY)))
                        .withTier(SourceTier.INTERPRETATION));

        assertEquals(SourceTier.INTERPRETATION, resolver.resolve("Oslo, Norway").getFirst().tier());
    }

    @Test
    @DisplayName("Policy is applied per location, so a mixed list resolves each correctly")
    void testMixedListResolvesEachIndependently() {
        extractorReturns(
                new ExtractedLocation("Remote, Canada", LocationKind.REMOTE_SPECIFIER,
                        "Canada", SpecifierType.COUNTRY),
                new ExtractedLocation("Remote, US", LocationKind.REMOTE_SPECIFIER,
                        "US", SpecifierType.COUNTRY));

        List<ResolvedLocation> resolved = resolver.resolve("Remote, Canada; Remote, US");

        assertEquals("Canada", resolved.get(0).input().geocodeQuery());
        assertEquals(ORIGIN, resolved.get(1).input().geocodeQuery());
    }

    // ------------------------------------------------------------- geocoding

    @Test
    @DisplayName("Each resolved location is geocoded")
    void testGeocodingHappens() {
        List<ResolvedLocation> resolved = resolver.resolve("Remote US");

        assertTrue(resolved.getFirst().isMatchable());
        assertEquals(47.7231d, resolved.getFirst().outcome().latitude());
        verify(geocoder).geocode(ORIGIN);
    }

    @Test
    @DisplayName("An UNDEFINED location is kept but never geocoded")
    void testUndefinedIsNotGeocoded() {
        extractorReturns(ExtractedLocation.of("???", LocationKind.UNPARSEABLE));

        List<ResolvedLocation> resolved = resolver.resolve("???");

        assertEquals(Resolution.UNDEFINED, resolved.getFirst().input().resolution());
        assertNull(resolved.getFirst().outcome());
        assertFalse(resolved.getFirst().isMatchable());
        // The row is kept so the job stays in the curation worklist rather than
        // vanishing, but there is nothing to look up.
        verifyNoInteractions(geocoder);
    }

    @Test
    @DisplayName("A query the geocoder cannot place is kept without coordinates")
    void testZeroResultsKeepsRowUnmatchable() {
        when(geocoder.geocode(anyString()))
                .thenReturn(GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS));
        extractorReturns(new ExtractedLocation("Atlantis", LocationKind.PLACE,
                "Atlantis", SpecifierType.CITY));

        ResolvedLocation only = resolver.resolve("Atlantis").getFirst();

        assertEquals(Resolution.PLACE, only.input().resolution());
        assertFalse(only.isMatchable());
    }

    // ------------------------------------------------------------------ cap

    @Test
    @DisplayName("Fan-out is capped, so a pathological label cannot fill the table")
    void testFanOutCapped() throws IOException {
        LocationResolver capped = newResolver(3);
        ExtractedLocation[] many = new ExtractedLocation[15];
        for (int i = 0; i < many.length; i++) {
            many[i] = new ExtractedLocation("State " + i, LocationKind.PLACE,
                    "Seattle, WA", SpecifierType.CITY);
        }
        extractorReturns(many);

        assertEquals(3, capped.resolve("fifteen states").size());
    }

    @Test
    @DisplayName("A cap below one is rejected")
    void testInvalidCapRejected() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> newResolver(0));
    }

    // ---------------------------------------------------------- propagation

    @Test
    @DisplayName("A model outage propagates so the caller can leave the job pending")
    void testExtractionFailurePropagates() {
        when(extractor.extract(anyString()))
                .thenThrow(new LocationExtractionException("Ollama unreachable"));

        assertThrows(LocationExtractionException.class, () -> resolver.resolve("Somewhere new"));
    }

    @Test
    @DisplayName("A geocoding failure propagates rather than being recorded as no-coordinates")
    void testGeocodingFailurePropagates() {
        when(geocoder.geocode(anyString()))
                .thenThrow(new GeocodingException("quota exhausted", false));

        assertThrows(GeocodingException.class, () -> resolver.resolve("Remote US"));
    }

    // ------------------------------------------------------------ empty label

    @Test
    @DisplayName("A null or blank label resolves to the origin rather than failing")
    void testBlankLabelResolvesToOrigin() {
        extractorReturns(ExtractedLocation.of("", LocationKind.SENTINEL));

        for (String label : new String[]{null, "", "   "}) {
            ResolvedLocation only = resolver.resolve(label).getFirst();
            assertEquals(Resolution.EMPTY_DEFAULT, only.input().resolution());
            assertEquals(ORIGIN, only.input().geocodeQuery());
        }
    }
}
