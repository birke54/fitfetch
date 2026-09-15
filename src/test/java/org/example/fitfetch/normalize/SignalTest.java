package org.example.fitfetch.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Signals survive a JSON round trip, as the signals column stores them")
    void testJsonRoundTrip() {
        // Signal has a second, shorter constructor; reading must still use the
        // full one, or skills and years would come back empty.
        List<Signal> signals = List.of(
                new Signal(SignalClassification.REQUIRED_SKILL, "Has 3+ years of Kubernetes on AWS or GCP.",
                        List.of("Kubernetes"), List.of("AWS", "GCP"), 3),
                new Signal(SignalClassification.CORE_RESPONSIBILITY, "Operates services."));

        String json = MAPPER.writeValueAsString(signals);
        List<Signal> read = MAPPER.readValue(json, new TypeReference<>() {
        });

        assertEquals(signals, read);
        assertTrue(json.contains("\"skills\":[\"Kubernetes\"]"), json);
        assertTrue(json.contains("\"anyOfSkills\":[\"AWS\",\"GCP\"]"), json);
        assertTrue(json.contains("\"minYears\":3"), json);
    }

    @Test
    @DisplayName("A signal stored before alternatives existed reads with none")
    void testReadsRowWithoutAlternatives() {
        String stored = """
                [{"classification":"REQUIRED_SKILL","text":"Knows Go.","skills":["Go"],"minYears":2}]""";

        List<Signal> read = MAPPER.readValue(stored, new TypeReference<>() {
        });

        assertEquals(List.of(new Signal(SignalClassification.REQUIRED_SKILL, "Knows Go.", List.of("Go"), 2)), read);
    }

    @Test
    @DisplayName("A signal rejects blank text and negative years")
    void testValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new Signal(SignalClassification.REQUIRED_SKILL, " ", List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Signal(SignalClassification.REQUIRED_SKILL, "Knows Go.", List.of("Go"), -1));
    }
}
