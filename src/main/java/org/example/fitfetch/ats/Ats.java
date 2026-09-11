package org.example.fitfetch.ats;

import org.example.fitfetch.fetching.records.AtsJobEntry;

import java.util.List;

/**
 * Abstraction over a single Applicant Tracking System (ATS) provider such as
 * Greenhouse, Lever or Workday.
 *
 * <p>An implementation is responsible for knowing which company boards
 * ("slugs") it should poll, for retrieving the currently posted jobs from that
 * provider's API and for returning only the entries that are new and relevant
 * (for example, filtering out jobs that have already been persisted or whose
 * title does not match the configured criteria).
 *
 * @see AtsName
 * @see AtsJobEntry
 */
public interface Ats {

    /**
     * Retrieves the current job postings from this ATS provider for every
     * configured slug and returns the entries that should be treated as newly
     * discovered.
     *
     * <p>Implementations are expected to filter the raw provider response,
     * typically dropping jobs that have already been stored and jobs whose
     * title is not of interest. Transport-level failures for an individual
     * slug should be handled internally (for example, logged and skipped)
     * rather than aborting the whole fetch.
     *
     * @return the list of new, relevant job entries; never {@code null}, but
     *         possibly empty when nothing new was found
     */
    List<AtsJobEntry> fetchJobs();

    /**
     * Returns the company board identifiers ("slugs") that this instance is
     * configured to poll.
     *
     * <p>The slugs are usually loaded once at construction time from external
     * configuration.
     *
     * @return the configured slugs in configuration order; never {@code null}
     */
    List<String> getSlugs();
}
