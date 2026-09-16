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
    public static final int VERSION = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Instructions, sent as Ollama's {@code system} field. */
    static final String SYSTEM = """
            You extract what a resume is matched against from a job posting: its level, its hard requirements, and its concrete requirements, responsibilities, and skills.

            Rules:
            - Include ONLY substantive role/technical signals: responsibilities, required skills, qualifications, and preferred/nice-to-have items.
            - EXCLUDE company marketing, mission/culture statements, legal disclaimers, benefits/perks, salary, and pure section headers.

            Seniority (choose exactly one): junior, midlevel, senior, staff, principal, distinguished.
            - First, use the JOB TITLE if it contains a level (including mapping "II"/"III"/"Lead" to the closest band).
            - Second, if the title has no level, use the years-of-experience requirement:
              junior (0-2), midlevel (2-5), senior (5+), staff (7-10+), principal (10+), distinguished (15-20+).
            - If a year count falls into multiple bands, pick the highest band whose lower bound it meets, unless title or scope indicates otherwise.
            - Third, if years are absent, use your best judgment from the rest of the description.

            Track (choose exactly one): "manager" if the role's main work is managing people (hiring, performance reviews, direct reports); otherwise "ic". A tech lead who still builds is "ic".

            Employment type (choose exactly one): full_time, part_time, contract, internship, temporary; "unstated" if the posting does not say.

            min_years_experience: the smallest number of years of overall professional experience required, as an integer. "5+ years" is 5; "3-5 years" is 3. Use 0 if none is stated. Years tied to one skill ("3+ years of Kubernetes") go on that signal, not here.

            Hard requirements. Count only what the posting requires, never what it prefers:
            - required_degree (choose exactly one): the lowest degree required: none, bachelors, masters, phd. Use "none" if no degree is mentioned or equivalent experience is accepted instead.
            - clearance_required: true if the role requires a security clearance, whether already held or to be obtained.
            - sponsorship (choose exactly one): "no" if the posting says it will not sponsor a visa or requires existing work authorization; "yes" if it says it will sponsor; otherwise "unstated".
            - required_certifications: certifications the posting requires, by common name. Empty if none; preferred certifications stay in signals only.
            - travel_required: true if the role requires travel.
            - on_call: true if the role includes an on-call rotation.

            domains: up to three business or industry domains the work is in, as short lower-case phrases such as "payments", "healthcare", "ad tech". Empty if the posting names none beyond software itself.

            Signal handling:
            - Normalize each signal to one crisp sentence. Strip label prefixes like "Drive Technical Direction:" and keep the actual requirement.
            - If a bullet contains multiple distinct requirements, split it into separate signals.
            - Deduplicate signals expressing the same requirement; keep the most specific phrasing.
            - If an item is not explicitly marked as preferred, optional, "a plus", or "nice to have", classify it as required.
            - skills: the things the signal names that a resume would list as a skill: technologies, languages, frameworks, tools, platforms, protocols, and named techniques or concepts ("Kubernetes", "BGP", "subnetting", "RAG", "event-driven architecture"). Each by its common canonical name ("PostgreSQL" not "Postgres", "Kubernetes" not "k8s", "Go" not "Golang").
              - Include every product, tool, service and protocol the signal names, even where the sentence is about the work rather than about a skill: "Integrate tooling into GitHub Actions and other CI workflows" names GitHub Actions and CI/CD; "Improve PostgreSQL performance by moving suitable workloads to Elasticsearch or ClickHouse" names PostgreSQL, Elasticsearch and ClickHouse; "integrated OpenTelemetry to make issues easier to diagnose" names OpenTelemetry.
              - Only what this signal's own text names. Never copy skills from other parts of the posting.
              - Name each separately: "TypeScript/Node" is two skills, TypeScript and Node.js. A name that is one thing, like "CI/CD" or "TCP/IP", stays whole.
              - Leave out general practices and qualities ("programming", "testing", "debugging", "performance", "security", "reliability", "maintainability", "communication", "collaboration", "mentoring"), vague phrases ("modern backend languages", "cloud native technologies"), and team or department names ("Infrastructure", "Data").
              - Leave out alternatives (see any_of_skills). Empty if it names none.
            - any_of_skills: when the signal offers alternatives and any one of them is enough ("at least one of AWS, Azure, or GCP", "Java or Go"), list every alternative here, by the same canonical names, and leave them out of skills. Examples introduced by "e.g." or "such as" are not alternatives; they stay in skills. If the alternatives are open-ended ("Go, or any other modern language", "LangGraph, LangChain, or a comparable framework"), the named ones are only examples of an open choice, so leave them out of both lists. If a signal offers two separate sets of alternatives, split it into two signals. Empty if it offers none.
            - min_years: the years of experience the signal itself asks for, as an integer; 0 if it states none. Keep those years in the sentence too: if min_years is not 0, the text must say them ("Has 3+ years of Kubernetes"), or they are dropped.

            Classify each signal's section as exactly one of: core responsibilities, required skills, preferred/nice-to-have skills, required qualifications, preferred/nice-to-have qualifications.

            - If the input is not a job description or has no extractable signals, return an empty signals array.
            - Return ONLY JSON matching the schema. No prose, no markdown.""";

    private static final String USER_PREFIX = """
            Respond with a JSON object of this exact form and nothing else — no prose, no markdown:
            {
              "seniority": string,
              "track": string,
              "employment_type": string,
              "min_years_experience": integer,
              "required_degree": string,
              "clearance_required": boolean,
              "sponsorship": string,
              "required_certifications": [string],
              "travel_required": boolean,
              "on_call": boolean,
              "domains": [string],
              "signals": [
                {"classification": string, "text": string, "skills": [string], "any_of_skills": [string], "min_years": integer}
              ]
            }
            - Each string field with a fixed set of values takes one of the values listed above.
            - "text" is the normalized one-sentence signal.
            - If the input is not a job description or has no extractable signals, return "signals": [].
            """;

    private static final ObjectNode SCHEMA = buildSchema();

    private SignalPrompt() {
    }

    /**
     * @param title          the job title, or {@code null} if the posting has
     *                       none. It goes first: the title is where the level
     *                       usually is, and descriptions rarely repeat it
     * @param jobDescription the description as plain text
     * @return the prompt for that job, sent as Ollama's {@code prompt} field
     *         alongside {@link #SYSTEM}
     */
    public static String forJob(String title, String jobDescription) {
        String shownTitle = title == null || title.isBlank() ? "(none given)" : title.strip();
        return USER_PREFIX + "\nJOB TITLE: " + shownTitle + "\n\nJOB DESCRIPTION:\n" + jobDescription;
    }

    /** @return the JSON Schema for the answer; a fresh copy, safe to modify */
    public static ObjectNode schema() {
        return SCHEMA.deepCopy();
    }

    /**
     * Builds the schema. Properties are declared in the order the model writes
     * them, and every one is required. Nothing is nullable: a number with
     * nothing stated is 0, which also keeps the schema to types every Ollama
     * version's grammar supports.
     */
    private static ObjectNode buildSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");

        ArrayNode seniorityEnum = enumProperty(properties, required, "seniority");
        for (Seniority band : Seniority.values()) {
            seniorityEnum.add(band.label());
        }
        labels(enumProperty(properties, required, "track"), Track.values());
        labels(enumProperty(properties, required, "employment_type"), EmploymentType.values());
        property(properties, required, "min_years_experience", "integer");
        labels(enumProperty(properties, required, "required_degree"), Degree.values());
        property(properties, required, "clearance_required", "boolean");
        labels(enumProperty(properties, required, "sponsorship"), Sponsorship.values());
        stringArray(properties, required, "required_certifications");
        property(properties, required, "travel_required", "boolean");
        property(properties, required, "on_call", "boolean");
        stringArray(properties, required, "domains");

        ObjectNode signals = property(properties, required, "signals", "array");
        ObjectNode item = signals.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        ArrayNode itemRequired = item.putArray("required");

        ArrayNode classificationEnum = enumProperty(itemProperties, itemRequired, "classification");
        for (SignalClassification section : SignalClassification.values()) {
            if (section != SignalClassification.OTHER) {
                classificationEnum.add(section.label());
            }
        }
        property(itemProperties, itemRequired, "text", "string");
        stringArray(itemProperties, itemRequired, "skills");
        stringArray(itemProperties, itemRequired, "any_of_skills");
        property(itemProperties, itemRequired, "min_years", "integer");
        return schema;
    }

    private static ObjectNode property(ObjectNode properties, ArrayNode required, String name, String type) {
        required.add(name);
        ObjectNode property = properties.putObject(name);
        property.put("type", type);
        return property;
    }

    /** @return the property's {@code enum} array, to be filled by the caller */
    private static ArrayNode enumProperty(ObjectNode properties, ArrayNode required, String name) {
        return property(properties, required, name, "string").putArray("enum");
    }

    private static void stringArray(ObjectNode properties, ArrayNode required, String name) {
        property(properties, required, name, "array").putObject("items").put("type", "string");
    }

    private static void labels(ArrayNode values, Labeled[] constants) {
        for (Labeled constant : constants) {
            values.add(constant.label());
        }
    }
}
