package org.example.fitfetch.metrics;

/**
 * Catalogue of the dimension (tag) keys attached to metrics emitted through
 * {@link MetricService}.
 *
 * <p>Each constant maps to the string key Micrometer uses for the tag.
 * Referencing the enum instead of the raw string keeps tag keys consistent
 * across metrics.
 *
 * <p>Every tag value must come from a small, fixed set. Each distinct value
 * starts a time series of its own, so a tag carrying a slug, a label or a
 * query would add one per board or place. There is no {@code slug} tag for
 * that reason: with thousands of boards it made the fetch counters thousands
 * of series each.
 *
 * @see MetricService#recordCounter(MetricName, java.util.Map)
 */
public enum TagName {
    /** Why an operation took a given path, e.g. an error category ({@code reason}). */
    REASON("reason"),
    /** The ATS provider a metric relates to ({@code ats}). */
    ATS("ats"),
    /**
     * A status, e.g. the one an external service answered with or the one a job
     * was written with ({@code status}).
     */
    STATUS("status"),
    /** How something was dealt with, e.g. a lookup answered from the cache ({@code result}). */
    RESULT("result"),
    /** Which tier of the location pipeline answered a label ({@code tier}). */
    TIER("tier"),
    /** Which check a result belongs to, e.g. one of a label audit's ({@code check}). */
    CHECK("check");

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
