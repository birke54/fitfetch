package org.example.fitfetch.fetching;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration that exposes the application's shared {@link RestClient}.
 *
 * <p>The bean is built with framework defaults and injected into the ATS
 * fetchers (for example {@link GreenhouseFetch}).
 */
@Configuration
public class RestClientConfig {

    /**
     * Creates the singleton {@link RestClient} used for outbound HTTP calls.
     *
     * @return a {@code RestClient} configured with default settings
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
    }
}