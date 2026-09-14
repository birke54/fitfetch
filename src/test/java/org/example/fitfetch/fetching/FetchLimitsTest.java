package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FetchLimitsTest {

    private static FetchLimits bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.fetch.limits", FetchLimits.class)
                .get();
    }

    private static final Map<String, String> DEFAULTS = Map.of(
            "app.fetch.limits.defaults.requests-per-second", "2",
            "app.fetch.limits.defaults.max-concurrent", "4",
            "app.fetch.limits.defaults.max-wait", "PT2M",
            "app.fetch.limits.defaults.initial-backoff", "PT5S",
            "app.fetch.limits.defaults.max-backoff", "PT5M");

    @Test
    @DisplayName("An ATS without an entry gets the defaults")
    void testDefaults() {
        FetchLimits limits = bind(DEFAULTS);

        assertEquals(new FetchLimits.Limit(2.0, 4, Duration.ofMinutes(2), Duration.ofSeconds(5),
                Duration.ofMinutes(5)), limits.forAts(AtsName.GREENHOUSE));
    }

    @Test
    @DisplayName("An entry keyed by the lower-case ATS name overrides only what it sets")
    void testPartialOverride() {
        Map<String, String> properties = new java.util.HashMap<>(DEFAULTS);
        properties.put("app.fetch.limits.ats.greenhouse.requests-per-second", "5");
        properties.put("app.fetch.limits.ats.greenhouse.max-concurrent", "8");

        FetchLimits.Limit greenhouse = bind(properties).forAts(AtsName.GREENHOUSE);

        assertEquals(5.0, greenhouse.requestsPerSecond());
        assertEquals(8, greenhouse.maxConcurrent());
        assertEquals(Duration.ofMinutes(2), greenhouse.maxWait(), "left unset, so taken from the defaults");
        assertEquals(Duration.ofSeconds(5), greenhouse.initialBackoff());
    }

    @Test
    @DisplayName("Defaults missing a field fail at startup rather than leaving some ATS unlimited")
    void testIncompleteDefaultsRejected() {
        Map<String, String> properties = Map.of(
                "app.fetch.limits.defaults.requests-per-second", "2");

        Exception error = assertThrows(Exception.class, () -> bind(properties));
        assertTrue(rootCause(error) instanceof IllegalArgumentException, error.toString());
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
