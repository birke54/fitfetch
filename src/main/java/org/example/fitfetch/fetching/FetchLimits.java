package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-ATS fetch limits, from {@code app.fetch.limits}: how fast requests may
 * go, how many slugs may be fetched at once, and how to back off after a 429.
 *
 * <p>Every ATS gets {@link #defaults()} unless it has its own entry under
 * {@code ats}, and an entry need only set what differs: anything it leaves out
 * comes from the defaults.
 *
 * @param defaults the limits for any ATS without its own entry; every field
 *                 must be set
 * @param ats      overrides per provider, keyed by {@link AtsName} (case does
 *                 not matter in the YAML)
 * @see AtsRateLimiter
 * @see org.example.fitfetch.ats.SlugSweep
 */
@ConfigurationProperties("app.fetch.limits")
public record FetchLimits(Limit defaults, Map<AtsName, Limit> ats) {

    public FetchLimits {
        Objects.requireNonNull(defaults, "app.fetch.limits.defaults must be set");
        if (defaults.requestsPerSecond() == null || defaults.maxConcurrent() == null
                || defaults.maxWait() == null || defaults.initialBackoff() == null
                || defaults.maxBackoff() == null) {
            throw new IllegalArgumentException("app.fetch.limits.defaults must set every field, but was " + defaults);
        }
        ats = ats == null || ats.isEmpty() ? Map.of() : new EnumMap<>(ats);
    }

    /**
     * One provider's limits. In an override, any field left {@code null} comes
     * from the defaults.
     *
     * @param requestsPerSecond how many requests may start per second, spaced
     *                          evenly
     * @param maxConcurrent     how many slugs may be fetched at once. Only
     *                          useful up to the point where the rate is the
     *                          limit: more in flight than
     *                          {@code requests-per-second} times a response's
     *                          duration just queue for the rate limiter
     * @param maxWait           the longest a request may wait for its turn
     *                          before the ATS's fetch cycle is ended instead
     * @param initialBackoff    the pause after a 429 with no {@code Retry-After}
     * @param maxBackoff        the most that pause may double to
     */
    public record Limit(Double requestsPerSecond, Integer maxConcurrent, Duration maxWait,
                        Duration initialBackoff, Duration maxBackoff) {
    }

    /**
     * @param name the provider
     * @return its limits, with every field set
     */
    public Limit forAts(AtsName name) {
        Limit override = ats.get(name);
        if (override == null) {
            return defaults;
        }
        return new Limit(
                orDefault(override.requestsPerSecond(), defaults.requestsPerSecond()),
                orDefault(override.maxConcurrent(), defaults.maxConcurrent()),
                orDefault(override.maxWait(), defaults.maxWait()),
                orDefault(override.initialBackoff(), defaults.initialBackoff()),
                orDefault(override.maxBackoff(), defaults.maxBackoff()));
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
