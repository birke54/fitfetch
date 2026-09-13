package org.example.fitfetch.location.records;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from Ollama's {@code POST /api/generate} endpoint.
 *
 * <p>Note that {@link #response()} is itself a JSON <em>string</em> that has to
 * be parsed a second time. Schema-constrained output guarantees its shape, not
 * that it arrives pre-parsed.
 *
 * @param model     the model that answered
 * @param response  the generated text; with a schema in the request this is a
 *                  JSON document encoded as a string
 * @param done      whether generation completed
 * @param doneReason why generation stopped, for example {@code "stop"} or
 *                  {@code "length"}. A truncated answer reports
 *                  {@code "length"} and is the one case where valid-looking
 *                  output should still be distrusted ({@code done_reason})
 */
public record OllamaGenerateResponse(
        String model,
        String response,
        boolean done,
        @JsonProperty("done_reason") String doneReason
) {
}
