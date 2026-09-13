package org.example.fitfetch.location;

/**
 * Signals a geocode lookup that failed for reasons unrelated to the query.
 *
 * <p>Two flavours, distinguished by {@link #isFatal()}:
 *
 * <ul>
 *   <li><strong>Retryable</strong> &mdash; quota exhausted, an unknown error on
 *       Google's side, a network fault. Back off and try again; the same query
 *       will very likely succeed later.</li>
 *   <li><strong>Fatal</strong> &mdash; the request was denied, which means the
 *       API key is wrong, restricted, or billing is not enabled. Retrying cannot
 *       help and will burn through the remaining budget, so the pass should
 *       stop rather than grind through every pending job.</li>
 * </ul>
 *
 * <p>Neither flavour may be cached. Persisting either as a {@link GeocodeStatus}
 * would turn a temporary condition into a permanent verdict about a place.
 */
public class GeocodingException extends RuntimeException {

    private final boolean fatal;

    /**
     * @param message what failed, including the query. Must never include the
     *                request URI, which carries the API key
     * @param fatal   whether retrying is pointless
     */
    public GeocodingException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    /**
     * @param message what failed, including the query
     * @param fatal   whether retrying is pointless
     * @param cause   the underlying transport failure
     */
    public GeocodingException(String message, boolean fatal, Throwable cause) {
        super(message, cause);
        this.fatal = fatal;
    }

    /**
     * @return {@code true} if retrying cannot help and the pass should stop
     */
    public boolean isFatal() {
        return fatal;
    }

    /** @return {@code true} if the same query is worth trying again later */
    public boolean isRetryable() {
        return !fatal;
    }
}
