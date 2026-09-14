package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RateLimitInterceptorTest {

    private static final String URL = "https://boards-api.greenhouse.io/v1/boards/acme/jobs";
    private static final Instant START = Instant.parse("2026-09-13T12:00:00Z");

    private AtsRateLimiterTest.MutableClock clock;
    private List<Duration> sleeps;
    private MetricService metricService;
    private MockRestServiceServer mockServer;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        clock = new AtsRateLimiterTest.MutableClock(START);
        sleeps = new ArrayList<>();
        AtsRateLimiter limiter = new AtsRateLimiter(AtsName.GREENHOUSE, AtsRateLimiterTest.LIMIT, clock,
                duration -> {
                    sleeps.add(duration);
                    clock.advance(duration);
                });
        metricService = mock(MetricService.class);

        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new RateLimitInterceptor(limiter, metricService, clock));
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private String get() {
        return restClient.get().uri(URL).retrieve().body(String.class);
    }

    private void respond(HttpStatus status, HttpHeaders headers) {
        mockServer.expect(once(), requestTo(URL)).andRespond(withStatus(status).headers(headers));
    }

    private static HttpHeaders retryAfter(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, value);
        return headers;
    }

    @Test
    @DisplayName("Every request through the client is paced by the limiter")
    void testRequestsArePaced() {
        mockServer.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo(URL))
                .andRespond(withSuccess());

        get();
        get();
        get();

        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(500)), sleeps);
        mockServer.verify();
    }

    @Test
    @DisplayName("A 429 reaches the caller unchanged, pauses the ATS for its Retry-After, and is counted")
    void testTooManyRequestsPauses() {
        respond(HttpStatus.TOO_MANY_REQUESTS, retryAfter("30"));
        respond(HttpStatus.OK, new HttpHeaders());

        assertThrows(HttpClientErrorException.TooManyRequests.class, this::get);
        get();

        assertEquals(List.of(Duration.ofSeconds(30)), sleeps, "the next request waited out the pause");
        verify(metricService).recordCounter(MetricName.FETCH_THROTTLED_COUNT,
                Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue()));
        mockServer.verify();
    }

    @Test
    @DisplayName("Without Retry-After a 429 backs off, and a good response resets the backoff")
    void testBackoffWithoutRetryAfter() {
        respond(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders());
        respond(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders());
        respond(HttpStatus.OK, new HttpHeaders());
        respond(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders());
        respond(HttpStatus.OK, new HttpHeaders());

        assertThrows(HttpClientErrorException.class, this::get);
        assertThrows(HttpClientErrorException.class, this::get);
        get();
        assertThrows(HttpClientErrorException.class, this::get);
        get();

        // 5 s, then doubled to 10 s; the good response resets it, so the next
        // 429 pauses for 5 s again. The 500 ms is the ordinary gap before the
        // request that followed the good one.
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(500),
                Duration.ofSeconds(5)), sleeps);
    }

    @Test
    @DisplayName("A pause past max-wait surfaces as AtsThrottledException, unwrapped, and sends nothing")
    void testLongPauseStopsTheCaller() {
        respond(HttpStatus.TOO_MANY_REQUESTS, retryAfter("600"));

        assertThrows(HttpClientErrorException.class, this::get);

        // Not a RestClientException, so an ATS's per-slug handler cannot
        // swallow it and race on through every remaining slug.
        assertThrows(AtsThrottledException.class, this::get);
        mockServer.verify();
    }

    @Test
    @DisplayName("Retry-After is read as seconds or as an HTTP date")
    void testRetryAfterParsing() {
        assertEquals(Duration.ofSeconds(120), RateLimitInterceptor.retryAfter(retryAfter(" 120 "), START));
        assertEquals(Duration.ofSeconds(90),
                RateLimitInterceptor.retryAfter(retryAfter("Sun, 13 Sep 2026 12:01:30 GMT"), START));
        assertEquals(Duration.ZERO,
                RateLimitInterceptor.retryAfter(retryAfter("Sun, 13 Sep 2026 11:00:00 GMT"), START),
                "a date already past means no wait");
        assertNull(RateLimitInterceptor.retryAfter(new HttpHeaders(), START));
        assertNull(RateLimitInterceptor.retryAfter(retryAfter("-5"), START));
        assertNull(RateLimitInterceptor.retryAfter(retryAfter("soon"), START));
    }
}
