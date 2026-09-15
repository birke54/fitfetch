package org.example.fitfetch.match;

import org.example.fitfetch.match.MatchResult.BulletMatch;
import org.example.fitfetch.normalize.Degree;
import org.example.fitfetch.normalize.HardRequirements;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.normalize.SignalClassification;
import org.example.fitfetch.normalize.Sponsorship;
import org.example.fitfetch.normalize.Track;
import org.example.fitfetch.profile.CandidateProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.example.fitfetch.match.MatchFixtures.SIGNAL;
import static org.example.fitfetch.match.MatchFixtures.job;
import static org.example.fitfetch.match.MatchFixtures.profile;
import static org.example.fitfetch.match.MatchFixtures.similarity;
import static org.junit.jupiter.api.Assertions.*;

class MatchScorerTest {

    private final MatchScorer scorer = new MatchScorer();

    private static Signal required(String text, String... skills) {
        return new Signal(SignalClassification.REQUIRED_SKILL, text, List.of(skills), 0);
    }

    private static List<float[]> signalVectors(int count) {
        return java.util.Collections.nCopies(count, SIGNAL);
    }

    // ------------------------------------------------------------- coverage

    @Test
    @DisplayName("Similarity at or above full covers a signal, at or below the floor covers none, linear between")
    void testRamp() {
        assertEquals(1.0, MatchScorer.ramp(0.9));
        assertEquals(1.0, MatchScorer.ramp(MatchScorer.SIMILARITY_FULL));
        assertEquals(0.0, MatchScorer.ramp(MatchScorer.SIMILARITY_FLOOR));
        assertEquals(0.0, MatchScorer.ramp(0.1));
        double mid = (MatchScorer.SIMILARITY_FLOOR + MatchScorer.SIMILARITY_FULL) / 2;
        assertEquals(0.5, MatchScorer.ramp(mid), 1e-9);
    }

    @Test
    @DisplayName("Each signal keeps its best bullets, most similar first, and is covered by the best one")
    void testBestBullets() {
        MatchResult result = scorer.score(job(required("Operates Kafka.")), signalVectors(1),
                profile(List.of(), "weak", "strong", "middling", "unrelated"),
                Map.of("weak", similarity(0.6), "strong", similarity(0.9), "middling", similarity(0.7),
                        "unrelated", similarity(0.1)));

        MatchResult.SignalMatch match = result.signals().getFirst();
        assertEquals(List.of("strong", "middling", "weak"),
                match.bestBullets().stream().map(BulletMatch::bulletId).toList());
        assertEquals(0.9, match.bestBullets().getFirst().similarity(), 1e-6);
        assertEquals(1.0, match.coverage(), "0.9 is above full");
    }

    @Test
    @DisplayName("A signal's skills the profile has cover it even when no bullet is similar")
    void testSkillsCoverSignal() {
        NormalizedData job = job(required("Knows Kafka and Spark.", "Kafka", "Spark"));
        CandidateProfile profile = profile(List.of(new CandidateProfile.Skill("Kafka", 4)), "one");

        MatchResult.SignalMatch match = scorer.score(job, signalVectors(1), profile,
                Map.of("one", similarity(0.1))).signals().getFirst();

        assertEquals(0.5, match.coverage(), 1e-9, "one of two skills");
        assertEquals(List.of("Kafka"), match.matchedSkills());
        assertEquals(List.of("Spark"), match.missingSkills());
    }

    @Test
    @DisplayName("A skill with fewer years than the signal asks for counts as missing")
    void testSkillYears() {
        Signal fiveYearsOfJava = new Signal(SignalClassification.REQUIRED_SKILL, "Has 5+ years of Java.",
                List.of("Java"), 5);
        CandidateProfile threeYears = profile(List.of(new CandidateProfile.Skill("java", 3)), "one");

        MatchResult.SignalMatch match = scorer.score(job(fiveYearsOfJava), signalVectors(1), threeYears,
                Map.of("one", similarity(0.1))).signals().getFirst();

        assertEquals(List.of("Java"), match.missingSkills());
        assertEquals(0.0, match.coverage());
    }

    @Test
    @DisplayName("Required signals outweigh responsibilities, which outweigh nice-to-haves")
    void testWeights() {
        assertTrue(MatchScorer.weight(SignalClassification.REQUIRED_SKILL)
                > MatchScorer.weight(SignalClassification.CORE_RESPONSIBILITY));
        assertTrue(MatchScorer.weight(SignalClassification.CORE_RESPONSIBILITY)
                > MatchScorer.weight(SignalClassification.PREFERRED_SKILL));

        // One covered required signal and one uncovered nice-to-have: coverage is
        // the required signal's share of the total weight.
        NormalizedData job = job(required("Covered."),
                new Signal(SignalClassification.PREFERRED_SKILL, "Uncovered."));
        List<float[]> vectors = List.of(SIGNAL, new float[]{0f, 1f});
        MatchResult result = scorer.score(job, vectors, profile(List.of(), "one"), Map.of("one", similarity(0.9)));

        double expected = 1.0 / (1.0 + MatchScorer.weight(SignalClassification.PREFERRED_SKILL));
        assertEquals(expected, result.parts().requirementCoverage(), 1e-9);
    }

