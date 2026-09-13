package org.example.fitfetch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.example.fitfetch.location.ExtractedLocation;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JPA entity for a cached model extraction, stored in the
 * {@code location_interpretation} table.
 *
 * <p>Rows here record work that cost something. Labels answered by the
 * hand-curated table never reach this cache &mdash; a curated answer costs a map
 * lookup, so remembering it would be pure overhead.
 *
 * <p>{@link #getOutputs() outputs} holds the <strong>pre-policy</strong>
 * extraction: what the posting says, with no knowledge of the search origin or
 * the home state. Caching the resolved {@code LocationInput} instead would bake
 * one particular address into every row, so reconfiguring the origin would
 * invalidate the whole cache and send every label back through the model.
 * Keeping policy in code makes re-running it free.
 *
 * <p>{@link #getModel() model} and {@link #getPromptVersion() promptVersion}
 * are what make the prompt safe to change. A version bump is applied lazily:
 * lower-version rows are treated as misses on read, so labels still in
 * circulation are re-extracted while dead tail entries age out through eviction
 * without ever costing a call.
 *
 * @see org.example.fitfetch.location.CachingLocationExtractor
 */
@Entity
@Table(
        name = "location_interpretation",
        indexes = @Index(name = "idx_location_interpretation_lru", columnList = "last_hit_at, hit_count")
)
public class LocationInterpretation {

    /**
     * The normalized location label.
     *
     * <p>{@code TEXT} rather than a bounded column: the longest label observed
     * across eleven Greenhouse boards is 261 characters, against a median of 22.
     * A 255-character cap would fail only on that tail, which is exactly the kind
     * of limit that survives testing and breaks in production.
     */
    @Id
    @Column(name = "location_key", nullable = false, columnDefinition = "text")
    private String locationKey;

    @Column(name = "raw", nullable = false, columnDefinition = "text")
    private String raw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outputs", nullable = false)
    private List<ExtractedLocation> outputs;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private int promptVersion;

    @Column(name = "interpreted_at", nullable = false)
    private OffsetDateTime interpretedAt;

    @Column(name = "last_hit_at", nullable = false)
    private OffsetDateTime lastHitAt;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected LocationInterpretation() {
    }

    /**
     * @param locationKey    the normalized label, as produced by
     *                       {@code LocationKey.normalize}
     * @param raw            the verbatim label, kept for debugging and for
     *                       replaying a changed prompt against real input
     * @param outputs        the pre-policy extraction
     * @param model          the model tag that produced it
     * @param promptVersion  the prompt version that produced it
     * @param now            the time of extraction
     */
    public LocationInterpretation(String locationKey, String raw, List<ExtractedLocation> outputs,
                                  String model, int promptVersion, OffsetDateTime now) {
        this.locationKey = locationKey;
        this.raw = raw;
        this.outputs = outputs;
        this.model = model;
        this.promptVersion = promptVersion;
        this.interpretedAt = now;
        this.lastHitAt = now;
        this.hitCount = 0L;
    }

    /** @return the normalized label this row answers */
    public String getLocationKey() {
        return locationKey;
    }

    /** @return the verbatim label as published */
    public String getRaw() {
        return raw;
    }

    /** @return the cached pre-policy extraction */
    public List<ExtractedLocation> getOutputs() {
        return outputs;
    }

    /** @param outputs the extraction to store */
    public void setOutputs(List<ExtractedLocation> outputs) {
        this.outputs = outputs;
    }

    /** @return the model tag that produced {@link #getOutputs()} */
    public String getModel() {
        return model;
    }

    /** @param model the model tag to record */
    public void setModel(String model) {
        this.model = model;
    }

    /** @return the prompt version that produced {@link #getOutputs()} */
    public int getPromptVersion() {
        return promptVersion;
    }

    /** @param promptVersion the prompt version to record */
    public void setPromptVersion(int promptVersion) {
        this.promptVersion = promptVersion;
    }

    /** @return when the model was asked */
    public OffsetDateTime getInterpretedAt() {
        return interpretedAt;
    }

    /** @param interpretedAt when the model was asked */
    public void setInterpretedAt(OffsetDateTime interpretedAt) {
        this.interpretedAt = interpretedAt;
    }

    /** @return when this row was last read; drives eviction order */
    public OffsetDateTime getLastHitAt() {
        return lastHitAt;
    }

    /** @param lastHitAt when this row was last read */
    public void setLastHitAt(OffsetDateTime lastHitAt) {
        this.lastHitAt = lastHitAt;
    }

    /** @return how many times this row has been read */
    public long getHitCount() {
        return hitCount;
    }

    /** @param hitCount the read count to set */
    public void setHitCount(long hitCount) {
        this.hitCount = hitCount;
    }

    /**
     * Records a read, for eviction ordering.
     *
     * @param now the current time
     */
    public void recordHit(OffsetDateTime now) {
        this.hitCount++;
        this.lastHitAt = now;
    }

    /**
     * @param currentPromptVersion the prompt version in force
     * @return {@code true} if this row predates the current prompt and must be
     *         treated as a miss
     */
    public boolean isStale(int currentPromptVersion) {
        return promptVersion < currentPromptVersion;
    }
}
