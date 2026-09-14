package org.example.fitfetch.normalize.records;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Options sent with a signal extraction request.
 *
 * <p>Sampling is pinned, as for location extraction, so a job gets the same
 * answer every time and a changed answer can only come from a changed prompt.
 * That is also what makes {@code FAILED} a fair verdict: a retry would not get a
 * different result.
 *
 * <p>The context window is set explicitly because job descriptions are long and
 * Ollama's default window is small. A prompt that does not fit is truncated
 * silently, which cuts off the instructions or the description and still
 * produces confident-looking output.
 *
 * @param temperature   sampling temperature; zero for greedy decoding
 * @param topK          candidate pool size ({@code top_k})
 * @param seed          fixed seed
 * @param contextLength context window in tokens ({@code num_ctx})
 */
public record SignalOptions(
        double temperature,
        @JsonProperty("top_k") int topK,
        int seed,
        @JsonProperty("num_ctx") int contextLength
) {

    /**
     * @param contextLength the context window in tokens
     * @return greedy decoding with a fixed seed and the given window
     */
    public static SignalOptions deterministic(int contextLength) {
        return new SignalOptions(0.0d, 1, 42, contextLength);
    }
}
