package org.example.fitfetch.normalize.records;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The model's answer as the schema shapes it, before it is checked.
 *
 * <p>Every field is boxed or a string, so a field the model left out arrives as
 * {@code null} and the extractor can decide what that means.
 *
 * @param seniority              one of the {@code Seniority} labels, or anything
 *                               else if the model strayed from the schema
 * @param track                  one of the {@code Track} labels
 * @param employmentType         one of the {@code EmploymentType} labels
 * @param minYearsExperience     years of overall experience required
 * @param requiredDegree         one of the {@code Degree} labels
 * @param clearanceRequired      whether a security clearance is required
 * @param sponsorship            one of the {@code Sponsorship} labels
 * @param requiredCertifications certifications required
 * @param travelRequired         whether travel is required
 * @param onCall                 whether the role is on call
 * @param domains                business domains
 * @param signals                the extracted requirements
 */
public record SignalPayload(String seniority,
                            String track,
                            @JsonProperty("employment_type") String employmentType,
                            @JsonProperty("min_years_experience") Integer minYearsExperience,
                            @JsonProperty("required_degree") String requiredDegree,
                            @JsonProperty("clearance_required") Boolean clearanceRequired,
                            String sponsorship,
                            @JsonProperty("required_certifications") List<String> requiredCertifications,
                            @JsonProperty("travel_required") Boolean travelRequired,
                            @JsonProperty("on_call") Boolean onCall,
                            List<String> domains,
                            List<Item> signals) {

    /**
     * @param classification one of the {@code SignalClassification} labels
     * @param text           the requirement as one sentence
     * @param skills         the technologies and methods it names
     * @param minYears       years of experience it asks for
     */
    public record Item(String classification,
                       String text,
                       List<String> skills,
                       @JsonProperty("min_years") Integer minYears) {
    }
}
