package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Paces the requests sent to one ATS provider, and holds them all back when the
 * provider answers {@code 429 Too Many Requests}.
 *
 * <p><strong>Rate.</strong> Requests are spaced evenly, one every
 * {@code 1 / requests-per-second}, rather than allowed in bursts: a provider sees
 * a steady trickle whatever the caller does. A caller already slower than the
 * rate never waits.
 *
 * <p><strong>Backoff.</strong> A 429 pauses every request to the provider, not
 * just the one that got it, since the provider is throttling the client as a
 * whole. That includes requests already waiting for their turn when it arrives.
 * The pause lasts as long as the response's {@code Retry-After} asks. Without
 * one it starts at {@code initial-backoff} and doubles with each 429 in a row,
 * up to {@code max-backoff}; any other response resets it.
 *
 * <p><strong>Giving up.</strong> A request that would wait longer than
 * {@code max-wait} throws {@link AtsThrottledException} instead of waiting, and
 * takes no slot. In practice only a pause can cause that, and it ends the ATS's
 * fetch cycle early rather than holding the run for an hour on a long
 * {@code Retry-After}.
 *
 * <p>State is held in memory, per provider, so it is right for a single
 * instance; a restart forgets any pause. Instances are thread-safe.
 */
public final class AtsRateLimiter {

    /** How a waiting request passes the time; injectable so tests need not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Sleeps the calling thread, which is cheap on a virtual thread. */
    public static final Sleeper THREAD_SLEEP = Thread::sleep;

    private final AtsName ats;
    private final Duration interval;
    private final Duration maxWait;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Clock clock;
    private final Sleeper sleeper;

    /** The earliest the next request may go. Guarded by {@code this}. */
    private Instant nextSlot = Instant.MIN;

    /** No request may go before this. Guarded by {@code this}. */
    private Instant pausedUntil = Instant.MIN;

    /** The pause the next 429 without {@code Retry-After} gets. Guarded by {@code this}. */
    private Duration backoff;

    /**
     * How many times a pause has begun or been extended, so a waiting request
     * can tell whether one started while it waited. Guarded by {@code this}.
     */
    private long pauses;

    /**
     * @param ats     the provider this limits
     * @param limit   its limits, fully resolved
     * @param clock   time source
     * @param sleeper how a waiting request waits
     */
    public AtsRateLimiter(AtsName ats, FetchLimits.Limit limit, Clock clock, Sleeper sleeper) {
        this.ats = Objects.requireNonNull(ats, "ats");
        Objects.requireNonNull(limit, "limit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");

        double perSecond = limit.requestsPerSecond();
        if (!(perSecond > 0) || Double.isInfinite(perSecond)) {
            throw new IllegalArgumentException(ats + ": requests-per-second must be positive, but was " + perSecond);
        }
        this.interval = Duration.ofNanos(Math.round(1_000_000_000d / perSecond));
        this.maxWait = limit.maxWait();
        this.initialBackoff = limit.initialBackoff();
        this.maxBackoff = limit.maxBackoff();
        if (interval.compareTo(maxWait) > 0) {
            // Every request after the first would be refused.
            throw new IllegalArgumentException(ats + ": max-wait " + maxWait
                    + " is shorter than the gap between requests, " + interval);
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(ats + ": backoff must be positive with max-backoff at least "
                    + "initial-backoff, but was " + initialBackoff + " to " + maxBackoff);
        }
        this.backoff = initialBackoff;
    }

    /**
     * Waits until this request may go, and takes its slot.
     *
     * @throws AtsThrottledException if that would mean waiting longer than
     *                               {@code max-wait}; no slot is taken
     * @throws InterruptedException  if interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        while (true) {
            Duration wait;
            long pausesBefore;
            synchronized (this) {
                Instant now = clock.instant();
                Instant slot = latest(now, nextSlot, pausedUntil);
                wait = Duration.between(now, slot);
                if (wait.compareTo(maxWait) > 0) {
                    throw new AtsThrottledException(ats, wait);
                }
                nextSlot = slot.plus(interval);
                pausesBefore = pauses;
            }
            if (wait.isPositive()) {
                sleeper.sleep(wait);
            }
            synchronized (this) {
                if (pauses == pausesBefore) {
                    return;
                }
            }
            // Another request got a 429 while this one waited for its turn. Its
            // slot was taken before that pause began, so going now would send
            // it straight into the throttle; wait the pause out instead.
        }
    }

    /**
     * Records a 429 and pauses every request to the provider.
     *
     * <p>A 429 that arrives while the provider is already paused came from a
     * request sent before the pause began. It says nothing new, so it does not
     * double the backoff again; only a {@code Retry-After} can extend the pause.
     *
     * @param retryAfter how long the provider asked for, or {@code null} if it
     *                   did not say
     * @return how long requests are now paused for
     */
    public synchronized Duration throttled(Duration retryAfter) {
        Instant now = clock.instant();
        boolean alreadyPaused = now.isBefore(pausedUntil);
        Duration pause;
        if (retryAfter != null) {
            pause = retryAfter;
        } else if (alreadyPaused) {
            pause = Duration.ZERO;
        } else {
            pause = backoff;
            Duration doubled = backoff.multipliedBy(2);
            backoff = doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
        }
        Instant until = now.plus(pause);
        // A shorter pause never cuts an existing one short.
        if (until.isAfter(pausedUntil)) {
            pausedUntil = until;
            pauses++;
        }
        return Duration.between(now, pausedUntil);
    }

    /** Records a response that was not a 429, which resets the backoff. */
    public synchronized void succeeded() {
        backoff = initialBackoff;
    }

    /** @return the provider this limits */
    public AtsName ats() {
        return ats;
    }

    private static Instant latest(Instant first, Instant... rest) {
        Instant latest = first;
        for (Instant candidate : rest) {
            if (candidate.isAfter(latest)) {
                latest = candidate;
            }
        }
        return latest;
    }
}
