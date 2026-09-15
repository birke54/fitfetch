package org.example.fitfetch.normalize;

/** What a posting says about sponsoring a work visa. */
public enum Sponsorship implements Labeled {
    /** The posting says it will sponsor. */
    YES("yes"),
    /** The posting says it will not, or requires existing work authorization. */
    NO("no"),
    UNSTATED("unstated");

    private final String label;

    Sponsorship(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * @param label an answer as the model wrote it, in any case
     * @return the matching answer, or {@link #UNSTATED} if it names none
     */
    public static Sponsorship fromLabel(String label) {
        return Labeled.fromLabel(Sponsorship.class, label, UNSTATED);
    }
}
