package org.example.fitfetch;

import org.example.fitfetch.fetching.RestClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks, in the {@code /actuator/prometheus} output itself, the metrics that
 * come from Spring rather than from the app's own code: scheduled pass timings,
 * log event counts, and outbound request timings from the clients
 * {@link RestClientConfig} builds.
 */
@SpringBootTest(classes = {ActuatorEndpointTest.ActuatorOnly.class, ProvidedMetricsTest.Extras.class,
        RestClientConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProvidedMetricsTest {

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Extras {
        @Bean
        Pass pass() {
            return new Pass();
        }
    }

    /** Stands in for a scheduled pass such as the location pass. */
    static class Pass {
        final CountDownLatch ran = new CountDownLatch(1);

        @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
        void resolvePendingLocations() {
            ran.countDown();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private Pass pass;

    @Autowired
    private RestClient restClient;

    private String scrape() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return response.body();
        }
    }

    /** Scrapes until the text appears, since some are recorded just after the work they time. */
    private String scrapeUntil(String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String body = scrape();
        while (!body.contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            body = scrape();
        }
        return body;
    }

    @Test
    @DisplayName("Each scheduled pass is timed as tasks.scheduled.execution, named by its method")
    void testScheduledPassesTimed() throws Exception {
        assertTrue(pass.ran.await(5, TimeUnit.SECONDS), "the scheduled method ran");

        String body = scrapeUntil("code_function=\"resolvePendingLocations\"");

        assertTrue(body.contains("tasks_scheduled_execution_seconds_count"), body);
        assertTrue(body.contains("code_function=\"resolvePendingLocations\""), body);
    }

    @Test
    @DisplayName("Log lines are counted by level as logback.events")
    void testLogEventsCounted() throws Exception {
        assertTrue(scrape().contains("logback_events_total{"));
    }

    @Test
    @DisplayName("Requests through the shared client are timed as http.client.requests, with histogram buckets")
    void testOutboundRequestsTimed() throws Exception {
        restClient.get().uri("http://localhost:" + port + "/actuator/health").retrieve().toBodilessEntity();

        String body = scrapeUntil("http_client_requests_seconds_count");

        assertTrue(body.contains("http_client_requests_seconds_count"), body);
        assertTrue(body.contains("uri=\"/actuator/health\""), "tagged with the path template");
        assertTrue(body.contains("http_client_requests_seconds_bucket"), "percentile histogram published");
    }
}
