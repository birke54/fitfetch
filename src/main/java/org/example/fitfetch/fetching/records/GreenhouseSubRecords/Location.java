package org.example.fitfetch.fetching.records.GreenhouseSubRecords;

/**
 * The {@code location} object on a Greenhouse job: a single free-text location
 * label with no further structure.
 *
 * @param name the human-readable location, e.g. {@code "Remote - US"} or
 *             {@code "New York, NY"}
 */
public record Location(
        String name
) {}
