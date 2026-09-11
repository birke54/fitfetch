package org.example.fitfetch.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Thin wrapper over the Micrometer {@link MeterRegistry} that records
 * application metrics using the {@link MetricName} and {@link TagName} enums
 * instead of raw strings.
 *
 * <p>Centralizing metric emission here keeps metric and tag naming consistent
 * across the codebase; callers such as
 * {@link org.example.fitfetch.ats.GreenhouseAts} only reference the enums.
 */
@Service
public class MetricService {
    private final MeterRegistry meterRegistry;

    /**
     * @param meterRegistry the Micrometer registry all metrics are published to
     */
    public MetricService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments the counter identified by {@code metricName} by one, attaching
     * the given tags.
     *
     * <p>Each map entry becomes a Micrometer {@link Tag} keyed by
     * {@link TagName#key()}. Micrometer identifies a counter by the combination
     * of name and tag set, so a given name emitted with different tag values
     * produces separate time series.
     *
     * @param metricName the metric to increment
     * @param tags       tag name/value pairs to attach; may be {@code null} or
     *                   empty for an untagged counter
     */
    public void recordCounter(MetricName metricName, Map<TagName, String> tags) {
        if (tags == null) {
            tags = Map.of();
        }
        meterRegistry.counter(
                metricName.metricName(),
                tags.entrySet().stream()
                        .map(entry -> Tag.of(entry.getKey().key(), entry.getValue()))
                        .toList()
        ).increment();
    }
}
