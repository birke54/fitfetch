package org.example.fitfetch.embedding;

import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.fetching.RestClientConfig;
import org.example.fitfetch.metrics.MetricService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** Wires the embedding pipeline: the model, the vector cache and the bullet vectors. */
@Configuration
public class EmbeddingConfig {

    /**
     * @param model          {@code app.embedding.llm.model}
     * @param queryPrefix    {@code app.embedding.query-prefix}, put before job signals
     * @param documentPrefix {@code app.embedding.document-prefix}, put before bullets
     * @return the settings in force
     */
    @Bean
    public EmbeddingSettings embeddingSettings(@Value("${app.embedding.llm.model}") String model,
                                               @Value("${app.embedding.query-prefix:}") String queryPrefix,
                                               @Value("${app.embedding.document-prefix:}") String documentPrefix) {
        return new EmbeddingSettings(model, queryPrefix, documentPrefix);
    }

    /**
     * Ollama gets its own HTTP client, as for extraction and normalization, so
     * the embedding model's first call after loading has time to answer.
     *
     * @param baseUrl             {@code app.embedding.llm.base-url}
     * @param settings            the model to call
     * @param connectTimeout      {@code app.http.connect-timeout}
     * @param readTimeout         {@code app.embedding.llm.read-timeout}
     * @param observationRegistry where each request to Ollama is observed
     * @return the embedder
     */
    @Bean
    public Embedder embedder(@Value("${app.embedding.llm.base-url}") String baseUrl,
                             EmbeddingSettings settings,
                             @Value("${app.http.connect-timeout}") Duration connectTimeout,
                             @Value("${app.embedding.llm.read-timeout}") Duration readTimeout,
                             ObservationRegistry observationRegistry) {
        return new OllamaEmbedder(RestClientConfig.withTimeouts(connectTimeout, readTimeout, observationRegistry),
                baseUrl, settings.model());
    }

    /**
     * @param repository    the cache table
     * @param embedder      the model
     * @param settings      the model tag, part of the cache key
     * @param metricService where cache hits and misses are counted
     * @param clock         time source
     * @param batchSize     {@code app.embedding.batch-size}
     * @return the cache
     */
    @Bean
    public EmbeddingCache embeddingCache(EmbeddingRepository repository, Embedder embedder,
                                         EmbeddingSettings settings, MetricService metricService, Clock clock,
                                         @Value("${app.embedding.batch-size}") int batchSize) {
        return new EmbeddingCache(repository, embedder, settings.model(), metricService, clock, batchSize);
    }

    /** @return the holder for the profile's bullet vectors */
    @Bean
    public ProfileEmbeddings profileEmbeddings() {
        return new ProfileEmbeddings();
    }
}
