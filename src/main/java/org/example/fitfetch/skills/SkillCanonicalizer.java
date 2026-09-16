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

    /** Every key that maps to each canonical name, the name's own included, by the name's key. */
    private final Map<String, List<String>> keysByCanonicalKey;

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
        Map<String, List<String>> keys = new HashMap<>();
        byKey.forEach((key, canonical) -> keys.computeIfAbsent(key(canonical), k -> new ArrayList<>()).add(key));
        this.keysByCanonicalKey = Map.copyOf(keys);
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

    /**
     * @param skill a skill, as written or canonical
     * @return whether the table knows it, by either spelling
     */
    public boolean isKnown(String skill) {
        Objects.requireNonNull(skill, "skill");
        return canonicalByKey.containsKey(key(skill));
    }

    /**
     * Whether a text names a skill, by its canonical name or any alias of it.
     *
     * <p>A name must stand as whole words, ignoring case and runs of
     * whitespace, so "Go" is not found in "Google" nor "Java" in "JavaScript".
     * A plural "s" is allowed after it, so "APIs" names the skill "API".
     *
     * @param skill a skill, as written or canonical
     * @param text  the text to look in
     * @return whether the text names it; never for a blank skill or text
     */
    public boolean isNamedIn(String skill, String text) {
        Objects.requireNonNull(skill, "skill");
        if (skill.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        String haystack = key(text);
        List<String> names = keysByCanonicalKey.getOrDefault(key(canonical(skill)), List.of(key(skill)));
        return names.stream().anyMatch(name -> containsWord(haystack, name));
    }

    /** @return whether {@code word} occurs in {@code text} with no letter or digit either side, bar a plural "s" */
    private static boolean containsWord(String text, String word) {
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            if (end < text.length() && text.charAt(end) == 's') {
                end++;
            }
            boolean startsWord = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean endsWord = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startsWord && endsWord) {
                return true;
            }
        }
        return false;
    }

    private static String key(String skill) {
        return skill.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
