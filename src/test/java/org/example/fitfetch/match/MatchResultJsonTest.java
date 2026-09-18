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
                List.of(new BulletMatch("acme-kafka-migration", 0.83)), List.of("Kafka"), List.of("Spark"),
                List.of("AWS", "Azure", "GCP"), List.of("bounded suppression")));
        ScoreParts parts = new ScoreParts(0.7, 0.5, 0.85, -15, 4);

        List<SignalMatch> readSignals = MAPPER.readValue(MAPPER.writeValueAsString(signals), new TypeReference<>() {
        });
        ScoreParts readParts = MAPPER.readValue(MAPPER.writeValueAsString(parts), ScoreParts.class);

        assertEquals(signals, readSignals);
        assertEquals(parts, readParts);
    }

    @Test
    @DisplayName("A job whose skills were never scored keeps that apart from having scored zero")
    void testUnscoredSkillMatch() {
        ScoreParts parts = new ScoreParts(0.7, null, 0.85, 0, 1);

        ScoreParts read = MAPPER.readValue(MAPPER.writeValueAsString(parts), ScoreParts.class);

        assertNull(read.skillMatch());
        assertEquals(parts, read);
    }
}
