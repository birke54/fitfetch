package org.example.fitfetch.location;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves country, state and macro-region labels to stable codes, and rejects
 * labels that name no real place.
 *
 * <p>In an earlier design this was the parser. It is now three much smaller
 * jobs, because extraction moved to a model:
 *
 * <ol>
 *   <li><strong>Hallucination guard.</strong> A specifier typed as a country
 *       that resolves to no ISO code is rejected rather than geocoded, so a
 *       model that invents a place cannot spend an API call proving it.</li>
 *   <li><strong>Alias resolution.</strong> Real boards write {@code "US"},
 *       {@code "USA"} and {@code "United States"} interchangeably &mdash;
 *       sometimes within a single board &mdash; and the JDK knows only the
 *       formal names.</li>
 *   <li><strong>Classification.</strong> Whether a label is the United States,
 *       a US state, or a multi-country region decides which policy branch
 *       applies, and therefore whether a job lands inside the search radius.</li>
 * </ol>
 *
 * <p>Formal country names come free from {@link Locale}; only the informal
 * spellings are hand-maintained. Note that the United Kingdom is ISO
 * {@code "GB"}, not {@code "UK"} &mdash; storing the literal token instead of
 * the resolved code is a classic source of silent mismatches.
 *
 * <p>This is a stateless utility; all members are static and all lookups take
 * {@link LocationKey}-normalized input.
 */
public final class Gazetteer {

    /** ISO alpha-2 for the United States. */
    public static final String US = "US";

    /** Formal English country name to ISO alpha-2, sourced from the JDK. */
    private static final Map<String, String> COUNTRIES_BY_NAME = loadIsoCountries();

    /**
     * Informal spellings the JDK does not supply. Every entry here was observed
     * in live Greenhouse data, including the misspelling.
     */
    private static final Map<String, String> COUNTRY_ALIASES = Map.ofEntries(
            Map.entry("us", US),
            Map.entry("u.s.", US),
            Map.entry("usa", US),
            Map.entry("u.s.a.", US),
            Map.entry("united states of america", US),
            Map.entry("america", US),
            Map.entry("uk", "GB"),
            Map.entry("u.k.", "GB"),
            Map.entry("great britain", "GB"),
            Map.entry("britain", "GB"),
            Map.entry("england", "GB"),
            Map.entry("scotland", "GB"),
            Map.entry("wales", "GB"),
            Map.entry("holland", "NL"),
            Map.entry("the netherlands", "NL"),
            Map.entry("ksa", "SA"),
            Map.entry("uae", "AE"),
            Map.entry("phillipines", "PH"),   // sic: appears misspelled in real postings
            Map.entry("south korea", "KR"),
            Map.entry("russia", "RU"),
            Map.entry("vietnam", "VN"),
            Map.entry("czechia", "CZ"),
            Map.entry("czech republic", "CZ"),
            // CLDR renames leave the JDK knowing only the current official name
            // while postings keep using the former one. Turkey is the live case
            // -- the JDK now returns "Turkiye" -- but the same trap applies to
            // every country that has been renamed, so both spellings are mapped.
            Map.entry("turkiye", "TR"),
            Map.entry("turkey", "TR"),
            Map.entry("ivory coast", "CI"),
            Map.entry("cape verde", "CV"),
            Map.entry("swaziland", "SZ"),
            Map.entry("burma", "MM"),
            Map.entry("macedonia", "MK")
    );

    /**
     * Multi-country groupings. These geocode to centroids that are nobody's
     * actual location, which is why they are kept out of the country branch.
     */
    private static final Set<String> MACRO_REGIONS = Set.of(
            "emea", "apac", "apj", "latam", "anz", "benelux", "nordics",
            "north america", "south america", "central america", "americas",
            "europe", "asia", "africa", "middle east", "asia pacific",
            "eu", "european union", "worldwide", "global", "anywhere"
    );

    /** US state and territory names and postal abbreviations to ISO 3166-2 codes. */
    private static final Map<String, String> US_STATES = loadUsStates();

    private Gazetteer() {
    }

    /**
     * @param specifier a raw specifier; normalized internally
     * @return {@code true} if this names the United States under any observed
     *         spelling
     */
    public static boolean isUnitedStates(String specifier) {
        return US.equals(countryCode(specifier).orElse(null));
    }

    /**
     * @param specifier a raw specifier; normalized internally
     * @return {@code true} if this names a multi-country region rather than a
     *         single country
     */
    public static boolean isMacroRegion(String specifier) {
        return MACRO_REGIONS.contains(LocationKey.normalize(specifier));
    }

