package org.example.fitfetch.location;

import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves one raw location label all the way to coordinates.
 *
 * <p>Chains the three tiers and then the geocoder:
 *
 * <pre>
 *   curated table  -&gt;  interpretation cache  -&gt;  model
 *                              |
 *                          policy switch
 *                              |
 *                     geocode cache  -&gt;  geocoder
 * </pre>
 *
 * <p>The curated table short-circuits everything below it, because its entries
 * are already resolved: a hand-checked label carries its own answer, so there is
 * nothing for the model or the policy switch to decide. That is what keeps
 * roughly four fifths of jobs off the model entirely.
 *
 * <p>Both failure modes propagate rather than being swallowed here.
 * {@link LocationExtractionException} and {@link GeocodingException} mean the
 * job must be left pending and retried, and only the caller knows how to record
 * that.
 *
 * <p>Instances are immutable and safe to share.
 */
public class LocationResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationResolver.class);

    private final CuratedLocations curated;
    private final LocationExtractor extractor;
    private final LocationPolicy policy;
    private final Geocoder geocoder;
    private final MetricService metricService;
    private final int maxLocationsPerJob;

    /**
     * @param curated            the hand-curated exact-match table
     * @param extractor          the extraction chain, cache included
     * @param policy             the rules that turn an extraction into a
     *                           resolved location
     * @param geocoder           the geocoding chain, cache included
     * @param metricService      where each resolved label's tier is counted
     * @param maxLocationsPerJob a sanity cap on fan-out. One real posting
     *                           enumerates remote eligibility across fifteen
     *                           states; the cap stops a pathological label from
     *                           filling the table
     */
    public LocationResolver(CuratedLocations curated,
                            LocationExtractor extractor,
                            LocationPolicy policy,
                            Geocoder geocoder,
                            MetricService metricService,
                            int maxLocationsPerJob) {
        this.curated = Objects.requireNonNull(curated, "curated");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.geocoder = Objects.requireNonNull(geocoder, "geocoder");
        this.metricService = Objects.requireNonNull(metricService, "metricService");
        if (maxLocationsPerJob < 1) {
            throw new IllegalArgumentException("maxLocationsPerJob must be at least 1");
        }
        this.maxLocationsPerJob = maxLocationsPerJob;
    }

    /**
     * Resolves a label to its locations and their coordinates.
     *
     * @param rawLocationName the verbatim label; may be {@code null} or blank,
     *                        which resolves to the search origin rather than
     *                        being discarded
     * @return one entry per location named, never empty
     * @throws LocationExtractionException if the model was unreachable
     * @throws GeocodingException          if geocoding failed for reasons
     *                                     unrelated to the query, including
     *                                     {@link GeocodingDisabledException}
     *                                     when a query was not cached and
     *                                     lookups are switched off
     */
    public List<ResolvedLocation> resolve(String rawLocationName) {
        String raw = rawLocationName == null ? "" : rawLocationName;

        Resolved resolved = resolveInputs(raw);
        List<LocationInput> inputs = resolved.inputs();

        if (inputs.size() > maxLocationsPerJob) {
            LOGGER.warn("Capping '{}' at {} locations, {} were named",
                    raw, maxLocationsPerJob, inputs.size());
            inputs = inputs.subList(0, maxLocationsPerJob);
        }

        List<ResolvedLocation> out = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            LocationInput input = inputs.get(i);
            // The first location named is the one to lead with in display. It
            // drives presentation only -- matching considers every row.
            out.add(new ResolvedLocation(input, geocode(input), resolved.tier(), i == 0));
        }
        // Counted only once every location is geocoded: a label that fails here
        // is retried, and would otherwise be counted again on the next pass.
        metricService.recordCounter(MetricName.LOCATION_LABELS_RESOLVED_COUNT,
                Map.of(TagName.TIER, resolved.tier().name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private Resolved resolveInputs(String raw) {
        if (LocationKey.normalize(raw).isEmpty()) {
            // Rule 4: a posting with no location resolves to the search origin.
            // The curated table cannot hold an empty key, and the extractor would
            // report it as UNPARSEABLE, so the rule is applied here. Like a
            // curated entry it is decided by code rather than a model, hence the
            // CURATED tier.
            LocationInput origin = policy.apply(ExtractedLocation.of(raw, LocationKind.SENTINEL));
            return new Resolved(List.of(origin), SourceTier.CURATED);
        }
        return curated.lookup(raw)
                .map(inputs -> new Resolved(inputs, SourceTier.CURATED))
                .orElseGet(() -> {
                    ExtractionResult extraction = extractor.extract(raw);
                    List<LocationInput> inputs = extraction.locations().stream()
                            .map(policy::apply)
                            .toList();
                    return new Resolved(inputs, extraction.tier());
                });
    }

    private GeocodeOutcome geocode(LocationInput input) {
        if (input.geocodeQuery() == null) {
            // UNDEFINED: nothing to look up, and nothing that could ever match.
            return null;
        }
        GeocodeOutcome outcome = geocoder.geocode(input.geocodeQuery());
        if (!outcome.status().hasCoordinates()) {
            // The geocoder knows this query names nowhere. The row is still kept,
            // as UNDEFINED and without coordinates, so the job stays visible in
            // the curation worklist rather than disappearing.
            LOGGER.debug("Query '{}' resolved to no coordinates ({})",
                    input.geocodeQuery(), outcome.status());
        }
        return outcome;
    }

    /** The inputs for one label, and which tier produced them. */
    private record Resolved(List<LocationInput> inputs, SourceTier tier) {
    }
}
