package org.example.fitfetch.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stores and compares embedding vectors.
 *
 * <p>Vectors are kept as {@code bytea}, four little-endian bytes per dimension,
 * and compared in Java. At this scale (thousands of job signals against dozens
 * of bullets) that takes well under a second, so it needs neither the pgvector
 * extension nor the Postgres image change that would bring.
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class Vectors {

    private Vectors() {
    }

    /** @return the vector as four little-endian bytes per dimension */
    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    /**
     * @return the vector {@link #toBytes} wrote
     * @throws IllegalArgumentException if the length is not a whole number of floats
     */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("A vector of " + bytes.length + " bytes is not whole floats");
        }
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    /**
     * @return the cosine similarity, from -1 to 1; 0 if either vector is all zeros
     * @throws IllegalArgumentException if the vectors differ in length, as
     *                                  vectors from two models would
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Cannot compare vectors of " + a.length + " and " + b.length
                    + " dimensions; they come from different models");
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
