package org.example.fitfetch.location;

/**
 * What shape a single location expression takes, as classified by the
 * extraction step.
 *
 * <p>This is the extractor's vocabulary, deliberately kept separate from
 * {@link Resolution}: the extractor describes what the text <em>says</em>, and
 * {@link LocationPolicy} decides what that <em>means</em> for this user. Keeping
 * the two apart is what lets the search origin change without invalidating a
 * single cached interpretation.
 */
public enum LocationKind {

    /** A geographic place: an address, city, state, country or region. */
    PLACE,

    /** A remote marker with no geographic qualifier attached. */
    REMOTE_BARE,

    /** A remote marker qualified by a place, as in {@code "Remote - India"}. */
    REMOTE_SPECIFIER,

    /** A non-location placeholder such as {@code "N/A"} or {@code "LOCATION"}. */
    SENTINEL,

    /** Text the extractor could make no sense of. */
    UNPARSEABLE
}
