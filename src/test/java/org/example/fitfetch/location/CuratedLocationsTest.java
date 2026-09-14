package org.example.fitfetch.location;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CuratedLocationsTest {

    private static final String ORIGIN = "3001 NE 130th St, Seattle, WA 98125";
    private static CuratedLocations curated;

    @BeforeAll
    static void loadTable() throws IOException {
        curated = new CuratedLocations(new ClassPathResource("location_table.json"), ORIGIN);
    }

    @Test
    @DisplayName("The shipped table loads and covers the curated head of the distribution")
    void testTableLoads() {
        assertEquals(112, curated.size());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @CsvSource(delimiter = '|', textBlock = """
            New York, New York, USA | PLACE            | New York, NY, USA
            Amsterdam               | PLACE            | Amsterdam, Netherlands
            Bangalore               | PLACE            | Bengaluru, India
            Remote Canada           | REMOTE_ELSEWHERE | Canada
            Poland                  | COUNTRY_OTHER    | Poland
            California, United States | STATE_OTHER    | California, USA
            """)
    @DisplayName("Curated entries resolve to their hand-checked query")
    void testSingleOutputEntries(String raw, String resolution, String query) {
        List<LocationInput> inputs = curated.lookup(raw).orElseThrow();

        assertEquals(1, inputs.size());
        assertEquals(Resolution.valueOf(resolution), inputs.getFirst().resolution());
        assertEquals(query, inputs.getFirst().geocodeQuery());
        assertFalse(inputs.getFirst().followsOrigin());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> origin")
    @CsvSource(delimiter = '|', textBlock = """
            Remote US      | REMOTE_IN_US
            Remote - US    | REMOTE_IN_US
            US-Remote      | REMOTE_IN_US
            US Remote      | REMOTE_IN_US
            Remote, USA    | REMOTE_IN_US
            United States  | COUNTRY_US
            USA            | COUNTRY_US
            Remote         | REMOTE_BARE
            N/A            | EMPTY_DEFAULT
            LOCATION       | EMPTY_DEFAULT
            """)
    @DisplayName("Origin-redirected entries carry the configured origin, not the token")
    void testOriginSubstitution(String raw, String resolution) {
        LocationInput input = curated.lookup(raw).orElseThrow().getFirst();

        assertEquals(Resolution.valueOf(resolution), input.resolution());
        assertEquals(ORIGIN, input.geocodeQuery());
        assertFalse(input.isOriginToken(), "the origin token must be substituted at load time");
        assertTrue(input.followsOrigin(), "substituting the token marks the input as following the origin");
    }

    @Test
    @DisplayName("No entry leaks the unsubstituted origin token")
    void testNoTokenLeaks() {
        // A leaked token would be sent to the geocoder verbatim.
        List<String> leaked = List.of("Remote US", "United States", "N/A", "LOCATION",
                        "Boston or Remote", "Remote; State College, PA", "US / Canada").stream()
                .flatMap(raw -> curated.lookup(raw).orElseThrow().stream())
                .filter(LocationInput::isOriginToken)
                .map(LocationInput::raw)
                .toList();

        assertTrue(leaked.isEmpty(), "unsubstituted origin token in: " + leaked);
    }

    @Test
    @DisplayName("Multi-location entries keep every location")
    void testMultiOutputEntry() {
        // Dropping either half would filter out a job with an office near the user.
        List<LocationInput> inputs = curated.lookup("Boston, Massachusetts, USA; New York, New York, USA")
                .orElseThrow();

        assertEquals(2, inputs.size());
        assertEquals(Set.of("Boston, MA, USA", "New York, NY, USA"),
                inputs.stream().map(LocationInput::geocodeQuery).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("An or-list mixing a place and a remote marker yields both")
    void testOrListEntry() {
        List<LocationInput> inputs = curated.lookup("State College, PA or Remote").orElseThrow();

        assertEquals(2, inputs.size());
        Map<Resolution, String> byResolution = inputs.stream()
                .collect(Collectors.toMap(LocationInput::resolution, LocationInput::geocodeQuery));
        assertEquals("State College, PA, USA", byResolution.get(Resolution.PLACE));
        assertEquals(ORIGIN, byResolution.get(Resolution.REMOTE_BARE));
    }

    @Test
    @DisplayName("A slash-separated list is split, and the US half redirects to the origin")
    void testSlashList() {
        List<LocationInput> inputs = curated.lookup("US / Canada").orElseThrow();

        assertEquals(2, inputs.size());
        Map<Resolution, String> byResolution = inputs.stream()
                .collect(Collectors.toMap(LocationInput::resolution, LocationInput::geocodeQuery));
        assertEquals(ORIGIN, byResolution.get(Resolution.COUNTRY_US));
        assertEquals("Canada", byResolution.get(Resolution.COUNTRY_OTHER));
    }

    @Test
    @DisplayName("Lookup is normalized, so case and padding variants hit the same entry")
    void testLookupIsNormalized() {
        assertEquals(curated.lookup("Remote US"), curated.lookup("  REMOTE   us  "));
        assertTrue(curated.lookup("amsterdam").isPresent());
    }

    @Test
    @DisplayName("Curation resolves ambiguous city names that a model would guess at")
    void testDisambiguatedCities() {
        assertEquals("Dublin, Ireland", curated.lookup("Dublin").orElseThrow().getFirst().geocodeQuery());
        assertEquals("London, United Kingdom", curated.lookup("London").orElseThrow().getFirst().geocodeQuery());
        assertEquals("Amsterdam, Netherlands", curated.lookup("Amsterdam").orElseThrow().getFirst().geocodeQuery());
        assertEquals("Paris, France", curated.lookup("Paris").orElseThrow().getFirst().geocodeQuery());
    }

    @Test
    @DisplayName("Spelling variants of one place collapse onto a single geocode query")
    void testSpellingVariantsCollapse() {
        // Five labels, one query -- so one geocode call rather than five.
        Set<String> queries = List.of("New York, New York, USA", "New York, NY", "New York", "NYC", "NYC-Privy")
                .stream()
                .map(raw -> curated.lookup(raw).orElseThrow().getFirst().geocodeQuery())
                .collect(Collectors.toSet());

        assertEquals(Set.of("New York, NY, USA"), queries);
    }

    @Test
    @DisplayName("The whole table collapses to far fewer distinct geocode queries than entries")
    void testQueryCardinality() {
        Set<String> queries = List.of(
                        "New York, New York, USA", "New York, NY", "New York", "NYC",
                        "Bangalore", "Bangalore, India", "Bengaluru",
                        "London", "London, UK", "London, United Kingdom")
                .stream()
                .map(raw -> curated.lookup(raw).orElseThrow().getFirst().geocodeQuery())
                .collect(Collectors.toSet());

        assertEquals(3, queries.size(), "ten labels should reduce to three queries");
    }

    @Test
    @DisplayName("An uncurated label falls through rather than guessing")
    void testMissFallsThrough() {
        assertTrue(curated.lookup("Ouagadougou, Burkina Faso").isEmpty());
        assertTrue(curated.lookup("").isEmpty());
        assertTrue(curated.lookup(null).isEmpty());
    }

    @Test
    @DisplayName("Colliding entries fail loudly instead of shadowing each other")
    void testCollisionDetected() {
        // "Remote US" and "remote  us " normalize identically, so one would be
        // unreachable. Loading must fail rather than silently drop it.
        String json = """
                { "version": 1, "entries": [
                  { "raw": "Remote US",   "outputs": [
                      { "raw": "Remote US", "resolution": "REMOTE_BARE", "geocodeQuery": "__ORIGIN__" } ] },
                  { "raw": "remote  us ", "outputs": [
                      { "raw": "remote  us ", "resolution": "REMOTE_BARE", "geocodeQuery": "__ORIGIN__" } ] }
                ] }
                """;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CuratedLocations(resource(json), ORIGIN));
        assertTrue(error.getMessage().contains("normalize to"), error.getMessage());
    }

    @Test
    @DisplayName("An unknown resolution name fails loudly at load")
    void testUnknownResolutionRejected() {
        String json = """
                { "version": 1, "entries": [
                  { "raw": "Somewhere", "outputs": [
                      { "raw": "Somewhere", "resolution": "TELEPORT", "geocodeQuery": "Somewhere" } ] }
                ] }
                """;

        assertThrows(IllegalStateException.class, () -> new CuratedLocations(resource(json), ORIGIN));
    }

    @Test
    @DisplayName("A malformed or missing table fails at construction, not first use")
    void testMalformedTable() {
        assertThrows(IllegalStateException.class,
                () -> new CuratedLocations(resource("{ \"version\": 1 }"), ORIGIN));
        assertThrows(FileNotFoundException.class,
                () -> new CuratedLocations(new ClassPathResource("no-such-table.json"), ORIGIN));
    }

    private static Resource resource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test_table.json";
            }
        };
    }
}
