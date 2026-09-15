package org.example.fitfetch.fetching;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Spring configuration that exposes the application's shared {@link RestClient}.
 *
 * <p>The bean is injected into the geocoder. ATS fetchers use their own
 * rate-limited clients from {@link AtsRestClients} instead, built with
 * {@link #builderWithTimeouts}. Every client built here carries a connect and a
 * read timeout. The JDK client underneath has neither by default, so one hung
 * connection would otherwise stall a scheduled run indefinitely.
 *
 * <p>Every client is also observed, so each request is timed as
 * {@code http.client.requests}, tagged with its host ({@code client.name}),
 * status and URI template. Spring Boot does this for the {@link RestClient.Builder}
 * it provides, but not for one started from {@link RestClient#builder()}, which
 * is why the registry is passed in here. The {@code uri} tag is the template
 * given to {@code uri(...)}, so a path must keep variables such as a slug as
 * template variables rather than concatenating them in: each distinct string
 * would become a series of its own. A client given a {@link java.net.URI} object
 * has no template, and is tagged {@code none}.
 */
@Configuration
@EnableConfigurationProperties(FetchLimits.class)
public class RestClientConfig {

    /**
     * Creates the singleton {@link RestClient} used for outbound HTTP calls.
     *
     * @param connectTimeout      {@code app.http.connect-timeout}
     * @param readTimeout         {@code app.http.read-timeout}
     * @param observationRegistry where each request is observed
     * @return a {@code RestClient} with those timeouts applied
     */
    @Bean
    public RestClient restClient(@Value("${app.http.connect-timeout}") Duration connectTimeout,
                                 @Value("${app.http.read-timeout}") Duration readTimeout,
                                 ObservationRegistry observationRegistry) {
        return withTimeouts(connectTimeout, readTimeout, observationRegistry);
    }

    /**
     * Builds a {@link RestClient} on the JDK HTTP client with the given timeouts.
     *
     * <p>Exposed for callers that need different limits from the shared client,
     * such as a local model whose answers take far longer than an API call.
     * Either timeout surfaces as a
     * {@link org.springframework.web.client.ResourceAccessException}.
     *
     * @param connectTimeout      how long to wait for a connection to open
     * @param readTimeout         how long to wait for a response once connected
     * @param observationRegistry where each request is observed
     * @return a new client
     */
    public static RestClient withTimeouts(Duration connectTimeout, Duration readTimeout,
                                          ObservationRegistry observationRegistry) {
        return builderWithTimeouts(connectTimeout, readTimeout, observationRegistry).build();
    }

    /**
     * Starts a {@link RestClient} on the JDK HTTP client with the given
     * timeouts, for callers that add more before building, such as an
     * interceptor.
     *
     * @param connectTimeout      how long to wait for a connection to open
     * @param readTimeout         how long to wait for a response once connected
     * @param observationRegistry where each request is observed
     * @return a builder with the timeouts applied
     */
    public static RestClient.Builder builderWithTimeouts(Duration connectTimeout, Duration readTimeout,
                                                         ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry);
    }
}
