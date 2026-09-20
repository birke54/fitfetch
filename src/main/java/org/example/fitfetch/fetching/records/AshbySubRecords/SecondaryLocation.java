package org.example.fitfetch.fetching.records.AshbySubRecords;

/**
 * One entry from an Ashby job's {@code secondaryLocations} array: an additional
 * place the posting is open in, beyond the primary {@code location}.
 *
 * <p>These are first-class locations, not annotations on the primary one. A
 * posting listing twenty European countries carries one of these per country
 * and a primary of {@code "Remote - European Union"}, so dropping them would
 * lose nineteen of the twenty places the job is actually open in &mdash; see
 * {@link org.example.fitfetch.fetching.records.AshbyJobEntry#locationName()},
 * which folds them into the single label the location pipeline resolves.
 *
 * @param location the free-text label for this location, e.g. {@code "Spain"}
 *                 or {@code "Berlin"}; may be {@code null}
 * @param address  the structured office address for this location; may be
 *                 {@code null}
 */
public record SecondaryLocation(
        String location,
        Address address
) {}
