package org.example.fitfetch.domain;

import jakarta.persistence.*;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * JPA entity for a single job posting retrieved from an ATS provider and stored
 * in the {@code fetched_jobs} table.
 *
 * <p>A row is uniquely identified by the combination of {@link #getAts() ATS
 * name}, {@link #getJobId() job ID} and {@link #getSlug() board slug}
 * (enforced by the {@code uq_fetched_jobs_ats_name_job_id_slug} unique
 * constraint); the ATS name/job ID pairing is what {@code GreenhouseAts}
 * checks to avoid re-ingesting a job, and what {@link JobLocation} points at
 * with its foreign key. The full provider
 * payload is kept verbatim in {@link #getJobData() jobData} as a JSON column,
 * and {@link #isNormalized()} tracks whether that payload has since been mapped
 * into the normalized job model.
 *
 * <p>{@code fetchedAt} and {@code createdAt} are populated by Hibernate on
 * insert and are not updatable.
 *
 * @see org.example.fitfetch.ats.AtsName
 * @see AtsJobEntry
 */
@Entity
@Table(
        name = "fetched_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fetched_jobs_ats_name_job_id_slug",
                columnNames = {"ats_name", "job_id", "slug"}
        ),
        indexes = {
                @Index(name = "idx_is_normalized", columnList = "is_normalized"),
        }

)
public class FetchedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Convert(converter = AtsNameConverter.class)
    @Column(name="ats_name", nullable = false, length = 50)
    private AtsName atsName;

    @Column(name = "job_id", nullable = false, length = 255)
    private String jobId;

    @Column(name = "slug", nullable = false, length = 255)
    private String slug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_data", nullable = false)
    private AtsJobEntry jobData;

    @Column(name="is_normalized", nullable = false)
    @ColumnDefault("false")
    private boolean isNormalized;

    /**
     * How far this job has got through location resolution.
     *
     * <p>Tracked separately from {@link #isNormalized} because the two passes
     * are siblings rather than stages: location resolution reads
     * {@code job_data->'location'} and needs nothing normalization produces, and
     * the two fail in ways that call for opposite responses.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_status", nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private LocationStatus locationStatus = LocationStatus.PENDING;

    @CreationTimestamp
    @Column(name = "fetched_at", nullable = false, updatable = false)
    private OffsetDateTime fetchedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected FetchedJob() {}

    /**
     * Creates a new fetched-job record for persistence.
     *
     * @param atsName      the ATS provider the job came from
     * @param jobId        the provider's identifier for the job, unique within
     *                     that provider
     * @param slug         the ATS board slug the job was fetched from
     * @param jobData      the raw provider payload, stored as JSON
     * @param isNormalized whether {@code jobData} has already been mapped into
     *                     the normalized job model
     */
    public FetchedJob(AtsName atsName, String jobId, String slug, AtsJobEntry jobData, boolean isNormalized) {
        this.atsName = atsName;
        this.jobId = jobId;
        this.slug = slug;
        this.jobData = jobData;
        this.isNormalized = isNormalized;
    }

    /** @return the generated primary key, or {@code null} before persistence */
    public Long getId() {
        return id;
    }

    /** @param id the primary key to set */
    public void setId(Long id) {
        this.id = id;
    }

    /** @return the ATS provider this job was fetched from */
    public AtsName getAts() {
        return this.atsName;
    }

    /** @param ats the ATS provider to set */
    public void setAts(AtsName ats) {
        this.atsName = ats;
    }

    /** @return the provider's job identifier, unique within {@link #getAts()} */
    public String getJobId() {
        return jobId;
    }

    /** @param jobId the provider job identifier to set */
    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    /** @return the ATS board slug this job was fetched from */
    public String getSlug() {
        return slug;
    }

    /** @param slug the ATS board slug to set */
    public void setSlug(String slug) {
        this.slug = slug;
    }

    /** @return the raw provider payload stored for this job */
    public AtsJobEntry getJobData() {
        return jobData;
    }

    /** @param jobData the raw provider payload to set */
    public void setJobData(AtsJobEntry jobData) {
        this.jobData = jobData;
    }

    /** @return how far this job has got through location resolution */
    public LocationStatus getLocationStatus() {
        return locationStatus;
    }

    /** @param locationStatus the location resolution state to set */
    public void setLocationStatus(LocationStatus locationStatus) {
        this.locationStatus = locationStatus;
    }

    /** @return {@code true} once {@link #getJobData()} has been normalized */
    public boolean isNormalized() { return isNormalized; }

    /** @param isNormalized the normalization flag to set */
    public void setIsNormalized(boolean isNormalized) { this.isNormalized = isNormalized; }

    /** @return when the job was fetched; set by Hibernate on insert */
    public OffsetDateTime getFetchedAt() {
        return fetchedAt;
    }

    /** @param fetchedAt the fetch timestamp to set */
    public void setFetchedAt(OffsetDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    /** @return when the row was created; set by Hibernate on insert */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt the creation timestamp to set */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
