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
import org.example.fitfetch.location.GeocodeOutcome;
import org.example.fitfetch.location.LocationInput;
import org.example.fitfetch.location.Resolution;
import org.example.fitfetch.location.SourceTier;

import java.time.OffsetDateTime;

/**
 * JPA entity for one resolved location of a fetched job, stored in the
 * {@code job_locations} table.
 *
 * <p>A job has as many rows here as the posting named places. Keeping all of
 * them is the point: collapsing a job to a single location would filter out a
 * role with an office near the user simply because a different office happened
 * to be listed first.
 *
 * <p>Coordinates are held here outright rather than joined from
 * {@link GeocodeCacheEntry}. Both caches are evicted under a size bound, so a
 * foreign key into one would either block eviction or null out coordinates on
 * live jobs. A cache is disposable; job data is not.
 *
 * <p>{@link #getResolution() resolution} is what display code must read, never
 * the coordinate. Several resolutions share the search origin as their
 * coordinate, so a remote-US role, a genuine local role and a posting with no
 * location data are indistinguishable by position alone &mdash; but rendering
 * the first as "Seattle, WA" would be a lie to the reader.
 *
 * @see org.example.fitfetch.location.LocationService
 */
@Entity
@Table(
        name = "job_locations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_job_locations_job_raw_resolution",
                columnNames = {"fetched_job_id", "raw", "resolution"}
        ),
        indexes = {
                @Index(name = "idx_job_locations_fetched_job", columnList = "fetched_job_id"),
                @Index(name = "idx_job_locations_resolution", columnList = "resolution")
        }
)
public class JobLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "fetched_job_id", nullable = false)
    private Long fetchedJobId;

    /**
     * The verbatim element this row came from, not the whole location label.
     * {@code "Dublin, London"} yields two rows. Keeping it is what lets a rule
     * change be replayed over stored rows without re-calling the model or the
     * geocoder.
     */
    @Column(name = "raw", nullable = false, columnDefinition = "text")
    private String raw;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 24)
    private Resolution resolution;

    @Column(name = "geocode_query", length = 255)
    private String geocodeQuery;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "formatted_address", columnDefinition = "text")
    private String formattedAddress;

    @Column(name = "place_id", length = 255)
    private String placeId;

    @Column(name = "region_code", length = 8)
    private String regionCode;

    /** Drives display ordering only ("Seattle, WA + 2 more"), never filtering. */
    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_tier", nullable = false, length = 16)
    private SourceTier sourceTier;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected JobLocation() {
    }

    /**
     * @param fetchedJobId the job this location belongs to
     * @param input        the resolved location
     * @param outcome      the geocode result, or {@code null} when the
     *                     resolution carries no coordinates
     * @param sourceTier   which tier answered the label
     * @param primary      whether this is the location to lead with in display
     * @param now          creation time
     */
    public JobLocation(Long fetchedJobId, LocationInput input, GeocodeOutcome outcome,
                       SourceTier sourceTier, boolean primary, OffsetDateTime now) {
        this.fetchedJobId = fetchedJobId;
        this.raw = input.raw();
        this.resolution = input.resolution();
        this.geocodeQuery = input.geocodeQuery();
        this.regionCode = input.regionCode();
        this.sourceTier = sourceTier;
        this.primary = primary;
        this.createdAt = now;
        if (outcome != null) {
            this.latitude = outcome.latitude();
            this.longitude = outcome.longitude();
            this.formattedAddress = outcome.formattedAddress();
            this.placeId = outcome.placeId();
        }
    }

    /**
     * @return {@code true} if this row can ever match a radius search. A row
     *         without coordinates is kept so the job stays visible in the
     *         curation worklist, but it matches nothing
     */
    public boolean isMatchable() {
        return latitude != null && longitude != null;
    }

    /** @return the generated primary key, or {@code null} before persistence */
    public Long getId() {
        return id;
    }

    /** @return the job this location belongs to */
    public Long getFetchedJobId() {
        return fetchedJobId;
    }

    /** @return the verbatim element this row was resolved from */
    public String getRaw() {
        return raw;
    }

    /** @return why this row holds the coordinate it holds */
    public Resolution getResolution() {
        return resolution;
    }

    /** @return the string sent to the geocoder, or {@code null} if none was */
    public String getGeocodeQuery() {
        return geocodeQuery;
    }

    /** @return latitude in decimal degrees, or {@code null} if unresolved */
    public Double getLatitude() {
        return latitude;
    }

    /** @return longitude in decimal degrees, or {@code null} if unresolved */
    public Double getLongitude() {
        return longitude;
    }

    /** @return the geocoder's canonical rendering of the place */
    public String getFormattedAddress() {
        return formattedAddress;
    }

    /** @return the geocoder's stable identifier for the place */
    public String getPlaceId() {
        return placeId;
    }

    /** @return ISO 3166-1 alpha-2, or 3166-2 for a subdivision */
    public String getRegionCode() {
        return regionCode;
    }

    /** @return whether this is the location to lead with in display */
    public boolean isPrimary() {
        return primary;
    }

    /** @param primary whether this is the location to lead with */
    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    /** @return which tier of the pipeline answered this label */
    public SourceTier getSourceTier() {
        return sourceTier;
    }

    /** @return when this row was written */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
