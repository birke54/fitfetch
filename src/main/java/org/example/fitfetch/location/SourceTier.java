package org.example.fitfetch.location;

/**
 * Which tier of the resolution pipeline answered a label.
 *
 * <p>Recorded on every persisted location as provenance. It costs one small
 * column and is the first thing worth knowing when a match looks wrong: a
 * surprising result from {@link #CURATED} means a hand-checked entry is wrong,
 * while the same result from {@link #LLM} means the model guessed.
 *
 * <p>Tracked as a distribution too. A rising {@link #LLM} share means new boards
 * are introducing label shapes worth curating, which is the signal that the
 * curated table needs extending.
 *
 * <p>These constants are mirrored by the {@code ck_job_locations_source_tier}
 * check constraint.
 */
public enum SourceTier {

    /** Answered by the hand-curated exact-match table, at the cost of a map lookup. */
    CURATED,

    /** Answered from a previously cached model extraction. */
    INTERPRETATION,

    /** Answered by a fresh model call. */
    LLM
}
