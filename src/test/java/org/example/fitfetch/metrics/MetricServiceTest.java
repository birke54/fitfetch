package org.example.fitfetch.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MetricServiceTest {

    private SimpleMeterRegistry registry;
    private MetricService metricService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricService = new MetricService(registry);
    }

    @Test
    @DisplayName("A timing is recorded against the timer with its tags")
    void testRecordTimer() {
        metricService.recordTimer(MetricName.LOCATION_GEOCODE_REQUEST,
                Map.of(TagName.STATUS, "ok"), Duration.ofMillis(120));
        metricService.recordTimer(MetricName.LOCATION_GEOCODE_REQUEST,
                Map.of(TagName.STATUS, "ok"), Duration.ofMillis(80));

        Timer timer = registry.get("location.geocode.request").tag("status", "ok").timer();
        assertEquals(2, timer.count());
        assertEquals(200, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("Each tag value gets its own timer, so its count is a per-status request count")
    void testTimerPerTagValue() {
        metricService.recordTimer(MetricName.LOCATION_GEOCODE_REQUEST,
                Map.of(TagName.STATUS, "ok"), Duration.ofMillis(10));
        metricService.recordTimer(MetricName.LOCATION_GEOCODE_REQUEST,
                Map.of(TagName.STATUS, "transport"), Duration.ofMillis(10));

        assertEquals(1, registry.get("location.geocode.request").tag("status", "ok").timer().count());
        assertEquals(1, registry.get("location.geocode.request").tag("status", "transport").timer().count());
    }

    @Test
    @DisplayName("A gauge reads its current value each time it is collected")
    void testRegisterGauge() {
        AtomicLong backlog = new AtomicLong(5);
        metricService.registerGauge(MetricName.LOCATION_JOBS_BACKLOG,
                Map.of(TagName.STATUS, "pending"), backlog::get);

        Gauge gauge = registry.get("location.jobs.backlog").tag("status", "pending").gauge();
        assertEquals(5, gauge.value());
        backlog.set(9);
        assertEquals(9, gauge.value());
    }

    @Test
    @DisplayName("Null tags record an untagged timer")
    void testRecordTimerWithoutTags() {
        metricService.recordTimer(MetricName.LOCATION_GEOCODE_REQUEST, null, Duration.ofMillis(5));

        assertEquals(1, registry.get("location.geocode.request").timer().count());
    }

    @Test
    @DisplayName("A distribution counts each value into the buckets given")
    void testRecordDistribution() {
        double[] buckets = {2048, 4096, 8192};
        metricService.recordDistribution(MetricName.NORMALIZE_PROMPT_TOKENS, null, 1000, buckets);
        metricService.recordDistribution(MetricName.NORMALIZE_PROMPT_TOKENS, null, 3000, buckets);
        metricService.recordDistribution(MetricName.NORMALIZE_PROMPT_TOKENS, null, 8000, buckets);

        DistributionSummary summary = registry.get("normalize.prompt.tokens").summary();
        assertEquals(3, summary.count());
        assertEquals(8000, summary.max());
        CountAtBucket[] counts = summary.takeSnapshot().histogramCounts();
        assertEquals(3, counts.length);
        assertEquals(1, counts[0].count(), "at or under 2048");
        assertEquals(2, counts[1].count(), "at or under 4096");
        assertEquals(3, counts[2].count(), "at or under 8192");
    }
}
