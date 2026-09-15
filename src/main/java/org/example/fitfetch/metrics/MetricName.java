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
     * Jobs an ATS returned, tagged with the {@code result}: {@code new},
     * {@code known}, {@code filtered_title} or {@code invalid}
     * ({@code fetching.jobs.count}).
     */
    FETCH_JOBS_COUNT("fetching.jobs.count"),
    /** New jobs saved to {@code fetched_jobs} ({@code fetching.jobs.saved.count}). */
    FETCH_JOBS_SAVED_COUNT("fetching.jobs.saved.count"),
    /**
     * Gauge: when an ATS's jobs were last fetched and stored without an
     * exception, in epoch seconds, tagged with the {@code ats}
     * ({@code fetching.last.success.seconds}). Starts at the time the app
     * started, so its age is how long the ATS has gone without a successful
     * cycle.
     */
    FETCH_LAST_SUCCESS_SECONDS("fetching.last.success.seconds"),
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
    LOCATION_PASS_STOPPED_COUNT("location.pass.stopped.count"),
    /**
     * A label resolved to its locations, tagged with the {@code tier} that
     * answered it ({@code location.labels.resolved.count}).
     */
    LOCATION_LABELS_RESOLVED_COUNT("location.labels.resolved.count"),
    /**
     * Jobs whose locations were written, tagged with the {@code status} they
     * were written with ({@code location.jobs.written.count}).
     */
    LOCATION_JOBS_WRITTEN_COUNT("location.jobs.written.count"),
    /**
     * Jobs left pending by a pass, tagged with the {@code reason}
     * ({@code location.jobs.deferred.count}). Counted on every pass that defers
     * them.
     */
    LOCATION_JOBS_DEFERRED_COUNT("location.jobs.deferred.count"),
    /**
     * Gauge: jobs by location status, tagged with the {@code status},
     * {@code pending} or {@code failed} ({@code location.jobs.backlog}).
     * {@code failed} is the curation worklist. Refreshed after each successful
     * location pass, so a scrape never queries the database.
     */
    LOCATION_JOBS_BACKLOG("location.jobs.backlog"),
    /**
     * Gauge: when the location pass last ran without stopping, in epoch seconds
     * ({@code location.pass.last.success.seconds}). Starts at the time the app
     * started, so its age is how long the pass has gone without a successful run.
     */
    LOCATION_PASS_LAST_SUCCESS_SECONDS("location.pass.last.success.seconds");

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
