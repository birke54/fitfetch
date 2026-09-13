package org.example.fitfetch.location;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GoogleGeocoderTest {

    private static final String BASE_URL = "https://maps.example.test";
    private static final String API_KEY = "test-key-123";

    private MockRestServiceServer mockServer;
    private Geocoder geocoder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        geocoder = new GoogleGeocoder(builder.build(), API_KEY, BASE_URL);
    }

    private void respond(String body) {
        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/maps/api/geocode/json")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void respondStatus(String status) {
        respond("{\"status\":\"%s\",\"results\":[]}".formatted(status));
    }

    private static final String SEATTLE_OK = """
            {"status":"OK","results":[{
              "formatted_address":"3001 NE 130th St, Seattle, WA 98125, USA",
              "geometry":{"location":{"lat":47.7231,"lng":-122.2967},"location_type":"ROOFTOP"},
              "place_id":"ChIJtest","types":["street_address"]}]}
            """;

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("A resolved query returns coordinates and its place identifier")
    void testSuccessfulLookup() {
        respond(SEATTLE_OK);

        GeocodeOutcome outcome = geocoder.geocode("3001 NE 130th St, Seattle, WA 98125");

        assertEquals(GeocodeStatus.OK, outcome.status());
        assertEquals(47.7231d, outcome.latitude());
        assertEquals(-122.2967d, outcome.longitude());
        assertEquals("3001 NE 130th St, Seattle, WA 98125, USA", outcome.formattedAddress());
        assertEquals("ChIJtest", outcome.placeId());
        assertEquals("ROOFTOP", outcome.locationType());
        assertFalse(outcome.partialMatch());
        mockServer.verify();
    }

    @Test
    @DisplayName("Google's lng key maps onto longitude")
    void testLngMapsToLongitude() {
        // Google writes "lng"; getting this wrong would silently null the value.
        respond(SEATTLE_OK);
        assertNotNull(geocoder.geocode("anywhere").longitude());
    }

    @Test
    @DisplayName("The first result wins when several are returned")
    void testFirstResultWins() {
        respond("""
                {"status":"OK","results":[
                  {"formatted_address":"Dublin, Ireland",
                   "geometry":{"location":{"lat":53.35,"lng":-6.26},"location_type":"APPROXIMATE"},
                   "place_id":"ie"},
                  {"formatted_address":"Dublin, OH, USA",
                   "geometry":{"location":{"lat":40.09,"lng":-83.11},"location_type":"APPROXIMATE"},
                   "place_id":"us"}]}
                """);

        assertEquals("ie", geocoder.geocode("Dublin, Ireland").placeId());
    }

    @Test
    @DisplayName("A partial match is flagged rather than silently accepted")
    void testPartialMatchFlagged() {
        respond("""
                {"status":"OK","results":[{
                  "formatted_address":"Somewhere, Elsewhere",
                  "geometry":{"location":{"lat":1.0,"lng":2.0},"location_type":"APPROXIMATE"},
                  "place_id":"p","partial_match":true}]}
                """);

        assertTrue(geocoder.geocode("Vague Place").partialMatch());
    }

    // ------------------------------------------------------ cacheable failures

    @Test
    @DisplayName("ZERO_RESULTS is a real answer and carries no coordinates")
    void testZeroResults() {
        respondStatus("ZERO_RESULTS");

        GeocodeOutcome outcome = geocoder.geocode("Atlantis");

        assertEquals(GeocodeStatus.ZERO_RESULTS, outcome.status());
        assertNull(outcome.latitude());
        assertNull(outcome.longitude());
        assertFalse(outcome.status().hasCoordinates());
    }

    @Test
    @DisplayName("INVALID_REQUEST is cacheable, since a malformed query stays malformed")
    void testInvalidRequest() {
        respond("""
                {"status":"INVALID_REQUEST","results":[],"error_message":"Invalid request."}
                """);

        assertEquals(GeocodeStatus.INVALID_REQUEST, geocoder.geocode("???").status());
    }

    @Test
    @DisplayName("OK with no results degrades to ZERO_RESULTS rather than a coordinate-less OK")
    void testOkWithNoResults() {
        respondStatus("OK");

        assertEquals(GeocodeStatus.ZERO_RESULTS, geocoder.geocode("Nowhere").status());
    }

    @Test
    @DisplayName("OK with a result missing coordinates degrades too")
    void testOkWithoutCoordinates() {
        respond("""
                {"status":"OK","results":[{"formatted_address":"X","geometry":null,"place_id":"p"}]}
                """);

        assertEquals(GeocodeStatus.ZERO_RESULTS, geocoder.geocode("X").status());
    }

    // ---------------------------------------------------- transient failures

    @ParameterizedTest
    @ValueSource(strings = {"OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT", "UNKNOWN_ERROR"})
    @DisplayName("Transient statuses throw retryably and are never cacheable")
    void testTransientStatusesThrow(String status) {
        // Caching a quota blip as ZERO_RESULTS would turn a momentary failure
        // into a permanent "this place does not exist".
        respondStatus(status);

        GeocodingException error = assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA"));

        assertTrue(error.isRetryable());
        assertFalse(error.isFatal());
        assertTrue(error.getMessage().contains("Seattle, WA"));
    }

    @Test
    @DisplayName("REQUEST_DENIED is fatal, so the pass stops instead of burning budget")
    void testRequestDeniedIsFatal() {
        respond("""
                {"status":"REQUEST_DENIED","results":[],
                 "error_message":"The provided API key is invalid."}
                """);

        GeocodingException error = assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA"));

        assertTrue(error.isFatal());
        assertFalse(error.isRetryable());
    }

    @Test
    @DisplayName("An unrecognised status throws rather than being guessed at")
    void testUnknownStatusThrows() {
        respondStatus("SOMETHING_NEW");

        assertThrows(GeocodingException.class, () -> geocoder.geocode("Seattle, WA"));
    }

    @Test
    @DisplayName("A transport failure throws retryably")
    void testTransportFailure() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withServerError());

        GeocodingException error = assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA"));
        assertTrue(error.isRetryable());
    }

    @Test
    @DisplayName("A missing status throws rather than producing an empty outcome")
    void testMissingStatus() {
        respond("{\"results\":[]}");

        assertThrows(GeocodingException.class, () -> geocoder.geocode("Seattle, WA"));
    }

    // --------------------------------------------------------- request shape

    @Test
    @DisplayName("The address is URL-encoded, so commas and spaces survive intact")
    void testAddressIsEncoded() {
        mockServer.expect(requestToUriTemplate(
                        BASE_URL + "/maps/api/geocode/json?address={a}&key={k}",
                        "New York, NY, USA", API_KEY))
                .andRespond(withSuccess(SEATTLE_OK, MediaType.APPLICATION_JSON));

        geocoder.geocode("New York, NY, USA");
        mockServer.verify();
    }

    @Test
    @DisplayName("Non-ASCII place names survive encoding")
    void testNonAsciiEncoded() {
        mockServer.expect(requestToUriTemplate(
                        BASE_URL + "/maps/api/geocode/json?address={a}&key={k}",
                        "São Paulo, Brazil", API_KEY))
                .andRespond(withSuccess(SEATTLE_OK, MediaType.APPLICATION_JSON));

        // The accented original is sent, not a diacritic-folded lookup key --
        // the geocoder can use information the key deliberately discards.
        geocoder.geocode("São Paulo, Brazil");
        mockServer.verify();
    }

    // ------------------------------------------------------------- API key

    @Test
    @DisplayName("Failure messages never leak the API key")
    void testApiKeyNotLeakedInErrors() {
        // Exception messages reach logs; the request URI carries the key.
        respondStatus("OVER_QUERY_LIMIT");
        GeocodingException quota = assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA"));
        assertFalse(quota.getMessage().contains(API_KEY), "quota error leaked the API key");

        mockServer.reset();
        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withServerError());
        GeocodingException transport = assertThrows(GeocodingException.class,
                () -> geocoder.geocode("Seattle, WA"));
        assertFalse(transport.getMessage().contains(API_KEY), "transport error leaked the API key");
    }

    // ---------------------------------------------------------- construction

    @Test
    @DisplayName("Blank configuration and blank queries are rejected")
    void testValidation() {
        RestClient client = RestClient.builder().build();

        assertThrows(IllegalArgumentException.class, () -> new GoogleGeocoder(client, ""));
        assertThrows(IllegalArgumentException.class, () -> new GoogleGeocoder(client, null));
        assertThrows(IllegalArgumentException.class, () -> new GoogleGeocoder(client, "k", " "));
        assertThrows(NullPointerException.class, () -> new GoogleGeocoder(null, "k"));
        assertThrows(IllegalArgumentException.class, () -> geocoder.geocode(" "));
        assertThrows(IllegalArgumentException.class, () -> geocoder.geocode(null));
    }

    @Test
    @DisplayName("A trailing slash on the base URL does not double up in the path")
    void testTrailingSlashTolerated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Geocoder tolerant = new GoogleGeocoder(builder.build(), API_KEY, BASE_URL + "/");

        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/maps/api/geocode/json")))
                .andRespond(withSuccess(SEATTLE_OK, MediaType.APPLICATION_JSON));

        tolerant.geocode("Seattle, WA");
        server.verify();
    }

    // ------------------------------------------------------------ invariants

    @Test
    @DisplayName("An outcome cannot be built with coordinates that contradict its status")
    void testOutcomeInvariant() {
        // Mirrors ck_geocode_cache_coords_match_status, so a bad mapping fails
        // here rather than on insert.
        assertThrows(IllegalArgumentException.class,
                () -> new GeocodeOutcome(GeocodeStatus.OK, null, null, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GeocodeOutcome(GeocodeStatus.ZERO_RESULTS, 1.0, 2.0, null, null, null, false));
    }

    @Test
    @DisplayName("Transposed coordinates are rejected")
    void testTransposedCoordinatesRejected() {
        // Seattle is (47.72, -122.29); transposed, the latitude is out of range.
        assertThrows(IllegalArgumentException.class,
                () -> new GeocodeOutcome(GeocodeStatus.OK, -122.2967, 47.7231, null, null, null, false));
    }
}
