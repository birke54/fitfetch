package org.example.fitfetch.profile;

import org.example.fitfetch.normalize.Degree;
import org.example.fitfetch.normalize.EmploymentType;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Track;
import org.example.fitfetch.skills.SkillCanonicalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProfileLoaderTest {

    /** The committed example, loaded as users will load their own copy of it. */
    private static final Path EXAMPLE = Path.of("profile", "profile.example.yaml");

    private static final String MINIMAL = """
            summary:
              total_years: 5
              level: senior
              track: ic
              degree: bachelors
              clearance: false
              needs_sponsorship: false
            skills:
              - {name: Java, years: 5}
            roles:
              - id: acme
                company: Acme
                title: Engineer
                start: 2020-01
                end: present
                bullets:
                  - id: acme-one
                    text: Built a thing in Java.
                    skills: [Java]
            """;

    @TempDir
    Path dir;

    private final ProfileLoader loader;

    ProfileLoaderTest() throws IOException {
        loader = new ProfileLoader(SkillCanonicalizer.load(new ClassPathResource("skill_aliases.json")));
    }

    private Path write(String yaml) throws IOException {
        return Files.writeString(dir.resolve("profile.yaml"), yaml);
    }

    private List<String> problems(String yaml) throws IOException {
        Path file = write(yaml);
        return assertThrows(InvalidProfileException.class, () -> loader.load(file)).problems();
    }

    private static void assertMentions(List<String> problems, String text) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(text)),
                "expected a problem mentioning '" + text + "' in " + problems);
    }

    // ---------------------------------------------------------------- example

    @Test
    @DisplayName("The committed example loads, so it cannot drift out of date")
    void testExampleLoads() {
        LoadedProfile loaded = loader.load(EXAMPLE);
        CandidateProfile profile = loaded.profile();

        assertEquals(8, profile.summary().totalYears());
        assertEquals(Seniority.SENIOR, profile.summary().level());
        assertEquals(Track.IC, profile.summary().track());
        assertEquals(Degree.BACHELORS, profile.summary().degree());
        assertEquals(List.of(EmploymentType.FULL_TIME, EmploymentType.CONTRACT),
                profile.preferences().employmentTypes());
        assertFalse(profile.preferences().travelOk());
        assertEquals(List.of("ad tech"), profile.preferences().avoidedDomains());
        assertEquals(2, profile.roles().size());
        assertEquals(Optional.empty(), profile.roles().getFirst().endMonth());
        assertEquals(Optional.of(YearMonth.of(2021, 2)), profile.roles().get(1).endMonth());
        assertTrue(profile.roles().getFirst().bullets().getFirst().pinned());
        assertEquals(List.of(), loaded.warnings());
    }

    @Test
    @DisplayName("Skills and bullet skills come back in canonical names")
    void testSkillsCanonicalized() {
        CandidateProfile profile = loader.load(EXAMPLE).profile();

        assertTrue(profile.skills().contains(new CandidateProfile.Skill("Kubernetes", 3)), "k8s -> Kubernetes");
        assertEquals(List.of("Kubernetes"), profile.roles().get(1).bullets().get(1).skills());
    }

    @Test
    @DisplayName("The hash identifies the file's content, so a changed profile is noticed")
    void testHash() throws IOException {
        Path file = write(MINIMAL);
        String first = loader.load(file).sha256();

        assertEquals(first, loader.load(file).sha256(), "same content, same hash");
        Files.writeString(file, MINIMAL.replace("Built a thing", "Built another thing"));
        assertNotEquals(first, loader.load(file).sha256());
        assertEquals(64, first.length());
    }

    // --------------------------------------------------------------- defaults

    @Test
    @DisplayName("Left-out preferences place no restriction")
    void testPreferencesDefaultToAny() throws IOException {
        CandidateProfile.Preferences preferences = loader.load(write(MINIMAL)).profile().preferences();

        assertEquals(List.of(), preferences.employmentTypes());
        assertTrue(preferences.travelOk());
        assertTrue(preferences.onCallOk());
    }

    @Test
    @DisplayName("A bullet naming a skill the skills list lacks is a warning, not an error")
    void testUnlistedBulletSkillWarns() throws IOException {
        LoadedProfile loaded = loader.load(write(MINIMAL.replace("skills: [Java]", "skills: [Java, Go]")));

        assertEquals(1, loaded.warnings().size());
        assertTrue(loaded.warnings().getFirst().contains("Go"), loaded.warnings().toString());
    }

    // ----------------------------------------------------------------- errors

    @Test
    @DisplayName("Missing summary fields are reported, all at once")
    void testMissingSummaryFields() throws IOException {
        List<String> problems = problems(MINIMAL
                .replace("  total_years: 5\n", "")
                .replace("  needs_sponsorship: false\n", ""));

        assertMentions(problems, "summary.total_years is missing");
        assertMentions(problems, "summary.needs_sponsorship is missing");
    }

    @Test
    @DisplayName("An unknown key is an error, so a typo is not silently ignored")
    void testUnknownKeyRejected() throws IOException {
        List<String> problems = problems(MINIMAL.replace("  track: ic\n", "  track: ic\n  on_cal_ok: true\n"));

        assertMentions(problems, "on_cal_ok");
    }

    @Test
    @DisplayName("A value outside a field's choices is an error")
    void testUnknownEnumRejected() throws IOException {
        assertMentions(problems(MINIMAL.replace("level: senior", "level: seniour")), "seniour");
    }

    @Test
    @DisplayName("Ids must be well formed and unique, bullets across the whole profile")
    void testIds() throws IOException {
        String twoRoles = MINIMAL + """
                  - id: acme
                    company: Other
                    title: Engineer
                    start: 2018-01
                    end: 2019-12
                    bullets:
                      - id: acme-one
                        text: Did another thing.
                      - id: Bad_Id
                        text: Did a third thing.
                """;
        List<String> problems = problems(twoRoles);

        assertMentions(problems, "roles[1].id 'acme' is used by another role");
        assertMentions(problems, "roles[1].bullets[0].id 'acme-one' is used by another bullet");
        assertMentions(problems, "'Bad_Id' must be lower case");
    }

    @Test
    @DisplayName("An end month before the start, or not a month, is an error")
    void testEnds() throws IOException {
        assertMentions(problems(MINIMAL.replace("end: present", "end: 2019-05")), "is before its start");
        assertMentions(problems(MINIMAL.replace("end: present", "end: someday")), "must be a month");
        assertMentions(problems(MINIMAL.replace("    end: present\n", "")), "end is missing");
    }

    @Test
    @DisplayName("A bullet must be one line with text")
    void testBulletText() throws IOException {
        assertMentions(problems(MINIMAL.replace("text: Built a thing in Java.", "text: \"Built\\na thing.\"")),
                "must be one line");
        assertMentions(problems(MINIMAL.replace("text: Built a thing in Java.", "text: \"\"")),
                "text is missing");
    }

    @Test
    @DisplayName("Two spellings of one skill in the skills list are an error")
    void testDuplicateSkill() throws IOException {
        List<String> problems = problems(MINIMAL.replace("  - {name: Java, years: 5}",
                "  - {name: Kubernetes, years: 3}\n  - {name: k8s, years: 2}\n  - {name: Java, years: 5}"));

        assertMentions(problems, "Kubernetes is listed more than once (as 'k8s')");
    }

    @Test
    @DisplayName("A profile with no bullets is an error, since matching compares against them")
    void testNoBullets() throws IOException {
        String noBullets = MINIMAL.substring(0, MINIMAL.indexOf("    bullets:"));

        assertMentions(problems(noBullets), "roles hold no bullets");
    }

    @Test
    @DisplayName("An empty or unparseable file is an error")
    void testUnparseable() throws IOException {
        assertMentions(problems(""), "empty");
        assertFalse(problems("summary: [unclosed").isEmpty());
    }

    @Test
    @DisplayName("A parse error gives its position without quoting the file, which holds personal data")
    void testParseErrorDoesNotQuoteFile() throws IOException {
        List<String> problems = problems(MINIMAL.replace("level: senior", "level: seniour"));

        assertFalse(problems.getFirst().contains("Built a thing in Java"), problems.getFirst());
        assertFalse(problems.getFirst().contains("total_years"), problems.getFirst());
    }
}
