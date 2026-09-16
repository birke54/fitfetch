package org.example.fitfetch.normalize;

import org.example.fitfetch.skills.SkillCanonicalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the skills a signal offers as alternatives, by reading its text.
 *
 * <p>The prompt asks the model to separate them itself, into
 * {@code any_of_skills}. No model tried has ever done it: qwen2.5, llama3.1 and
 * a 32b all put "Go, Python, Java, or C#" in {@code skills}, where scoring reads
 * them as all required and marks a candidate down for the ones they lack.
 *
 * <p>A series is read backwards from its "or": the items before it, back across
 * commas, belong to it; anything after does not. That keeps the examples in
 * "one of AWS, Azure, or GCP, e.g. VPCs, subnetting and CDNs" out of the
 * alternatives, where they would otherwise be met by holding any one of them.
 *
 * <p>An open-ended series ("or any other modern language") names examples of a
 * choice with no list, so a candidate with something unnamed meets it and the
 * named ones must not count as missing. Those skills are dropped from both
 * lists; the signal's text still carries them for similarity.
 *
 * <p>This is a stateless holder; all members are static.
 */
final class SkillAlternatives {

    /** Ends a clause, so a series never reaches back past one. */
    private static final Pattern CLAUSE_END = Pattern.compile("[:;()\\[\\]]|\\.\\s");

    /** The last item of a series: "…, or CI/CD workflows", "Java or Go". */
    private static final Pattern OR_ITEM = Pattern.compile("(,\\s*)?\\bor\\b\\s", Pattern.CASE_INSENSITIVE);

    /** A series left open, so the items named are only examples of it. */
    private static final Pattern OPEN_ENDED = Pattern.compile(
            "\\b(another|any other|other|similar|comparable|equivalent|the like)\\b", Pattern.CASE_INSENSITIVE);

    private SkillAlternatives() {
    }

    /**
     * What a signal requires and what it offers a choice of.
     *
     * @param skills      the skills it requires
     * @param anyOfSkills the skills it offers as alternatives, empty if none
     */
    record Split(List<String> skills, List<String> anyOfSkills) {
    }

    /**
     * @param text          the signal's text
     * @param skills        its skills, canonical and named in the text
     * @param canonicalizer knows every spelling of a skill
     * @return the skills split into required ones and alternatives. Unchanged
     *         if the text offers no choice of two or more of them
     */
    static Split of(String text, List<String> skills, SkillCanonicalizer canonicalizer) {
        if (skills.size() < 2) {
            return new Split(skills, List.of());
        }
        String series = series(text);
        if (series == null) {
            return new Split(skills, List.of());
        }
        List<String> named = skills.stream().filter(skill -> canonicalizer.isNamedIn(skill, series)).toList();
        if (named.size() < 2) {
            return new Split(skills, List.of());
        }
        List<String> required = new ArrayList<>(skills);
        required.removeAll(named);
        // An open-ended series names examples, so neither list should hold them.
        return new Split(required, OPEN_ENDED.matcher(series).find() ? List.of() : named);
    }

    /**
     * @return the text of the last "or" series in the text, or {@code null} if
     *         it has none. It runs from the item after the nearest clause end
     *         through the item the "or" introduces
     */
    private static String series(String text) {
        Matcher or = OR_ITEM.matcher(text);
        int at = -1;
        int after = -1;
        while (or.find()) {
            at = or.start();
            after = or.end();
        }
        if (at < 0) {
            return null;
        }
        int end = end(text, after);
        Matcher clause = CLAUSE_END.matcher(text.substring(0, at));
        int start = 0;
        while (clause.find()) {
            start = clause.end();
        }
        return text.substring(start, end).strip();
    }

    /** @return where the series' last item ends: at the next comma, clause end, or the text's end */
    private static int end(String text, int from) {
        for (int at = from; at < text.length(); at++) {
            char character = text.charAt(at);
            if (character == ',' || character == ';' || character == ':') {
                return at;
            }
            if (character == '.' && (at + 1 == text.length() || Character.isWhitespace(text.charAt(at + 1)))) {
                return at;
            }
        }
        return text.length();
    }
}
