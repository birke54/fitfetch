package org.example.fitfetch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.example.fitfetch.match.MatchResult;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JPA entity for how well one normalized job matches the candidate, stored in
 * the {@code job_matches} table.
 *
 * <p>One row per job, for the profile version, embedder and scoring rules that
 * produced it. A change to any of the three makes the row stale, and the
 * matching pass scores the job again and replaces it.
 */
@Entity
@Table(
        name = "job_matches",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_job_matches_normalized_job",
                columnNames = "normalized_job_id"
        ),
        indexes = @Index(name = "idx_job_matches_eligible_score", columnList = "eligible, score")
)
public class JobMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "normalized_job_id", nullable = false)
    private Long normalizedJobId;

    @Column(name = "fetched_job_id", nullable = false)
    private Long fetchedJobId;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "eligible", nullable = false)
    private boolean eligible;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_failures", nullable = false)
    private List<String> gateFailures;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parts", nullable = false)
    private MatchResult.ScoreParts parts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signals", nullable = false)
    private List<MatchResult.SignalMatch> signals;

    @Column(name = "profile_sha256", nullable = false, length = 64)
    private String profileSha256;

    @Column(name = "embedder_key", nullable = false, length = 100)
    private String embedderKey;

    @Column(name = "scoring_version", nullable = false)
    private int scoringVersion;

    @Column(name = "scored_at", nullable = false)
    private OffsetDateTime scoredAt;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected JobMatch() {
    }

    /**
     * @param normalizedJob  the job scored
     * @param result         its score and reasons
     * @param profileSha256  the profile version scored against
     * @param embedderKey    what made the vectors compared
     * @param scoringVersion the scoring rules used
     * @param now            when it was scored
     */
    public JobMatch(NormalizedJob normalizedJob, MatchResult result, String profileSha256, String embedderKey,
                    int scoringVersion, OffsetDateTime now) {
        this.normalizedJobId = normalizedJob.getId();
        this.fetchedJobId = normalizedJob.getFetchedJobId();
        this.score = result.score();
        this.eligible = result.eligible();
        this.gateFailures = result.gateFailures();
        this.parts = result.parts();
        this.signals = result.signals();
        this.profileSha256 = profileSha256;
        this.embedderKey = embedderKey;
        this.scoringVersion = scoringVersion;
        this.scoredAt = now;
    }

    /** @return the normalized job scored */
    public Long getNormalizedJobId() {
        return normalizedJobId;
    }

    /** @return the fetched job it came from, for its title and company */
    public Long getFetchedJobId() {
        return fetchedJobId;
    }

    /** @return the score, 0 to 100 */
    public int getScore() {
        return score;
    }

    /** @return whether every gate passed */
    public boolean isEligible() {
        return eligible;
    }

    /** @return why the job is excluded; empty if it is not */
    public List<String> getGateFailures() {
        return gateFailures;
    }

    /** @return the parts the score is made of */
    public MatchResult.ScoreParts getParts() {
        return parts;
    }

    /** @return each signal's coverage and best bullets */
    public List<MatchResult.SignalMatch> getSignals() {
        return signals;
    }

    /** @return the profile version scored against */
    public String getProfileSha256() {
        return profileSha256;
    }

    /** @return what made the vectors compared */
    public String getEmbedderKey() {
        return embedderKey;
    }

    /** @return the scoring rules used */
    public int getScoringVersion() {
        return scoringVersion;
    }
}
