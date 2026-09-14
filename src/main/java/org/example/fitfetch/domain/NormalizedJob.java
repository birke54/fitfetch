package org.example.fitfetch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Signal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JPA entity for the normalization of one fetched job, stored in the
 * {@code normalized_jobs} table: its seniority and its requirement signals.
 *
 * <p>One row per job. Normalizing a job again replaces its row rather than
 * adding one, and {@link #getModel() model} and
 * {@link #getPromptVersion() promptVersion} record what produced the current one.
 *
 * @see org.example.fitfetch.normalize.NormalizeService
 */
@Entity
@Table(
        name = "normalized_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_normalized_jobs_fetched_job",
                columnNames = "fetched_job_id"
        ),
        indexes = @Index(name = "idx_normalized_jobs_seniority", columnList = "seniority")
)
public class NormalizedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "fetched_job_id", nullable = false)
    private Long fetchedJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "seniority", nullable = false, length = 16)
    private Seniority seniority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signals", nullable = false)
    private List<Signal> signals;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private int promptVersion;

    @Column(name = "normalized_at", nullable = false)
    private OffsetDateTime normalizedAt;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected NormalizedJob() {
    }

    /**
     * @param fetchedJobId  the job this normalizes
     * @param data          what the model extracted
     * @param model         the model tag that produced it
     * @param promptVersion the prompt version that produced it
     * @param now           normalization time
     */
    public NormalizedJob(Long fetchedJobId, NormalizedData data, String model, int promptVersion,
                         OffsetDateTime now) {
        this.fetchedJobId = fetchedJobId;
        this.seniority = data.seniority();
        this.signals = data.signals();
        this.model = model;
        this.promptVersion = promptVersion;
        this.normalizedAt = now;
    }

    /** @return the generated primary key, or {@code null} before persistence */
    public Long getId() {
        return id;
    }

    /** @return the job this normalizes */
    public Long getFetchedJobId() {
        return fetchedJobId;
    }

    /** @return the level the job is pitched at */
    public Seniority getSeniority() {
        return seniority;
    }

    /** @return the job's requirement signals */
    public List<Signal> getSignals() {
        return signals;
    }

    /** @return the model tag that produced this row */
    public String getModel() {
        return model;
    }

    /** @return the {@code SignalPrompt} version that produced this row */
    public int getPromptVersion() {
        return promptVersion;
    }

    /** @return when this row was written */
    public OffsetDateTime getNormalizedAt() {
        return normalizedAt;
    }
}
