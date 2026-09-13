package org.example.fitfetch.location;

import org.example.fitfetch.location.records.GoogleGeocodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * {@link Geocoder} backed by the Google Geocoding API.
 *
 * <p>Google reports application-level outcomes over an HTTP 200, so the status
 * code alone says almost nothing: a quota breach, a rejected key and a
 * successful match all arrive as 200. The mapping from {@code status} to either
 * a {@link GeocodeOutcome} or a {@link GeocodingException} is therefore the
 * whole job of this class, and it is what keeps a transient failure from ever
 * being written to the cache as a verdict about a place.
 *
 * <table>
 *   <caption>Status handling</caption>
 *   <tr><th>Google status</th><th>Result</th></tr>
 *   <tr><td>{@code OK}</td><td>outcome with coordinates</td></tr>
 *   <tr><td>{@code ZERO_RESULTS}</td><td>negative outcome, cacheable</td></tr>
 *   <tr><td>{@code INVALID_REQUEST}</td><td>negative outcome, cacheable</td></tr>
 *   <tr><td>{@code OVER_QUERY_LIMIT}, {@code OVER_DAILY_LIMIT}</td>
 *       <td>retryable exception</td></tr>
 *   <tr><td>{@code UNKNOWN_ERROR}</td><td>retryable exception</td></tr>
 *   <tr><td>{@code REQUEST_DENIED}</td><td>fatal exception</td></tr>
 * </table>
 *
 * <p>This class performs no caching, batching or rate limiting; those belong to
 * the layer above. It also does not retry, since the caller owns the backoff
 * policy and the daily call budget.
 *
 * <p>Instances are immutable and safe to share.
 */
public class GoogleGeocoder implements Geocoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleGeocoder.class);
    private static final String DEFAULT_BASE_URL = "https://maps.googleapis.com";
    private static final String GEOCODE_PATH = "/maps/api/geocode/json";

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    /**
     * @param restClient the HTTP client; configure timeouts on it, since a hung
     *                   lookup must fail the pass rather than block it
     * @param apiKey     the Google Maps Platform API key
     */
    public GoogleGeocoder(RestClient restClient, String apiKey) {
        this(restClient, apiKey, DEFAULT_BASE_URL);
    }

    /**
     * @param restClient the HTTP client
     * @param apiKey     the Google Maps Platform API key
     * @param baseUrl    API base URL; overridable so tests can point elsewhere
     */
    public GoogleGeocoder(RestClient restClient, String apiKey, String baseUrl) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.apiKey = apiKey.trim();
        String trimmed = baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public GeocodeOutcome geocode(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        GoogleGeocodeResponse response;
        try {
            response = restClient.get()
                    .uri(buildUri(query))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GoogleGeocodeResponse.class);
        } catch (RestClientException e) {
            // Deliberately reports the query, never the URI: the URI carries the
            // API key, and exception messages end up in logs.
            throw new GeocodingException("Geocoding transport failed for '" + query + "'", false, e);
        }

        if (response == null || response.status() == null) {
            throw new GeocodingException("Geocoding returned no status for '" + query + "'", false);
        }

        return switch (response.status()) {
            case "OK" -> toOutcome(response, query);
            case "ZERO_RESULTS" -> {
                LOGGER.debug("Geocoding found no place for '{}'", query);
                yield GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS);
            }
            case "INVALID_REQUEST" -> {
                LOGGER.warn("Geocoding rejected '{}' as malformed: {}", query, response.errorMessage());
                yield GeocodeOutcome.empty(GeocodeStatus.INVALID_REQUEST);
            }
            case "OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT" -> throw new GeocodingException(
                    "Geocoding quota exhausted while resolving '" + query + "'", false);
            case "REQUEST_DENIED" -> throw new GeocodingException(
                    "Geocoding request denied while resolving '" + query
                            + "' -- check the API key and billing: " + response.errorMessage(), true);
            case "UNKNOWN_ERROR" -> throw new GeocodingException(
                    "Geocoding failed transiently for '" + query + "'", false);
            default -> throw new GeocodingException(
                    "Geocoding returned unrecognised status '" + response.status()
                            + "' for '" + query + "'", false);
        };
    }

    private URI buildUri(String query) {
        return UriComponentsBuilder.fromUriString(baseUrl + GEOCODE_PATH)
                .queryParam("address", query)
                .queryParam("key", apiKey)
                .build()
                .encode()
                .toUri();
    }

    private static GeocodeOutcome toOutcome(GoogleGeocodeResponse response, String query) {
        List<GoogleGeocodeResponse.Result> results = response.results();
        if (results == null || results.isEmpty()) {
            // OK with nothing in it should not happen, but treating it as a
            // successful match would produce a row with no coordinates.
            LOGGER.warn("Geocoding reported OK but returned no results for '{}'", query);
            return GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS);
        }

        GoogleGeocodeResponse.Result best = results.getFirst();
        GoogleGeocodeResponse.LatLng location =
                best.geometry() == null ? null : best.geometry().location();
        if (location == null || location.lat() == null || location.lng() == null) {
            LOGGER.warn("Geocoding result for '{}' carried no coordinates", query);
            return GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS);
        }

        boolean partial = Boolean.TRUE.equals(best.partialMatch());
        if (partial) {
            // Google matched something other than what was asked for. Worth
            // recording: it usually means the query was too vague to trust.
            LOGGER.debug("Geocoding only partially matched '{}' -> {}", query, best.formattedAddress());
        }

        return new GeocodeOutcome(
                GeocodeStatus.OK,
                location.lat(),
                location.lng(),
                best.formattedAddress(),
                best.placeId(),
                best.geometry().locationType(),
                partial);
    }
}
