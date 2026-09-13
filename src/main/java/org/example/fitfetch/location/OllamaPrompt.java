package org.example.fitfetch.location;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The extraction prompt and the JSON Schema that constrains the model's answer.
 *
 * <p>Both are versioned together by {@link #VERSION}, which is written to every
 * interpretation cache row. Bumping it does not invalidate the cache eagerly:
 * rows carrying a lower version are simply treated as misses on read, so labels
 * still in circulation are re-extracted while dead tail entries age out through
 * the cache's own eviction without ever costing a call.
 *
 * <p>The instructions encode what a survey of eleven live Greenhouse boards
 * showed, because none of it is guessable:
 *
 * <ul>
 *   <li>Semicolons, ampersands and the word "or" always separate locations;
 *       commas do not reliably do either job.</li>
 *   <li>Remote markers fuse to places with no separator at all
 *       ({@code "Remote US"}, {@code "Remote Ireland"}, {@code "Seattle WA"}),
 *       and one board writes the country first ({@code "US-NYC"}).</li>
 *   <li>The remote marker is not reliably first: {@code "Remote, United States"},
 *       {@code "United States, Remote"} and {@code "Connecticut, USA, Remote"}
 *       all occur.</li>
 *   <li>Placeholders reach production. {@code "N/A"} and an unfilled
 *       {@code "LOCATION"} template both appear on real postings.</li>
 * </ul>
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class OllamaPrompt {

    /**
     * Version of everything that shapes a cached extraction other than the model.
     * Increment on any change to:
     *
     * <ul>
     *   <li>the prompt text or the JSON Schema in this class;</li>
     *   <li>the sampling options in
     *       {@link org.example.fitfetch.location.records.OllamaOptions#deterministic()};</li>
     *   <li>how {@link OllamaLocationExtractor} turns the model's output into
     *       {@link ExtractedLocation}s, since the cache stores the parsed result
     *       and a parsing fix would otherwise never reach existing rows.</li>
     * </ul>
     *
     * <p>Nothing enforces this; it relies on whoever makes the change. When in
     * doubt, bump: a missed bump silently serves stale answers for every label
     * still in circulation, while a needless one costs a single re-extraction of
     * those labels and nothing for the dead tail.
     *
     * <p>A change of model tag needs no bump, since rows from another model are
     * already treated as misses. A tag re-pulled with new weights does, because
     * the tag string is all the cache can see.
     */
    public static final int VERSION = 1;

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "analysis": { "type": "string" },
                "locations": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "raw":            { "type": "string" },
                      "kind":           { "type": "string",
                                          "enum": ["PLACE","REMOTE_BARE","REMOTE_SPECIFIER",
                                                   "SENTINEL","UNPARSEABLE"] },
                      "specifier":      { "type": ["string","null"] },
                      "specifier_type": { "type": ["string","null"],
                                          "enum": ["ADDRESS","CITY","STATE","COUNTRY",
                                                   "MACRO_REGION",null] }
                    },
                    "required": ["raw","kind","specifier","specifier_type"]
                  }
                }
              },
              "required": ["analysis","locations"]
            }
            """;

    private static final JsonNode SCHEMA = new ObjectMapper().readTree(SCHEMA_JSON);

    private static final String INSTRUCTIONS = """
            You extract structured location data from job posting location labels.

            A label may name one location or several.

            SEPARATORS
            - ";", "&", " or ", and newlines ALWAYS separate distinct locations.
            - "," is ambiguous. It can join parts of one location ("New York, NY")
              or separate list items ("Dublin, London"). Decide from the parts:
              if each part is a well-known place in its own right, it is a list.
            - Some labels fuse a remote marker or a country to a place with no
              separator at all: "Remote US", "Remote Ireland", "Seattle WA",
              "US-NYC", "Remote-US", "US- Chicago".
            - The remote marker is NOT reliably first. "Remote, United States",
              "United States, Remote" and "Connecticut, USA, Remote" all mean the
              same thing.

            FOR EACH LOCATION, REPORT
            - kind:
                PLACE             a place with no remote marker
                REMOTE_BARE       a remote marker with no place attached
                REMOTE_SPECIFIER  a remote marker qualified by a place
                SENTINEL          a placeholder, not a location: "N/A", "None",
                                  "TBD", "Various", "Multiple Locations", or an
                                  unfilled template such as "LOCATION"
                UNPARSEABLE       text you cannot interpret as any of the above
            - specifier: the place itself. null for REMOTE_BARE, SENTINEL and
              UNPARSEABLE.
            - specifier_type: ADDRESS, CITY, STATE, COUNTRY or MACRO_REGION.
              null when there is no specifier. MACRO_REGION covers multi-country
              groupings such as EMEA, APAC, LATAM and North America.
            - raw: the exact substring of the input this location came from.

            SPECIFIER FORMAT
            Return a specifier a geocoder can resolve. If the label supplies a
            region or country, include it: "Bangalore, India", not "Bangalore".
            Never invent one that the label does not supply. Strip team or office
            names appended to a city, but keep district or ward names that are
            genuinely part of the address.

            Use the "analysis" field to work through the splitting before you
            answer. Keep it to one or two sentences.

            EXAMPLES

            Input: Remote, Canada; Remote, United States
            {"analysis":"Semicolon splits two remote-with-country locations.",
             "locations":[
              {"raw":"Remote, Canada","kind":"REMOTE_SPECIFIER","specifier":"Canada","specifier_type":"COUNTRY"},
              {"raw":"Remote, United States","kind":"REMOTE_SPECIFIER","specifier":"United States","specifier_type":"COUNTRY"}]}

            Input: San Francisco, CA, Seattle WA, New York, NY
            {"analysis":"Comma acts as both list and component separator; three cities, one fused with its state.",
             "locations":[
              {"raw":"San Francisco, CA","kind":"PLACE","specifier":"San Francisco, CA","specifier_type":"CITY"},
              {"raw":"Seattle WA","kind":"PLACE","specifier":"Seattle, WA","specifier_type":"CITY"},
              {"raw":"New York, NY","kind":"PLACE","specifier":"New York, NY","specifier_type":"CITY"}]}

            Input: Remote Ireland; Remote, Germany
            {"analysis":"First element fuses the marker to the country with no comma.",
             "locations":[
              {"raw":"Remote Ireland","kind":"REMOTE_SPECIFIER","specifier":"Ireland","specifier_type":"COUNTRY"},
              {"raw":"Remote, Germany","kind":"REMOTE_SPECIFIER","specifier":"Germany","specifier_type":"COUNTRY"}]}

            Input: US-NYC; US-Remote; Canada-Toronto
            {"analysis":"Country written first, hyphen-joined; the middle element is remote rather than a city.",
             "locations":[
              {"raw":"US-NYC","kind":"PLACE","specifier":"New York, NY, USA","specifier_type":"CITY"},
              {"raw":"US-Remote","kind":"REMOTE_SPECIFIER","specifier":"US","specifier_type":"COUNTRY"},
              {"raw":"Canada-Toronto","kind":"PLACE","specifier":"Toronto, Canada","specifier_type":"CITY"}]}

            Input: Connecticut, USA, Remote
            {"analysis":"State plus country plus a trailing remote marker: one state-level remote location.",
             "locations":[
              {"raw":"Connecticut, USA, Remote","kind":"REMOTE_SPECIFIER","specifier":"Connecticut","specifier_type":"STATE"}]}

            Input: Boston or Remote
            {"analysis":"\\"or\\" separates a city from a bare remote option.",
             "locations":[
              {"raw":"Boston","kind":"PLACE","specifier":"Boston, MA, USA","specifier_type":"CITY"},
              {"raw":"Remote","kind":"REMOTE_BARE","specifier":null,"specifier_type":null}]}

            Input: Tokyo, Chiyoda, Japan
            {"analysis":"City, ward and country: one address, not a list.",
             "locations":[
              {"raw":"Tokyo, Chiyoda, Japan","kind":"PLACE","specifier":"Tokyo, Chiyoda, Japan","specifier_type":"CITY"}]}

            Input: Remote, EMEA
            {"analysis":"EMEA is a multi-country grouping, not a country.",
             "locations":[
              {"raw":"Remote, EMEA","kind":"REMOTE_SPECIFIER","specifier":"EMEA","specifier_type":"MACRO_REGION"}]}

            Input: N/A
            {"analysis":"A placeholder, not a location.",
             "locations":[{"raw":"N/A","kind":"SENTINEL","specifier":null,"specifier_type":null}]}

            Now extract from this input.

            Input:\s""";

    private OllamaPrompt() {
    }

    /**
     * @return the JSON Schema constraining the model's output; shared and not to
     *         be mutated
     */
    public static JsonNode schema() {
        return SCHEMA;
    }

    /**
     * Builds the full prompt for one label.
     *
     * @param rawLocationName the verbatim label to extract from
     * @return instructions, few-shot examples and the input, ready to send
     */
    public static String forLabel(String rawLocationName) {
        return INSTRUCTIONS + rawLocationName;
    }
}
