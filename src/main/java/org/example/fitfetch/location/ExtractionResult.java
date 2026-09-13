package org.example.fitfetch.location;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of extracting one raw location label, together with whether that
 * outcome is worth remembering.
 *
 * <p>The {@code cacheable} flag exists because not every answer is
 * authoritative. A model returning well-formed, schema-conformant output has
 * told us something about the input, and repeating the call would spend another
 * second or two of local compute learning the same thing. A model that returned
 * malformed output twice running has told us only that it was having a bad time;
 * caching that would make a momentary failure permanent for that label, which is
 * the same trap as caching a geocoder's quota error as "no such place".
 *
 * @param locations the extracted expressions, never empty. A label the model
 *                  could make no sense of yields a single
 *                  {@link LocationKind#UNPARSEABLE} entry rather than an empty
 *                  list, so the job stays visible in the curation worklist
 *                  instead of vanishing
 * @param cacheable whether this outcome may be written to the interpretation
 *                  cache
 * @param tier      which tier produced it. Persisted as provenance on every
 *                  resulting location, and tracked as a distribution: a rising
 *                  {@link SourceTier#LLM} share means new boards are
 *                  introducing label shapes worth curating
 */
public record ExtractionResult(List<ExtractedLocation> locations, boolean cacheable, SourceTier tier) {

    public ExtractionResult {
        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(tier, "tier");
        if (locations.isEmpty()) {
            throw new IllegalArgumentException(
                    "locations must not be empty; use unparseable() or untrusted() instead");
        }
        locations = List.copyOf(locations);
    }

    /**
     * @param locations the extracted expressions
     * @return an authoritative result, safe to cache
     */
    public static ExtractionResult of(List<ExtractedLocation> locations) {
        return new ExtractionResult(locations, true, SourceTier.LLM);
    }

    /**
     * @param tier the tier that answered
     * @return a copy attributed to a different tier, used when a cache serves a
     *         result the model originally produced
     */
    public ExtractionResult withTier(SourceTier tier) {
        return new ExtractionResult(locations, cacheable, tier);
    }

    /**
     * The model answered properly, but nothing usable came back.
     *
     * <p>Cacheable: with a pinned temperature and seed the same label would
     * produce the same answer, so re-asking costs compute to learn nothing new.
     * The label surfaces in the curation worklist instead, which is where an
     * unrecognised shape should be dealt with.
     *
     * @param raw the label that could not be understood
     * @return a cacheable result carrying a single
     *         {@link LocationKind#UNPARSEABLE} entry
     */
    public static ExtractionResult unparseable(String raw) {
        return new ExtractionResult(
                List.of(ExtractedLocation.of(raw, LocationKind.UNPARSEABLE)), true, SourceTier.LLM);
    }

    /**
     * The model's output could not be trusted, so the label is treated as
     * unusable but deliberately not remembered as such.
     *
     * @param raw the label whose extraction produced unusable output
     * @return a non-cacheable result carrying a single
     *         {@link LocationKind#UNPARSEABLE} entry
     */
    public static ExtractionResult untrusted(String raw) {
        return new ExtractionResult(
                List.of(ExtractedLocation.of(raw, LocationKind.UNPARSEABLE)), false, SourceTier.LLM);
    }
}
