package org.example.fitfetch.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class LocationPolicyTest {

    private static final String ORIGIN = "3001 NE 130th St, Seattle, WA 98125";
    private static final LocationPolicy POLICY = new LocationPolicy(ORIGIN, "WA");

    private static LocationInput resolve(LocationKind kind, String specifier, SpecifierType type) {
        return POLICY.apply(new ExtractedLocation("raw", kind, specifier, type));
    }

    // ---------------------------------------------------------------- rule 1

    @Test
    @DisplayName("Rule 1: a city geocodes as itself")
    void testGenuineCity() {
        LocationInput result = resolve(LocationKind.PLACE, "Bangalore, India", SpecifierType.CITY);

        assertEquals(Resolution.PLACE, result.resolution());
        assertEquals("Bangalore, India", result.geocodeQuery());
    }

    // ---------------------------------------------------------------- rule 2

    @Test
    @DisplayName("Rule 2: bare Remote resolves to the origin so it is always in range")
    void testBareRemote() {
        LocationInput result = POLICY.apply(ExtractedLocation.of("Remote", LocationKind.REMOTE_BARE));

        assertEquals(Resolution.REMOTE_BARE, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
    }

    // ------------------------------------------------------------ rules 3/3b

    @ParameterizedTest
    @ValueSource(strings = {"US", "USA", "United States", "America"})
    @DisplayName("Rule 3: remote within the US redirects to the origin")
    void testRemoteInUs(String specifier) {
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, specifier, SpecifierType.COUNTRY);

        assertEquals(Resolution.REMOTE_IN_US, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
        assertEquals("US", result.regionCode());
    }

    @Test
    @DisplayName("Rule 3: remote in a non-US country keeps that country, which falls out of radius")
    void testRemoteElsewhereCountry() {
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, "Canada", SpecifierType.COUNTRY);

        assertEquals(Resolution.REMOTE_ELSEWHERE, result.resolution());
        assertEquals("Canada", result.geocodeQuery());
        assertEquals("CA", result.regionCode());
    }

    @Test
    @DisplayName("Rule 3: remote qualified by a city keeps the city")
    void testRemoteElsewhereCity() {
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, "Bellevue", SpecifierType.CITY);

        assertEquals(Resolution.REMOTE_ELSEWHERE, result.resolution());
        assertEquals("Bellevue", result.geocodeQuery());
    }

    @Test
    @DisplayName("Rule 3b: a macro-region resolves to its centroid, not the origin")
    void testRemoteMacroRegion() {
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, "EMEA", SpecifierType.MACRO_REGION);

        assertEquals(Resolution.REMOTE_REGION, result.resolution());
        assertEquals("EMEA", result.geocodeQuery());
    }

    // ---------------------------------------------------------------- rule 4

    @Test
    @DisplayName("Rule 4: a sentinel resolves to the origin")
    void testSentinel() {
        LocationInput result = POLICY.apply(ExtractedLocation.of("N/A", LocationKind.SENTINEL));

        assertEquals(Resolution.EMPTY_DEFAULT, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
    }

    // ---------------------------------------------------------------- rule 5

    @Test
    @DisplayName("Rule 5: unparseable text never matches")
    void testUnparseable() {
        LocationInput result = POLICY.apply(ExtractedLocation.of("???", LocationKind.UNPARSEABLE));

        assertEquals(Resolution.UNDEFINED, result.resolution());
        assertNull(result.geocodeQuery());
        assertFalse(result.resolution().hasCoordinates());
    }

    @Test
    @DisplayName("Rule 5: a hallucinated country is rejected before it costs a geocode call")
    void testHallucinatedCountryRejected() {
        LocationInput result = resolve(LocationKind.PLACE, "Atlantis", SpecifierType.COUNTRY);

        assertEquals(Resolution.UNDEFINED, result.resolution());
        assertNull(result.geocodeQuery());
    }

    @Test
    @DisplayName("Rule 5: a hallucinated state is rejected too")
    void testHallucinatedStateRejected() {
        assertEquals(Resolution.UNDEFINED,
                resolve(LocationKind.REMOTE_SPECIFIER, "Westeros", SpecifierType.STATE).resolution());
    }

    // ---------------------------------------------------------------- rule 6

    @ParameterizedTest
    @ValueSource(strings = {"United States", "US", "USA"})
    @DisplayName("Rule 6: a bare US label with no remote marker still resolves to the origin")
    void testBareCountryUs(String specifier) {
        LocationInput result = resolve(LocationKind.PLACE, specifier, SpecifierType.COUNTRY);

        assertEquals(Resolution.COUNTRY_US, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
        assertEquals("US", result.regionCode());
    }

    @Test
    @DisplayName("Rule 6: a bare non-US country keeps its own centroid")
    void testBareCountryOther() {
        LocationInput result = resolve(LocationKind.PLACE, "Poland", SpecifierType.COUNTRY);

        assertEquals(Resolution.COUNTRY_OTHER, result.resolution());
        assertEquals("Poland", result.geocodeQuery());
        assertEquals("PL", result.regionCode());
    }

    // --------------------------------------------------------------- rule 6b

    @Test
    @DisplayName("Rule 6b: a bare home-state label resolves to the origin")
    void testBareHomeState() {
        LocationInput result = resolve(LocationKind.PLACE, "Washington", SpecifierType.STATE);

        assertEquals(Resolution.PLACE, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
        assertEquals("US-WA", result.regionCode());
    }

    @Test
    @DisplayName("Rule 6b: a bare non-home state keeps its centroid")
    void testBareOtherState() {
        LocationInput result = resolve(LocationKind.PLACE, "California", SpecifierType.STATE);

        assertEquals(Resolution.STATE_OTHER, result.resolution());
        assertEquals("California", result.geocodeQuery());
        assertEquals("US-CA", result.regionCode());
    }

    // ---------------------------------------------------------------- rule 7

    @Test
    @DisplayName("Rule 7: remote in the home state lands at the origin, not the state centroid")
    void testRemoteInHomeState() {
        // Washington's centroid is near Ellensburg, roughly 100 miles from
        // Seattle -- far enough to fall outside a 50-mile radius and drop a job
        // the user is genuinely eligible for.
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, "Washington", SpecifierType.STATE);

        assertEquals(Resolution.REMOTE_IN_US, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
        assertEquals("US-WA", result.regionCode());
    }

    @Test
    @DisplayName("Rule 7: remote in another state stays at that state's centroid")
    void testRemoteInOtherState() {
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, "Connecticut", SpecifierType.STATE);

        assertEquals(Resolution.REMOTE_ELSEWHERE, result.resolution());
        assertEquals("Connecticut", result.geocodeQuery());
        assertEquals("US-CT", result.regionCode());
    }

    @Test
    @DisplayName("Rules 6b and 7 are inert when no home state is configured")
    void testNoHomeStateConfigured() {
        LocationPolicy noHome = new LocationPolicy(ORIGIN, null);

        assertEquals(Resolution.REMOTE_ELSEWHERE,
                noHome.apply(new ExtractedLocation("raw", LocationKind.REMOTE_SPECIFIER,
                        "Washington", SpecifierType.STATE)).resolution());
        assertEquals(Resolution.STATE_OTHER,
                noHome.apply(new ExtractedLocation("raw", LocationKind.PLACE,
                        "Washington", SpecifierType.STATE)).resolution());
    }

    // ------------------------------------------------------------ degradation

    @Test
    @DisplayName("A remote marker with an unusable qualifier degrades to bare remote, not undefined")
    void testRemoteSpecifierWithoutSpecifierDegrades() {
        LocationInput result = resolve(LocationKind.REMOTE_SPECIFIER, null, null);

        assertEquals(Resolution.REMOTE_BARE, result.resolution());
        assertEquals(ORIGIN, result.geocodeQuery());
    }

    @Test
    @DisplayName("A place with no specifier is undefined rather than guessed at")
    void testPlaceWithoutSpecifier() {
        assertEquals(Resolution.UNDEFINED, resolve(LocationKind.PLACE, "  ", SpecifierType.CITY).resolution());
        assertEquals(Resolution.UNDEFINED, resolve(LocationKind.PLACE, "Dublin", null).resolution());
    }

    // ------------------------------------------------------------- invariants

    @Test
    @DisplayName("Every kind and specifier type combination produces a usable input")
    void testEveryCombinationIsTotal() {
        for (LocationKind kind : LocationKind.values()) {
            for (SpecifierType type : SpecifierType.values()) {
                LocationInput result = POLICY.apply(
                        new ExtractedLocation("raw", kind, "United States", type));

                assertNotNull(result, kind + "/" + type + " returned null");
                // The coordinate invariant the database also enforces.
                assertEquals(result.resolution().hasCoordinates(), result.geocodeQuery() != null,
                        kind + "/" + type + " violated the coordinate invariant");
            }
        }
    }

    @Test
    @DisplayName("A blank origin is rejected at construction")
    void testBlankOriginRejected() {
        assertThrows(NullPointerException.class, () -> new LocationPolicy(null, "WA"));
        assertThrows(IllegalArgumentException.class, () -> new LocationPolicy("  ", "WA"));
    }
}
