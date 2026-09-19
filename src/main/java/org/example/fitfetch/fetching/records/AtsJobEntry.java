package org.example.fitfetch.fetching.records;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.OffsetDateTime;

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
        @JsonSubTypes.Type(value = GreenhouseJobEntry.class, name = "GREENHOUSE"),
        @JsonSubTypes.Type(value = AshbyJobEntry.class, name = "ASHBY")
        // Add future integrations here (e.g. Lever, Workday)
})
public sealed interface AtsJobEntry permits GreenhouseJobEntry, AshbyJobEntry {

    /** @return the human-readable ATS provider name (e.g. {@code "Greenhouse"}) */
    String atsName();

    /** @return the job description body, typically HTML */
    String content();

    /**
     * Returns the provider's own identifier for this job.
     *
     * <p>This is the sole identifier on the contract: providers disagree on its
     * shape &mdash; Greenhouse numbers its jobs, others use opaque strings
     * &mdash; so it is carried as a {@code String} rather than a numeric type.
     * Combined with {@link #atsName()} it uniquely identifies a job, and it is
     * what dedup checks key on.
     *
     * @return the provider job identifier, or {@code null} if the payload
     *         carried none; callers drop such an entry as unusable
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
     * Returns when the provider says this job was put on the board.
     *
     * <p>Every ATS dates a posting differently, and not all of them date it at
     * all, so an implementation answers with the best date it has: when the job
     * was first published, failing that when it was last changed. A
     * {@code null} means the payload carried neither, and the caller supplies
     * the fetch time in its place &mdash; see
     * {@link org.example.fitfetch.domain.FetchedJob#getPostedAt()}, which is
     * never null and is what the normalization pass ages a job by.
     *
     * @return the provider's own date for the posting, or {@code null} if it
     *         published none
     */
    OffsetDateTime postedAt();

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