package org.example.fitfetch.location;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces a raw location label to the lookup key shared by every tier of the
 * resolution pipeline.
 *
 * <p>The curated table, the interpretation cache and the model path must all key
 * off byte-identical strings. If any one of them normalizes differently, every
 * curated miss becomes a cache miss too and the pipeline silently re-pays for
 * work it has already done. That is why this is a single method with a single
 * caller at the top of the pipeline, rather than normalization applied
 * piecemeal.
 *
 * <p>Trimming alone is worth doing: across eleven Greenhouse boards it collapsed
 * 459 distinct strings to 435, because at least one board publishes both
 * {@code "United States"} and {@code "United States "} for different jobs.
 *
 * <p>This is a stateless utility; all members are static.
 *
 * @see org.example.fitfetch.utilities.TitleFilter
 */
public final class LocationKey {

    /**
     * Unicode dash variants folded to ASCII hyphen. Boards use en dashes, em
     * dashes and minus signs interchangeably with plain hyphens.
     *
     * <p>Written as escapes so this file stays pure ASCII and compiles
     * identically whatever source encoding the toolchain picks.
     */
    private static final Pattern DASHES = Pattern.compile("[\\u2010-\\u2015\\u2212]");

    /** Combining marks left behind by NFD decomposition. */
    private static final Pattern COMBINING = Pattern.compile("\\p{M}+");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private LocationKey() {
    }

    /**
     * Normalizes a raw location label for exact-match lookup.
     *
     * <p>Applies, in order: NFD decomposition with combining marks stripped, so
     * an accented {@code "Montreal"} and its plain spelling share a key; dash-variant
     * folding; whitespace collapsing and trimming; and lower-casing under
     * {@link Locale#ROOT}, which avoids the Turkish dotted-I trap that a
     * default-locale {@code toLowerCase} would walk into.
     *
     * <p><strong>Diacritics are folded for the key only.</strong> The original
     * string must be kept for geocoding &mdash; stripping accents from
     * a city such as Sao Paulo before sending it to a geocoder loses information the
     * geocoder can use.
     *
     * <p>Spacing around punctuation is deliberately preserved, so
     * {@code "Remote - US"} and {@code "Remote-US"} remain distinct keys. Both
     * appear in real data and both are curated; collapsing them would be safe
     * here but would couple the key to assumptions about the curated table.
     *
     * @param raw the raw label, possibly {@code null}
     * @return the normalized key; empty for {@code null}, empty or
     *         whitespace-only input
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String key = Normalizer.normalize(raw, Normalizer.Form.NFD);
        key = COMBINING.matcher(key).replaceAll("");
        key = DASHES.matcher(key).replaceAll("-");
        key = WHITESPACE.matcher(key).replaceAll(" ").trim();
        return key.toLowerCase(Locale.ROOT);
    }

    /**
     * @param raw the raw label, possibly {@code null}
     * @return {@code true} if {@code raw} carries no usable text at all
     */
    public static boolean isBlank(String raw) {
        return normalize(raw).isEmpty();
    }
}
