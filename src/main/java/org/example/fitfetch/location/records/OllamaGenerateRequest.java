package org.example.fitfetch.location.records;

import tools.jackson.databind.JsonNode;

/**
 * Request body for Ollama's {@code POST /api/generate} endpoint.
 *
 * @param model   the model tag, for example {@code "llama3.1:8b"}
 * @param prompt  the full prompt, instructions and few-shot examples included
 * @param format  a JSON Schema constraining the model's output. This is what
 *                makes the response parseable by construction rather than by
 *                hope; without it the model is free to wrap its answer in prose
 *                and every caller ends up scraping text
 * @param stream  always {@code false}: the extraction is a single short answer,
 *                and streaming would only complicate the transport
 * @param options pinned sampling settings
 */
public record OllamaGenerateRequest(
        String model,
        String prompt,
        JsonNode format,
        boolean stream,
        OllamaOptions options
) {
}
