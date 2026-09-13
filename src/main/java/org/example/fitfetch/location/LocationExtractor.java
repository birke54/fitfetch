package org.example.fitfetch.location;

/**
 * Turns one raw {@code location.name} label into the location expressions it
 * contains.
 *
 * <p>Extraction is tier three of the resolution pipeline, reached only for
 * labels the hand-curated table and the interpretation cache could not answer.
 * That is the point: the distribution is head-heavy enough that a 112-entry
 * curated table covers roughly four fifths of jobs, leaving the model a long
 * tail in which most labels appear exactly once.
 *
 * <p>Implementations describe what a label <em>says</em> and nothing more. They
 * do not know the search origin, the home state, or which countries the user may
 * work in &mdash; that is {@link LocationPolicy}'s job. Keeping the split means
 * a cached extraction survives a change to any of those.
 *
 * @see LocationPolicy
 * @see CuratedLocations
 */
public interface LocationExtractor {

    /**
     * Extracts every location expression from a raw label.
     *
     * <p>A label naming several places yields several expressions: real payloads
     * separate them with semicolons, ampersands or the word "or", and one
     * observed string enumerates fifteen.
     *
     * @param rawLocationName the verbatim label; must not be {@code null}
     * @return the extraction outcome, never {@code null} and never carrying an
     *         empty list
     * @throws LocationExtractionException if the model was unreachable or its
     *         transport failed, in which case the caller must leave the job
     *         pending rather than record a result
     */
    ExtractionResult extract(String rawLocationName);
}
