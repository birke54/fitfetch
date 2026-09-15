package org.example.fitfetch.skills;

import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a skill as written ("k8s", "postgres") to one canonical name
 * ("Kubernetes", "PostgreSQL").
 *
 * <p>Applied to both sides of a match: the skills the model extracts from job
 * signals, and the candidate profile. Exact skill matching only works if both
 * use the same names, and a model asked for canonical names is not perfectly
 * consistent about it.
 *
 * <p>Lookup ignores case and runs of whitespace. A skill the table does not
 * know is kept as written, stripped, so an unlisted skill still matches itself.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class SkillCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> canonicalByKey;

    /**
     * @param aliasesByCanonical each canonical name and the aliases that map to
     *                           it. A canonical name matches itself too
     * @throws IllegalArgumentException if a name is blank, or if an alias or
     *                                  canonical name would map to two skills
     */
    public SkillCanonicalizer(Map<String, List<String>> aliasesByCanonical) {
        Map<String, String> byKey = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        aliasesByCanonical.forEach((canonical, aliases) -> {
            if (canonical == null || canonical.isBlank()) {
                throw new IllegalArgumentException("A canonical skill name is blank");
            }
            String name = canonical.strip();
            register(byKey, conflicts, name, name);
            for (String alias : aliases == null ? List.<String>of() : aliases) {
                if (alias == null || alias.isBlank()) {
                    throw new IllegalArgumentException("A blank alias is listed for " + name);
                }
                register(byKey, conflicts, alias, name);
            }
        });
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("Skill names that map to more than one skill: " + conflicts);
        }
        this.canonicalByKey = Map.copyOf(byKey);
    }

    private static void register(Map<String, String> byKey, List<String> conflicts, String written, String canonical) {
        String previous = byKey.putIfAbsent(key(written), canonical);
        if (previous != null && !previous.equals(canonical)) {
            conflicts.add("'" + written + "' -> " + previous + " and " + canonical);
        }
    }

    /** @return a canonicalizer with no table, which only strips */
    public static SkillCanonicalizer none() {
        return new SkillCanonicalizer(Map.of());
    }

    /**
     * Loads a table shaped as {@code {"skills": {"Kubernetes": ["k8s"], ...}}}.
     *
     * @param json the table resource
     * @return the canonicalizer
     * @throws IOException if the resource cannot be read
     */
    public static SkillCanonicalizer load(Resource json) throws IOException {
        JsonNode skills;
        try (InputStream in = json.getInputStream()) {
            skills = MAPPER.readTree(in).path("skills");
        }
        if (!skills.isObject()) {
            throw new IllegalArgumentException("Skill alias table " + json.getDescription()
                    + " has no \"skills\" object");
        }
        Map<String, List<String>> aliasesByCanonical = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : skills.properties()) {
            List<String> aliases = new ArrayList<>();
            entry.getValue().forEach(alias -> aliases.add(alias.asString()));
            aliasesByCanonical.put(entry.getKey(), aliases);
        }
        return new SkillCanonicalizer(aliasesByCanonical);
    }

    /**
     * @param skill a skill as written
     * @return its canonical name, or the skill stripped if the table does not
     *         know it
     */
    public String canonical(String skill) {
        Objects.requireNonNull(skill, "skill");
        String stripped = skill.strip().replaceAll("\\s+", " ");
        return canonicalByKey.getOrDefault(key(stripped), stripped);
    }

    /**
     * @param skills skills as written; may be {@code null}
     * @return their canonical names in the order given, without blanks or
     *         repeats. Two spellings of one skill keep only the first
     */
    public List<String> canonicalAll(Collection<String> skills) {
        if (skills == null) {
            return List.of();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String skill : skills) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            String canonical = canonical(skill);
            byKey.putIfAbsent(key(canonical), canonical);
        }
        return List.copyOf(byKey.values());
    }

    private static String key(String skill) {
        return skill.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
