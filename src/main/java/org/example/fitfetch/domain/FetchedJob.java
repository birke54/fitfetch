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
 * payload is kept verbatim in {@link #getJobData() jobData} as a JSON column.
 * {@link #getLocationStatus()} and {@link #getNormalizeStatus()} track how far
 * the job has got through the two passes that read that payload.
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
                @Index(name = "idx_fetched_jobs_normalize_status",
                        columnList = "normalize_status, location_status, id"),
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

    /**
     * How far this job has got through location resolution.
     *
     * <p>Tracked separately from {@link #normalizeStatus} because the two passes
     * fail in ways that call for opposite responses: location resolution depends
     * on services whose failures are transient, while a model answer that
     * cannot be used will be the same answer next time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_status", nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private LocationStatus locationStatus = LocationStatus.PENDING;

    /**
     * How far this job has got through normalization, which waits for location
     * resolution: only a job located within the search radius is normalized.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "normalize_status", nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private NormalizeStatus normalizeStatus = NormalizeStatus.PENDING;

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
     */
    public FetchedJob(AtsName atsName, String jobId, String slug, AtsJobEntry jobData) {
        this.atsName = atsName;
        this.jobId = jobId;
        this.slug = slug;
        this.jobData = jobData;
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

    /** @return how far this job has got through normalization */
    public NormalizeStatus getNormalizeStatus() {
        return normalizeStatus;
    }

    /** @param normalizeStatus the normalization state to set */
    public void setNormalizeStatus(NormalizeStatus normalizeStatus) {
        this.normalizeStatus = normalizeStatus;
    }

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
