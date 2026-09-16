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
     * @param think          {@code app.normalize.llm.think}; blank to leave the
     *                       field out of the request, as models that do not
     *                       think require
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
            @Value("${app.normalize.llm.think:}") String think,
            ObservationRegistry observationRegistry,
            SkillCanonicalizer skills,
            MetricService metricService) {
        return new OllamaSignalExtractor(
                RestClientConfig.withTimeouts(connectTimeout, readTimeout, observationRegistry),
                baseUrl, model, contextLength, thinking(think), skills, metricService);
    }

    /**
     * @param think the configured value, blank if unset
     * @return what to send as {@code think}, or {@code null} to leave it out
     * @throws IllegalArgumentException if it is neither blank, true nor false
     */
    static Boolean thinking(String think) {
        if (think == null || think.isBlank()) {
            return null;
        }
        String value = think.strip();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("app.normalize.llm.think must be true, false or unset, but was "
                    + think);
        }
        return Boolean.valueOf(value);
    }
}
