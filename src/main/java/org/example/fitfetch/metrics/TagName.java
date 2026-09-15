package org.example.fitfetch.metrics;

/**
 * Catalogue of the dimension (tag) keys attached to metrics emitted through
 * {@link MetricService}.
 *
 * <p>Each constant maps to the string key Micrometer uses for the tag.
 * Referencing the enum instead of the raw string keeps tag keys consistent
 * across metrics.
 *
 * @see MetricService#recordCounter(MetricName, java.util.Map)
 */
public enum TagName {
    /** Why an operation took a given path, e.g. an error category ({@code reason}). */
    REASON("reason"),
    /** The ATS provider a metric relates to ({@code ats}). */
    ATS("ats"),
    /** The company board identifier a metric relates to ({@code slug}). */
    SLUG("slug"),
    /** The status an external service answered with ({@code status}). */
    STATUS("status"),
    /** How a lookup was answered, e.g. from the cache or not ({@code result}). */
    RESULT("result");

    private final String key;

    TagName(String key) {
        this.key = key;
    }

    /**
     * @return the tag key string used when building a Micrometer tag
     */
    public String key() {
        return key;
    }
}
