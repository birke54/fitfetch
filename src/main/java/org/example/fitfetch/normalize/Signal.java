package org.example.fitfetch.normalize;

import java.util.Objects;

/**
 * One requirement, responsibility or skill from a job description, normalized to
 * a single sentence so a resume can be matched against it.
 *
 * @param classification which part of the description it came from
 * @param text           the requirement as one crisp sentence
 */
public record Signal(SignalClassification classification, String text) {

    public Signal {
        Objects.requireNonNull(classification, "classification");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