    // ------------------------------------------------------ skill and level

    @Test
    @DisplayName("Skill match is the share of required skills held; a job naming none scores it 1")
    void testSkillMatch() {
        NormalizedData job = job(required("A.", "Java", "Go"), required("B.", "java"),
                new Signal(SignalClassification.PREFERRED_SKILL, "C.", List.of("Rust"), 0));
        CandidateProfile javaOnly = profile(List.of(new CandidateProfile.Skill("Java", 8)), "one");

        assertEquals(0.5, scorer.score(job, signalVectors(3), javaOnly, Map.of("one", similarity(0.1)))
                .parts().skillMatch(), 1e-9, "Java of Java and Go; Rust is only preferred");
        assertEquals(1.0, scorer.score(job(required("None named.")), signalVectors(1), javaOnly,
                Map.of("one", similarity(0.1))).parts().skillMatch());
    }

    @Test
    @DisplayName("Level fit falls with each band between the job and the profile, and with missing years")
    void testLevelFit() {
        CandidateProfile senior = profile(List.of(), "one");
        Map<String, float[]> bullets = Map.of("one", similarity(0.1));

        assertEquals(1.0, levelFit(Seniority.SENIOR, 0, senior, bullets));
        assertEquals((0.7 + 1.0) / 2, levelFit(Seniority.STAFF, 0, senior, bullets), 1e-9);
        assertEquals((0.3 + 1.0) / 2, levelFit(Seniority.JUNIOR, 0, senior, bullets), 1e-9);
        assertEquals((1.0 + 0.8) / 2, levelFit(Seniority.SENIOR, 10, senior, bullets), 1e-9, "8 of 10 years");
    }

    private double levelFit(Seniority seniority, int minYears, CandidateProfile profile, Map<String, float[]> bullets) {
        return scorer.score(job(seniority, minYears, HardRequirements.NONE, List.of(), required("A.")),
                signalVectors(1), profile, bullets).parts().levelFit();
    }

    // ----------------------------------------------------- score and gates

    @Test
    @DisplayName("A perfect match scores 100, and nothing matching scores only its level fit")
    void testScoreBounds() {
        CandidateProfile profile = profile(List.of(), "one");

        assertEquals(100, scorer.score(job(required("A.")), signalVectors(1), profile,
                Map.of("one", similarity(0.95))).score());
        // No coverage, no required skills named (skill match 1), same level (1).
        assertEquals(40, scorer.score(job(required("A.")), signalVectors(1), profile,
                Map.of("one", similarity(0.1))).score());
    }

    @Test
    @DisplayName("An avoided domain costs points, a preferred one adds a few, within 0 to 100")
    void testDomains() {
        CandidateProfile.Summary summary = new CandidateProfile.Summary(8, Seniority.SENIOR, Track.IC,
                Degree.BACHELORS, List.of(), false, false);
        CandidateProfile likesPayments = profile(summary, new CandidateProfile.Preferences(List.of(), true, true,
                List.of("payments"), List.of("ad tech")), List.of(), "one");
        Map<String, float[]> weak = Map.of("one", similarity(0.1));

        assertEquals(45, score(List.of("b2b payments"), likesPayments, weak));
        assertEquals(25, score(List.of("ad tech"), likesPayments, weak));
        assertEquals(25, score(List.of("payments", "ad tech"), likesPayments, weak), "avoiding wins");
        assertEquals(100, score(List.of("payments"), likesPayments, Map.of("one", similarity(0.95))),
                "capped at 100");
    }

    private int score(List<String> domains, CandidateProfile profile, Map<String, float[]> bullets) {
        return scorer.score(job(Seniority.SENIOR, 0, HardRequirements.NONE, domains, required("A.")),
                signalVectors(1), profile, bullets).score();
    }

    @Test
    @DisplayName("A job failing a gate is ineligible, with its reasons, but keeps the score it would have had")
    void testIneligibleKeepsScore() {
        NormalizedData clearance = job(Seniority.SENIOR, 0,
                new HardRequirements(Degree.NONE, true, Sponsorship.UNSTATED, List.of(), false, false),
                List.of(), required("A."));

        MatchResult result = scorer.score(clearance, signalVectors(1), profile(List.of(), "one"),
                Map.of("one", similarity(0.95)));

        assertFalse(result.eligible());
        assertEquals(List.of("requires a security clearance"), result.gateFailures());
        assertEquals(100, result.score());
    }

    @Test
    @DisplayName("A vector count that does not match the signals is refused")
    void testVectorCountMismatch() {
        assertThrows(IllegalArgumentException.class, () -> scorer.score(job(required("A.")), signalVectors(2),
                profile(List.of(), "one"), Map.of("one", similarity(0.5))));
    }
}
