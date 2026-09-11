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
}