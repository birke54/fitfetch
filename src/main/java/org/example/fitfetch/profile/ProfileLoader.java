package org.example.fitfetch.profile;

import org.example.fitfetch.profile.CandidateProfile.Bullet;
import org.example.fitfetch.profile.CandidateProfile.Role;
import org.example.fitfetch.profile.CandidateProfile.Skill;
import org.example.fitfetch.profile.CandidateProfile.Summary;
import org.example.fitfetch.skills.SkillCanonicalizer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a hand-written {@code profile.yaml} and checks it.
 *
 * <p>Strict, since a mistake here skews every match without failing anything:
 * an unknown key (a typo such as {@code on_cal_ok}) is an error rather than
 * ignored, and every problem in the file is reported at once. Skills are
 * mapped to canonical names on the way in, so they compare with the skills
 * extracted from jobs.
 *
 * <p>Instances are immutable and safe to share.
 */
public class ProfileLoader {

    /** Lower case letters and digits in hyphen-separated words: {@code acme-kafka-migration}. */
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // A bullet left without "pinned" is unpinned. Every other number
            // and flag is boxed and checked explicitly, so this hides nothing.
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final SkillCanonicalizer skills;

    /** @param skills maps skill names to the canonical ones jobs use */
    public ProfileLoader(SkillCanonicalizer skills) {
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    /**
     * @param path the profile file
     * @return the checked profile, with skills in canonical names
     * @throws InvalidProfileException if the file does not parse, or breaks any
     *                                 rule; every problem is listed
     * @throws UncheckedIOException    if the file cannot be read
     */
    public LoadedProfile load(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read profile " + path, e);
        }

        if (new String(bytes, StandardCharsets.UTF_8).isBlank()) {
            throw new InvalidProfileException(path, List.of("the file is empty"));
        }
        CandidateProfile raw;
        try {
            raw = YAML.readValue(bytes, CandidateProfile.class);
        } catch (JacksonException e) {
            throw new InvalidProfileException(path, List.of(describe(e)));
        }
        if (raw == null) {
            throw new InvalidProfileException(path, List.of("the file is empty"));
        }

        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkSummary(raw.summary(), problems);
        List<Skill> canonicalSkills = checkSkills(raw.skills(), problems);
        List<Role> canonicalRoles = checkRoles(raw.roles(), canonicalSkills, problems, warnings);
        if (!problems.isEmpty()) {
            throw new InvalidProfileException(path, problems);
        }

        Summary summary = raw.summary();
        CandidateProfile profile = new CandidateProfile(
                new Summary(summary.totalYears(), summary.level(), summary.track(), summary.degree(),
                        stripped(summary.certifications()), summary.clearance(), summary.needsSponsorship()),
                new CandidateProfile.Preferences(raw.preferences().employmentTypes(),
                        raw.preferences().travelOk(), raw.preferences().onCallOk(),
                        lowerCase(raw.preferences().preferredDomains()),
                        lowerCase(raw.preferences().avoidedDomains())),
                canonicalSkills,
                canonicalRoles);
        return new LoadedProfile(profile, sha256(bytes), path, warnings);
    }

    /**
     * A parse error by position only. Jackson's own message quotes the file
     * around the error, which would copy personal data into the logs.
     */
    private static String describe(JacksonException e) {
        TokenStreamLocation location = e.getLocation();
        String where = location == null ? "" : " (line " + location.getLineNr() + ", column "
                + location.getColumnNr() + ")";
        return e.getOriginalMessage() + where;
    }

    private static void checkSummary(Summary summary, List<String> problems) {
        if (summary == null) {
            problems.add("summary is missing");
            return;
        }
        if (summary.totalYears() == null) {
            problems.add("summary.total_years is missing");
        } else if (summary.totalYears() < 0) {
            problems.add("summary.total_years must not be negative");
        }
        require(summary.level(), "summary.level", problems);
        require(summary.track(), "summary.track", problems);
        require(summary.degree(), "summary.degree", problems);
        require(summary.clearance(), "summary.clearance", problems);
        require(summary.needsSponsorship(), "summary.needs_sponsorship", problems);
    }

