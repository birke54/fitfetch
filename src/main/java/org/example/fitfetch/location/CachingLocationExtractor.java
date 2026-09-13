package org.example.fitfetch.location;

import org.example.fitfetch.domain.LocationInterpretation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link LocationExtractor} that remembers what the model already told it.
 *
 * <p>Sits between the hand-curated table and the model itself. Labels the
 * curated table answers never reach here at all; what does reach here is the
 * long tail, where most labels appear exactly once and a cache hit is the
 * difference between a map lookup and a second or two of local inference.
 *
 * <p>What gets written is governed by {@link ExtractionResult#cacheable()}.
 * Output the model produced properly is remembered, including a well-formed
 * "nothing found", since with pinned sampling asking again would produce the
 * same answer. Output that could not be trusted is deliberately forgotten, so a
 * bad moment does not become a permanent verdict about a label.
 *
 * <p>Cached rows carry the model tag and prompt version that produced them. A
 * model or prompt change is applied lazily rather than by bulk invalidation:
 * rows from another model or below the current version are treated as misses,
 * so labels still in circulation are re-extracted while dead tail entries age
 * out through eviction without ever costing a call.
 *
 * <p>Instances are immutable and safe to share; the underlying repository
 * handles its own concurrency.
 */
public class CachingLocationExtractor implements LocationExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachingLocationExtractor.class);

    private final LocationExtractor delegate;
    private final LocationInterpretationRepository repository;
    private final String model;
    private final int promptVersion;
    private final Duration hitGranularity;
    private final Clock clock;

    /**
     * @param delegate       the extractor to fall through to on a miss
     * @param repository     the interpretation cache
     * @param model          the model tag in force; recorded against new rows,
     *                       and rows from any other model are treated as misses
     * @param promptVersion  the prompt version in force; rows below it are
     *                       treated as misses
     * @param hitGranularity how stale a row's last-read timestamp must be before
     *                       it is worth updating. Read statistics drive eviction,
     *                       so every read implies a write; coarsening to a day
     *                       cuts that by orders of magnitude while leaving
     *                       eviction order perfectly usable
     * @param clock          time source; injectable so expiry is testable
     */
    public CachingLocationExtractor(LocationExtractor delegate,
                                    LocationInterpretationRepository repository,
                                    String model,
                                    int promptVersion,
                                    Duration hitGranularity,
                                    Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.model = Objects.requireNonNull(model, "model");
        this.promptVersion = promptVersion;
        this.hitGranularity = Objects.requireNonNull(hitGranularity, "hitGranularity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExtractionResult extract(String rawLocationName) {
        Objects.requireNonNull(rawLocationName, "rawLocationName");
        String key = LocationKey.normalize(rawLocationName);
        if (key.isEmpty()) {
            // Nothing to key on, and nothing worth asking a model about.
            // LocationResolver answers blank labels itself, under rule 4, so
            // this only guards other callers.
            return ExtractionResult.unparseable(rawLocationName);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<LocationInterpretation> cached = repository.findById(key);

        if (cached.isPresent()) {
            LocationInterpretation entry = cached.get();
            if (!entry.isStale(model, promptVersion)) {
                recordHit(entry, now);
                return ExtractionResult.of(entry.getOutputs())
                        .withTier(SourceTier.INTERPRETATION);
            }
            LOGGER.debug("Re-extracting '{}': cached by {} at prompt v{}, current is {} at v{}",
                    rawLocationName, entry.getModel(), entry.getPromptVersion(), model, promptVersion);
        }

        // Propagates LocationExtractionException, which is the point: a transport
        // failure must leave the job pending rather than be written down.
        ExtractionResult result = delegate.extract(rawLocationName);

        if (result.cacheable()) {
            LocationInterpretation entry = cached
                    .map(existing -> refresh(existing, result, now))
                    .orElseGet(() -> new LocationInterpretation(
                            key, rawLocationName, result.locations(), model, promptVersion, now));
            repository.save(entry);
        } else {
            LOGGER.debug("Not caching untrusted extraction for '{}'", rawLocationName);
        }
        return result;
    }

    private LocationInterpretation refresh(LocationInterpretation entry, ExtractionResult result,
                                           OffsetDateTime now) {
        entry.setOutputs(result.locations());
        entry.setModel(model);
        entry.setPromptVersion(promptVersion);
        entry.setInterpretedAt(now);
        return entry;
    }

    private void recordHit(LocationInterpretation entry, OffsetDateTime now) {
        // Only touch the row once its last-read timestamp is genuinely old. A
        // write on every read would turn a read-heavy cache into a write-heavy
        // one for no benefit to eviction ordering.
        if (entry.getLastHitAt() == null || entry.getLastHitAt().plus(hitGranularity).isBefore(now)) {
            entry.recordHit(now);
            repository.save(entry);
        }
    }
}
