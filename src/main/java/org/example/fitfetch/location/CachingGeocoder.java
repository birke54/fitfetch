package org.example.fitfetch.location;

import org.example.fitfetch.domain.GeocodeCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Geocoder} that answers from the cache before spending a call.
 *
 * <p>This is where the economics of the pipeline live. Across the sampled
 * boards, 112 curated labels collapse to 57 distinct queries, and the search
 * origin alone accounts for a quarter of all jobs; once those are resolved, a
 * whole pass can run without a single outbound request.
 *
 * <p>Negative results are cached too. Without that, a string naming no place
 * costs a call every cycle, forever &mdash; which is the same money as a useful
 * lookup, spent to re-learn nothing. They are pruned on a separate short
 * schedule rather than refreshed, since a dead string does not come back to life.
 *
 * <p>Successful lookups are refreshed on access once older than the configured
 * window: a stale entry is treated as a miss and looked up again. Refreshing on
 * read rather than on a schedule is self-limiting, because only entries actually
 * being used cost anything, and the long tail of places nobody searches near
 * never costs a penny.
 *
 * <p>Failures are never cached, and cannot be: {@link GeocodeStatus} models only
 * durable outcomes, so a quota breach arrives as a {@link GeocodingException}
 * and propagates to the caller untouched.
 *
 * <p>Instances are immutable and safe to share; the underlying repository
 * handles its own concurrency.
 */
public class CachingGeocoder implements Geocoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachingGeocoder.class);

    private final Geocoder delegate;
    private final GeocodeCacheRepository repository;
    private final Duration coordinateTtl;
    private final Duration hitGranularity;
    private final boolean lookupEnabled;
    private final Clock clock;

    /**
     * @param delegate       the geocoder to fall through to on a miss
     * @param repository     the geocode cache
     * @param coordinateTtl  how long a successful lookup stays usable before it
     *                       is refreshed on next access
     * @param hitGranularity how stale a row's last-read timestamp must be before
     *                       it is worth updating
     * @param lookupEnabled  whether misses may call the delegate at all. Setting
     *                       this false gives a cache-only mode: the pass runs,
     *                       resolves whatever is already known, and spends
     *                       nothing. That makes local runs and tests free and
     *                       hermetic, and lets the cache be warmed deliberately
     *                       before any budget is committed
     * @param clock          time source; injectable so expiry is testable
     */
    public CachingGeocoder(Geocoder delegate,
                           GeocodeCacheRepository repository,
                           Duration coordinateTtl,
                           Duration hitGranularity,
                           boolean lookupEnabled,
                           Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.coordinateTtl = Objects.requireNonNull(coordinateTtl, "coordinateTtl");
        this.hitGranularity = Objects.requireNonNull(hitGranularity, "hitGranularity");
        this.lookupEnabled = lookupEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public GeocodeOutcome geocode(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        String key = LocationKey.normalize(query);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<GeocodeCacheEntry> cached = repository.findById(key);

        if (cached.isPresent()) {
            GeocodeCacheEntry entry = cached.get();
            if (!entry.isStale(coordinateTtl, now)) {
                recordHit(entry, now);
                return entry.toOutcome();
            }
            LOGGER.debug("Refreshing stale coordinates for '{}'", query);
        }

        if (!lookupEnabled) {
            // Cache-only mode. Report a miss as a durable "no result" for this
            // pass without writing it down, so enabling lookups later resolves
            // it properly rather than finding a poisoned negative entry.
            LOGGER.debug("Geocoding disabled; '{}' left unresolved", query);
            return GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS);
        }

        // Propagates GeocodingException. A quota breach or denied key must reach
        // the caller intact so the pass can back off or stop, and must never be
        // written to the cache as a verdict about this place.
        GeocodeOutcome outcome = delegate.geocode(query);

        GeocodeCacheEntry entry = cached.orElse(null);
        if (entry == null) {
            repository.save(new GeocodeCacheEntry(key, outcome, now));
        } else {
            entry.apply(outcome, now);
            repository.save(entry);
        }
        return outcome;
    }

    private void recordHit(GeocodeCacheEntry entry, OffsetDateTime now) {
        if (entry.getLastHitAt() == null || entry.getLastHitAt().plus(hitGranularity).isBefore(now)) {
            entry.recordHit(now);
            repository.save(entry);
        }
    }
}
