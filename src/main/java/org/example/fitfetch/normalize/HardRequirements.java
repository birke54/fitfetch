package org.example.fitfetch.normalize;

import java.util.List;
import java.util.Objects;

/**
 * What a job requires outright: things a candidate either meets or does not,
 * which gate a match rather than add to its similarity.
 *
 * <p>Only requirements count. A preferred degree or certification is a
 * {@link Signal}, and stays out of here.
 *
 * @param requiredDegree         the lowest degree required
 * @param clearanceRequired      whether a security clearance is held or must be
 *                               obtained
 * @param sponsorship            what the posting says about visa sponsorship
 * @param requiredCertifications certifications required, by common name
 * @param travelRequired         whether the role requires travel
 * @param onCall                 whether the role includes an on-call rotation
 */
public record HardRequirements(Degree requiredDegree,
                               boolean clearanceRequired,
                               Sponsorship sponsorship,
                               List<String> requiredCertifications,
                               boolean travelRequired,
                               boolean onCall) {

    public HardRequirements {
        Objects.requireNonNull(requiredDegree, "requiredDegree");
        Objects.requireNonNull(sponsorship, "sponsorship");
        requiredCertifications = requiredCertifications == null ? List.of() : List.copyOf(requiredCertifications);
    }

    /** A job requiring none of these. */
    public static final HardRequirements NONE =
            new HardRequirements(Degree.NONE, false, Sponsorship.UNSTATED, List.of(), false, false);
}
