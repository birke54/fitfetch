package org.example.fitfetch.location.records;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body from the Google Geocoding API's
 * {@code GET /maps/api/geocode/json} endpoint.
 *
 * <p>Only the fields the pipeline stores are modelled; the API also returns
 * address components, viewports, plus codes and place types, none of which are
 * used. Unknown keys are ignored on deserialization.
 *
 * <p>Note that {@code status} reports application-level outcomes over an HTTP
 * 200: a quota breach or a rejected key arrives as a successful response with a
 * failing status, so the HTTP code alone tells you almost nothing.
 *
 * @param status       the application-level outcome, for example {@code "OK"},
 *                     {@code "ZERO_RESULTS"} or {@code "OVER_QUERY_LIMIT"}
 * @param results      matched places, best first; empty unless {@code status} is
 *                     {@code "OK"}
 * @param errorMessage a human-readable explanation present on some failures
 *                     ({@code error_message})
 */
public record GoogleGeocodeResponse(
        String status,
        List<Result> results,
        @JsonProperty("error_message") String errorMessage
) {

    /**
     * One matched place.
     *
     * @param formattedAddress Google's canonical rendering of the place
     *                         ({@code formatted_address})
     * @param geometry         the coordinates and their precision
     * @param placeId          Google's stable identifier for the place
     *                         ({@code place_id})
     * @param partialMatch     whether Google matched something other than what
     *                         was asked for ({@code partial_match}); absent from
     *                         the payload when false
     */
    public record Result(
            @JsonProperty("formatted_address") String formattedAddress,
            Geometry geometry,
            @JsonProperty("place_id") String placeId,
            @JsonProperty("partial_match") Boolean partialMatch
    ) {
    }

    /**
     * @param location     the matched coordinates
     * @param locationType precision of the match, for example {@code "ROOFTOP"}
     *                     or {@code "APPROXIMATE"} ({@code location_type}). A
     *                     country-level query returns {@code APPROXIMATE} at a
     *                     national centroid, which is a real point in the middle
     *                     of nowhere rather than an error
     */
    public record Geometry(
            LatLng location,
            @JsonProperty("location_type") String locationType
    ) {
    }

    /**
     * @param lat latitude in decimal degrees
     * @param lng longitude in decimal degrees. Note Google's key is {@code lng},
     *            not {@code lon}
     */
    public record LatLng(Double lat, Double lng) {
    }
}
