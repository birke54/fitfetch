package org.example.fitfetch.normalize;

/**
 * Signals that signal extraction could not be completed because the model was
 * unreachable or its transport failed, rather than because its answer was
 * unusable.
 *
 * <p>The distinction decides what the caller records. A job whose extraction
 * threw stays {@code PENDING} and is tried again next run; marking it
 * {@code FAILED} would turn a passing outage into a permanent verdict on the job.
 */
public class SignalExtractionException extends RuntimeException {

    /**
     * @param message what failed
     * @param cause   the underlying transport failure
     */
    public SignalExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
