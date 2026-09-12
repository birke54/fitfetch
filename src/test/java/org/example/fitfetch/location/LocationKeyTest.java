package org.example.fitfetch.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class LocationKeyTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            Remote US                  | remote us
            REMOTE US                  | remote us
            ReMoTe Us                  | remote us
            Remote    US               | remote us
            'Remote\tUS'               | remote us
            New York, New York, USA    | new york, new york, usa
            N/A                        | n/a
            LOCATION                   | location
            """)
    @DisplayName("Case and internal whitespace fold away")
    void testBasicNormalization(String raw, String expected) {
        assertEquals(expected, LocationKey.normalize(raw));
    }

    @Test
    @DisplayName("Trailing whitespace variants collapse to one key")
    void testTrailingWhitespaceCollapses() {
        // Airbnb publishes both spellings for different jobs; untrimmed they
        // would be two cache entries and two geocode calls for one place.
        assertEquals(LocationKey.normalize("United States"),
                LocationKey.normalize("United States "));
    }

    @Test
    @DisplayName("Diacritics fold, so accented and plain spellings share a key")
    void testDiacriticsFold() {
        assertEquals("montreal, quebec, canada",
                LocationKey.normalize("Montréal, Quebec, Canada"));
        assertEquals("sao paulo", LocationKey.normalize("São Paulo"));
        assertEquals(LocationKey.normalize("São Paulo"),
                LocationKey.normalize("Sao Paulo"));
    }

    @ParameterizedTest(name = "[{index}] dash variant {0}")
    @ValueSource(strings = {
            "Remote ‐ US",  // hyphen
            "Remote ‒ US",  // figure dash
            "Remote – US",  // en dash
            "Remote — US",  // em dash
            "Remote − US"   // minus sign
    })
    @DisplayName("Every dash variant folds to the ASCII hyphen key")
    void testDashVariantsFold(String raw) {
        assertEquals("remote - us", LocationKey.normalize(raw));
    }

    @Test
    @DisplayName("Spacing around punctuation is preserved, keeping curated keys distinct")
    void testPunctuationSpacingIsPreserved() {
        // Both spellings occur in real data and both are curated separately.
        assertNotEquals(LocationKey.normalize("Remote - US"), LocationKey.normalize("Remote-US"));
        assertEquals("remote-us", LocationKey.normalize("Remote-US"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n  \t "})
    @DisplayName("Blank input normalizes to empty and reports as blank")
    void testBlankInput(String raw) {
        assertEquals("", LocationKey.normalize(raw));
        assertTrue(LocationKey.isBlank(raw));
    }

    @Test
    @DisplayName("Null input is tolerated rather than thrown")
    void testNullInput() {
        assertEquals("", LocationKey.normalize(null));
        assertTrue(LocationKey.isBlank(null));
    }

    @Test
    @DisplayName("The longest observed real label normalizes without loss")
    void testLongestObservedLabel() {
        // 261 characters, from Datadog enumerating remote eligibility by state.
        String raw = "Boston, Massachusetts, USA; Connecticut, USA, Remote; Delaware, USA, Remote; "
                + "District of Columbia, USA, Remote; Maryland, USA, Remote; Massachusetts, USA, Remote; "
                + "New Jersey, USA, Remote; New York, New York, USA";

        String key = LocationKey.normalize(raw);

        assertFalse(key.isEmpty());
        assertTrue(key.startsWith("boston, massachusetts, usa;"));
        assertEquals(raw.length(), key.length(), "no characters should be added or dropped");
    }

    @Test
    @DisplayName("Normalization is idempotent")
    void testIdempotent() {
        String once = LocationKey.normalize("  Remote — México  ");
        assertEquals(once, LocationKey.normalize(once));
    }
}
