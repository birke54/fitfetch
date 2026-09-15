package org.example.fitfetch.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    public void recordCounter(MetricName metricName) {
        meterRegistry.counter(
                metricName.metricName(),
                new ArrayList<>()
        ).increment();
    }

    public void recordCounterByIncrement(MetricName metricName, Map<TagName, String> tags, int increment) {
        if (tags == null) {
            tags = Map.of();
        }
        meterRegistry.counter(
                metricName.metricName(),
                tags.entrySet().stream()
                        .map(entry -> Tag.of(entry.getKey().key(), entry.getValue()))
                        .toList()
        ).increment(increment);
    }

    /**
     * Records one timing against the timer identified by {@code metricName},
     * attaching the given tags.
     *
     * <p>The timer publishes a percentile histogram, so latency percentiles can
     * be computed in Prometheus across instances and time ranges. Its count
     * doubles as a counter of the timed operation.
     *
     * @param metricName the timer to record against
     * @param tags       tag name/value pairs to attach; may be {@code null} or
     *                   empty for an untagged timer
     * @param duration   how long the operation took
     */
    public void recordTimer(MetricName metricName, Map<TagName, String> tags, Duration duration) {
        if (tags == null) {
            tags = Map.of();
        }
        Timer.builder(metricName.metricName())
                .tags(tags.entrySet().stream()
                        .map(entry -> Tag.of(entry.getKey().key(), entry.getValue()))
                        .toList())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * Registers a gauge whose value is read from {@code value} each time metrics
     * are collected.
     *
     * <p>Register once per name and tag set, typically at construction, and
     * update whatever the supplier reads, such as an {@code AtomicLong} field.
     * The gauge holds the supplier strongly. Micrometer otherwise keeps only a
     * weak reference to what a gauge reads, and once that is collected the gauge
     * reports {@code NaN} instead of failing.
     *
     * @param metricName the gauge to register
     * @param tags       tag name/value pairs to attach; may be {@code null} or
     *                   empty for an untagged gauge
     * @param value      supplies the current value
     */
    public void registerGauge(MetricName metricName, Map<TagName, String> tags, Supplier<Number> value) {
        if (tags == null) {
            tags = Map.of();
        }
        Gauge.builder(metricName.metricName(), value)
                .tags(tags.entrySet().stream()
                        .map(entry -> Tag.of(entry.getKey().key(), entry.getValue()))
                        .toList())
                .strongReference(true)
                .register(meterRegistry);
    }
}
