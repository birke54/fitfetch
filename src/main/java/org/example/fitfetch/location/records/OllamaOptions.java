package org.example.fitfetch.location.records;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sampling options sent with an Ollama generate request.
 *
 * <p>All three are pinned rather than left to the model's defaults. Cached
 * interpretations have to be reproducible: if the same label can yield different
 * extractions on different runs, a cache entry stops being an answer and becomes
 * a snapshot of one lucky sample. Pinned sampling also makes prompt evaluation
 * meaningful, since a score change can then only come from the prompt.
 *
 * @param temperature sampling temperature; zero for greedy decoding
 * @param topK        candidate pool size; one, so only the top token is ever
 *                    considered ({@code top_k})
 * @param seed        fixed seed, so any residual non-determinism is pinned too
 */
public record OllamaOptions(
        double temperature,
        @JsonProperty("top_k") int topK,
        int seed
) {

    /** Deterministic defaults: greedy decoding with a fixed seed. */
    public static OllamaOptions deterministic() {
        return new OllamaOptions(0.0d, 1, 42);
    }
}
