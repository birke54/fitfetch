package org.example.fitfetch.location;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OllamaLocationExtractorTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String GENERATE = BASE_URL + "/api/generate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private OllamaLocationExtractor extractor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        extractor = new OllamaLocationExtractor(builder.build(), BASE_URL, "llama3.1:8b");
    }

    /** Wraps a payload the way Ollama does: the answer is a JSON string field. */
    private static String ollamaBody(String payloadJson) {
        return """
                {"model":"llama3.1:8b","created_at":"2026-09-12T00:00:00Z",
                 "response":%s,"done":true,"done_reason":"stop"}
                """.formatted(MAPPER.writeValueAsString(payloadJson));
    }

    private void respondWith(String payloadJson) {
        mockServer.expect(requestTo(GENERATE))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(ollamaBody(payloadJson), MediaType.APPLICATION_JSON));
    }

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("A single location is extracted and reported as cacheable")
    void testSingleLocation() {
        respondWith("""
                {"analysis":"One city.","locations":[
                  {"raw":"Bangalore, India","kind":"PLACE",
                   "specifier":"Bangalore, India","specifier_type":"CITY"}]}
                """);

        ExtractionResult result = extractor.extract("Bangalore, India");

        assertTrue(result.cacheable());
        assertEquals(1, result.locations().size());
        ExtractedLocation only = result.locations().getFirst();
        assertEquals(LocationKind.PLACE, only.kind());
        assertEquals("Bangalore, India", only.specifier());
        assertEquals(SpecifierType.CITY, only.specifierType());
        mockServer.verify();
    }

    @Test
    @DisplayName("A multi-location label yields one entry per location")
    void testMultipleLocations() {
        respondWith("""
                {"analysis":"Semicolon splits two.","locations":[
                  {"raw":"Remote, Canada","kind":"REMOTE_SPECIFIER",
                   "specifier":"Canada","specifier_type":"COUNTRY"},
                  {"raw":"Remote, US","kind":"REMOTE_SPECIFIER",
                   "specifier":"US","specifier_type":"COUNTRY"}]}
                """);

        ExtractionResult result = extractor.extract("Remote, Canada; Remote, US");

        assertEquals(2, result.locations().size());
        assertEquals(List.of("Canada", "US"),
                result.locations().stream().map(ExtractedLocation::specifier).toList());
        mockServer.verify();
    }

    @Test
    @DisplayName("A null specifier and type survive as nulls, not empty strings")
    void testBareRemote() {
        respondWith("""
                {"analysis":"No place given.","locations":[
                  {"raw":"Remote","kind":"REMOTE_BARE","specifier":null,"specifier_type":null}]}
                """);

        ExtractedLocation only = extractor.extract("Remote").locations().getFirst();

        assertEquals(LocationKind.REMOTE_BARE, only.kind());
        assertNull(only.specifier());
        assertNull(only.specifierType());
        assertFalse(only.hasSpecifier());
    }

    @Test
    @DisplayName("Blank specifier strings are normalized to null")
    void testBlankSpecifierBecomesNull() {
        respondWith("""
                {"analysis":"Placeholder.","locations":[
                  {"raw":"N/A","kind":"SENTINEL","specifier":"   ","specifier_type":""}]}
                """);

        ExtractedLocation only = extractor.extract("N/A").locations().getFirst();

        assertEquals(LocationKind.SENTINEL, only.kind());
        assertNull(only.specifier());
        assertNull(only.specifierType());
    }

    // ------------------------------------------------------------ the request

    @Test
    @DisplayName("The request pins the model, disables streaming and sends the schema")
    void testRequestShape() {
        mockServer.expect(requestTo(GENERATE))
                .andExpect(jsonPath("$.model").value("llama3.1:8b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.format.type").value("object"))
                .andExpect(jsonPath("$.format.required[0]").value("analysis"))
                .andExpect(jsonPath("$.options.temperature").value(0.0))
                .andExpect(jsonPath("$.options.top_k").value(1))
                .andExpect(jsonPath("$.options.seed").value(42))
                .andRespond(withSuccess(ollamaBody("""
                        {"analysis":"x","locations":[
                          {"raw":"Remote","kind":"REMOTE_BARE","specifier":null,"specifier_type":null}]}
                        """), MediaType.APPLICATION_JSON));

        extractor.extract("Remote");
        mockServer.verify();
    }

    @Test
    @DisplayName("The label under extraction reaches the prompt")
    void testPromptCarriesLabel() {
        mockServer.expect(requestTo(GENERATE))
                .andExpect(jsonPath("$.prompt").value(org.hamcrest.Matchers.containsString("Remote, Faroe Islands")))
                .andRespond(withSuccess(ollamaBody("""
                        {"analysis":"x","locations":[
                          {"raw":"Remote, Faroe Islands","kind":"REMOTE_SPECIFIER",
                           "specifier":"Faroe Islands","specifier_type":"COUNTRY"}]}
                        """), MediaType.APPLICATION_JSON));

        extractor.extract("Remote, Faroe Islands");
        mockServer.verify();
    }

    @Test
    @DisplayName("analysis is declared before locations, so the model reasons before answering")
    void testSchemaOrdersAnalysisFirst() {
        // Generation is autoregressive: a scratchpad field only helps if it is
        // written first. Field order in the schema is load-bearing, not cosmetic.
        JsonNode required = OllamaPrompt.schema().get("required");
        assertEquals("analysis", required.get(0).asString());
        assertEquals("locations", required.get(1).asString());
    }

    // ------------------------------------------------------- unusable output

    @Test
    @DisplayName("Unparseable output is retried once, then returned uncached")
    void testRetryThenUntrusted() {
        mockServer.expect(twice(), requestTo(GENERATE))
                .andRespond(withSuccess(ollamaBody("not json at all"), MediaType.APPLICATION_JSON));

        ExtractionResult result = extractor.extract("Somewhere odd");

        assertFalse(result.cacheable(), "a model having a bad moment must not be cached");
        assertEquals(LocationKind.UNPARSEABLE, result.locations().getFirst().kind());
        mockServer.verify();
    }

    @Test
    @DisplayName("A retry that succeeds is used, and is cacheable")
    void testRetrySucceeds() {
        mockServer.expect(requestTo(GENERATE))
                .andRespond(withSuccess(ollamaBody("{ truncated"), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(GENERATE))
                .andRespond(withSuccess(ollamaBody("""
                        {"analysis":"ok","locations":[
                          {"raw":"Lisbon, Portugal","kind":"PLACE",
                           "specifier":"Lisbon, Portugal","specifier_type":"CITY"}]}
                        """), MediaType.APPLICATION_JSON));

        ExtractionResult result = extractor.extract("Lisbon, Portugal");

        assertTrue(result.cacheable());
        assertEquals("Lisbon, Portugal", result.locations().getFirst().specifier());
        mockServer.verify();
    }

    @Test
    @DisplayName("Truncated generation is rejected even though its JSON parses")
    void testTruncatedAnswerRejected() {
        // done_reason "length" means entries may have been lost off the end,
        // which is worse than failing outright because it looks like success.
        String truncated = """
                {"model":"llama3.1:8b","response":"{\\"analysis\\":\\"x\\",\\"locations\\":[]}",
                 "done":true,"done_reason":"length"}
                """;
        mockServer.expect(twice(), requestTo(GENERATE))
                .andRespond(withSuccess(truncated, MediaType.APPLICATION_JSON));

        ExtractionResult result = extractor.extract("A very long label");

        assertFalse(result.cacheable());
        mockServer.verify();
    }

    @Test
    @DisplayName("An empty location list is a real answer, so it is cached")
    void testEmptyLocationsIsCacheable() {
        respondWith("""
                {"analysis":"Nothing here.","locations":[]}
                """);

        ExtractionResult result = extractor.extract("!!!");

        assertTrue(result.cacheable(), "a well-formed 'nothing found' is an answer about the input");
        assertEquals(LocationKind.UNPARSEABLE, result.locations().getFirst().kind());
        assertEquals("!!!", result.locations().getFirst().raw());
    }

    @Test
    @DisplayName("An unknown kind degrades that entry instead of losing the response")
    void testUnknownKindDegrades() {
        respondWith("""
                {"analysis":"x","locations":[
                  {"raw":"Mars Base","kind":"ORBITAL","specifier":"Mars","specifier_type":"PLANET"}]}
                """);

        ExtractedLocation only = extractor.extract("Mars Base").locations().getFirst();

        assertEquals(LocationKind.UNPARSEABLE, only.kind());
        assertNull(only.specifierType(), "an unknown specifier type degrades to null");
    }

    @Test
    @DisplayName("A missing raw falls back to the label being extracted")
    void testMissingRawFallsBack() {
        respondWith("""
                {"analysis":"x","locations":[
                  {"raw":null,"kind":"PLACE","specifier":"Milan, Italy","specifier_type":"CITY"}]}
                """);

        assertEquals("Milan, Italy", extractor.extract("Milan, Italy").locations().getFirst().raw());
    }

    // --------------------------------------------------- transport failures

    @Test
    @DisplayName("A server error throws rather than recording a result")
    void testServerErrorThrows() {
        mockServer.expect(requestTo(GENERATE)).andRespond(withServerError());

        // Recording UNDEFINED here would bake an outage into durable data.
        LocationExtractionException error = assertThrows(LocationExtractionException.class,
                () -> extractor.extract("Remote, Canada"));
        assertTrue(error.getMessage().contains("Remote, Canada"));
    }

    @Test
    @DisplayName("Ollama not running throws rather than recording a result")
    void testConnectionFailureThrows() {
        mockServer.expect(requestTo(GENERATE))
                .andRespond(request -> {
                    throw new java.net.ConnectException("Connection refused");
                });

        assertThrows(LocationExtractionException.class, () -> extractor.extract("Remote"));
    }

    @Test
    @DisplayName("An empty body is retried, then returned uncached")
    void testEmptyBody() {
        mockServer.expect(twice(), requestTo(GENERATE))
                .andRespond(withSuccess("""
                        {"model":"llama3.1:8b","response":"","done":true,"done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));

        assertFalse(extractor.extract("Remote").cacheable());
        mockServer.verify();
    }

    // ---------------------------------------------------------- construction

    @Test
    @DisplayName("A trailing slash on the base URL does not double up in the path")
    void testTrailingSlashTolerated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LocationExtractor tolerant = new OllamaLocationExtractor(builder.build(), BASE_URL + "/", "llama3.1:8b");

        server.expect(requestTo(GENERATE)).andRespond(withSuccess(ollamaBody("""
                {"analysis":"x","locations":[
                  {"raw":"Remote","kind":"REMOTE_BARE","specifier":null,"specifier_type":null}]}
                """), MediaType.APPLICATION_JSON));

        tolerant.extract("Remote");
        server.verify();
    }

    @Test
    @DisplayName("Blank configuration is rejected at construction")
    void testBlankConfigRejected() {
        RestClient client = RestClient.builder().build();

        assertThrows(IllegalArgumentException.class, () -> new OllamaLocationExtractor(client, "", "m"));
        assertThrows(IllegalArgumentException.class, () -> new OllamaLocationExtractor(client, BASE_URL, " "));
        assertThrows(NullPointerException.class, () -> new OllamaLocationExtractor(null, BASE_URL, "m"));
    }

    // ------------------------------------------------ end-to-end with policy

    @Test
    @DisplayName("Extraction feeds the policy switch without adaptation")
    void testFeedsPolicyDirectly() {
        respondWith("""
                {"analysis":"Two remote countries.","locations":[
                  {"raw":"Remote, Canada","kind":"REMOTE_SPECIFIER",
                   "specifier":"Canada","specifier_type":"COUNTRY"},
                  {"raw":"Remote, United States","kind":"REMOTE_SPECIFIER",
                   "specifier":"United States","specifier_type":"COUNTRY"}]}
                """);
        LocationPolicy policy = new LocationPolicy("3001 NE 130th St, Seattle, WA 98125", "WA");

        List<LocationInput> resolved = extractor.extract("Remote, Canada; Remote, United States")
                .locations().stream().map(policy::apply).toList();

        assertEquals(Resolution.REMOTE_ELSEWHERE, resolved.get(0).resolution());
        assertEquals("Canada", resolved.get(0).geocodeQuery());
        assertEquals(Resolution.REMOTE_IN_US, resolved.get(1).resolution());
        assertEquals("3001 NE 130th St, Seattle, WA 98125", resolved.get(1).geocodeQuery());
    }
}
