package org.example.fitfetch.normalize.records;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * Request body for Ollama's {@code POST /api/generate} endpoint, for signal
 * extraction.
 *
 * @param model   the model tag
 * @param system  the instructions, kept apart from the description itself
 * @param prompt  the answer format and the job description
 * @param format  a JSON Schema constraining the output, so it parses by
 *                construction
 * @param stream  always {@code false}
 * @param options pinned sampling settings and the context window
 * @param think   whether a thinking model should reason before answering, or
 *                {@code null} to leave the field out. Ollama rejects it for a
 *                model that does not think, so it is sent only when configured
 */
public record SignalGenerateRequest(
        String model,
        String system,
        String prompt,
        JsonNode format,
        boolean stream,
        SignalOptions options,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean think
) {
}
