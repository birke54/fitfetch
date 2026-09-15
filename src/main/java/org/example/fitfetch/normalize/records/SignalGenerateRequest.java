package org.example.fitfetch.normalize.records;

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
 */
public record SignalGenerateRequest(
        String model,
        String system,
        String prompt,
        JsonNode format,
        boolean stream,
        SignalOptions options
) {
}
