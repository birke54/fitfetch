package org.example.fitfetch.location;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks, without a model, that a label's locations account for the label:
 * every {@link LocationInput#raw()} is text from the label, and every word of
 * the label is in some raw.
 *
 * <p>The prompt asks for each raw as "the exact substring of the input this
 * location came from", so both hold whenever the model does what it was asked.
 * A raw the label does not contain means the model rewrote or invented an
 * element. A word no raw contains means it left one out, as when
 * {@code "Dublin, London"} comes back as Dublin alone. Neither takes a second
 * opinion to spot, which is what makes the check cheap enough to run on every
 * label.
 *
 * <p>It checks the text only. An element kept word for word but resolved to the
 * wrong place passes.
 *
 * <p>Both sides are compared after {@link LocationKey#normalize}, which is also
 * what the interpretation cache keys on. A cached answer carries the raws of
 * whichever spelling of the label first reached the model, so case, padding,
 * accents and dash variants must not count as differences.
 *
 * @param uncoveredWords distinct words of the label that no raw contains, in
 *                       label order
 * @param unmatchedRaws  raws the label does not contain, as the answer gave them
 */
public record LabelAudit(List<String> uncoveredWords, List<String> unmatchedRaws) {

    /** Anything but a letter or a digit separates words. */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Words a raw may leave out without losing a location. "or" and "and" join
     * elements, as in {@code "Boston or Remote"}; the rest describe the
     * workplace rather than where it is. "remote" is deliberately not here:
     * dropping it from {@code "Boston or Remote"} loses the remote option.
     */
    private static final Set<String> IGNORED_WORDS = Set.of("or", "and", "hybrid", "onsite", "on", "site", "office");

    public LabelAudit {
        uncoveredWords = List.copyOf(uncoveredWords);
        unmatchedRaws = List.copyOf(unmatchedRaws);
    }

    /**
     * @param label  the label as the posting gave it; may be {@code null}
     * @param inputs every location resolved from it, before any cap is applied
     * @return what, if anything, the locations fail to account for
     */
    public static LabelAudit of(String label, List<LocationInput> inputs) {
        String key = LocationKey.normalize(label);
        Set<String> rawWords = new HashSet<>();
        List<String> unmatched = new ArrayList<>();
        for (LocationInput input : inputs) {
            String raw = LocationKey.normalize(input.raw());
            if (raw.isEmpty()) {
                // A blank label's single location has a blank raw, and so does
                // nothing else legitimately. Either way it names no text, so there
                // is nothing to find; whatever it should have named shows up as an
                // uncovered word instead.
                continue;
            }
            if (!containsWhole(key, raw)) {
                unmatched.add(input.raw());
            }
            rawWords.addAll(words(raw));
        }
        List<String> uncovered = words(key).stream()
                .filter(word -> !IGNORED_WORDS.contains(word) && !rawWords.contains(word))
                .distinct()
                .toList();
        return new LabelAudit(uncovered, unmatched);
    }

    /** @return {@code true} if every word of the label is in some raw */
    public boolean covered() {
        return uncoveredWords.isEmpty();
    }

    /** @return {@code true} if every raw is text from the label */
    public boolean verbatim() {
        return unmatchedRaws.isEmpty();
    }

    private static List<String> words(String normalized) {
        return Arrays.stream(NON_WORD.split(normalized)).filter(word -> !word.isEmpty()).toList();
    }

    /**
     * Whether {@code part} occurs in {@code text} without starting or ending
     * inside a word, so that a raw {@code "ny"} is not found in
     * {@code "sunnyvale, ca"}.
     */
    private static boolean containsWhole(String text, String part) {
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            int end = at + part.length();
            boolean startsInsideWord = at > 0
                    && isWordChar(text.charAt(at - 1)) && isWordChar(part.charAt(0));
            boolean endsInsideWord = end < text.length()
                    && isWordChar(text.charAt(end)) && isWordChar(part.charAt(part.length() - 1));
            if (!startsInsideWord && !endsInsideWord) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
