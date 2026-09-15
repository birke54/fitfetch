package org.example.fitfetch.normalize;

import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.fetching.RestClientConfig;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.skills.SkillCanonicalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the normalization pipeline.
 *
 * @see NormalizeService
 */
@Configuration
public class NormalizeConfig {

    /**
     * Ollama gets its own HTTP client rather than the shared one, as for location
     * extraction, and a longer read timeout still: the model reads a whole job
     * description and writes out every requirement in it.
     *
     * @param baseUrl        {@code app.normalize.llm.base-url}
     * @param model          {@code app.normalize.llm.model}
     * @param connectTimeout {@code app.http.connect-timeout}
     * @param readTimeout    {@code app.normalize.llm.read-timeout}
     * @param contextLength  {@code app.normalize.llm.context-length}
     * @param observationRegistry where each request to Ollama is observed
     * @param skills         maps extracted skills to the names the profile uses
     * @param metricService  where failed calls, prompt sizes and fallbacks are recorded
     * @return the signal extractor
     */
    @Bean
    public LlmSignalExtractor signalExtractor(
            @Value("${app.normalize.llm.base-url}") String baseUrl,
            @Value("${app.normalize.llm.model}") String model,
            @Value("${app.http.connect-timeout}") Duration connectTimeout,
            @Value("${app.normalize.llm.read-timeout}") Duration readTimeout,
            @Value("${app.normalize.llm.context-length}") int contextLength,
            ObservationRegistry observationRegistry,
            SkillCanonicalizer skills,
            MetricService metricService) {
        return new OllamaSignalExtractor(
                RestClientConfig.withTimeouts(connectTimeout, readTimeout, observationRegistry),
                baseUrl, model, contextLength, skills, metricService);
    }
}
