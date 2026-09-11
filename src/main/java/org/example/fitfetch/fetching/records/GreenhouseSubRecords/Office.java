package org.example.fitfetch.fetching.records.GreenhouseSubRecords;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One entry from a Greenhouse job's {@code offices} array.
 *
 * <p>Offices form a shallow tree on a board: a top-level office may have child
 * offices, linked through {@code parentId} / {@code childIds}. A single job can
 * be attached to several offices, which is why the job carries a list.
 *
 * @param id       the office identifier, unique within the board
 * @param name     the office display name, e.g. {@code "New York"} or
 *                 {@code "Remote"}
 * @param location free-text location label for the office, e.g.
 *                 {@code "New York, NY, United States"}; may be {@code null}
 * @param parentId the {@link #id()} of this office's parent, or {@code null}
 *                 for a top-level office ({@code parent_id})
 * @param childIds identifiers of this office's direct children; empty when the
 *                 office is a leaf ({@code child_ids})
 */
public record Office(
        Long id,
        String name,
        String location,
        @JsonProperty("parent_id") Long parentId,
        @JsonProperty("child_ids") List<Long> childIds
) {}
