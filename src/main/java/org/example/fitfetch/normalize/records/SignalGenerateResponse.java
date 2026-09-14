package org.example.fitfetch.normalize.records;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from Ollama's {@code POST /api/generate} endpoint.
 *
 * @param response        the generated text; with a schema in the request, a
 *                        JSON document encoded as a string, parsed a second time
 * @param done            whether generation completed
 * @param doneReason      why generation stopped; {@code "length"} means the
 *                        answer was cut off ({@code done_reason})
 * @param promptEvalCount how many prompt tokens the model read
 *                        ({@code prompt_eval_count}). Reaching the context window
 *                        means the prompt was truncated to fit
 */
public record SignalGenerateResponse(
        String response,
        boolean done,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount
) {
}
