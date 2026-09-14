package org.example.fitfetch.normalize;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The signal extraction prompt and the JSON Schema that constrains the model's
 * answer.
 *
 * <p>Both are versioned together by {@link #VERSION}, which is recorded on every
 * {@code normalized_jobs} row.
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class SignalPrompt {

    /**
     * Version of everything that shapes a normalization other than the model.
     * Increment on any change to the prompt text, the schema, the sampling
     * options in {@link OllamaSignalExtractor}, or how its output is parsed.
     *
     * <p>A bump only affects jobs normalized after it; the pass reads
     * {@code PENDING} jobs and nothing moves a job back. To redo jobs under the
     * new prompt, requeue them after deploying &mdash; the failed ones first,
     * since they are usually why the prompt changed:
     *
     * <pre>{@code
     * UPDATE fetched_jobs SET normalize_status = 'PENDING' WHERE normalize_status = 'FAILED';
     * }</pre>
     *
     * <p>Include {@code 'NORMALIZED'} to redo every job; the pass replaces each
     * job's existing row. Rows from an older version can be found by
     * {@code prompt_version}.
     */
    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Instructions, sent as Ollama's {@code system} field. */
    static final String SYSTEM = """
            You extract concrete requirements, responsibilities, skills, and seniority from a job description so a resume can be matched against them.

            Rules:
            - Include ONLY substantive role/technical signals: responsibilities, required skills, qualifications, and preferred/nice-to-have items.
            - EXCLUDE company marketing, mission/culture statements, legal disclaimers, benefits/perks, salary, and pure section headers.

            Seniority (choose exactly one): junior, midlevel, senior, staff, principal, distinguished.
            - First, use the job title if it contains a level (including mapping "II"/"III"/"Lead" to the closest band).
            - Second, if the title has no level, use the years-of-experience requirement:
              junior (0-2), midlevel (2-5), senior (5+), staff (7-10+), principal (10+), distinguished (15-20+).
            - If a year count falls into multiple bands, pick the highest band whose lower bound it meets, unless title or scope indicates otherwise.
            - Third, if years are absent, use your best judgment from the rest of the description.

            Signal handling:
            - Normalize each signal to one crisp sentence. Strip label prefixes like "Drive Technical Direction:" and keep the actual requirement.
            - If a bullet contains multiple distinct requirements, split it into separate signals.
            - Deduplicate signals expressing the same requirement; keep the most specific phrasing.
            - If an item is not explicitly marked as preferred, optional, "a plus", or "nice to have", classify it as required.

            Classify each signal's section as exactly one of: core responsibilities, required skills, preferred/nice-to-have skills, required qualifications, preferred/nice-to-have qualifications.

            - If the input is not a job description or has no extractable signals, return the schema with empty arrays.
            - Return ONLY JSON matching the schema. No prose, no markdown.""";

    private static final String USER_PREFIX = """
            Respond with a JSON object of this exact form and nothing else — no prose, no markdown:
            {
              "seniority": string,
              "signals": [
                {"classification": string, "text": string}
              ]
            }
            - "seniority" is one of the six values above.
            - "classification" is one of the five values above.
            - "text" is the normalized one-sentence signal.
            - If the input is not a job description or has no extractable signals, return {"seniority": "", "signals": []}.

            JOB DESCRIPTION:
            """;

    private static final ObjectNode SCHEMA = buildSchema();

    private SignalPrompt() {
    }

    /**
     * @param jobDescription the description as plain text
     * @return the prompt for that description, sent as Ollama's {@code prompt}
     *         field alongside {@link #SYSTEM}
     */
    public static String forDescription(String jobDescription) {
        return USER_PREFIX + "\n\n" + jobDescription;
    }

    /** @return the JSON Schema for the answer; a fresh copy, safe to modify */
    public static ObjectNode schema() {
        return SCHEMA.deepCopy();
    }

    private static ObjectNode buildSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode seniority = properties.putObject("seniority");
        seniority.put("type", "string");
        ArrayNode seniorityEnum = seniority.putArray("enum");
        for (Seniority band : Seniority.values()) {
            seniorityEnum.add(band.label());
        }

        ObjectNode signals = properties.putObject("signals");
        signals.put("type", "array");
        ObjectNode item = signals.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");

        ObjectNode classification = itemProperties.putObject("classification");
        classification.put("type", "string");
        ArrayNode classificationEnum = classification.putArray("enum");
        for (SignalClassification section : SignalClassification.values()) {
            if (section != SignalClassification.OTHER) {
                classificationEnum.add(section.label());
            }
        }
        itemProperties.putObject("text").put("type", "string");
        item.putArray("required").add("classification").add("text");

        schema.putArray("required").add("seniority").add("signals");
        return schema;
    }
}
