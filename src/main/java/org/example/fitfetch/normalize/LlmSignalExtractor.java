package org.example.fitfetch.normalize;

import java.util.Optional;

/**
 * Extracts seniority, the job-level fields that gate a match, and requirement
 * signals from a job posting.
 *
 * <p>Failures are split by what they say about the input, as for location
 * extraction:
 *
 * <ul>
 *   <li><strong>Transport failure</strong> &mdash; the model is down, refused
 *       or timed out. Throws {@link SignalExtractionException}; the job stays
 *       pending and is tried again next run.</li>
 *   <li><strong>No usable answer</strong> &mdash; the output would not parse,
 *       was truncated, named no seniority band, or held no signals. Returns
 *       empty; sampling is deterministic, so asking again next run would get the
 *       same answer.</li>
 * </ul>
 *
 * @see OllamaSignalExtractor
 */
public interface LlmSignalExtractor {

    /**
     * @param title          the job title, which usually carries the level;
     *                       may be {@code null}
     * @param jobDescription the description as plain text; must not be blank
     * @return the extraction, or empty if the model gave no usable answer
     * @throws SignalExtractionException if the model could not be reached
     */
    Optional<NormalizedData> extract(String title, String jobDescription);

    /** @return the model tag, recorded against every normalized job */
    String model();
}
