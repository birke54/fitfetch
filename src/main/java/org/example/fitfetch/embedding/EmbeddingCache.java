package org.example.fitfetch.embedding;

import org.example.fitfetch.domain.Embedding;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gets the vector for each text, from the {@code embeddings} table when it is
 * there and from the model when it is not.
 *
 * <p>Only texts not already cached reach the model, in batches, and each is
 * saved as soon as its batch returns, so an outage partway loses nothing done
 * before it. A text asked for twice in one call is embedded once.
 *
 * <p>Instances are immutable and safe to share.
 */
public class EmbeddingCache {

    private final EmbeddingRepository repository;
    private final Embedder embedder;
    private final String model;
    private final MetricService metricService;
    private final Clock clock;
    private final int batchSize;

    /**
     * @param repository    the cache table
     * @param embedder      the model, for texts not yet cached
     * @param model         the model tag, part of the cache key
     * @param metricService where cache hits and misses are counted
     * @param clock         time source
     * @param batchSize     texts per call to the model; at least 1
     */
    public EmbeddingCache(EmbeddingRepository repository, Embedder embedder, String model,
                          MetricService metricService, Clock clock, int batchSize) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.model = Objects.requireNonNull(model, "model");
        this.metricService = Objects.requireNonNull(metricService, "metricService");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, but was " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * @param inputs the exact texts to embed, prefixes included
     * @return one vector per input, in the same order
     * @throws EmbeddingException if the model could not be reached; vectors made
     *                            before the failure are already saved
     */
    public List<float[]> vectorsFor(List<String> inputs) {
        Map<String, String> hashByInput = new LinkedHashMap<>();
        for (String input : inputs) {
            hashByInput.computeIfAbsent(Objects.requireNonNull(input, "input"), EmbeddingCache::sha256);
        }

        Map<String, float[]> vectorByHash = new HashMap<>();
        for (Embedding cached : repository.findByModelAndInputSha256In(model, hashByInput.values())) {
            vectorByHash.put(cached.getInputSha256(), cached.getVector());
        }

        List<String> missing = hashByInput.keySet().stream()
                .filter(input -> !vectorByHash.containsKey(hashByInput.get(input)))
                .toList();
        record("cached", hashByInput.size() - missing.size());
        for (int from = 0; from < missing.size(); from += batchSize) {
            List<String> batch = missing.subList(from, Math.min(from + batchSize, missing.size()));
            List<float[]> vectors = embedder.embed(batch);
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<Embedding> rows = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                String hash = hashByInput.get(batch.get(i));
                vectorByHash.put(hash, vectors.get(i));
                rows.add(new Embedding(model, hash, vectors.get(i), now));
            }
            repository.saveAll(rows);
            record("embedded", batch.size());
        }

        return inputs.stream().map(input -> vectorByHash.get(hashByInput.get(input))).toList();
    }

    private void record(String result, int count) {
        if (count > 0) {
            metricService.recordCounterByIncrement(MetricName.EMBEDDING_INPUTS_COUNT,
                    Map.of(TagName.RESULT, result), count);
        }
    }

    static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
