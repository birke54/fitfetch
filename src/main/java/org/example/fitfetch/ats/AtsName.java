package org.example.fitfetch.ats;

import org.springframework.util.ClassUtils;

import java.util.Arrays;

/**
 * Enumerates the supported Applicant Tracking System (ATS) providers.
 *
 * <p>Each constant pairs an internal identifier with the {@link #stringValue()
 * canonical string} used when persisting the provider, keeping the storage
 * representation stable even if the enum name changes. Add a new constant here
 * once the corresponding {@link Ats} implementation exists.
 *
 * @see Ats
 */
public enum AtsName {
    /** The Greenhouse ATS (<a href="https://www.greenhouse.io/">greenhouse.io</a>). */
    GREENHOUSE("Greenhouse"),
    /** The Ashby ATS (<a href="https://www.ashbyhq.com/">ashbyhq.com</a>). */
    ASHBY("Ashby");
    // Add additional ATSs once implemented

    private final String stringValue;

    AtsName(String stringValue) {
        this.stringValue = stringValue;
    }

    /**
     * Returns the canonical string for this provider as stored in the database.
     *
     * <p>Prefer this over {@link #name()} for persistence and external
     * comparisons so the stored value stays decoupled from the enum constant.
     *
     * @return the database representation of this provider
     */
    public String stringValue() {
        return stringValue;
    }

    /**
     * Resolves the {@code AtsName} whose {@link #stringValue()} equals the given
     * string.
     *
     * @param stringValue the persisted string representation
     * @return the matching constant
     * @throws IllegalArgumentException if no constant has that string value
     */
    public static AtsName fromStringValue(String stringValue) {
        return Arrays.stream(values())
                .filter(ats -> ats.stringValue.equals(stringValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No AtsName with string value: " + stringValue));
    }

    /**
     * Resolves the {@code AtsName} that corresponds to a given {@link Ats}
     * implementation class.
     *
     * <p>Any Spring proxy wrapper is stripped first, then the enum whose
     * {@link #name() name} is contained in the (upper-cased) simple class name
     * is returned. For example, {@code GreenhouseAts} resolves to
     * {@link #GREENHOUSE}.
     *
     * @param clazz the ATS board implementation class, possibly a Spring proxy
     * @return the matching {@code AtsName}
     * @throws IllegalArgumentException if no constant matches the class name
     */
    public static AtsName fromBoardClass(Class<? extends Ats> clazz) {
        // Strips away any Spring proxy wrapper to get the underlying user class
        Class<?> userClass = ClassUtils.getUserClass(clazz);
        String className = userClass.getSimpleName().toUpperCase();

        return Arrays.stream(values())
                .filter(ats -> className.contains(ats.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No matching Ats enum configuration found for board class: " + userClass.getName()));
    }
}
