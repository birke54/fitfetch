package org.example.fitfetch.location;

import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.fetching.RestClientConfig;
import org.example.fitfetch.metrics.MetricService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the location resolution pipeline.
 *
 * <p>The chain is assembled here rather than through component scanning because
 * the order of decoration is the design: caches wrap transports, and the curated
 * table sits outside both. Expressing that as annotations on the classes
 * themselves would hide it.
 *
 * @see LocationService
 */
@Configuration
public class LocationConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationConfig.class);

    /**
     * @return the system clock. Injected everywhere rather than called directly
     *         so cache expiry and scheduling stay testable without sleeping
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * @param tableJson the curated table resource ({@code app.location.table})
     * @param origin    the configured search origin
     * @return the hand-curated exact-match table, loaded eagerly so a malformed
     *         or colliding entry fails startup rather than surfacing mid-pass
     * @throws IOException if the resource cannot be read
     */
    @Bean
    public CuratedLocations curatedLocations(
            @Value("${app.location.table}") Resource tableJson,
            @Value("${app.location.default-origin}") String origin) throws IOException {
        return new CuratedLocations(tableJson, origin);
    }

    /**
     * @param origin    the search origin; every resolution that redirects here
     *                  uses it verbatim
     * @param homeState the user's state, enabling rules 6b and 7
     * @return the policy switch
     */
    @Bean
    public LocationPolicy locationPolicy(
            @Value("${app.location.default-origin}") String origin,
            @Value("${app.location.home-state:}") String homeState) {
        return new LocationPolicy(origin, homeState.isBlank() ? null : homeState);
    }

    /**
     * Builds the extraction chain: the interpretation cache in front of Ollama.
     *
     * <p>Ollama gets its own HTTP client rather than the shared one. A local
     * model can take far longer than an API call to answer, especially the
     * first call after it loads, so it needs a longer read timeout. It still
     * needs one: a hung model must fail the pass, not block it.
     *
     * @param repository      the interpretation cache
     * @param clock           time source
     * @param baseUrl         Ollama's base URL
     * @param model           the model tag
     * @param connectTimeout  {@code app.http.connect-timeout}
     * @param readTimeout     {@code app.location.llm.read-timeout}
     * @param hitGranularity  how stale a row's last-read timestamp must be
     *                        before it is worth rewriting
     * @param metricService   where failed model calls are counted
     * @param observationRegistry where each request to Ollama is observed
     * @return the cached extractor
     */
    @Bean
    public LocationExtractor locationExtractor(
            LocationInterpretationRepository repository,
            Clock clock,
            MetricService metricService,
            ObservationRegistry observationRegistry,
            @Value("${app.location.llm.base-url}") String baseUrl,
            @Value("${app.location.llm.model}") String model,
            @Value("${app.http.connect-timeout}") Duration connectTimeout,
            @Value("${app.location.llm.read-timeout}") Duration readTimeout,
            @Value("${app.location.cache.last-hit-granularity}") Duration hitGranularity) {
        RestClient ollamaClient = RestClientConfig.withTimeouts(connectTimeout, readTimeout, observationRegistry);
        return new CachingLocationExtractor(
                new OllamaLocationExtractor(ollamaClient, baseUrl, model, metricService),
                repository, model, OllamaPrompt.VERSION, hitGranularity, clock);
    }

    /**
     * Builds the geocoding chain: the geocode cache in front of Google.
     *
     * <p>When geocoding is switched off, or no API key is configured, the
     * delegate is one that refuses to be called and the cache runs in
     * cache-only mode. That keeps the application startable without a key and
     * makes local runs free: the pass still executes and resolves whatever is
     * already cached, writing nothing for the rest, so enabling lookups later
     * resolves those properly rather than finding poisoned negative entries.
     *
     * @param restClient      shared HTTP client
     * @param repository      the geocode cache
     * @param clock           time source
     * @param enabled         whether outbound lookups are permitted
     * @param apiKey          the Google Maps Platform API key, possibly blank
     * @param coordinateTtl   how long coordinates stay usable before a refresh
     * @param hitGranularity  read-statistics write coarsening
     * @param metricService   where cache results and Google requests are recorded
     * @return the cached geocoder
     */
    @Bean
    public Geocoder geocoder(
            RestClient restClient,
            GeocodeCacheRepository repository,
            Clock clock,
            MetricService metricService,
            @Value("${app.location.geocoding.enabled}") boolean enabled,
            @Value("${app.location.geocoding.api-key:}") String apiKey,
            @Value("${app.location.geocoding.coordinate-ttl}") Duration coordinateTtl,
            @Value("${app.location.cache.last-hit-granularity}") Duration hitGranularity) {

        boolean canLookUp = enabled && !apiKey.isBlank();
        if (enabled && apiKey.isBlank()) {
            LOGGER.warn("Geocoding is enabled but no API key is set; running cache-only");
        } else if (!enabled) {
            LOGGER.info("Geocoding disabled; running cache-only");
        }

        Geocoder delegate = canLookUp
                ? new GoogleGeocoder(restClient, apiKey, metricService)
                : query -> {
                    throw new GeocodingException(
                            "Geocoding is not configured; '" + query + "' cannot be resolved", true);
                };

        return new CachingGeocoder(delegate, repository, coordinateTtl, hitGranularity, canLookUp, clock,
                metricService);
    }

    /**
     * @param curated            the curated table
     * @param extractor          the extraction chain
     * @param policy             the policy switch
     * @param geocoder           the geocoding chain
     * @param maxLocationsPerJob fan-out cap
     * @return the full resolution chain
     */
    @Bean
    public LocationResolver locationResolver(
            CuratedLocations curated,
            LocationExtractor extractor,
            LocationPolicy policy,
            Geocoder geocoder,
            @Value("${app.location.max-locations-per-job}") int maxLocationsPerJob) {
        return new LocationResolver(curated, extractor, policy, geocoder, maxLocationsPerJob);
    }
}