    private List<Skill> checkSkills(List<Skill> raw, List<String> problems) {
        List<Skill> canonical = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Skill skill = raw.get(i);
            String where = "skills[" + i + "]";
            if (skill == null || skill.name() == null || skill.name().isBlank()) {
                problems.add(where + ".name is missing");
                continue;
            }
            String name = skills.canonical(skill.name());
            if (skill.years() == null) {
                problems.add(where + " (" + name + ").years is missing");
            } else if (skill.years() < 0) {
                problems.add(where + " (" + name + ").years must not be negative");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                problems.add(where + ": " + name + " is listed more than once"
                        + (name.equals(skill.name().strip()) ? "" : " (as '" + skill.name().strip() + "')"));
                continue;
            }
            canonical.add(new Skill(name, skill.years()));
        }
        return canonical;
    }

    private List<Role> checkRoles(List<Role> raw, List<Skill> knownSkills, List<String> problems,
                                  List<String> warnings) {
        Set<String> known = new HashSet<>();
        knownSkills.forEach(skill -> known.add(skill.name().toLowerCase(Locale.ROOT)));
        Set<String> roleIds = new HashSet<>();
        Set<String> bulletIds = new HashSet<>();
        List<Role> canonical = new ArrayList<>();
        int bulletCount = 0;

        for (int r = 0; r < raw.size(); r++) {
            Role role = raw.get(r);
            String where = "roles[" + r + "]";
            if (role == null) {
                problems.add(where + " is empty");
                continue;
            }
            checkId(role.id(), where, roleIds, "role", problems);
            requireText(role.company(), where + ".company", problems);
            requireText(role.title(), where + ".title", problems);
            require(role.start(), where + ".start", problems);
            checkEnd(role, where, problems);

            List<Bullet> bullets = new ArrayList<>();
            for (int b = 0; b < role.bullets().size(); b++) {
                Bullet bullet = role.bullets().get(b);
                String at = where + ".bullets[" + b + "]";
                if (bullet == null) {
                    problems.add(at + " is empty");
                    continue;
                }
                checkId(bullet.id(), at, bulletIds, "bullet", problems);
                if (bullet.text() == null || bullet.text().isBlank()) {
                    problems.add(at + ".text is missing");
                } else if (bullet.text().strip().contains("\n")) {
                    problems.add(at + ".text must be one line: a bullet is one sentence on a resume");
                }
                List<String> bulletSkills = skills.canonicalAll(bullet.skills());
                for (String skill : bulletSkills) {
                    if (!known.contains(skill.toLowerCase(Locale.ROOT))) {
                        warnings.add(at + " (" + bullet.id() + ") names " + skill
                                + ", which the skills list does not; add it there with its years");
                    }
                }
                bullets.add(new Bullet(bullet.id(), bullet.text() == null ? null : bullet.text().strip(),
                        bulletSkills, bullet.pinned()));
                bulletCount++;
            }
            canonical.add(new Role(role.id(), strip(role.company()), strip(role.title()), role.start(),
                    role.end() == null ? null : role.end().strip(), bullets));
        }
        if (bulletCount == 0) {
            problems.add("roles hold no bullets; matching compares job requirements against them");
        }
        return canonical;
    }

    private static void checkId(String id, String where, Set<String> seen, String kind, List<String> problems) {
        if (id == null || id.isBlank()) {
            problems.add(where + ".id is missing");
        } else if (!ID.matcher(id).matches()) {
            problems.add(where + ".id '" + id + "' must be lower case letters and digits joined by hyphens");
        } else if (!seen.add(id)) {
            problems.add(where + ".id '" + id + "' is used by another " + kind);
        }
    }

    private static void checkEnd(Role role, String where, List<String> problems) {
        if (role.end() == null || role.end().isBlank()) {
            problems.add(where + ".end is missing: a month such as 2023-05, or present");
            return;
        }
        if (Role.PRESENT.equalsIgnoreCase(role.end().strip())) {
            return;
        }
        YearMonth end;
        try {
            end = YearMonth.parse(role.end().strip());
        } catch (DateTimeParseException e) {
            problems.add(where + ".end '" + role.end() + "' must be a month such as 2023-05, or present");
            return;
        }
        if (role.start() != null && end.isBefore(role.start())) {
            problems.add(where + ".end " + end + " is before its start " + role.start());
        }
    }

    private static void require(Object value, String where, List<String> problems) {
        if (value == null) {
            problems.add(where + " is missing");
        }
    }

    private static void requireText(String value, String where, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(where + " is missing");
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static List<String> stripped(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).distinct().toList();
    }

    private static List<String> lowerCase(List<String> values) {
        return stripped(values).stream().map(v -> v.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
