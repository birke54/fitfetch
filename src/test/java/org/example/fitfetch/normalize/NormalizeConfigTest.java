package org.example.fitfetch.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NormalizeConfigTest {

    @Test
    @DisplayName("Thinking is unset unless configured, so the field stays out of the request")
    void testThinkingUnset() {
        assertNull(NormalizeConfig.thinking(null));
        assertNull(NormalizeConfig.thinking(""));
        assertNull(NormalizeConfig.thinking("  "));
    }

    @Test
    @DisplayName("Thinking is read in any case, with surrounding space")
    void testThinkingRead() {
        assertEquals(Boolean.FALSE, NormalizeConfig.thinking("false"));
        assertEquals(Boolean.FALSE, NormalizeConfig.thinking(" FALSE "));
        assertEquals(Boolean.TRUE, NormalizeConfig.thinking("true"));
    }

    @Test
    @DisplayName("A value that is neither true nor false stops startup rather than being read as false")
    void testThinkingRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> NormalizeConfig.thinking("no"));
        assertTrue(error.getMessage().contains("no"), error.getMessage());
    }
}
