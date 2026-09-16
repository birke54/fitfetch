package org.example.fitfetch.normalize;

import org.example.fitfetch.normalize.records.SignalGenerateRequest;
import org.example.fitfetch.normalize.records.SignalGenerateResponse;
import org.example.fitfetch.normalize.records.SignalOptions;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.example.fitfetch.normalize.records.SignalPayload;
import org.example.fitfetch.skills.SkillCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link LlmSignalExtractor} backed by a locally hosted Ollama model.
 *
 * <p>Calls {@code POST /api/generate} once per job, with the output constrained
 * by {@link SignalPrompt#schema()} so the answer is parseable by construction.
 * Garbled or cut-off output is retried once, since a schema-constrained answer
 * failing to parse is usually a one-off; a second failure is reported as no
 * answer. A well-formed answer with nothing in it is not retried: sampling is
 * deterministic, so it would come back the same.
 *
 * <p>Instances are immutable and safe to share.
 */
public class OllamaSignalExtractor implements LlmSignalExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaSignalExtractor.class);
    private static final String GENERATE_PATH = "/api/generate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A number of years, as postings write it: "5+ years", "3-5 years", "3 to 5
     * yrs", "two years".
     */
    private static final Pattern STATES_YEARS = Pattern.compile(
            "\\b(?:\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten)\\s*\\+?"
                    + "\\s*(?:(?:-|–|to)\\s*\\d{1,2}\\s*\\+?\\s*)?(?:years?|yrs?)\\b",
            Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;
    private final SignalOptions options;
    private final SkillCanonicalizer skills;
    private final MetricService metricService;

    /**
     * Upper bounds of the prompt-size buckets: a quarter, half, three quarters,
     * nine tenths and all of the context window, so the distribution reads as
     * how full the window gets.
     */
    private final double[] promptTokenBuckets;

    /**
     * @param restClient    the HTTP client; give it a read timeout long enough
     *                      for a full description, since a hung model must fail
     *                      the run rather than block it
     * @param baseUrl       the Ollama base URL; a trailing slash is tolerated
     * @param model         the model tag
     * @param contextLength the context window in tokens. It must hold the
     *                      instructions, the description and the answer; a
     *                      prompt that fills it has been truncated and its
     *                      answer is not used
     * @param skills        maps each signal's skills to canonical names, the
     *                      same ones the candidate profile uses
     * @param metricService where failed calls, prompt sizes and fallbacks are
     *                      recorded
     */
    public OllamaSignalExtractor(RestClient restClient, String baseUrl, String model, int contextLength,
                                 SkillCanonicalizer skills, MetricService metricService) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.metricService = Objects.requireNonNull(metricService, "metricService");
        this.model = requireText(model, "model");
        if (contextLength <= 0) {
            throw new IllegalArgumentException("contextLength must be positive, but was " + contextLength);
        }
        this.options = SignalOptions.deterministic(contextLength);
        this.promptTokenBuckets = new double[]{contextLength * 0.25, contextLength * 0.5, contextLength * 0.75,
                contextLength * 0.9, contextLength};
        String trimmed = requireText(baseUrl, "baseUrl");
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public Optional<NormalizedData> extract(String title, String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new IllegalArgumentException("jobDescription must not be blank");
        }
        Attempt attempt = generate(title, jobDescription);
        if (attempt.retryable()) {
            LOGGER.debug("Retrying signal extraction after unusable model output");
            attempt = generate(title, jobDescription);
        }
        return attempt.data();
    }

    @Override
    public String model() {
        return model;
    }

    /**
     * One call's outcome.
     *
     * @param data      the answer, or empty if there is none to use
     * @param retryable whether asking again might help. True only for garbled
     *                  or cut-off output; an answer that is well-formed but
     *                  empty, or a prompt too long for the window, would come
     *                  back the same
     */
    private record Attempt(Optional<NormalizedData> data, boolean retryable) {

        static Attempt of(NormalizedData data) {
            return new Attempt(Optional.of(data), false);
        }

        static Attempt garbled() {
            return new Attempt(Optional.empty(), true);
        }

        static Attempt noAnswer() {
            return new Attempt(Optional.empty(), false);
        }
    }

    /**
     * @throws SignalExtractionException on any transport failure
     */
    private Attempt generate(String title, String jobDescription) {
        SignalGenerateRequest request = new SignalGenerateRequest(model, SignalPrompt.SYSTEM,
                SignalPrompt.forJob(title, jobDescription), SignalPrompt.schema(), false, options);

        SignalGenerateResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + GENERATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SignalGenerateResponse.class);
        } catch (RestClientException e) {
            recordFailure("transport");
            throw new SignalExtractionException("Ollama request failed during signal extraction", e);
        }

        if (response == null || response.response() == null || response.response().isBlank()) {
            LOGGER.warn("Ollama returned an empty body for signal extraction");
            recordFailure("empty_body");
            return Attempt.garbled();
        }
        if (response.promptEvalCount() != null) {
            metricService.recordDistribution(MetricName.NORMALIZE_PROMPT_TOKENS, Map.of(),
                    response.promptEvalCount(), promptTokenBuckets);
        }
        if (response.promptEvalCount() != null && response.promptEvalCount() >= options.contextLength()) {
            // Ollama fits an oversized prompt by dropping part of it, silently.
            // The answer would then be about a fragment of the description, or
            // made without the instructions.
            LOGGER.warn("Prompt filled the {}-token context window and was truncated; "
                    + "raise app.normalize.llm.context-length", options.contextLength());
            recordFailure("prompt_too_long");
            return Attempt.noAnswer();
        }
        if ("length".equals(response.doneReason())) {
            // A truncated array can still parse, having lost signals off the end.
            LOGGER.warn("Ollama cut off its signal extraction answer");
            recordFailure("cut_off");
            return Attempt.garbled();
        }

        SignalPayload payload;
        try {
            payload = MAPPER.readValue(response.response(), SignalPayload.class);
        } catch (JacksonException e) {
            LOGGER.warn("Could not parse signal extraction output: {}", e.getMessage());
            recordFailure("unparseable_json");
            return Attempt.garbled();
        }
        return toData(payload);
    }

    private Attempt toData(SignalPayload payload) {
        Seniority seniority = Seniority.fromLabel(payload.seniority());
        if (seniority == null) {
            // The prompt asks for an empty seniority when the input is not a job
            // description, so this is the model saying there is nothing here.
            LOGGER.warn("Model named no seniority band ('{}')", payload.seniority());
            recordFailure("no_seniority");
            return Attempt.noAnswer();
        }
        List<Signal> signals = new ArrayList<>();
        if (payload.signals() != null) {
            for (SignalPayload.Item item : payload.signals()) {
                if (item == null || item.text() == null || item.text().isBlank()) {
                    continue;
                }
                signals.add(signal(item));
            }
        }
        if (signals.isEmpty()) {
            LOGGER.warn("Model extracted no signals");
            recordFailure("no_signals");
            return Attempt.noAnswer();
        }
        // The job-level fields fall back rather than reject the answer: the
        // schema constrains them, so an odd value is rare, and the signals are
        // still good. Each fallback is the reading that excludes no job, and is
        // counted, since a rising count means the model is drifting from the schema.
        HardRequirements requirements = new HardRequirements(
                checked("required_degree", payload.requiredDegree(), Degree.fromLabel(payload.requiredDegree())),
                Boolean.TRUE.equals(payload.clearanceRequired()),
                checked("sponsorship", payload.sponsorship(), Sponsorship.fromLabel(payload.sponsorship())),
                cleaned(payload.requiredCertifications(), false),
                Boolean.TRUE.equals(payload.travelRequired()),
                Boolean.TRUE.equals(payload.onCall()));
        return Attempt.of(new NormalizedData(
                seniority,
                checked("track", payload.track(), Track.fromLabel(payload.track())),
                checked("employment_type", payload.employmentType(),
                        EmploymentType.fromLabel(payload.employmentType())),
                years(payload.minYearsExperience()),
                requirements,
                cleaned(payload.domains(), true),
                signals));
    }

    /**
     * Builds one signal, its skills in canonical names. A section outside the
     * schema is kept as {@code OTHER} and counted as a fallback.
     *
     * <p>A skill the signal's text does not name is dropped. The model copies
     * skills from elsewhere in the posting onto signals that never mention
     * them, and each one would count as a requirement the job never made.
     *
     * <p>A skill the model put in both lists is an alternative, since that is
     * the narrower claim: kept in {@code skills} it would count as required. A
     * single alternative is no choice at all, so it joins the required skills.
     *
     * <p>Years are kept only if the signal's text states a number of years. The
     * model copies a posting's one "3+ years" onto most of its signals, and
     * scoring would then ask for 3 years of every skill they name.
     */
    private Signal signal(SignalPayload.Item item) {
        SignalClassification classification = SignalClassification.fromLabel(item.classification());
        if (classification == SignalClassification.OTHER) {
            recordFallback("classification");
        }
        String text = item.text().strip();
        List<String> required = namedIn(text, skills.canonicalAll(item.skills()));
        List<String> anyOf = namedIn(text, skills.canonicalAll(item.anyOfSkills()));
        if (anyOf.size() < 2) {
            // canonicalAll drops the repeat if the one alternative is also required.
            required = skills.canonicalAll(Stream.concat(required.stream(), anyOf.stream()).toList());
            anyOf = List.of();
        } else {
            Set<String> alternatives = anyOf.stream()
                    .map(skill -> skill.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            required = required.stream()
                    .filter(skill -> !alternatives.contains(skill.toLowerCase(Locale.ROOT))).toList();
        }
        int minYears = years(item.minYears());
        if (minYears > 0 && !STATES_YEARS.matcher(text).find()) {
            LOGGER.debug("Cleared {} years from a signal that states none: {}", minYears, text);
            recordFallback("min_years");
            minYears = 0;
        }
        return new Signal(classification, text, required, anyOf, minYears);
    }

    /** @return the skills the text names, in order, having counted each one dropped */
    private List<String> namedIn(String text, List<String> candidates) {
        List<String> named = new ArrayList<>(candidates.size());
        for (String skill : candidates) {
            if (skills.isNamedIn(skill, text)) {
                named.add(skill);
            } else {
                LOGGER.debug("Dropped skill '{}', which the signal does not name: {}", skill, text);
                metricService.recordCounter(MetricName.NORMALIZE_SKILLS_DROPPED_COUNT);
            }
        }
        return named;
    }

    /**
     * @return {@code value}, having counted a fallback if the model gave no
     *         value for the field or one the schema does not offer
     */
    private <E extends Labeled> E checked(String field, String given, E value) {
        if (given == null || !given.strip().equalsIgnoreCase(value.label())) {
            recordFallback(field);
        }
        return value;
    }

    private void recordFailure(String reason) {
        metricService.recordCounter(MetricName.NORMALIZE_EXTRACTION_FAILURE_COUNT, Map.of(TagName.REASON, reason));
    }

    private void recordFallback(String field) {
        metricService.recordCounter(MetricName.NORMALIZE_FIELD_FALLBACK_COUNT, Map.of(TagName.FIELD, field));
    }

    /**
     * @return the values stripped, without blanks or repeats, in the order the
     *         model gave them; lower-cased first if {@code lowerCase}. Repeats
     *         are found ignoring case, so "Kafka" and "kafka" keep only the first
     */
    private static List<String> cleaned(List<String> values, boolean lowerCase) {
        if (values == null) {
            return List.of();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String stripped = lowerCase ? value.strip().toLowerCase(Locale.ROOT) : value.strip();
            byKey.putIfAbsent(stripped.toLowerCase(Locale.ROOT), stripped);
        }
        return List.copyOf(byKey.values());
    }

    /** @return the year count, or 0 if the model left it out or gave a negative one */
    private static int years(Integer years) {
        return years == null ? 0 : Math.max(0, years);
    }
}
