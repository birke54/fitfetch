package org.example.fitfetch.embedding;

import java.util.List;

/**
 * Turns text into vectors, so a job requirement and a resume bullet can be
 * compared by meaning rather than by wording.
 *
 * @see OllamaEmbedder
 */
public interface Embedder {

    /**
     * @param inputs texts to embed, in order; may be empty
     * @return one vector per input, in the same order
     * @throws EmbeddingException if the model could not be reached; the caller
     *                            tries again next run
     */
    List<float[]> embed(List<String> inputs);
}
