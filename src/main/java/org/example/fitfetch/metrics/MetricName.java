package org.example.fitfetch.metrics;

/**
 * Catalogue of the metric names emitted through {@link MetricService}.
 *
 * <p>Each constant maps to the dotted string Micrometer registers the meter
 * under. Referencing the enum instead of the raw string keeps names consistent
 * and greppable.
 *
 * @see MetricService#recordCounter(MetricName, java.util.Map)
 */
public enum MetricName {
    /** A slug's jobs were fetched successfully ({@code fetching.success.count}). */
    SLUG_FETCH_SUCCESS_COUNT("fetching.success.count"),
    /** A slug fetch failed with an HTTP client or server error ({@code fetching.error.count}). */
    SLUG_FETCH_ERROR_COUNT("fetching.error.count"),
    /** A slug fetch returned a {@code null} or bodyless response ({@code fetching.null.response.count}). */
    SLUG_FETCH_NULL_RESPONSE_COUNT("fetching.null.response.count"),
    /** An ATS answered {@code 429 Too Many Requests}, pausing requests to it ({@code fetching.throttled.count}). */
    FETCH_THROTTLED_COUNT("fetching.throttled.count"),
    /**
     * An ATS's fetch cycle ended early because it asked for a pause longer than
     * {@code max-wait} ({@code fetching.stopped.count}).
     */
    FETCH_STOPPED_COUNT("fetching.stopped.count"),
    /**
     * A call to the location model produced no usable location, tagged with the
     * {@code reason} ({@code location.llm.extraction.failure.count}). Counted per
     * call, so a label whose output is unusable twice counts twice.
     */
    LOCATION_LLM_EXTRACTION_FAILURE_COUNT("location.llm.extraction.failure.count"),
    /**
     * A request to the Google Geocoding API, timed and tagged with its
     * {@code status} ({@code location.geocode.request}). A timer, so its count
     * is the number of requests: what Google bills for.
     */
    LOCATION_GEOCODE_REQUEST("location.geocode.request"),
    /**
     * A geocode lookup answered from the cache or not, tagged with the
     * {@code result} ({@code location.geocode.cache.count}).
     */
    LOCATION_GEOCODE_CACHE_COUNT("location.geocode.cache.count"),
    /**
     * A location pass stopped before writing its page, tagged with the
     * {@code reason} ({@code location.pass.stopped.count}).
     */
    LOCATION_PASS_STOPPED_COUNT("location.pass.stopped.count");

    private final String metricName;

    MetricName(String metricName) {
        this.metricName = metricName;
    }

    /**
     * @return the dotted metric name to register with Micrometer
     */
    public String metricName() {
        return metricName;
    }
}
