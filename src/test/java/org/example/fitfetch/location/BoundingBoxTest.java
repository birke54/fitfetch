package org.example.fitfetch.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BoundingBoxTest {

    private static final double ORIGIN_LAT = 47.7231;
    private static final double ORIGIN_LON = -122.2967;

    /** The point {@code miles} from a start point along a bearing, on the same sphere. */
    private static double[] destination(double latitude, double longitude, double miles, double bearingDegrees) {
        double angular = miles / BoundingBox.EARTH_RADIUS_MILES;
        double lat = Math.toRadians(latitude);
        double bearing = Math.toRadians(bearingDegrees);
        double lat2 = Math.asin(Math.sin(lat) * Math.cos(angular)
                + Math.cos(lat) * Math.sin(angular) * Math.cos(bearing));
        double lon2 = Math.toRadians(longitude) + Math.atan2(
                Math.sin(bearing) * Math.sin(angular) * Math.cos(lat),
                Math.cos(angular) - Math.sin(lat) * Math.sin(lat2));
        return new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)};
    }

    private static boolean contains(BoundingBox box, double[] point) {
        return point[0] >= box.minLatitude() && point[0] <= box.maxLatitude()
                && point[1] >= box.minLongitude() && point[1] <= box.maxLongitude();
    }

    @ParameterizedTest(name = "[{index}] {2} miles from {0}, {1}")
    @CsvSource({
            "47.7231, -122.2967, 50",
            "47.7231, -122.2967, 500",
            "0, 0, 50",
            "-33.8688, 151.2093, 100",
            "70, 20, 300"
    })
    @DisplayName("Every point on the circle falls inside the box")
    void testEnclosesCircle(double latitude, double longitude, double miles) {
        // The box only prefilters; excluding a point the circle includes would
        // silently drop a job that is in range.
        BoundingBox box = BoundingBox.around(latitude, longitude, miles);

        for (int bearing = 0; bearing < 360; bearing++) {
            double[] point = destination(latitude, longitude, miles * 0.9999, bearing);
            assertTrue(contains(box, point),
                    "bearing " + bearing + " at " + point[0] + ", " + point[1] + " is outside " + box);
        }
    }

    @Test
    @DisplayName("The box is tight: the circle reaches every edge")
    void testBoxIsTight() {
        BoundingBox box = BoundingBox.around(ORIGIN_LAT, ORIGIN_LON, 50);

        double maxLatSeen = -90;
        double maxLonSeen = -180;
        for (int tenth = 0; tenth < 3600; tenth++) {
            double[] point = destination(ORIGIN_LAT, ORIGIN_LON, 50, tenth / 10.0);
            maxLatSeen = Math.max(maxLatSeen, point[0]);
            maxLonSeen = Math.max(maxLonSeen, point[1]);
        }
        assertEquals(box.maxLatitude(), maxLatSeen, 1e-6);
        assertEquals(box.maxLongitude(), maxLonSeen, 1e-4);
    }

    @Test
    @DisplayName("The longitude span is wider than radius / cos(latitude), which falls short")
    void testLongitudeSpanIsExact() {
        // A circle is widest poleward of its centre. The common estimate uses the
        // centre's latitude and so under-covers, most visibly at high latitude.
        double miles = 300;
        BoundingBox box = BoundingBox.around(70, 20, miles);
        double estimate = Math.toDegrees(miles / BoundingBox.EARTH_RADIUS_MILES) / Math.cos(Math.toRadians(70));

        assertTrue(box.maxLongitude() - 20 > estimate);
    }

    @Test
    @DisplayName("A circle taking in a pole spans every meridian")
    void testPole() {
        BoundingBox box = BoundingBox.around(89.5, 10, 100);

        assertEquals(90, box.maxLatitude());
        assertEquals(-180, box.minLongitude());
        assertEquals(180, box.maxLongitude());
    }

    @Test
    @DisplayName("A circle crossing the antimeridian spans every meridian rather than wrapping")
    void testAntimeridian() {
        BoundingBox box = BoundingBox.around(-17.7, 179.9, 50);

        assertEquals(-180, box.minLongitude());
        assertEquals(180, box.maxLongitude());
        assertTrue(box.maxLatitude() < 0, "latitude is still bounded");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -5, Double.NaN, Double.POSITIVE_INFINITY})
    @DisplayName("A radius that is not positive and finite is rejected")
    void testInvalidRadius(double miles) {
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.around(ORIGIN_LAT, ORIGIN_LON, miles));
    }
}
