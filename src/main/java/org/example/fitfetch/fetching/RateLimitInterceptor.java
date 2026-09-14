package org.example.fitfetch.fetching;

import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * Applies an {@link AtsRateLimiter} to every request made through one ATS's
 * {@link org.springframework.web.client.RestClient}.
 *
 * <p>Enforced per HTTP request rather than per slug because the provider
 * counts requests. A board that pages its results costs several, and every one
 * of them goes through here without the fetcher having to remember.
 *
 * <p>A 429 is passed back to the caller unchanged, after pausing the provider:
 * the caller decides whether to retry, and a retry waits out the pause on its
 * way through here.
 *
 * @see AtsRestClients
 */
public class RateLimitInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final AtsRateLimiter limiter;
    private final MetricService metricService;
    private final Clock clock;

    /**
     * @param limiter       the provider's limiter
     * @param metricService sink for the throttled counter
     * @param clock         time source, for an HTTP-date {@code Retry-After}
     */
    public RateLimitInterceptor(AtsRateLimiter limiter, MetricService metricService, Clock clock) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.metricService = Objects.requireNonNull(metricService, "metricService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting to send a request to " + limiter.ats().stringValue());
        }

        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            Duration pause = limiter.throttled(retryAfter(response.getHeaders(), clock.instant()));
            LOGGER.warn("{} answered 429 Too Many Requests for {}; pausing every request to it for {}s",
                    limiter.ats().stringValue(), request.getURI().getPath(), pause.toSeconds());
            metricService.recordCounter(MetricName.FETCH_THROTTLED_COUNT,
                    Map.of(TagName.ATS, limiter.ats().stringValue()));
        } else {
            limiter.succeeded();
        }
        return response;
    }

    /**
     * Reads a {@code Retry-After} header, which is either a number of seconds
     * or an HTTP date.
     *
     * @param headers the response headers
     * @param now     the current time, for a date
     * @return how long to wait; zero for a date already past; {@code null} if
     *         the header is missing or unreadable, so the backoff applies
     */
    static Duration retryAfter(HttpHeaders headers, Instant now) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            // Fall through to the date form.
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, at);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
