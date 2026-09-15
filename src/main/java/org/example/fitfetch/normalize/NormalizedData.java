package org.example.fitfetch.normalize;

import java.util.List;
import java.util.Objects;

/**
 * What normalization extracts from one job posting: the fields that filter and
 * gate a match, and the signals that are scored against a resume.
 *
 * <p>The title, company, location and dates are not here. They are in the
 * fetched payload already, and asking the model to repeat them would only cost
 * tokens.
 *
 * @param seniority          the level the job is pitched at
 * @param track              individual contributor or people manager
 * @param employmentType     the terms it is offered on
 * @param minYearsExperience years of overall experience required; 0 if none is
 *                           stated
 * @param requirements       what it requires outright
 * @param domains            business domains the work is in, lower case; empty
 *                           if it names none
 * @param signals            its requirements, never empty: a description with
 *                           nothing to extract has no usable answer
 */
public record NormalizedData(Seniority seniority,
                             Track track,
                             EmploymentType employmentType,
                             int minYearsExperience,
                             HardRequirements requirements,
                             List<String> domains,
                             List<Signal> signals) {

    public NormalizedData {
        Objects.requireNonNull(seniority, "seniority");
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(employmentType, "employmentType");
        Objects.requireNonNull(requirements, "requirements");
        if (minYearsExperience < 0) {
            throw new IllegalArgumentException("minYearsExperience must not be negative, but was "
                    + minYearsExperience);
        }
        domains = domains == null ? List.of() : List.copyOf(domains);
        if (signals == null || signals.isEmpty()) {
            throw new IllegalArgumentException("signals must not be empty");
        }
        signals = List.copyOf(signals);
    }
}
