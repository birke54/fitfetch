package org.example.fitfetch.embedding;

import org.example.fitfetch.embedding.records.EmbedRequest;
import org.example.fitfetch.embedding.records.EmbedResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@link Embedder} backed by a locally hosted Ollama embedding model, through
 * {@code POST /api/embed}, which takes a batch of inputs in one call.
 *
 * <p>A transport failure or an empty answer is an {@link EmbeddingException}:
 * the model is down or loading, and the next run tries again. An answer with
 * the wrong number of vectors is an {@link IllegalStateException}, since asking
 * again would not fix it.
 *
 * <p>Instances are immutable and safe to share.
 */
public class OllamaEmbedder implements Embedder {

    private static final String EMBED_PATH = "/api/embed";

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;

    /**
     * @param restClient the HTTP client, with timeouts configured
     * @param baseUrl    the Ollama base URL; a trailing slash is tolerated
     * @param model      the embedding model tag, for example {@code nomic-embed-text}
     */
    public OllamaEmbedder(RestClient restClient, String baseUrl, String model) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.model = requireText(model, "model");
        String trimmed = requireText(baseUrl, "baseUrl");
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        EmbedResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + EMBED_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new EmbedRequest(model, inputs))
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (RestClientException e) {
            throw new EmbeddingException("Ollama embedding request failed", e);
        }
        if (response == null || response.embeddings() == null || response.embeddings().length == 0) {
            throw new EmbeddingException("Ollama returned no embeddings", null);
        }
        if (response.embeddings().length != inputs.size()) {
            throw new IllegalStateException("Ollama returned " + response.embeddings().length
                    + " embeddings for " + inputs.size() + " inputs");
        }
        for (float[] vector : response.embeddings()) {
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("Ollama returned an empty embedding");
            }
        }
        return Arrays.asList(response.embeddings());
    }
}
