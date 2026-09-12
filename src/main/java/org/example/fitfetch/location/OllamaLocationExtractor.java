package org.example.fitfetch.location;

import org.example.fitfetch.location.records.ExtractionPayload;
import org.example.fitfetch.location.records.OllamaGenerateRequest;
import org.example.fitfetch.location.records.OllamaGenerateResponse;
import org.example.fitfetch.location.records.OllamaOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link LocationExtractor} backed by a locally hosted Ollama model.
 *
 * <p>Calls {@code POST /api/generate} once per distinct label, with the output
 * constrained by {@link OllamaPrompt#schema()} so the answer is parseable by
 * construction rather than scraped out of prose. One call per label, rather than
 * a batch, is deliberate: the interpretation cache keys on a single label, so
 * batching would either break caching or require unpicking batch boundaries, and
 * a small model asked for a long output array will quietly drop or misalign
 * entries.
 *
 * <p>Failures are separated by what they say about the input:
 *
 * <ul>
 *   <li><strong>Transport failure</strong> &mdash; Ollama down, refused,
 *       timed out. Throws {@link LocationExtractionException}; the caller leaves
 *       the job pending and retries next cycle.</li>
 *   <li><strong>Unusable output</strong> &mdash; unparseable JSON, or generation
 *       truncated. Retried once, then returned as a non-cacheable
 *       {@link ExtractionResult}, so a bad moment is not remembered forever.</li>
 *   <li><strong>Nothing found</strong> &mdash; well-formed output containing no
 *       locations. A real answer about the input, so it is cached and the label
 *       surfaces in the curation worklist.</li>
 * </ul>
 *
 * <p>Instances are immutable and safe to share.
 */
public class OllamaLocationExtractor implements LocationExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaLocationExtractor.class);
    private static final String GENERATE_PATH = "/api/generate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;
    private final OllamaOptions options;

    /**
     * @param restClient the HTTP client; configure connect and read timeouts on
     *                   this, since a hung model must fail the pass rather than
     *                   block it indefinitely
     * @param baseUrl    the Ollama base URL, for example
     *                   {@code http://localhost:11434}; a trailing slash is
     *                   tolerated
     * @param model      the model tag, for example {@code llama3.1:8b}
     */
    public OllamaLocationExtractor(RestClient restClient, String baseUrl, String model) {
        this(restClient, baseUrl, model, OllamaOptions.deterministic());
    }

    /**
     * @param restClient the HTTP client
     * @param baseUrl    the Ollama base URL
     * @param model      the model tag
     * @param options    sampling options; pin these unless you have a reason not
     *                   to, since cached interpretations must be reproducible
     */
    public OllamaLocationExtractor(RestClient restClient, String baseUrl, String model, OllamaOptions options) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.model = requireText(model, "model");
        this.options = Objects.requireNonNull(options, "options");
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
    public ExtractionResult extract(String rawLocationName) {
        Objects.requireNonNull(rawLocationName, "rawLocationName");

        ExtractionPayload payload = generate(rawLocationName);
        if (payload == null) {
            // One retry: schema-constrained output failing to parse is usually a
            // one-off. A second failure means the output cannot be trusted, and
            // must not be written to the cache.
            LOGGER.debug("Retrying extraction for '{}' after unusable model output", rawLocationName);
            payload = generate(rawLocationName);
            if (payload == null) {
                LOGGER.warn("Model produced unusable output twice for '{}'; not caching", rawLocationName);
                return ExtractionResult.untrusted(rawLocationName);
            }
        }

        List<ExtractedLocation> locations = toLocations(payload, rawLocationName);
        if (locations.isEmpty()) {
            // Well-formed output naming no location. That is an answer about the
            // input rather than a malfunction, so it is cached; the label shows
            // up in the curation worklist to be dealt with there.
            LOGGER.debug("Model found no locations in '{}'", rawLocationName);
            return ExtractionResult.unparseable(rawLocationName);
        }
        return ExtractionResult.of(locations);
    }

    /**
     * @return the parsed payload, or {@code null} if the response could not be
     *         used
     * @throws LocationExtractionException on any transport failure
     */
    private ExtractionPayload generate(String rawLocationName) {
        OllamaGenerateRequest request = new OllamaGenerateRequest(
                model, OllamaPrompt.forLabel(rawLocationName), OllamaPrompt.schema(), false, options);

        OllamaGenerateResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + GENERATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OllamaGenerateResponse.class);
        } catch (RestClientException e) {
            throw new LocationExtractionException(
                    "Ollama request failed for location '" + rawLocationName + "'", e);
        }

        if (response == null || response.response() == null || response.response().isBlank()) {
            LOGGER.warn("Ollama returned an empty body for '{}'", rawLocationName);
            return null;
        }
        if ("length".equals(response.doneReason())) {
            // Truncated output can still parse as valid JSON while having lost
            // entries off the end, which is worse than failing outright.
            LOGGER.warn("Ollama truncated its answer for '{}'", rawLocationName);
            return null;
        }

        try {
            return MAPPER.readValue(response.response(), ExtractionPayload.class);
        } catch (JacksonException e) {
            LOGGER.warn("Could not parse model output for '{}': {}", rawLocationName, e.getMessage());
            return null;
        }
    }

    private static List<ExtractedLocation> toLocations(ExtractionPayload payload, String rawLocationName) {
        if (payload.locations() == null) {
            return List.of();
        }
        List<ExtractedLocation> locations = new ArrayList<>(payload.locations().size());
        for (ExtractionPayload.Item item : payload.locations()) {
            if (item == null) {
                continue;
            }
            LocationKind kind = parse(LocationKind.class, item.kind());
            if (kind == null) {
                // The schema constrains this, so an unknown value means the model
                // ignored the constraint. Keep the entry rather than dropping the
                // location entirely; policy turns it into UNDEFINED.
                LOGGER.warn("Unknown location kind '{}' for '{}'", item.kind(), rawLocationName);
                kind = LocationKind.UNPARSEABLE;
            }
            String raw = item.raw() == null || item.raw().isBlank() ? rawLocationName : item.raw();
            locations.add(new ExtractedLocation(
                    raw, kind, blankToNull(item.specifier()),
                    parse(SpecifierType.class, item.specifierType())));
        }
        return locations;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
