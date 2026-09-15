package org.example.fitfetch.location;

/**
 * The latitude/longitude rectangle enclosing a circle on the earth's surface.
 *
 * <p>It exists to make a radius search cheap rather than to decide it. The
 * rectangle is a plain range on each column, which the
 * {@code idx_job_locations_lat_lon} index can serve, so the exact great-circle
 * distance is only computed for the few rows inside it. It must therefore never
 * exclude a point the circle includes; being too wide only costs a few extra
 * distance checks.
 *
 * <p>The longitude span is not {@code radius / cos(latitude)}. A circle is
 * widest poleward of its centre, so that estimate falls short; the span here is
 * the exact one for a sphere. Two cases fall back to every meridian: a circle
 * that takes in a pole, which really does span them all, and one that crosses
 * the antimeridian, which a single {@code BETWEEN} cannot wrap around.
 *
 * @param minLatitude  southern edge, decimal degrees
 * @param maxLatitude  northern edge, decimal degrees
 * @param minLongitude western edge, decimal degrees
 * @param maxLongitude eastern edge, decimal degrees
 */
public record BoundingBox(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {

    /**
     * Mean earth radius in miles. The radius search takes it as a parameter, so
     * the rectangle and the distance check always agree on the sphere.
     */
    public static final double EARTH_RADIUS_MILES = 3958.8;

    private static final double HALF_PI = Math.PI / 2;

    /**
     * @param latitude  centre latitude, decimal degrees
     * @param longitude centre longitude, decimal degrees
     * @param miles     circle radius; must be positive and finite
     * @return the smallest rectangle enclosing the circle, or a wider one when
     *         the circle takes in a pole or crosses the antimeridian
     */
    public static BoundingBox around(double latitude, double longitude, double miles) {
        if (!(miles > 0) || Double.isInfinite(miles)) {
            throw new IllegalArgumentException("miles must be positive and finite, but was " + miles);
        }
        double angular = miles / EARTH_RADIUS_MILES;
        double lat = Math.toRadians(latitude);
        double lon = Math.toRadians(longitude);
        double minLat = lat - angular;
        double maxLat = lat + angular;

        if (minLat <= -HALF_PI || maxLat >= HALF_PI) {
            return new BoundingBox(Math.max(Math.toDegrees(minLat), -90), Math.min(Math.toDegrees(maxLat), 90),
                    -180, 180);
        }

        // Where a meridian is tangent to the circle. Defined because the circle
        // stops short of both poles, which makes sin(angular) < cos(lat).
        double deltaLon = Math.asin(Math.sin(angular) / Math.cos(lat));
        double minLon = lon - deltaLon;
        double maxLon = lon + deltaLon;
        if (minLon < -Math.PI || maxLon > Math.PI) {
            return new BoundingBox(Math.toDegrees(minLat), Math.toDegrees(maxLat), -180, 180);
        }
        return new BoundingBox(Math.toDegrees(minLat), Math.toDegrees(maxLat),
                Math.toDegrees(minLon), Math.toDegrees(maxLon));
    }
}
