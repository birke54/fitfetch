package org.example.fitfetch.embedding;

import org.example.fitfetch.domain.Embedding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class VectorsTest {

    @Test
    @DisplayName("A vector survives the round trip through bytes, four per dimension")
    void testRoundTrip() {
        float[] vector = {0.25f, -1.5f, 3.0e-5f, Float.MIN_VALUE};

        byte[] bytes = Vectors.toBytes(vector);

        assertEquals(16, bytes.length);
        assertArrayEquals(vector, Vectors.fromBytes(bytes));
    }

    @Test
    @DisplayName("Bytes that are not whole floats are rejected")
    void testPartialFloatRejected() {
        assertThrows(IllegalArgumentException.class, () -> Vectors.fromBytes(new byte[5]));
    }

    @Test
    @DisplayName("Cosine similarity is 1 for the same direction, 0 for orthogonal and -1 for opposite")
    void testCosine() {
        assertEquals(1.0, Vectors.cosine(new float[]{1, 2, 3}, new float[]{2, 4, 6}), 1e-9);
        assertEquals(0.0, Vectors.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertEquals(-1.0, Vectors.cosine(new float[]{1, 1}, new float[]{-1, -1}), 1e-9);
        assertEquals(0.0, Vectors.cosine(new float[]{0, 0}, new float[]{1, 1}), "a zero vector matches nothing");
    }

    @Test
    @DisplayName("Vectors of different lengths are refused, as vectors from two models would be")
    void testDimensionMismatch() {
        assertThrows(IllegalArgumentException.class, () -> Vectors.cosine(new float[3], new float[4]));
    }

    @Test
    @DisplayName("The cache row stores the vector and its dimensions, and gives it back")
    void testEntityRoundTrip() {
        float[] vector = {0.5f, 0.25f, -0.125f};

        Embedding row = new Embedding("nomic-embed-text", "abc", vector, OffsetDateTime.now());

        assertArrayEquals(vector, row.getVector());
    }
}
