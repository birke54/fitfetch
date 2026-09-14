package org.example.fitfetch.normalize;

import java.util.Locale;

/**
 * The level a job is pitched at, as the model reads it off the title and the
 * experience requirement.
 *
 * <p>Two spellings, kept apart on purpose: the constant name is what is stored
 * ({@code ck_normalized_jobs_seniority}), and the lower-case {@link #label()} is
 * what the prompt and the JSON Schema offer the model, since that is how the
 * bands are written in the instructions.
 */
public enum Seniority {
    JUNIOR("junior"),
    MIDLEVEL("midlevel"),
    SENIOR("senior"),
    STAFF("staff"),
    PRINCIPAL("principal"),
    DISTINGUISHED("distinguished");

    private final String label;

    Seniority(String label) {
        this.label = label;
    }

    /** @return the spelling the prompt and schema use */
    public String label() {
        return label;
    }

    /**
     * @param label a band as the model wrote it, in any case
     * @return the matching band, or {@code null} if it names none
     */
    public static Seniority fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String wanted = label.strip().toLowerCase(Locale.ROOT);
        for (Seniority seniority : values()) {
            if (seniority.label.equals(wanted)) {
                return seniority;
            }
        }
        return null;
    }
}
