package org.example.fitfetch.match;

/**
 * Thrown when the matching pass runs with no candidate profile loaded.
 *
 * <p>Its own type so the pass can tell it apart from a failure. Matching
 * enabled with nothing to score against is a configuration to fix, not an
 * outage to retry, but it is not a working pass either: it writes no matches,
 * and counted as a success it would read exactly like a pass with nothing left
 * to do. It stops the run instead, so {@code match.pass.stopped.count} names it
 * and {@code match.pass.last.success.seconds} goes stale.
 *
 * @see org.example.fitfetch.profile.ProfileSource
 */
public class MissingProfileException extends RuntimeException {

    /** @param message what was missing */
    public MissingProfileException(String message) {
        super(message);
    }
}
