package org.example.fitfetch.location;

/**
 * A circle around the search origin, with the rectangle that encloses it: every
 * parameter a radius query needs, in one value.
 *
 * <p>Obtain one from {@link RadiusSearchService#around(double)}, which looks up
 * the origin as it is configured now.
 *
 * @param latitude  origin latitude, decimal degrees
 * @param longitude origin longitude, decimal degrees
 * @param miles     radius
 * @param box       the rectangle enclosing the circle, for the index prefilter
 */
public record OriginRadius(double latitude, double longitude, double miles, BoundingBox box) {

    /**
     * @param latitude  origin latitude, decimal degrees
     * @param longitude origin longitude, decimal degrees
     * @param miles     radius; must be positive and finite
     * @return the circle, with its bounding box computed
     */
    public static OriginRadius of(double latitude, double longitude, double miles) {
        return new OriginRadius(latitude, longitude, miles, BoundingBox.around(latitude, longitude, miles));
    }
}
