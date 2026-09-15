package org.example.fitfetch.embedding.records;

/**
 * Ollama's answer to {@code POST /api/embed}: one vector per input, in order.
 * Ollama normalizes each to unit length.
 *
 * @param model      the model that answered
 * @param embeddings the vectors
 */
public record EmbedResponse(String model, float[][] embeddings) {
}
