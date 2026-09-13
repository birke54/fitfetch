package org.example.fitfetch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.example.fitfetch.location.GeocodeOutcome;
import org.example.fitfetch.location.GeocodeStatus;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * JPA entity for a cached geocode lookup, stored in the {@code geocode_cache}
 * table.
 *
 * <p>Only outcomes that say something durable about the query are ever stored,
 * which is enforced by {@link GeocodeStatus} modelling nothing else. A quota
 * breach or a rejected key cannot reach this table, so a momentary failure can
 * never become a permanent verdict that a place does not exist.
 *
 * <p>Coordinates are refreshed on access rather than on a schedule: an entry
 * older than the configured window is treated as a miss and looked up again.
 * That is self-limiting by construction, since only entries actually being read
 * cost anything, and it keeps the cache within the terms that allow a place
 * identifier to be retained indefinitely while coordinates are not.
 *
 * @see org.example.fitfetch.location.CachingGeocoder
 */
@Entity
@Table(
        name = "geocode_cache",
        indexes = @Index(name = "idx_geocode_cache_lru", columnList = "last_hit_at, hit_count")
)
public class GeocodeCacheEntry {

    /** The normalized query. Post-split single locations, comfortably inside 255. */
    @Id
    @Column(name = "query_key", nullable = false, length = 255)
    private String queryKey;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "formatted_address", columnDefinition = "text")
    private String formattedAddress;

    /**
     * Google's stable identifier for the place. Retained separately from the
     * coordinates because it is the part of a result that does not go stale, and
     * it is how a refresh correlates back to the same place.
     */
    @Column(name = "place_id", length = 255)
    private String placeId;

    @Column(name = "location_type", length = 32)
    private String locationType;

    @Column(name = "partial_match")
    private Boolean partialMatch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GeocodeStatus status;

    @Column(name = "resolved_at", nullable = false)
    private OffsetDateTime resolvedAt;

    @Column(name = "refreshed_at")
    private OffsetDateTime refreshedAt;

    @Column(name = "last_hit_at", nullable = false)
    private OffsetDateTime lastHitAt;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected GeocodeCacheEntry() {
    }

    /**
     * @param queryKey the normalized query this row answers
     * @param outcome  the cacheable lookup outcome
     * @param now      the time of the lookup
     */
    public GeocodeCacheEntry(String queryKey, GeocodeOutcome outcome, OffsetDateTime now) {
        this.queryKey = queryKey;
        apply(outcome, now);
        this.lastHitAt = now;
        this.hitCount = 0L;
    }

    /**
     * Overwrites this row with a fresh lookup, leaving read statistics intact.
     *
     * @param outcome the new outcome
     * @param now     the time of the lookup
     */
    public void apply(GeocodeOutcome outcome, OffsetDateTime now) {
        this.status = outcome.status();
        this.latitude = outcome.latitude();
        this.longitude = outcome.longitude();
        this.formattedAddress = outcome.formattedAddress();
        this.placeId = outcome.placeId();
        this.locationType = outcome.locationType();
        this.partialMatch = outcome.partialMatch();
        this.resolvedAt = now;
        this.refreshedAt = now;
    }

    /** @return this row as the outcome it caches */
    public GeocodeOutcome toOutcome() {
        return new GeocodeOutcome(status, latitude, longitude, formattedAddress, placeId,
                locationType, Boolean.TRUE.equals(partialMatch));
    }

    /**
     * Whether the coordinates here are old enough to be looked up again.
     *
     * <p>Only successful lookups go stale. A negative entry is pruned on its own
     * short schedule instead, so refreshing it would spend a call to re-learn
     * that a string still names no place.
     *
     * @param ttl how long coordinates stay usable
     * @param now the current time
     * @return {@code true} if this row should be treated as a miss
     */
    public boolean isStale(Duration ttl, OffsetDateTime now) {
        if (status != GeocodeStatus.OK) {
            return false;
        }
        OffsetDateTime asOf = refreshedAt != null ? refreshedAt : resolvedAt;
        return asOf == null || asOf.plus(ttl).isBefore(now);
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

    /** @return the normalized query this row answers */
    public String getQueryKey() {
        return queryKey;
    }

    /** @return latitude in decimal degrees, or {@code null} for a negative entry */
    public Double getLatitude() {
        return latitude;
    }

    /** @return longitude in decimal degrees, or {@code null} for a negative entry */
    public Double getLongitude() {
        return longitude;
    }

    /** @return Google's canonical rendering of the place */
    public String getFormattedAddress() {
        return formattedAddress;
    }

    /** @return Google's stable identifier for the place */
    public String getPlaceId() {
        return placeId;
    }

    /** @return precision of the match, for example {@code ROOFTOP} */
    public String getLocationType() {
        return locationType;
    }

    /** @return whether the geocoder matched something other than what was asked */
    public Boolean getPartialMatch() {
        return partialMatch;
    }

    /** @return why this row holds what it holds */
    public GeocodeStatus getStatus() {
        return status;
    }

    /** @return when this query was first resolved */
    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    /** @return when the coordinates were last refreshed */
    public OffsetDateTime getRefreshedAt() {
        return refreshedAt;
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
}
