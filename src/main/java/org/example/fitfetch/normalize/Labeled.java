package org.example.fitfetch.normalize;

import java.util.Locale;

/**
 * An enum offered to the model by a lower-case label, as the prompt and the
 * JSON Schema spell it, while the constant name is what is stored.
 *
 * <p>Implemented by the job-level fields added alongside {@link Seniority}, which
 * predates it and keeps its own lookup.
 */
interface Labeled {

    /** @return the spelling the prompt and schema use */
    String label();

    /**
     * Finds the constant the model named, falling back rather than failing.
     *
     * <p>The schema constrains decoding, so an unknown value should not arrive.
     * When one does, the fallback keeps the rest of an otherwise good answer,
     * as {@link SignalClassification#OTHER} does for a signal.
     *
     * @param type     the enum
     * @param label    the value as the model wrote it, in any case
     * @param fallback the constant to use when the label names none
     * @return the matching constant, or {@code fallback}
     */
    static <E extends Enum<E> & Labeled> E fromLabel(Class<E> type, String label, E fallback) {
        if (label == null) {
            return fallback;
        }
        String wanted = label.strip().toLowerCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.label().equals(wanted)) {
                return constant;
            }
        }
        return fallback;
    }
}
