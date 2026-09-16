package org.example.fitfetch.normalize;

import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.example.fitfetch.skills.SkillCanonicalizer;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private MetricService metricService;
    private OllamaSignalExtractor extractor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        metricService = mock(MetricService.class);
        extractor = new OllamaSignalExtractor(builder.build(), BASE_URL + "/", "qwen2.5:14b", CONTEXT, null,
                new SkillCanonicalizer(Map.of("Kubernetes", List.of("k8s"), "PostgreSQL", List.of("postgres"),
                        "Go", List.of("golang"))),
                metricService);
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
    @DisplayName("Signal skills are mapped to the canonical names the profile uses, and repeat spellings dropped")
    void testSkillsCanonicalized() {
        respondWith("""
                {"seniority":"senior","signals":[{"classification":"required skills",
                  "text":"Runs Postgres on k8s with Terraform.","skills":["k8s","Postgres","Kubernetes","Terraform"],
                  "min_years":0}]}
                """);

        NormalizedData data = extractor.extract(TITLE, DESCRIPTION).orElseThrow();

        assertEquals(List.of("Kubernetes", "PostgreSQL", "Terraform"), data.signals().getFirst().skills());
    }

    @Test
    @DisplayName("Alternatives are canonicalized and kept apart from the required skills")
    void testAlternatives() {
        // The model listed Postgres as both required and an alternative: an
        // alternative is the narrower claim, so it is not also required.
        respondWith("""
                {"seniority":"senior","signals":[{"classification":"required skills",
                  "text":"Runs Postgres or MySQL on k8s.","skills":["k8s","Postgres"],
                  "any_of_skills":["postgres","MySQL","mysql"],"min_years":0}]}
                """);

        Signal signal = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals().getFirst();

        assertEquals(List.of("Kubernetes"), signal.skills());
        assertEquals(List.of("PostgreSQL", "MySQL"), signal.anyOfSkills());
    }

    @Test
    @DisplayName("A single alternative is no choice, so it joins the required skills")
    void testSingleAlternativeIsRequired() {
        respondWith("""
                {"seniority":"senior","signals":[
                  {"classification":"required skills","text":"Knows Kafka and Go.","skills":["Kafka"],
                   "any_of_skills":["Golang"],"min_years":0},
                  {"classification":"required skills","text":"Knows k8s.","skills":["Kubernetes"],
                   "any_of_skills":["k8s"],"min_years":0}]}
                """);

        List<Signal> signals = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals();

        assertEquals(List.of("Kafka", "Go"), signals.get(0).skills());
        assertEquals(List.of(), signals.get(0).anyOfSkills());
        assertEquals(List.of("Kubernetes"), signals.get(1).skills(), "the repeat is dropped");
        assertEquals(List.of(), signals.get(1).anyOfSkills());
    }

    @Test
    @DisplayName("Skills the signal's text does not name are dropped and counted")
    void testUnnamedSkillsDropped() {
        // Both as a real posting came back: languages copied from another
        // bullet, and "AGI" where the text says "ADK".
        respondWith("""
                {"seniority":"staff","signals":[
                  {"classification":"required skills",
                   "text":"Hands-on experience building agentic or large language model-based systems.",
                   "skills":["Go","Rust","Python"],"min_years":0},
                  {"classification":"required qualifications",
                   "text":"Experience with AI/agentic systems (DAP, ADK, LangGraph, or similar).",
                   "skills":["AI","AGI"],"min_years":2}]}
                """);

        List<Signal> signals = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals();

        assertEquals(List.of(), signals.get(0).skills());
        assertEquals(List.of("AI"), signals.get(1).skills());
        verify(metricService, times(4)).recordCounter(MetricName.NORMALIZE_SKILLS_DROPPED_COUNT);
    }

    @Test
    @DisplayName("Unnamed alternatives are dropped before deciding whether a choice remains")
    void testUnnamedAlternativesDropped() {
        respondWith("""
                {"seniority":"senior","signals":[
                  {"classification":"required skills","text":"Knows Java or Golang.","skills":[],
                   "any_of_skills":["Java","Go","Rust"],"min_years":0},
                  {"classification":"required skills","text":"Knows Go.","skills":[],
                   "any_of_skills":["Go","Rust"],"min_years":0}]}
                """);

        List<Signal> signals = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals();

        assertEquals(List.of("Java", "Go"), signals.get(0).anyOfSkills(), "Go is named by its alias");
        assertEquals(List.of("Go"), signals.get(1).skills(), "one alternative left is no choice");
        assertEquals(List.of(), signals.get(1).anyOfSkills());
    }

    @Test
    @DisplayName("Alternatives the model left in skills are read out of the signal's text")
    void testAlternativesReadFromText() {
        // As llama3.1:8b and qwen2.5 both answered: a choice, listed as skills.
        respondWith("""
                {"seniority":"midlevel","signals":[{"classification":"preferred/nice-to-have skills",
                  "text":"Experience with Go, Python, Java, or C#.","skills":["Go","Python","Java","C#"],
                  "any_of_skills":[],"min_years":0}]}
                """);

        Signal signal = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals().getFirst();

        assertEquals(List.of(), signal.skills());
        assertEquals(List.of("Go", "Python", "Java", "C#"), signal.anyOfSkills());
    }

    @Test
    @DisplayName("Alternatives the model named itself are kept, not read again from the text")
    void testModelAlternativesKept() {
        respondWith("""
                {"seniority":"senior","signals":[{"classification":"required skills",
                  "text":"Runs Kubernetes on Postgres or MySQL.","skills":["k8s"],
                  "any_of_skills":["Postgres","MySQL"],"min_years":0}]}
                """);

        Signal signal = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals().getFirst();

        assertEquals(List.of("Kubernetes"), signal.skills());
        assertEquals(List.of("PostgreSQL", "MySQL"), signal.anyOfSkills());
    }

    @Test
    @DisplayName("A signal keeps its years only if its text states a number of years, and each one cleared is counted")
    void testUnstatedYearsCleared() {
        // As a real posting came back: its one "3+ years" copied onto signals
        // that state none.
        respondWith("""
                {"seniority":"senior","signals":[
                  {"classification":"required skills","text":"3+ years of experience building distributed systems",
                   "skills":[],"min_years":3},
                  {"classification":"required qualifications",
                   "text":"Hands-on experience with building production-level code. Experience in C++ is required",
                   "skills":["C++"],"min_years":3},
                  {"classification":"required qualifications",
                   "text":"Solid verbal and written communication skills","skills":[],"min_years":3}]}
                """);

        List<Signal> signals = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals();

        assertEquals(List.of(3, 0, 0), signals.stream().map(Signal::minYears).toList());
        verify(metricService, times(2)).recordCounter(MetricName.NORMALIZE_FIELD_FALLBACK_COUNT,
                Map.of(TagName.FIELD, "min_years"));
    }

    @Test
    @DisplayName("Years count as stated however a posting writes them")
    void testStatedYearsForms() {
        List<String> texts = List.of("Has 5+ years of Java.", "3-5 years of Go.", "3 – 5 yrs of Rust.",
                "At least two years of Python.", "Ten years in fintech.", "7+years of C++.", "1 year of Scala.");
        StringBuilder signals = new StringBuilder();
        for (String text : texts) {
            signals.append(signals.isEmpty() ? "" : ",").append("""
                    {"classification":"required skills","text":"%s","skills":[],"min_years":2}""".formatted(text));
        }
        respondWith("""
                {"seniority":"senior","signals":[%s]}""".formatted(signals));

        List<Signal> read = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals();

        assertTrue(read.stream().allMatch(signal -> signal.minYears() == 2), read.toString());
        verify(metricService, never()).recordCounter(MetricName.NORMALIZE_FIELD_FALLBACK_COUNT,
                Map.of(TagName.FIELD, "min_years"));
    }

    @Test
    @DisplayName("Numbers that are not years do not keep a signal's years")
    void testNumbersNotYears() {
        respondWith("""
                {"seniority":"senior","signals":[
                  {"classification":"required skills","text":"Handles 40k events per second in Go.",
                   "skills":["Go"],"min_years":3},
                  {"classification":"required skills","text":"Supports Python 3 and ES2015.",
                   "skills":[],"min_years":3}]}
                """);

        List<Signal> read = extractor.extract(TITLE, DESCRIPTION).orElseThrow().signals();

        assertEquals(List.of(0, 0), read.stream().map(Signal::minYears).toList());
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
    @DisplayName("Thinking is left out of the request unless it is configured, since some models reject the field")
    void testThinkOmittedUnlessSet() {
        mockServer.expect(requestTo(GENERATE))
                .andExpect(request -> assertFalse(((MockClientHttpRequest) request).getBodyAsString()
                        .contains("think"), "the field must not be sent"))
                .andRespond(withSuccess(ollamaBody(GOOD_ANSWER, "stop", 900), MediaType.APPLICATION_JSON));

        extractor.extract(TITLE, DESCRIPTION);

        mockServer.verify();
    }

    @Test
    @DisplayName("A thinking model is told not to reason before answering when so configured")
    void testThinkSent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaSignalExtractor qwen3 = new OllamaSignalExtractor(builder.build(), BASE_URL, "qwen3:8b", CONTEXT,
                false, SkillCanonicalizer.none(), metricService);
        server.expect(requestTo(GENERATE))
                .andExpect(request -> {
                    JsonNode body = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertTrue(body.has("think"), body.toString());
                    assertFalse(body.path("think").asBoolean());
                })
                .andRespond(withSuccess(ollamaBody(GOOD_ANSWER, "stop", 900), MediaType.APPLICATION_JSON));

        qwen3.extract(TITLE, DESCRIPTION);

        server.verify();
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
        assertEquals(List.of("classification", "text", "skills", "any_of_skills", "min_years"),
                names(item.path("properties")));
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

    // ------------------------------------------------------------- metrics

    private void verifyFailure(String reason, int times) {
        verify(metricService, times(times)).recordCounter(MetricName.NORMALIZE_EXTRACTION_FAILURE_COUNT,
                Map.of(TagName.REASON, reason));
    }

    private void verifyFallback(String field) {
        verify(metricService).recordCounter(MetricName.NORMALIZE_FIELD_FALLBACK_COUNT, Map.of(TagName.FIELD, field));
    }

    @Test
    @DisplayName("Each prompt's size is recorded, with buckets at fractions of the context window")
    void testPromptTokensRecorded() {
        respondWith(once(), ollamaBody(GOOD_ANSWER, "stop", 900));

        extractor.extract(TITLE, DESCRIPTION);

        verify(metricService).recordDistribution(MetricName.NORMALIZE_PROMPT_TOKENS, Map.of(), 900.0,
                CONTEXT * 0.25, CONTEXT * 0.5, CONTEXT * 0.75, CONTEXT * 0.9, CONTEXT);
    }

    @Test
    @DisplayName("A prompt that filled the window is recorded at its size and counted as too long")
    void testPromptTooLongCounted() {
        respondWith(once(), ollamaBody(GOOD_ANSWER, "stop", CONTEXT));

        extractor.extract(TITLE, DESCRIPTION);

        verify(metricService).recordDistribution(eq(MetricName.NORMALIZE_PROMPT_TOKENS), anyMap(),
                eq((double) CONTEXT), any(double[].class));
        verifyFailure("prompt_too_long", 1);
    }

    @Test
    @DisplayName("Garbled output is counted per call, so a failed retry counts twice")
    void testUnparseableCountedPerCall() {
        respondWith(twice(), ollamaBody("not json at all", "stop", 900));

        extractor.extract(TITLE, DESCRIPTION);

        verifyFailure("unparseable_json", 2);
    }

    @Test
    @DisplayName("A cut-off answer, an empty body and a transport failure are each counted with their reason")
    void testOtherFailuresCounted() {
        respondWith(once(), ollamaBody(GOOD_ANSWER, "length", 900));
        respondWith(once(), """
                {"model":"qwen2.5:14b","response":"","done":true,"done_reason":"stop"}
                """);
        extractor.extract(TITLE, DESCRIPTION);
        verifyFailure("cut_off", 1);
        verifyFailure("empty_body", 1);

        mockServer.reset();
        mockServer.expect(requestTo(GENERATE)).andRespond(withServerError());
        assertThrows(SignalExtractionException.class, () -> extractor.extract(TITLE, DESCRIPTION));
        verifyFailure("transport", 1);
    }

    @Test
    @DisplayName("An answer naming no seniority or no signals is counted with its reason")
    void testNoAnswerReasonsCounted() {
        respondWith("""
                {"seniority":"","signals":[{"classification":"required skills","text":"Knows SQL."}]}
                """);
        extractor.extract(TITLE, DESCRIPTION);
        verifyFailure("no_seniority", 1);

        mockServer.reset();
        respondWith("""
                {"seniority":"senior","signals":[]}
                """);
        extractor.extract(TITLE, DESCRIPTION);
        verifyFailure("no_signals", 1);
    }

    @Test
    @DisplayName("A field the model left out or answered outside the schema is counted as a fallback")
    void testFallbacksCounted() {
        respondWith("""
                {"seniority":"senior","track":"wizard","sponsorship":"maybe",
                 "signals":[{"classification":"benefits","text":"Knows SQL."}]}
                """);

        extractor.extract(TITLE, DESCRIPTION);

        verifyFallback("track");
        verifyFallback("sponsorship");
        verifyFallback("employment_type");
        verifyFallback("required_degree");
        verifyFallback("classification");
    }

    @Test
    @DisplayName("A complete, well-formed answer counts no failure and no fallback")
    void testGoodAnswerCountsNothing() {
        respondWith(GOOD_ANSWER);

        extractor.extract(TITLE, DESCRIPTION);

        verify(metricService, never()).recordCounter(eq(MetricName.NORMALIZE_EXTRACTION_FAILURE_COUNT), anyMap());
        verify(metricService, never()).recordCounter(eq(MetricName.NORMALIZE_FIELD_FALLBACK_COUNT), anyMap());
    }
}
