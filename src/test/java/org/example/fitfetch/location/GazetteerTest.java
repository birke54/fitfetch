package org.example.fitfetch.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GazetteerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "US", "U.S.", "USA", "U.S.A.", "United States",
            "United States of America", "America", "united states", "  usa  "
    })
    @DisplayName("Every observed US spelling resolves to the same country")
    void testUnitedStatesAliases(String spelling) {
        assertTrue(Gazetteer.isUnitedStates(spelling), spelling + " should resolve to the US");
        assertEquals("US", Gazetteer.countryCode(spelling).orElseThrow());
    }

    @Test
    @DisplayName("The United Kingdom is GB, not UK")
    void testUnitedKingdomIsGb() {
        // Storing the literal token instead of the resolved code is a classic
        // source of silent mismatches.
        assertEquals("GB", Gazetteer.countryCode("United Kingdom").orElseThrow());
        assertEquals("GB", Gazetteer.countryCode("UK").orElseThrow());
        assertEquals("GB", Gazetteer.countryCode("England").orElseThrow());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "Canada,CA", "Poland,PL", "Spain,ES", "Brazil,BR", "Mexico,MX",
            "China,CN", "Ireland,IE", "Israel,IL", "India,IN", "Japan,JP",
            "Netherlands,NL", "Germany,DE", "France,FR", "Australia,AU",
            "Singapore,SG", "Turkey,TR", "Portugal,PT", "Indonesia,ID"
    })
    @DisplayName("Formal country names resolve from the JDK with no hand-maintained list")
    void testJdkSourcedCountries(String name, String expected) {
        assertEquals(expected, Gazetteer.countryCode(name).orElseThrow());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({"KSA,SA", "UAE,AE", "Holland,NL", "South Korea,KR", "Phillipines,PH"})
    @DisplayName("Informal spellings and a real misspelling resolve")
    void testInformalAliases(String name, String expected) {
        // "Phillipines" is spelled that way in live Greenhouse data.
        assertEquals(expected, Gazetteer.countryCode(name).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Every country label observed across eleven live Greenhouse boards,
            // in the spellings the boards actually publish. This is the guard
            // against CLDR renames: the JDK returns "Turkiye" for TR, so a
            // posting saying "Turkey" would silently resolve to nothing without
            // an alias, and the job would be dropped as UNDEFINED.
            "Canada", "United States", "US", "USA", "United Kingdom", "UK",
            "India", "Poland", "Germany", "Israel", "Ireland", "Netherlands",
            "The Netherlands", "Spain", "Singapore", "Australia", "France",
            "Mexico", "KSA", "Japan", "Italy", "South Korea", "UAE",
            "United Arab Emirates", "Brazil", "Phillipines", "Philippines",
            "Austria", "Turkey", "China", "Indonesia", "Portugal", "Sweden"
    })
    @DisplayName("Every country spelling seen in live board data resolves")
    void testEveryObservedCountryResolves(String observed) {
        assertTrue(Gazetteer.countryCode(observed).isPresent(),
                observed + " appears in real postings but resolves to no country");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Atlantis", "Multiple Locations", "TBD", "Various", "N/A", "LOCATION"})
    @DisplayName("Labels naming no country are rejected rather than geocoded")
    void testHallucinationGuard(String bogus) {
        assertTrue(Gazetteer.countryCode(bogus).isEmpty(), bogus + " should not resolve");
        assertFalse(Gazetteer.isUnitedStates(bogus));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMEA", "APAC", "LATAM", "North America", "Europe", "emea", "Middle East"})
    @DisplayName("Macro-regions are recognised as multi-country groupings")
    void testMacroRegions(String region) {
        assertTrue(Gazetteer.isMacroRegion(region));
    }

    @Test
    @DisplayName("A country is not a macro-region")
    void testCountryIsNotMacroRegion() {
        assertFalse(Gazetteer.isMacroRegion("Canada"));
        assertFalse(Gazetteer.isMacroRegion("United States"));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "Washington,US-WA", "WA,US-WA", "California,US-CA", "CA,US-CA",
            "Connecticut,US-CT", "New York,US-NY", "District of Columbia,US-DC",
            "Maryland,US-MD", "Virginia,US-VA", "Texas,US-TX"
    })
    @DisplayName("State names and postal abbreviations both resolve")
    void testUsStates(String name, String expected) {
        assertEquals(expected, Gazetteer.usStateCode(name).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Connecticut, USA", "Maryland, USA", "Virginia, USA", "California, United States"})
    @DisplayName("A state with a trailing country still resolves")
    void testStateWithTrailingCountry(String specifier) {
        // Datadog writes state-level remote eligibility exactly this way.
        assertTrue(Gazetteer.usStateCode(specifier).isPresent(), specifier + " should resolve");
    }

    @Test
    @DisplayName("Home state matching accepts both bare and ISO forms")
    void testHomeStateMatching() {
        assertTrue(Gazetteer.isHomeState("Washington", "WA"));
        assertTrue(Gazetteer.isHomeState("Washington", "US-WA"));
        assertTrue(Gazetteer.isHomeState("WA", "WA"));
        assertFalse(Gazetteer.isHomeState("Connecticut", "WA"));
        assertFalse(Gazetteer.isHomeState("Washington", null));
        assertFalse(Gazetteer.isHomeState("Washington", ""));
    }

    @Test
    @DisplayName("Labels naming no state are rejected")
    void testUnknownState() {
        assertTrue(Gazetteer.usStateCode("Ontario").isEmpty());
        assertTrue(Gazetteer.usStateCode("Bavaria").isEmpty());
        assertTrue(Gazetteer.usStateCode("").isEmpty());
        assertTrue(Gazetteer.usStateCode(null).isEmpty());
    }

    @Test
    @DisplayName("Georgia and New York are deliberately ambiguous; the caller's type decides")
    void testAmbiguousLabelsResolveInBothNamespaces() {
        // Georgia is both a US state and a sovereign country; New York is both a
        // state and a city. The gazetteer answers both questions and lets the
        // extractor's specifier type pick the branch.
        assertEquals("US-GA", Gazetteer.usStateCode("Georgia").orElseThrow());
        assertEquals("GE", Gazetteer.countryCode("Georgia").orElseThrow());
        assertEquals("US-NY", Gazetteer.usStateCode("New York").orElseThrow());
        assertTrue(Gazetteer.countryCode("New York").isEmpty());
    }
}
