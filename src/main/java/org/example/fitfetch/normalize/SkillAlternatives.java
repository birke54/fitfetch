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
 * <p>A bracket or a dash closes a clause as surely as a colon does, at either
 * end of the series. The choice in "scripting (Bash or Python) with Terraform"
 * is between Bash and Python, and the one in "Kubernetes — on AWS, Azure, or
 * GCP" does not reach back to Kubernetes. Were either allowed to cross, a skill
 * the posting states outright would join the choice, and holding any one of the
 * others would meet it.
 *
 * <p>An open-ended series ("or any other modern language") names examples of a
 * choice with no list, so a candidate with something unnamed meets it and the
 * named ones must not count as missing. Those skills are dropped from both
 * lists; the signal's text still carries them for similarity.
 *
 * <p>Two of the series' items must name a skill, or it is a choice of
 * something else. Postings offer one constantly between verbs, and the skills
 * they govern sit in a single item: "building, deploying, or securing AI/ML
 * systems" chooses among the verbs, and holding one of AI and ML meets
 * neither. A choice of skills puts one in each item, as "AWS, Azure, or GCP"
 * does.
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class SkillAlternatives {

    /**
     * The characters that end a clause, as the body of a character class. Both
     * ends of a series are bounded by the same set, so neither can cross one.
     */
    private static final String CLAUSE_ENDS = ":;()\\[\\]—–";

    /** Ends a clause, so a series never reaches back past one. */
    private static final Pattern CLAUSE_END = Pattern.compile("[" + CLAUSE_ENDS + "]|\\.\\s");

    /**
     * Ends the last item of a series: the next comma, the end of its clause, or
     * the full stop closing the sentence.
     */
    private static final Pattern ITEM_END = Pattern.compile("[," + CLAUSE_ENDS + "]|\\.(\\s|$)");

    /** The last item of a series: "…, or CI/CD workflows", "Java or Go". */
    private static final Pattern OR_ITEM = Pattern.compile("(,\\s*)?\\bor\\b\\s", Pattern.CASE_INSENSITIVE);

    /** What separates the items of a series: its commas, and its "or". */
    private static final Pattern ITEM_SEPARATOR = Pattern.compile(",\\s*|\\s+\\bor\\b\\s+",
            Pattern.CASE_INSENSITIVE);

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
    public record Split(List<String> skills, List<String> anyOfSkills) {
    }

    /**
     * @param text          the signal's text
     * @param skills        its skills, canonical and named in the text
     * @param canonicalizer knows every spelling of a skill
     * @return the skills split into required ones and alternatives. Unchanged
     *         if the text offers no choice of two or more of them
     */
    public static Split of(String text, List<String> skills, SkillCanonicalizer canonicalizer) {
        if (skills.size() < 2) {
            return new Split(skills, List.of());
        }
        String series = series(text);
        if (series == null) {
            return new Split(skills, List.of());
        }
        List<String> named = skills.stream().filter(skill -> canonicalizer.isNamedIn(skill, series)).toList();
        if (named.size() < 2 || !isChoiceOfSkills(series, named, canonicalizer)) {
            return new Split(skills, List.of());
        }
        List<String> required = new ArrayList<>(skills);
        required.removeAll(named);
        // An open-ended series names examples, so neither list should hold them.
        return new Split(required, OPEN_ENDED.matcher(series).find() ? List.of() : named);
    }

    /**
     * @return whether the series chooses between skills rather than between
     *         something else that happens to carry them: two of its items name
     *         one. Every skill in one item means the choice is of what governs
     *         them, and each option demands them all
     */
    private static boolean isChoiceOfSkills(String series, List<String> named, SkillCanonicalizer canonicalizer) {
        int naming = 0;
        for (String item : ITEM_SEPARATOR.split(series)) {
            if (named.stream().anyMatch(skill -> canonicalizer.isNamedIn(skill, item))) {
                naming++;
            }
        }
        return naming >= 2;
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
        Matcher item = ITEM_END.matcher(text);
        return item.find(from) ? item.start() : text.length();
    }
}
