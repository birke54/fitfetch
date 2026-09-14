package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AtsRateLimiterTest {

    /** Two per second, four at once, at most 60 s waiting, and a 5 s backoff doubling to 40 s. */
    static final FetchLimits.Limit LIMIT = new FetchLimits.Limit(
            2.0, 4, Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(40));

    private MutableClock clock;
    private List<Duration> sleeps;
    private AtsRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-13T12:00:00Z"));
        sleeps = new ArrayList<>();
        // Sleeping moves the clock on, as real time would.
        limiter = new AtsRateLimiter(AtsName.GREENHOUSE, LIMIT, clock, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });
    }

    private void acquire(int times) throws InterruptedException {
        for (int i = 0; i < times; i++) {
            limiter.acquire();
        }
    }

    // ------------------------------------------------------------------ rate

    @Test
    @DisplayName("Back-to-back requests are spaced evenly; the first goes at once")
    void testSpacing() throws InterruptedException {
        acquire(3);

        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(500)), sleeps);
    }

    @Test
    @DisplayName("A caller already slower than the rate never waits")
    void testSlowCallerNeverWaits() throws InterruptedException {
        limiter.acquire();
        clock.advance(Duration.ofSeconds(1));
        limiter.acquire();

        assertTrue(sleeps.isEmpty());
    }

    @Test
    @DisplayName("Idle time does not bank a burst: after a gap, requests are still spaced")
    void testNoBurstAfterIdle() throws InterruptedException {
        limiter.acquire();
        clock.advance(Duration.ofMinutes(10));
        acquire(2);

        assertEquals(List.of(Duration.ofMillis(500)), sleeps);
    }

    // --------------------------------------------------------------- backoff

    @Test
    @DisplayName("A 429 with Retry-After holds every request back until it has passed")
    void testRetryAfterPauses() throws InterruptedException {
        limiter.acquire();

        assertEquals(Duration.ofSeconds(30), limiter.throttled(Duration.ofSeconds(30)));
        limiter.acquire();

        assertEquals(List.of(Duration.ofSeconds(30)), sleeps);
    }

    /** A 429 with no Retry-After, arriving once any current pause is over. */
    private Duration throttledAfterPause() {
        Duration pause = limiter.throttled(null);
        clock.advance(pause);
        return pause;
    }

    @Test
    @DisplayName("Without Retry-After the pause starts at the initial backoff and doubles, up to the maximum")
    void testExponentialBackoff() {
        assertEquals(Duration.ofSeconds(5), throttledAfterPause());
        assertEquals(Duration.ofSeconds(10), throttledAfterPause());
        assertEquals(Duration.ofSeconds(20), throttledAfterPause());
        assertEquals(Duration.ofSeconds(40), throttledAfterPause());
        assertEquals(Duration.ofSeconds(40), throttledAfterPause(), "capped at max-backoff");
    }

    @Test
    @DisplayName("A response that is not a 429 resets the backoff")
    void testSuccessResetsBackoff() {
        throttledAfterPause();
        throttledAfterPause();

        limiter.succeeded();

        assertEquals(Duration.ofSeconds(5), throttledAfterPause());
    }

    @Test
    @DisplayName("A Retry-After does not advance the backoff used when one is missing")
    void testRetryAfterLeavesBackoffAlone() {
        limiter.throttled(Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(5), limiter.throttled(null));
    }

    // ----------------------------------------------------------- concurrency

    @Test
    @DisplayName("429s from requests sent before the pause began do not double the backoff again")
    void testThrottlesDuringPauseDoNotEscalate() {
        // Several requests in flight when the provider starts throttling all come
        // back 429; that is one signal, not several.
        assertEquals(Duration.ofSeconds(5), limiter.throttled(null));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(4), limiter.throttled(null), "the rest of the same pause");
        clock.advance(Duration.ofSeconds(4));

        assertEquals(Duration.ofSeconds(10), limiter.throttled(null), "a 429 after the pause escalates");
    }

    @Test
    @DisplayName("A request already waiting for its turn when a 429 arrives waits out the pause too")
    void testWaitingRequestHonoursNewPause() throws InterruptedException {
        AtomicReference<AtsRateLimiter> self = new AtomicReference<>();
        AtomicBoolean throttled = new AtomicBoolean();
        self.set(new AtsRateLimiter(AtsName.GREENHOUSE, LIMIT, clock, duration -> {
            sleeps.add(duration);
            // Another thread's request gets a 429 while this one sleeps.
            if (throttled.compareAndSet(false, true)) {
                self.get().throttled(Duration.ofSeconds(30));
            }
            clock.advance(duration);
        }));

        self.get().acquire();
        self.get().acquire();

        // It slept towards its slot 500 ms out, then found the pause that began
        // meanwhile and slept until it ended, 30 s after it began.
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(29_500)), sleeps);
    }

    @Test
    @DisplayName("A shorter pause never cuts an existing one short")
    void testShorterPauseDoesNotShorten() throws InterruptedException {
        limiter.throttled(Duration.ofSeconds(30));
        limiter.throttled(Duration.ofSeconds(5));

        limiter.acquire();

        assertEquals(List.of(Duration.ofSeconds(30)), sleeps);
    }

    // -------------------------------------------------------------- giving up

    @Test
    @DisplayName("A request that would wait past max-wait is refused, and takes no slot")
    void testLongPauseRefused() throws InterruptedException {
        limiter.throttled(Duration.ofMinutes(10));

        AtsThrottledException refused = assertThrows(AtsThrottledException.class, limiter::acquire);
        assertEquals(AtsName.GREENHOUSE, refused.ats());
        assertEquals(Duration.ofMinutes(10), refused.waitRequired());
        assertTrue(sleeps.isEmpty(), "it does not wait at all");

        // Once the pause is over, requests go again without a queue of refused
        // slots ahead of them.
        clock.advance(Duration.ofMinutes(10));
        limiter.acquire();
        assertTrue(sleeps.isEmpty());
    }

    // ------------------------------------------------------------- validation

    @Test
    @DisplayName("Limits that could never let a request through are rejected at startup")
    void testInvalidLimitsRejected() {
        Clock fixed = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        AtsRateLimiter.Sleeper noSleep = duration -> { };

        assertThrows(IllegalArgumentException.class, () -> new AtsRateLimiter(AtsName.GREENHOUSE,
                new FetchLimits.Limit(0.0, 4, Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(40)),
                fixed, noSleep));
        assertThrows(IllegalArgumentException.class, () -> new AtsRateLimiter(AtsName.GREENHOUSE,
                new FetchLimits.Limit(0.01, 4, Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(40)),
                fixed, noSleep), "one request per 100 s can never fit a 60 s max-wait");
        assertThrows(IllegalArgumentException.class, () -> new AtsRateLimiter(AtsName.GREENHOUSE,
                new FetchLimits.Limit(2.0, 4, Duration.ofSeconds(60), Duration.ofSeconds(50), Duration.ofSeconds(40)),
                fixed, noSleep), "max-backoff below initial-backoff");
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
