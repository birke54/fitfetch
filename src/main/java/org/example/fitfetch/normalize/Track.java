package org.example.fitfetch.normalize;

/**
 * Whether a job is an individual contributor role or a people-management one.
 *
 * <p>{@link Seniority} is an IC ladder, so a manager role scored on seniority
 * alone would land on staff or principal and match IC experience it does not
 * ask for.
 */
public enum Track implements Labeled {
    /** Individual contributor, including a tech lead who still builds. */
    IC("ic"),
    /** The role's main work is managing people: hiring, reviews, direct reports. */
    MANAGER("manager");

    private final String label;

    Track(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * @param label a track as the model wrote it, in any case
     * @return the matching track, or {@link #IC} if it names none. The title
     *         filter already turns away most manager postings, so IC is the
     *         likelier reading
     */
    public static Track fromLabel(String label) {
        return Labeled.fromLabel(Track.class, label, IC);
    }
}
