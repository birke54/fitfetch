package org.example.fitfetch.fetching;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RestClientConfigTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/fast", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // Accepts the connection and then says nothing, like a hung board or model.
        server.createContext("/hang", exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
    }

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Test
    @DisplayName("A server that never answers fails after the read timeout instead of hanging")
    void readTimeoutFires() {
        RestClient client = RestClientConfig.withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(300),
                ObservationRegistry.NOOP);

        long start = System.nanoTime();
        assertThrows(ResourceAccessException.class,
                () -> client.get().uri(url("/hang")).retrieve().body(String.class));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis < 5_000, "gave up after " + elapsedMillis + " ms, not the 300 ms timeout");
    }

    @Test
    @DisplayName("A prompt answer is unaffected by the timeouts")
    void fastResponseSucceeds() {
        RestClient client = RestClientConfig.withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2),
                ObservationRegistry.NOOP);

        assertEquals("ok", client.get().uri(url("/fast")).retrieve().body(String.class));
    }

    @Test
    @DisplayName("Every request through a built client is timed as http.client.requests, timeouts included")
    void requestsAreObserved() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        RestClient client = RestClientConfig.withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(300), observations);

        client.get().uri(url("/fast")).retrieve().body(String.class);
        assertThrows(ResourceAccessException.class,
                () -> client.get().uri(url("/hang")).retrieve().body(String.class));

        long total = meters.get("http.client.requests").timers().stream().mapToLong(Timer::count).sum();
        assertEquals(2, total, "the timed-out request is recorded too");
        assertEquals(1, meters.get("http.client.requests").tag("outcome", "SUCCESS").timer().count());
    }
}
