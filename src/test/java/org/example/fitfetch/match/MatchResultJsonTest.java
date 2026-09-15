package org.example.fitfetch.match;

import org.example.fitfetch.match.MatchResult.BulletMatch;
import org.example.fitfetch.match.MatchResult.ScoreParts;
import org.example.fitfetch.match.MatchResult.SignalMatch;
import org.example.fitfetch.normalize.SignalClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchResultJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Signal matches and score parts survive a JSON round trip, as the job_matches columns store them")
    void testRoundTrip() {
        List<SignalMatch> signals = List.of(new SignalMatch(0, SignalClassification.REQUIRED_SKILL, 0.75,
                List.of(new BulletMatch("acme-kafka-migration", 0.83)), List.of("Kafka"), List.of("Spark")));
        ScoreParts parts = new ScoreParts(0.7, 0.5, 0.85, -15);

        List<SignalMatch> readSignals = MAPPER.readValue(MAPPER.writeValueAsString(signals), new TypeReference<>() {
        });
        ScoreParts readParts = MAPPER.readValue(MAPPER.writeValueAsString(parts), ScoreParts.class);

        assertEquals(signals, readSignals);
        assertEquals(parts, readParts);
    }
}
