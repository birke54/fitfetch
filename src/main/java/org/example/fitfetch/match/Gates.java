package org.example.fitfetch.match;

import org.example.fitfetch.normalize.EmploymentType;
import org.example.fitfetch.normalize.HardRequirements;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Sponsorship;
import org.example.fitfetch.normalize.Track;
import org.example.fitfetch.profile.CandidateProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The yes-or-no checks that exclude a job before any similarity is weighed.
 *
 * <p>A job that requires a clearance the candidate lacks is not a 0.8 match; it
 * is not a match. Each failure is described, so an excluded job shows why.
 * Anything a job leaves unstated passes: a gate only excludes on what the
 * posting actually says.
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class Gates {

    private Gates() {
    }

    /**
     * @param job     what normalization extracted from the job
     * @param profile the candidate
     * @return why the job is excluded, one reason per failed gate; empty if it
     *         passes them all
     */
    public static List<String> failures(NormalizedData job, CandidateProfile profile) {
        CandidateProfile.Summary summary = profile.summary();
        CandidateProfile.Preferences preferences = profile.preferences();
        HardRequirements requirements = job.requirements();
        List<String> failures = new ArrayList<>();

        if (job.track() != summary.track()) {
            failures.add(describe(job.track()) + " role, and the profile is " + describe(summary.track()));
        }
        if (job.employmentType() != EmploymentType.UNSTATED && !preferences.employmentTypes().isEmpty()
                && !preferences.employmentTypes().contains(job.employmentType())) {
            failures.add("offered as " + job.employmentType().label() + ", which the profile does not accept");
        }
        if (requirements.clearanceRequired() && !summary.clearance()) {
            failures.add("requires a security clearance");
        }
        if (summary.needsSponsorship() && requirements.sponsorship() == Sponsorship.NO) {
            failures.add("will not sponsor a visa");
        }
        if (requirements.requiredDegree().compareTo(summary.degree()) > 0) {
            failures.add("requires a " + requirements.requiredDegree().label() + " degree");
        }
        Set<String> held = summary.certifications().stream()
                .map(cert -> cert.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> missing = requirements.requiredCertifications().stream()
                .filter(cert -> !held.contains(cert.toLowerCase(Locale.ROOT))).toList();
        if (!missing.isEmpty()) {
            failures.add("requires certifications not held: " + String.join(", ", missing));
        }
        if (requirements.travelRequired() && !preferences.travelOk()) {
            failures.add("requires travel");
        }
        if (requirements.onCall() && !preferences.onCallOk()) {
            failures.add("includes an on-call rotation");
        }
        return failures;
    }

    private static String describe(Track track) {
        return switch (track) {
            case IC -> "an individual contributor";
            case MANAGER -> "a people-manager";
        };
    }
}