    /**
     * Resolves a country label to its ISO 3166-1 alpha-2 code.
     *
     * <p>An empty result is the hallucination guard firing: the label was typed
     * as a country but names none, so it must not reach the geocoder.
     *
     * @param specifier a raw country label; normalized internally
     * @return the alpha-2 code, or empty if this names no known country
     */
    public static Optional<String> countryCode(String specifier) {
        String key = LocationKey.normalize(specifier);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String alias = COUNTRY_ALIASES.get(key);
        return Optional.ofNullable(alias != null ? alias : COUNTRIES_BY_NAME.get(key));
    }

    /**
     * Resolves a US state label to its ISO 3166-2 code.
     *
     * <p>Accepts both the full name and the postal abbreviation, and tolerates a
     * trailing country, since real postings write
     * {@code "Connecticut, USA"} as a single state specifier.
     *
     * @param specifier a raw state label; normalized internally
     * @return the subdivision code such as {@code "US-WA"}, or empty if this
     *         names no US state
     */
    public static Optional<String> usStateCode(String specifier) {
        String key = LocationKey.normalize(specifier);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String direct = US_STATES.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        // "connecticut, usa" -> "connecticut"
        int comma = key.indexOf(',');
        if (comma > 0) {
            String head = key.substring(0, comma).trim();
            String tail = key.substring(comma + 1).trim();
            if (isUnitedStates(tail)) {
                return Optional.ofNullable(US_STATES.get(head));
            }
        }
        return Optional.empty();
    }

    /**
     * @param specifier  a raw state label
     * @param homeState  the configured home state, as a bare postal
     *                   abbreviation ({@code "WA"}) or an ISO 3166-2 code
     *                   ({@code "US-WA"})
     * @return {@code true} if {@code specifier} names the home state
     */
    public static boolean isHomeState(String specifier, String homeState) {
        if (homeState == null || homeState.isBlank()) {
            return false;
        }
        String expected = homeState.toUpperCase(Locale.ROOT);
        if (!expected.startsWith("US-")) {
            expected = "US-" + expected;
        }
        return usStateCode(specifier).filter(expected::equals).isPresent();
    }

    private static Map<String, String> loadIsoCountries() {
        Map<String, String> byName = new HashMap<>();
        for (String code : Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2)) {
            String display = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
            if (!display.isBlank() && !display.equals(code)) {
                byName.put(LocationKey.normalize(display), code);
            }
        }
        return Map.copyOf(byName);
    }

    private static Map<String, String> loadUsStates() {
        String[][] states = {
                {"alabama", "AL"}, {"alaska", "AK"}, {"arizona", "AZ"}, {"arkansas", "AR"},
                {"california", "CA"}, {"colorado", "CO"}, {"connecticut", "CT"}, {"delaware", "DE"},
                {"district of columbia", "DC"}, {"florida", "FL"}, {"georgia", "GA"}, {"hawaii", "HI"},
                {"idaho", "ID"}, {"illinois", "IL"}, {"indiana", "IN"}, {"iowa", "IA"},
                {"kansas", "KS"}, {"kentucky", "KY"}, {"louisiana", "LA"}, {"maine", "ME"},
                {"maryland", "MD"}, {"massachusetts", "MA"}, {"michigan", "MI"}, {"minnesota", "MN"},
                {"mississippi", "MS"}, {"missouri", "MO"}, {"montana", "MT"}, {"nebraska", "NE"},
                {"nevada", "NV"}, {"new hampshire", "NH"}, {"new jersey", "NJ"}, {"new mexico", "NM"},
                {"new york", "NY"}, {"north carolina", "NC"}, {"north dakota", "ND"}, {"ohio", "OH"},
                {"oklahoma", "OK"}, {"oregon", "OR"}, {"pennsylvania", "PA"}, {"rhode island", "RI"},
                {"south carolina", "SC"}, {"south dakota", "SD"}, {"tennessee", "TN"}, {"texas", "TX"},
                {"utah", "UT"}, {"vermont", "VT"}, {"virginia", "VA"}, {"washington", "WA"},
                {"west virginia", "WV"}, {"wisconsin", "WI"}, {"wyoming", "WY"},
                {"puerto rico", "PR"}
        };
        Map<String, String> byName = new HashMap<>();
        for (String[] state : states) {
            String iso = "US-" + state[1];
            byName.put(state[0], iso);
            byName.put(state[1].toLowerCase(Locale.ROOT), iso);
        }
        // "washington dc" is the city, but boards use it for the district.
        byName.put("washington dc", "US-DC");
        byName.put("washington d.c.", "US-DC");
        return Map.copyOf(byName);
    }
}
