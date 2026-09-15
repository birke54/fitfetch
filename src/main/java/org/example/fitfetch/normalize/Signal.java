package org.example.fitfetch.normalize;

import java.util.List;
import java.util.Objects;

/**
 * One requirement, responsibility or skill from a job description, normalized to
 * a single sentence so a resume can be matched against it.
 *
 * <p>The sentence is what gets embedded. The skills alongside it are for exact
 * matching, which embeddings do badly on technology names: "Java" and
 * "JavaScript" sit close together, while "Go" and "Golang" may not.
 *
 * @param classification which part of the description it came from
 * @param text           the requirement as one crisp sentence
 * @param skills         the technologies, languages, tools and methods it
 *                       names, by common name; empty if it names none
 * @param minYears       years of experience it asks for itself ("3+ years of
 *                       Kubernetes" is 3); 0 if it states none
 */
public record Signal(SignalClassification classification, String text, List<String> skills, int minYears) {

    public Signal {
        Objects.requireNonNull(classification, "classification");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (minYears < 0) {
            throw new IllegalArgumentException("minYears must not be negative, but was " + minYears);
        }
    }

    /**
     * A signal naming no skills and no years.
     *
     * @param classification which part of the description it came from
     * @param text           the requirement as one crisp sentence
     */
    public Signal(SignalClassification classification, String text) {
        this(classification, text, List.of(), 0);
    }
}
