package org.example.fitfetch.fetching;

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
 */
@Configuration
@EnableConfigurationProperties(FetchLimits.class)
public class RestClientConfig {

    /**
     * Creates the singleton {@link RestClient} used for outbound HTTP calls.
     *
     * @param connectTimeout {@code app.http.connect-timeout}
     * @param readTimeout    {@code app.http.read-timeout}
     * @return a {@code RestClient} with those timeouts applied
     */
    @Bean
    public RestClient restClient(@Value("${app.http.connect-timeout}") Duration connectTimeout,
                                 @Value("${app.http.read-timeout}") Duration readTimeout) {
        return withTimeouts(connectTimeout, readTimeout);
    }

    /**
     * Builds a {@link RestClient} on the JDK HTTP client with the given timeouts.
     *
     * <p>Exposed for callers that need different limits from the shared client,
     * such as a local model whose answers take far longer than an API call.
     * Either timeout surfaces as a
     * {@link org.springframework.web.client.ResourceAccessException}.
     *
     * @param connectTimeout how long to wait for a connection to open
     * @param readTimeout    how long to wait for a response once connected
     * @return a new client
     */
    public static RestClient withTimeouts(Duration connectTimeout, Duration readTimeout) {
        return builderWithTimeouts(connectTimeout, readTimeout).build();
    }

    /**
     * Starts a {@link RestClient} on the JDK HTTP client with the given
     * timeouts, for callers that add more before building, such as an
     * interceptor.
     *
     * @param connectTimeout how long to wait for a connection to open
     * @param readTimeout    how long to wait for a response once connected
     * @return a builder with the timeouts applied
     */
    public static RestClient.Builder builderWithTimeouts(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
