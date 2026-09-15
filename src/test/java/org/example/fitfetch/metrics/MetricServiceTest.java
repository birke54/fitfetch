package org.example.fitfetch.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    @DisplayName("Null tags record an untagged timer")
    void testRecordTimerWithoutTags() {
        metricService.recordTimer(MetricName.LOCATION_GEOCODE_REQUEST, null, Duration.ofMillis(5));

        assertEquals(1, registry.get("location.geocode.request").timer().count());
    }
}
