package org.example.fitfetch.normalize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OllamaSignalExtractorTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String GENERATE = BASE_URL + "/api/generate";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONTEXT = 8192;

    private static final String TITLE = "Senior Backend Engineer";
    private static final String DESCRIPTION = """
            Design and operate high-throughput services.
            5+ years of Java experience.
            Kubernetes experience is a plus.""";

    private MockRestServiceServer mockServer;
    private OllamaSignalExtractor extractor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        extractor = new OllamaSignalExtractor(builder.build(), BASE_URL + "/", "qwen2.5:14b", CONTEXT);
    }

    /** Wraps a payload the way Ollama does: the answer is a JSON string field. */
    private static String ollamaBody(String payloadJson, String doneReason, int promptTokens) {
        return """
                {"model":"qwen2.5:14b","created_at":"2026-09-13T00:00:00Z",
                 "response":%s,"done":true,"done_reason":"%s","prompt_eval_count":%d}
                """.formatted(MAPPER.writeValueAsString(payloadJson), doneReason, promptTokens);
    }

    private void respondWith(String payloadJson) {
        respondWith(once(), ollamaBody(payloadJson, "stop", 900));
    }

    private void respondWith(org.springframework.test.web.client.ExpectedCount count, String body) {
        mockServer.expect(count, requestTo(GENERATE))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static final String GOOD_ANSWER = """
            {"seniority":"senior","track":"ic","employment_type":"full_time","min_years_experience":5,
             "required_degree":"bachelors","clearance_required":false,"sponsorship":"no",
             "required_certifications":["AWS Certified Solutions Architect"],
             "travel_required":true,"on_call":true,"domains":["payments"],
             "signals":[
              {"classification":"core responsibilities","text":"Design and operate high-throughput services.",
               "skills":[],"min_years":0},
              {"classification":"required qualifications","text":"Has 5+ years of Java experience.",
               "skills":["Java"],"min_years":5},
              {"classification":"preferred/nice-to-have skills","text":"Has Kubernetes experience.",
               "skills":["Kubernetes"],"min_years":0}]}
            """;

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("A well-formed answer is parsed into its job-level fields and classified signals")
    void testExtractsEveryField() {
        respondWith(GOOD_ANSWER);

        NormalizedData data = extractor.extract(TITLE, DESCRIPTION).orElseThrow();

        assertEquals(Seniority.SENIOR, data.seniority());
        assertEquals(Track.IC, data.track());
        assertEquals(EmploymentType.FULL_TIME, data.employmentType());
        assertEquals(5, data.minYearsExperience());
        assertEquals(new HardRequirements(Degree.BACHELORS, false, Sponsorship.NO,
                List.of("AWS Certified Solutions Architect"), true, true), data.requirements());
        assertEquals(List.of("payments"), data.domains());
        assertEquals(List.of(
                new Signal(SignalClassification.CORE_RESPONSIBILITY, "Design and operate high-throughput services."),
                new Signal(SignalClassification.REQUIRED_QUALIFICATION, "Has 5+ years of Java experience.",
                        List.of("Java"), 5),
                new Signal(SignalClassification.PREFERRED_SKILL, "Has Kubernetes experience.",
                        List.of("Kubernetes"), 0)), data.signals());
        mockServer.verify();
    }

    @Test
    @DisplayName("Job-level fields the model left out or misspelled fall back to the reading that excludes no job")
    void testJobLevelFallbacks() {
        // The signals are still good, so an odd job-level field must not cost the answer.
        respondWith("""
                {"seniority":"senior","track":"wizard","employment_type":"gig","required_degree":"doctorate",
                 "sponsorship":"maybe","signals":[{"classification":"required skills","text":"Knows SQL."}]}
                """);

        NormalizedData data = extractor.extract(TITLE, DESCRIPTION).orElseThrow();

        assertEquals(Track.IC, data.track());
        assertEquals(EmploymentType.UNSTATED, data.employmentType());
        assertEquals(0, data.minYearsExperience());
        assertEquals(HardRequirements.NONE, data.requirements());
        assertEquals(List.of(), data.domains());
        assertEquals(List.of(), data.signals().getFirst().skills());
        assertEquals(0, data.signals().getFirst().minYears());
    }

    @Test
    @DisplayName("Skills and lists are stripped, blanks and repeats dropped, domains lower-cased, and negative years made 0")
    void testListsCleaned() {
        respondWith("""
                {"seniority":"senior","min_years_experience":-1,
                 "domains":[" Payments ","payments","","Ad Tech"],
                 "required_certifications":["  CKA ", "cka"],
                 "signals":[{"classification":"required skills","text":"Knows Kafka and Spark.",
                   "skills":[" Kafka","kafka","Spark ",""],"min_years":-3}]}
                """);

        NormalizedData data = extractor.extract(TITLE, DESCRIPTION).orElseThrow();

        assertEquals(0, data.minYearsExperience());
        assertEquals(List.of("payments", "ad tech"), data.domains());
        assertEquals(List.of("CKA"), data.requirements().requiredCertifications());
        assertEquals(List.of("Kafka", "Spark"), data.signals().getFirst().skills());
        assertEquals(0, data.signals().getFirst().minYears());
    }

    @Test
    @DisplayName("The request carries the instructions, the title, the description, the schema and pinned options")
    void testRequestShape() {
        mockServer.expect(requestTo(GENERATE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"num_ctx\":" + CONTEXT)))
                .andExpect(request -> {
                    JsonNode body = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("qwen2.5:14b", body.path("model").asString());
                    assertFalse(body.path("stream").asBoolean());
                    assertEquals(SignalPrompt.SYSTEM, body.path("system").asString());
                    // The title carries the level, and descriptions rarely repeat it.
                    assertTrue(body.path("prompt").asString().contains("JOB TITLE: " + TITLE));
                    assertTrue(body.path("prompt").asString().endsWith(DESCRIPTION));
                    assertEquals(SignalPrompt.schema(), body.path("format"));
                    assertEquals(0.0d, body.path("options").path("temperature").asDouble());
                })
                .andRespond(withSuccess(ollamaBody(GOOD_ANSWER, "stop", 900), MediaType.APPLICATION_JSON));

        extractor.extract(TITLE, DESCRIPTION);

        mockServer.verify();
    }

    @Test
    @DisplayName("A job with no title says so rather than sending an empty line")
    void testMissingTitle() {
        assertTrue(SignalPrompt.forJob(null, DESCRIPTION).contains("JOB TITLE: (none given)"));
        assertTrue(SignalPrompt.forJob("  ", DESCRIPTION).contains("JOB TITLE: (none given)"));
    }

    @Test
    @DisplayName("The schema offers the model exactly the prompt's values for every fixed-choice field")
    void testSchemaMatchesEnums() {
        JsonNode properties = SignalPrompt.schema().path("properties");

        assertEquals(List.of("junior", "midlevel", "senior", "staff", "principal", "distinguished"),
                texts(properties.path("seniority").path("enum")));
        assertEquals(List.of("ic", "manager"), texts(properties.path("track").path("enum")));
        assertEquals(List.of("full_time", "part_time", "contract", "internship", "temporary", "unstated"),
                texts(properties.path("employment_type").path("enum")));
        assertEquals(List.of("none", "bachelors", "masters", "phd"),
                texts(properties.path("required_degree").path("enum")));
        assertEquals(List.of("yes", "no", "unstated"), texts(properties.path("sponsorship").path("enum")));
        assertEquals(List.of("core responsibilities", "required skills", "preferred/nice-to-have skills",
                        "required qualifications", "preferred/nice-to-have qualifications"),
                texts(properties.path("signals").path("items").path("properties").path("classification")
                        .path("enum")));
    }

    @Test
    @DisplayName("Every schema property is required and none is nullable, so the answer always has each field")
    void testSchemaRequiresEveryField() {
        JsonNode schema = SignalPrompt.schema();
        JsonNode item = schema.path("properties").path("signals").path("items");

        assertEquals(names(schema.path("properties")), texts(schema.path("required")));
        assertEquals(names(item.path("properties")), texts(item.path("required")));
        assertEquals(List.of("classification", "text", "skills", "min_years"), names(item.path("properties")));
        assertFalse(schema.toString().contains("null"), "no nullable types");
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new java.util.ArrayList<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            texts.add(node.asString());
        }
        return texts;
    }

    @Test
    @DisplayName("Labels are matched case-insensitively, and an unknown section is kept as OTHER")
    void testLenientLabels() {
        respondWith("""
                {"seniority":" Staff ","signals":[
                  {"classification":"Required Skills","text":"Knows SQL."},
                  {"classification":"benefits","text":"Offers a pension."}]}
                """);

        NormalizedData data = extractor.extract(TITLE, DESCRIPTION).orElseThrow();

        assertEquals(Seniority.STAFF, data.seniority());
        assertEquals(SignalClassification.REQUIRED_SKILL, data.signals().get(0).classification());
        assertEquals(SignalClassification.OTHER, data.signals().get(1).classification());
    }

    @Test
    @DisplayName("Blank signals are dropped rather than stored")
    void testBlankSignalsDropped() {
        respondWith("""
                {"seniority":"junior","signals":[
                  {"classification":"required skills","text":"  "},
                  {"classification":"required skills","text":" Knows Git. "}]}
                """);

        NormalizedData data = extractor.extract(TITLE, DESCRIPTION).orElseThrow();

        assertEquals(List.of(new Signal(SignalClassification.REQUIRED_SKILL, "Knows Git.")), data.signals());
    }

    // ------------------------------------------------------------- no answer

    @Test
    @DisplayName("An answer with no signals is no answer, and is not retried")
    void testNoSignals() {
        // Deterministic sampling: asking again would return the same empty list.
        respondWith("""
                {"seniority":"senior","signals":[]}
                """);

        assertEquals(Optional.empty(), extractor.extract(TITLE, DESCRIPTION));
        mockServer.verify();
    }

    @Test
    @DisplayName("An answer with no seniority band, as the prompt asks for a non-description, is no answer")
    void testNoSeniority() {
        respondWith("""
                {"seniority":"","signals":[{"classification":"required skills","text":"Knows SQL."}]}
                """);

        assertEquals(Optional.empty(), extractor.extract(TITLE, DESCRIPTION));
        mockServer.verify();
    }

    @Test
    @DisplayName("A prompt that filled the context window was truncated, so its answer is not used")
    void testTruncatedPromptRejected() {
        respondWith(once(), ollamaBody(GOOD_ANSWER, "stop", CONTEXT));

        assertEquals(Optional.empty(), extractor.extract(TITLE, DESCRIPTION));
        mockServer.verify();
    }

    // ------------------------------------------------------------- retries

    @Test
    @DisplayName("Unparseable output is retried once, then reported as no answer")
    void testUnparseableRetriedOnce() {
        respondWith(twice(), ollamaBody("not json at all", "stop", 900));

        assertEquals(Optional.empty(), extractor.extract(TITLE, DESCRIPTION));
        mockServer.verify();
    }

    @Test
    @DisplayName("A cut-off answer is retried, and a good second answer is used")
    void testCutOffAnswerRetried() {
        respondWith(once(), ollamaBody(GOOD_ANSWER, "length", 900));
        respondWith(once(), ollamaBody(GOOD_ANSWER, "stop", 900));

        assertTrue(extractor.extract(TITLE, DESCRIPTION).isPresent());
        mockServer.verify();
    }

    // ------------------------------------------------------------- transport

    @Test
    @DisplayName("A server error is a transport failure, so the job is left for next run")
    void testServerErrorThrows() {
        mockServer.expect(requestTo(GENERATE)).andRespond(withServerError());

        assertThrows(SignalExtractionException.class, () -> extractor.extract(TITLE, DESCRIPTION));
    }

    @Test
    @DisplayName("A blank description is rejected before any call")
    void testBlankDescriptionRejected() {
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(TITLE, "  "));
        mockServer.verify();
    }
}
