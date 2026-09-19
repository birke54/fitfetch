package org.example.fitfetch.fetching.records;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.DataCompliance;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.Location;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.Metadata;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.Office;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@link AtsJobEntry} implementation mapping a single job object from the
 * Greenhouse job-board API (the {@code /v1/boards/{slug}/jobs?content=true}
 * response).
 *
 * <p>Field names follow the JSON payload; {@code @JsonProperty} bridges the
 * provider's snake_case keys to these components. Any key not modelled here is
 * ignored on deserialization &mdash; currently {@code departments} and the
 * {@code ai_disclaimer} / {@code include_ai_disclaimer} /
 * {@code ai_opt_out_request_url} trio.
 *
 * @param absoluteUrl         public URL of the job posting ({@code absolute_url})
 * @param education           education requirement classification, if provided
 * @param id                  the public job ID; Greenhouse's own identifier for
 *                            the posting and the value {@link #jobId()} derives from
 * @param internalJobId       the employer-facing job ID, distinct from {@link #id()}
 *                            ({@code internal_job_id})
 * @param updatedAt           last modification timestamp ({@code updated_at})
 * @param requisitionId       employer requisition ID ({@code requisition_id})
 * @param title               job title
 * @param companyName         hiring company name ({@code company_name})
 * @param firstPublished      when the job was first published ({@code first_published})
 * @param language            posting language code
 * @param applicationDeadline application close date, if set ({@code application_deadline})
 * @param content             the job description body, HTML-escaped by Greenhouse
 * @param location            the free-text location label for this job; the
 *                            recruiter's own answer to where the job is, and the
 *                            only field in the payload carrying workplace
 *                            semantics. May be {@code null}, and is {@code null}
 *                            on any entry deserialized from a {@code job_data}
 *                            payload stored before this component existed
 * @param offices             the offices this job is attached to ({@code offices});
 *                            may be {@code null} or empty. An office is a
 *                            board-level entity describing its own address, not a
 *                            statement of where the work may be performed
 * @param metadata            board-defined custom fields ({@code metadata}); may
 *                            be {@code null}
 * @param dataCompliance      applicant-data compliance regimes that apply to this
 *                            posting ({@code data_compliance}); may be {@code null}
 * @param slug                the ATS board slug this entry was fetched from;
 *                            {@code null} until tagged via {@link #withSlug(String)},
 *                            since it is not part of the Greenhouse JSON payload
 */
public record GreenhouseJobEntry (
        @JsonProperty("absolute_url") String absoluteUrl,
        String education,
        Long id,
        @JsonProperty("internal_job_id") Long internalJobId,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("requisition_id") String requisitionId,
        String title,
        @JsonProperty("company_name") String companyName,
        @JsonProperty("first_published") OffsetDateTime firstPublished,
        String language,
        @JsonProperty("application_deadline") OffsetDateTime applicationDeadline,
        String content,
        Location location,
        List<Office> offices,
        List<Metadata> metadata,
        @JsonProperty("data_compliance") List<DataCompliance> dataCompliance,
        String slug
) implements AtsJobEntry {

    /** @return always the literal {@code "Greenhouse"} */
    @Override
    public String atsName() {
        return "Greenhouse";
    }

    /**
     * Returns the string form of {@link #id()}, guarding against a {@code null}
     * {@code id}.
     *
     * <p>Combined with {@link #atsName()} this is the key dedup checks use. A
     * {@code null} result means the payload carried no {@code id} at all, which
     * makes the entry unusable; callers drop such an entry rather than store it.
     *
     * @return the job identifier as a string, or {@code null} if {@link #id()}
     *         is {@code null}
     */
    @Override
    public String jobId() {
        return id == null ? null : id.toString();
    }

    /**
     * Returns the raw location label for this job, guarding against a
     * {@code null} {@link #location()}.
     *
     * <p>A {@code null} result means the payload carried no {@code location}
     * object at all, which is distinct from a present-but-blank label; callers
     * that need to tell those apart should inspect {@link #location()} directly.
     *
     * @return the free-text location label, or {@code null} if absent
     */
    @Override
    public String locationName() {
        return location == null ? null : location.name();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Greenhouse publishes both dates. {@code first_published} is the one
     * that matters: {@code updated_at} moves whenever a recruiter touches the
     * posting, so a job open for a year can carry yesterday's date. It stands
     * in only when the board omitted {@code first_published} altogether.
     */
    @Override
    public OffsetDateTime postedAt() {
        return firstPublished != null ? firstPublished : updatedAt;
    }

    @Override
    public GreenhouseJobEntry withSlug(String slug) {
        return new GreenhouseJobEntry(absoluteUrl, education, id, internalJobId, updatedAt, requisitionId, title,
                companyName, firstPublished, language, applicationDeadline, content, location, offices, metadata,
                dataCompliance, slug);
    }
}
