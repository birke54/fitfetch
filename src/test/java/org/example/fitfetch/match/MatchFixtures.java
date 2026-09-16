package org.example.fitfetch.match;

import org.example.fitfetch.normalize.Degree;
import org.example.fitfetch.normalize.EmploymentType;
import org.example.fitfetch.normalize.HardRequirements;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.normalize.Sponsorship;
import org.example.fitfetch.normalize.Track;
import org.example.fitfetch.profile.CandidateProfile;
import org.example.fitfetch.skills.SkillCanonicalizer;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Jobs, profiles and vectors for the match tests. */
final class MatchFixtures {

    private MatchFixtures() {
    }

    /** The signal side of every test vector: along the first axis. */
    static final float[] SIGNAL = {1f, 0f};

    /** The skills the match tests use, as the shipped table spells them. */
    static final SkillCanonicalizer SKILLS = new SkillCanonicalizer(Map.ofEntries(
            Map.entry("Java", List.of()),
            Map.entry("Go", List.of("golang")),
            Map.entry("Rust", List.of()),
            Map.entry("Kafka", List.of()),
            Map.entry("Spark", List.of()),
            Map.entry("AWS", List.of()),
            Map.entry("Azure", List.of()),
            Map.entry("GCP", List.of()),
            Map.entry("VPC", List.of("vpcs")),
            Map.entry("VPN", List.of("vpns")),
            Map.entry("CDN", List.of("cdns")),
            Map.entry("Subnetting", List.of()),
            Map.entry("Routing", List.of()),
            Map.entry("Peering", List.of()),
            Map.entry("PrivateLink", List.of("private link")),
            Map.entry("Private Service Connect", List.of()),
            Map.entry("MCP", List.of("model context protocol")),
            Map.entry("CI/CD", List.of("ci", "cicd"))));

    /** @return a unit vector whose cosine similarity with {@link #SIGNAL} is exactly {@code cosine} */
    static float[] similarity(double cosine) {
        return new float[]{(float) cosine, (float) Math.sqrt(1 - cosine * cosine)};
    }

    static NormalizedData job(Seniority seniority, int minYears, HardRequirements requirements, List<String> domains,
                              Signal... signals) {
        return new NormalizedData(seniority, Track.IC, EmploymentType.FULL_TIME, minYears, requirements, domains,
                List.of(signals));
    }

    static NormalizedData job(Signal... signals) {
        return job(Seniority.SENIOR, 0, HardRequirements.NONE, List.of(), signals);
    }

    static CandidateProfile profile(List<CandidateProfile.Skill> skills, String... bulletIds) {
        return profile(new CandidateProfile.Summary(8, Seniority.SENIOR, Track.IC, Degree.BACHELORS, List.of(),
                false, false), CandidateProfile.Preferences.ANY, skills, bulletIds);
    }

    static CandidateProfile profile(CandidateProfile.Summary summary, CandidateProfile.Preferences preferences,
                                    List<CandidateProfile.Skill> skills, String... bulletIds) {
        List<CandidateProfile.Bullet> bullets = java.util.Arrays.stream(bulletIds)
                .map(id -> new CandidateProfile.Bullet(id, "Did " + id + ".", List.of(), false)).toList();
        return new CandidateProfile(summary, preferences, skills,
                List.of(new CandidateProfile.Role("acme", "Acme", "Engineer", YearMonth.of(2020, 1), "present",
                        bullets)));
    }

    static HardRequirements requirements(Degree degree, boolean clearance, Sponsorship sponsorship,
                                         List<String> certifications, boolean travel, boolean onCall) {
        return new HardRequirements(degree, clearance, sponsorship, certifications, travel, onCall);
    }
}
