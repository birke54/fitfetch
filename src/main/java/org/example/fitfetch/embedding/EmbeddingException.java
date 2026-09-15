package org.example.fitfetch.embedding;

/**
 * The embedding model could not be reached, or answered with nothing: a
 * failure of the moment, not of the input, so the work is left for the next run.
 */
public class EmbeddingException extends RuntimeException {

    /**
     * @param message what failed
     * @param cause   the underlying failure, or {@code null}
     */
    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
