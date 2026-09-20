package org.example.fitfetch.fetching.records.LeverSubRecords;

import java.util.List;

/**
 * The {@code categories} object on a Lever posting: the board's own
 * classification of the job, and the only place a location appears.
 *
 * <p><strong>Absent, not null.</strong> Unlike the top-level strings on a Lever
 * posting &mdash; which are always present and use {@code ""} for "not set"
 * &mdash; the keys inside {@code categories} are simply omitted when the
 * recruiter left the field blank. Across 12,231 postings sampled from 375 live
 * boards, {@code department} was missing on 3,170 (25.9%), {@code commitment}
 * on 409, {@code location} on 8 and {@code team} on 5, so every component here
 * is nullable and callers must say so. {@code allLocations} is the exception:
 * the key is always present, though it is an empty array on the same 8 postings
 * that carry no {@code location}.
 *
 * @param allLocations every place the posting is open in, in Lever's own order.
 *                     Always present, empty on 8 of 12,231 sampled postings.
 *                     This is the location input:
 *                     {@link org.example.fitfetch.fetching.records.LeverJobEntry#locationName()}
 *                     joins it and reads nothing else
 * @param location     the primary location label. <strong>Redundant</strong>
 *                     &mdash; on all 12,223 sampled postings that carry it, it
 *                     equals {@code allLocations[0]}, making
 *                     {@code allLocations} a strict superset. Modelled so the
 *                     payload round-trips, and read by nothing; may be
 *                     {@code null}
 * @param team         the team the posting sits under, e.g.
 *                     {@code "Information Technology"}; may be {@code null}
 * @param department   the department above that team, e.g.
 *                     {@code "Data Practice"}; {@code null} on 25.9% of sampled
 *                     postings, which is why nothing depends on it
 * @param commitment   free-text employment type, e.g. {@code "Full-time"},
 *                     {@code "Full-time, Limited term"}; may be {@code null}.
 *                     <strong>Uncontrolled</strong>: it is whatever the
 *                     recruiter typed, and the sample spells one working week
 *                     as {@code "Full-Time"} (1,556), {@code "Full-time"}
 *                     (1,513), {@code "Full Time"} (1,499) and
 *                     {@code "Full time"} (384). It is not a usable filter
 *                     &mdash; see {@link org.example.fitfetch.ats.LeverAts},
 *                     which deliberately builds no keep predicate from it
 * @param level        seniority band, e.g. {@code "All Levels"},
 *                     {@code "Senior"}, {@code "Level 5 - 6"}. The rarest key
 *                     in the whole payload: present on 34 of 12,231 sampled
 *                     postings (0.3%), and on no board the fixtures were
 *                     captured from. Modelled anyway, because this record is
 *                     what lands in {@code job_data} and a field left
 *                     unmodelled is dropped at fetch time; may be {@code null}
 */
public record Categories(
        List<String> allLocations,
        String location,
        String team,
        String department,
        String commitment,
        String level
) {}
