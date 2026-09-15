package org.example.fitfetch.location;

import org.example.fitfetch.domain.GeocodeCacheEntry;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
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
    private final MetricService metricService;

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
     *                       before any budget is committed. A miss in this mode
     *                       throws {@link GeocodingDisabledException}
     * @param clock          time source; injectable so expiry is testable
     * @param metricService  where each lookup's cache result is counted
     */
    public CachingGeocoder(Geocoder delegate,
                           GeocodeCacheRepository repository,
                           Duration coordinateTtl,
                           Duration hitGranularity,
                           boolean lookupEnabled,
                           Clock clock,
                           MetricService metricService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.coordinateTtl = Objects.requireNonNull(coordinateTtl, "coordinateTtl");
        this.hitGranularity = Objects.requireNonNull(hitGranularity, "hitGranularity");
        this.lookupEnabled = lookupEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metricService = Objects.requireNonNull(metricService, "metricService");
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
            // In cache-only mode a stale coordinate is still served: it cannot be
            // refreshed, and a place that has not moved beats no answer at all.
            if (!lookupEnabled || !entry.isStale(coordinateTtl, now)) {
                recordHit(entry, now);
                recordResult("hit");
                return entry.toOutcome();
            }
            LOGGER.debug("Refreshing stale coordinates for '{}'", query);
            recordResult("stale_refresh");
        } else {
            recordResult(lookupEnabled ? "miss" : "disabled_miss");
        }

        if (!lookupEnabled) {
            // Cache-only mode. A miss is not an answer about the query, so it is
            // neither written down nor reported as ZERO_RESULTS; either would mark
            // the job unresolvable when it only needs lookups switched on.
            LOGGER.debug("Geocoding disabled; '{}' left unresolved", query);
            throw new GeocodingDisabledException(query);
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

    private void recordResult(String result) {
        metricService.recordCounter(MetricName.LOCATION_GEOCODE_CACHE_COUNT, Map.of(TagName.RESULT, result));
    }

    private void recordHit(GeocodeCacheEntry entry, OffsetDateTime now) {
        if (entry.getLastHitAt() == null || entry.getLastHitAt().plus(hitGranularity).isBefore(now)) {
            entry.recordHit(now);
            repository.save(entry);
        }
    }
}
