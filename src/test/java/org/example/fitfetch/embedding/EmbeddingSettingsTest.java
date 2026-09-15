package org.example.fitfetch.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingSettingsTest {

    private final EmbeddingSettings nomic =
            new EmbeddingSettings("nomic-embed-text", "search_query: ", "search_document: ");

    @Test
    @DisplayName("Job signals are sent as queries and bullets as documents")
    void testPrefixes() {
        assertEquals("search_query: Knows Kafka.", nomic.query("Knows Kafka."));
        assertEquals("search_document: Ran Kafka.", nomic.document("Ran Kafka."));
    }

    @Test
    @DisplayName("The key names the model and changes with it or with either prefix")
    void testKey() {
        assertTrue(nomic.key().startsWith("nomic-embed-text#"));
        assertEquals(nomic.key(), new EmbeddingSettings("nomic-embed-text", "search_query: ", "search_document: ").key());
        assertNotEquals(nomic.key(), new EmbeddingSettings("bge-m3", "search_query: ", "search_document: ").key());
        assertNotEquals(nomic.key(), new EmbeddingSettings("nomic-embed-text", "", "search_document: ").key());
        assertNotEquals(nomic.key(), new EmbeddingSettings("nomic-embed-text", "search_query: ", "").key());
        assertTrue(nomic.key().length() <= 100, "fits normalized_jobs.embedded_with");
    }

    @Test
    @DisplayName("Missing prefixes are empty, and a model is required")
    void testDefaults() {
        assertEquals("text", new EmbeddingSettings("m", null, null).query("text"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingSettings(" ", "", ""));
    }
}
