package org.example.fitfetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts the web server with the real {@code application.yaml} and checks the
 * actuator endpoints Prometheus relies on.
 *
 * <p>Only auto-configuration runs, with the database left out, so this needs no
 * Postgres: the point is the server and the endpoint exposure, not the passes.
 */
@SpringBootTest(classes = ActuatorEndpointTest.ActuatorOnly.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointTest {

    // @Configuration rather than @SpringBootConfiguration: a second
    // @SpringBootConfiguration in this package would stop FitFetchApplicationTests
    // from finding FitFetchApplication.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
    static class ActuatorOnly {
    }

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("Prometheus can scrape /actuator/prometheus, with the application tag on every series")
    void testPrometheusEndpointServed() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("jvm_memory_used_bytes"), "JVM metrics are published");
        assertTrue(response.body().contains("application=\"FitFetch\""), "management.metrics.tags applies");
    }

    @Test
    @DisplayName("The health endpoint answers")
    void testHealthEndpointServed() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"UP\""));
    }

    @Test
    @DisplayName("Endpoints beyond health and prometheus stay unexposed")
    void testOtherEndpointsNotExposed() throws Exception {
        // env and configprops would publish configuration, database password included.
        assertEquals(404, get("/actuator/env").statusCode());
        assertEquals(404, get("/actuator/configprops").statusCode());
    }
}
