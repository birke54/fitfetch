package org.example.fitfetch.location;

/**
 * Signals that location extraction could not be completed because the model was
 * unreachable or its transport failed, rather than because the input was
 * unusable.
 *
 * <p>The distinction decides what the caller persists. A job whose extraction
 * threw must be left {@code PENDING} and retried on the next pass; recording an
 * {@code UNDEFINED} row instead would bake a transient outage into durable data
 * and quietly drop a job the model would have handled fine a minute later.
 *
 * <p>Contrast with an extraction that succeeds but yields
 * {@link LocationKind#UNPARSEABLE}: that is a real answer about the input, and
 * the caller should record it.
 */
public class LocationExtractionException extends RuntimeException {

    /**
     * @param message what failed, including the label being extracted
     * @param cause   the underlying transport or parsing failure
     */
    public LocationExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    /** @param message what failed, including the label being extracted */
    public LocationExtractionException(String message) {
        super(message);
    }
}
