package org.example.fitfetch.normalize;

/** The terms a job is offered on. */
public enum EmploymentType implements Labeled {
    FULL_TIME("full_time"),
    PART_TIME("part_time"),
    CONTRACT("contract"),
    INTERNSHIP("internship"),
    TEMPORARY("temporary"),
    /** The posting does not say. Most full-time postings never state it. */
    UNSTATED("unstated");

    private final String label;

    EmploymentType(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * @param label a type as the model wrote it, in any case
     * @return the matching type, or {@link #UNSTATED} if it names none
     */
    public static EmploymentType fromLabel(String label) {
        return Labeled.fromLabel(EmploymentType.class, label, UNSTATED);
    }
}
