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
    SLUG_FETCH_NULL_RESPONSE_COUNT("fetching.null.response.count");

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
