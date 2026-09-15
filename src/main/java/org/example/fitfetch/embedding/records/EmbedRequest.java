package org.example.fitfetch.embedding.records;

import java.util.List;

/**
 * Body of Ollama's {@code POST /api/embed}.
 *
 * @param model the embedding model tag
 * @param input the texts to embed, answered in the same order
 */
public record EmbedRequest(String model, List<String> input) {
}
