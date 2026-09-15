package org.example.fitfetch.normalize;

/**
 * The lowest degree a job requires, in increasing order.
 *
 * <p>{@link #NONE} covers both "no degree mentioned" and "or equivalent
 * experience": either way the degree is not a hard requirement. A preferred
 * degree is a signal, not this.
 */
public enum Degree implements Labeled {
    NONE("none"),
    BACHELORS("bachelors"),
    MASTERS("masters"),
    PHD("phd");

    private final String label;

    Degree(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * @param label a degree as the model wrote it, in any case
     * @return the matching degree, or {@link #NONE} if it names none, so an
     *         unreadable answer never excludes a job
     */
    public static Degree fromLabel(String label) {
        return Labeled.fromLabel(Degree.class, label, NONE);
    }
}
