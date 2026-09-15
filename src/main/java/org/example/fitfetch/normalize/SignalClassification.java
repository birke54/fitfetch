package org.example.fitfetch.normalize;

import java.util.Locale;

/**
 * Which part of a job description a {@link Signal} came from.
 *
 * <p>As with {@link Seniority}, the constant name is what is stored and the
 * {@link #label()} is what the prompt and schema offer the model.
 */
public enum SignalClassification {
    CORE_RESPONSIBILITY("core responsibilities"),
    REQUIRED_SKILL("required skills"),
    PREFERRED_SKILL("preferred/nice-to-have skills"),
    REQUIRED_QUALIFICATION("required qualifications"),
    PREFERRED_QUALIFICATION("preferred/nice-to-have qualifications"),

    /**
     * A section the model named outside the schema's list. The schema constrains
     * decoding, so this should not happen; when it does, the signal is kept
     * rather than dropped, since its text is still a requirement.
     */
    OTHER("other");

    private final String label;

    SignalClassification(String label) {
        this.label = label;
    }

    /** @return the spelling the prompt and schema use */
    public String label() {
        return label;
    }

    /**
     * @param label a section as the model wrote it, in any case
     * @return the matching classification, or {@link #OTHER} if it names none
     */
    public static SignalClassification fromLabel(String label) {
        if (label == null) {
            return OTHER;
        }
        String wanted = label.strip().toLowerCase(Locale.ROOT);
        for (SignalClassification classification : values()) {
            if (classification.label.equals(wanted)) {
                return classification;
            }
        }
        return OTHER;
    }
}
