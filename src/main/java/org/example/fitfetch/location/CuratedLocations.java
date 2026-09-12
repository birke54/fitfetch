package org.example.fitfetch.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tier one of location resolution: a hand-curated exact-match table, held in
 * memory and consulted before anything that costs time or money.
 *
 * <p>The distribution of location strings is extremely head-heavy. Across 1,986
 * jobs from eleven Greenhouse boards there were 435 distinct labels, but 27 of
 * them covered half the jobs and 112 covered four fifths, while 59% of the
 * distinct labels appeared exactly once. Those are two different problems: a
 * small, stable head worth answering deterministically, and a long singleton
 * tail where a model earns its keep. This table is the head.
 *
 * <p>Curation also buys accuracy a model cannot match, because the entries
 * encode board context. {@code "Dublin"} means Ireland rather than Ohio here,
 * {@code "London"} means the United Kingdom rather than Ontario, and
 * {@code "NYC-Privy"} is New York with an acquired team's name appended.
 * {@code "LOCATION"} is an unfilled template placeholder that a model would
 * quite reasonably try to geocode.
 *
 * <p>Hits here are deliberately <strong>not</strong> written to the
 * interpretation cache. That cache exists to remember work that cost something;
 * a curated answer costs a map lookup.
 *
 * <p>Instances are immutable after construction and safe to share.
 *
 * @see LocationKey
 */
public class CuratedLocations {

    private static final Logger LOGGER = LoggerFactory.getLogger(CuratedLocations.class);

    private final Map<String, List<LocationInput>> byKey;

    /**
     * Loads and indexes the curated table.
     *
     * <p>Keys are derived from each entry's {@code raw} label through
     * {@link LocationKey#normalize(String)} rather than stored pre-normalized, so
     * the table stays readable and a change to normalization rules is picked up
     * automatically instead of silently orphaning entries.
     *
     * <p>{@link LocationInput#ORIGIN_TOKEN} is substituted at load time, which is
     * what keeps the resource free of any particular user's address.
     *
     * @param tableJson the curated table resource
     * @param origin    the configured search origin
     * @throws FileNotFoundException    if the resource does not exist
     * @throws IOException              if the resource cannot be read
     * @throws IllegalStateException    if two entries normalize to the same key,
     *                                  which would make one of them unreachable
     */
    public CuratedLocations(Resource tableJson, String origin) throws IOException {
        if (!tableJson.exists()) {
            throw new FileNotFoundException("Curated location table not found: " + tableJson.getFilename());
        }
        byte[] bytes;
        try (InputStream in = tableJson.getInputStream()) {
            bytes = in.readAllBytes();
        }
        this.byKey = index(new ObjectMapper().readTree(bytes), origin);
        LOGGER.info("Loaded {} curated location entries from {}", byKey.size(), tableJson.getFilename());
    }

    private static Map<String, List<LocationInput>> index(JsonNode root, String origin) {
        JsonNode entries = root == null ? null : root.get("entries");
        if (entries == null || !entries.isArray()) {
            throw new IllegalStateException("Curated location table is missing an 'entries' array");
        }

        Map<String, List<LocationInput>> index = new HashMap<>();
        Map<String, String> rawByKey = new HashMap<>();

        for (JsonNode entry : entries) {
            String raw = text(entry, "raw");
            if (raw == null) {
                throw new IllegalStateException("Curated entry is missing 'raw': " + entry);
            }
            String key = LocationKey.normalize(raw);
            if (key.isEmpty()) {
                throw new IllegalStateException("Curated entry normalizes to an empty key: " + raw);
            }
            String clash = rawByKey.put(key, raw);
            if (clash != null) {
                // Two labels reducing to one key means whichever loaded second
                // would shadow the first, silently. Fail loudly instead.
                throw new IllegalStateException(
                        "Curated entries '" + clash + "' and '" + raw + "' both normalize to '" + key + "'");
            }

            JsonNode outputs = entry.get("outputs");
            if (outputs == null || !outputs.isArray() || outputs.isEmpty()) {
                throw new IllegalStateException("Curated entry '" + raw + "' has no outputs");
            }
            List<LocationInput> inputs = new ArrayList<>(outputs.size());
            for (JsonNode output : outputs) {
                inputs.add(toInput(output, raw, origin));
            }
            index.put(key, List.copyOf(inputs));
        }
        return Map.copyOf(index);
    }

    private static LocationInput toInput(JsonNode output, String entryRaw, String origin) {
        String raw = text(output, "raw");
        String resolutionName = text(output, "resolution");
        if (resolutionName == null) {
            throw new IllegalStateException("Curated output for '" + entryRaw + "' is missing 'resolution'");
        }
        Resolution resolution;
        try {
            resolution = Resolution.valueOf(resolutionName);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Curated output for '" + entryRaw + "' has unknown resolution '" + resolutionName + "'", e);
        }
        return new LocationInput(
                raw == null ? entryRaw : raw,
                resolution,
                text(output, "geocodeQuery"),
                text(output, "regionCode")
        ).withOrigin(origin);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /**
     * Looks up a raw location label.
     *
     * @param rawLocationName the verbatim {@code location.name}; normalized
     *                        internally, so callers need not pre-process it
     * @return the curated resolution, or empty if this label is not curated and
     *         must fall through to the interpretation cache
     */
    public Optional<List<LocationInput>> lookup(String rawLocationName) {
        String key = LocationKey.normalize(rawLocationName);
        return key.isEmpty() ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }

    /** @return how many labels this table answers */
    public int size() {
        return byKey.size();
    }
}
