package org.example.fitfetch.fetching.records;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Provider-agnostic view of a single job posting fetched from an ATS.
 *
 * <p>This sealed interface is the common type used throughout fetching and
 * persistence; each ATS integration contributes one implementing record (for
 * example {@link GreenhouseJobEntry}). It is polymorphically serialized: a
 * {@code "type"} property discriminates the concrete record on read (via the
 * {@link JsonSubTypes} mapping below), defaulting to {@link GreenhouseJobEntry}
 * when absent. Instances are stored as the JSON {@code job_data} column of
 * {@link org.example.fitfetch.domain.FetchedJob}.
 *
 * <p>To add a provider: create a record implementing this interface, add it to
 * both the {@code permits} clause and the {@link JsonSubTypes} list.
 *
 * @see GreenhouseJobEntry
 * @see org.example.fitfetch.ats.Ats
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = GreenhouseJobEntry.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = GreenhouseJobEntry.class, name = "GREENHOUSE")
        // Add future integrations here (e.g. Lever, Workday)
})
public sealed interface AtsJobEntry permits GreenhouseJobEntry {

    /** @return the provider's native numeric identifier for the job */
    Long id();

    /** @return the human-readable ATS provider name (e.g. {@code "Greenhouse"}) */
    String atsName();

    /** @return the job description body, typically HTML */
    String content();

    /**
     * @return the provider job identifier as a string; combined with the ATS
     *         name this uniquely identifies a job and is what dedup checks use
     */
    String jobId();

    /**
     * @return the ATS board slug this entry was fetched from, or {@code null}
     *         if it has not been tagged yet via {@link #withSlug(String)}
     */
    String slug();

    /**
     * Returns the provider's free-text location label for this job.
     *
     * <p>Every ATS publishes this as prose rather than structured geography, so
     * the location pipeline treats it as the single input to resolve. A
     * {@code null} result means the payload carried no location at all, which is
     * distinct from a present-but-blank label and from a placeholder such as
     * {@code "N/A"}.
     *
     * @return the raw location label, or {@code null} if the provider supplied
     *         none
     */
    String locationName();

    /**
     * @return the job title, which decides whether the job is kept at all (see
     *         {@link org.example.fitfetch.utilities.TitleFilter}); may be
     *         {@code null} if the provider supplied none
     */
    String title();

    /**
     * Returns a copy of this entry with {@link #slug()} set to the given value.
     *
     * <p>The board slug is a fetch-time parameter, not part of the provider's
     * JSON payload, so implementations start with a {@code null} slug on
     * deserialization; the fetching {@code Ats} implementation calls this to
     * stamp in the slug it was fetched under before the entry is persisted.
     *
     * @param slug the ATS board slug to tag this entry with
     * @return a new entry equal to this one except for {@link #slug()}
     */
    AtsJobEntry withSlug(String slug);
}