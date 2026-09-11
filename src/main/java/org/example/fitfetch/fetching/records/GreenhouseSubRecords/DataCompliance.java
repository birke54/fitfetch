package org.example.fitfetch.fetching.records.GreenhouseSubRecords;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One data-compliance regime entry from a Greenhouse job's
 * {@code data_compliance} array, describing the consent and retention rules
 * that apply to applicant data for that regime (for example {@code "gdpr"}).
 *
 * @param type                         the compliance regime identifier, e.g. {@code "gdpr"}
 * @param requiresConsent              whether applicant consent is required at all
 *                                     ({@code requires_consent})
 * @param requiresProcessingConsent    whether separate consent to process the
 *                                     data is required ({@code requires_processing_consent})
 * @param requiresRetentionConsent     whether separate consent to retain the
 *                                     data is required ({@code requires_retention_consent})
 * @param retentionPeriod              maximum retention period in days, or
 *                                     {@code null} if unspecified ({@code retention_period})
 * @param demographicDataConsentApplies whether the consent rules also cover
 *                                     demographic data
 *                                     ({@code demographic_data_consent_applies})
 */
public record DataCompliance(
        String type,
        @JsonProperty("requires_consent") boolean requiresConsent,
        @JsonProperty("requires_processing_consent") boolean requiresProcessingConsent,
        @JsonProperty("requires_retention_consent") boolean requiresRetentionConsent,
        @JsonProperty("retention_period") Integer retentionPeriod,
        @JsonProperty("demographic_data_consent_applies") boolean demographicDataConsentApplies
) {}