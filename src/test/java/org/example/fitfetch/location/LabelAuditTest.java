package org.example.fitfetch.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LabelAuditTest {

    private static final String ORIGIN = "3001 NE 130th St, Seattle, WA 98125";

    private static List<LocationInput> raws(String... raws) {
        return Stream.of(raws).map(LocationInput::undefined).toList();
    }

    @Test
    @DisplayName("Locations that split the label word for word pass both checks")
    void testVerbatimSplitPasses() {
        LabelAudit audit = LabelAudit.of("San Francisco, CA, Seattle WA, New York, NY",
                raws("San Francisco, CA", "Seattle WA", "New York, NY"));

        assertTrue(audit.covered());
        assertTrue(audit.verbatim());
    }

    @Test
    @DisplayName("An element the answer leaves out fails coverage")
    void testDroppedElementFailsCoverage() {
        LabelAudit audit = LabelAudit.of("Dublin, London", raws("Dublin"));

        assertEquals(List.of("london"), audit.uncoveredWords());
        assertTrue(audit.verbatim(), "the raw that was kept is still text from the label");
    }

    @Test
    @DisplayName("Dropping the remote option from an or-list fails coverage")
    void testDroppedRemoteFailsCoverage() {
        // "or" joins the elements and may be left out; "remote" is a location.
        assertTrue(LabelAudit.of("Boston or Remote", raws("Boston", "Remote")).covered());
        assertEquals(List.of("remote"), LabelAudit.of("Boston or Remote", raws("Boston")).uncoveredWords());
    }

    @Test
    @DisplayName("A raw the label does not contain fails verbatim")
    void testRewrittenRawFailsVerbatim() {
        LabelAudit audit = LabelAudit.of("Seattle WA", raws("Seattle, WA"));

        assertEquals(List.of("Seattle, WA"), audit.unmatchedRaws());
        assertTrue(audit.covered(), "the rewritten raw still has every word of the label");
    }

    @Test
    @DisplayName("An invented location fails verbatim")
    void testInventedRawFailsVerbatim() {
        LabelAudit audit = LabelAudit.of("Remote - US", raws("Remote - US", "Canada"));

        assertEquals(List.of("Canada"), audit.unmatchedRaws());
    }

    @Test
    @DisplayName("A raw must match whole words, not the inside of one")
    void testRawMatchesWholeWords() {
        assertFalse(LabelAudit.of("Sunnyvale, CA", raws("NY")).verbatim());
        assertTrue(LabelAudit.of("New York, NY", raws("NY")).verbatim());
        // A raw starting with punctuation has no word to start inside.
        assertTrue(LabelAudit.of("Seattle(Hybrid)", raws("Seattle", "(Hybrid)")).verbatim());
    }

    @Test
    @DisplayName("Case, padding, accents and dash variants are not differences")
    void testComparedAfterNormalizing() {
        // A cached answer carries the raws of whichever spelling reached the model first.
        // Escaped: an accented e and an en dash.
        LabelAudit audit = LabelAudit.of("  Montr\u00e9al,  Quebec \u2013 Remote",
                raws("montreal, quebec - remote"));

        assertTrue(audit.covered());
        assertTrue(audit.verbatim());
    }

    @Test
    @DisplayName("Words that describe the workplace rather than a place may be left out")
    void testWorkplaceWordsIgnored() {
        assertTrue(LabelAudit.of("Seattle, WA (Hybrid)", raws("Seattle, WA")).covered());
        assertTrue(LabelAudit.of("New York - On-site", raws("New York")).covered());
    }

    @Test
    @DisplayName("A blank label, resolved to the origin with a blank raw, passes")
    void testBlankLabelPasses() {
        for (String label : new String[]{null, "", "   "}) {
            LabelAudit audit = LabelAudit.of(label, raws(label == null ? "" : label));
            assertTrue(audit.covered());
            assertTrue(audit.verbatim());
        }
    }

    @Test
    @DisplayName("A blank raw on a real label is caught as the words it left out")
    void testBlankRawOnRealLabel() {
        LabelAudit audit = LabelAudit.of("Berlin", raws(""));

        assertEquals(List.of("berlin"), audit.uncoveredWords());
        assertTrue(audit.verbatim());
    }

    @Test
    @DisplayName("Every curated entry passes both checks")
    void testCuratedTablePasses() throws IOException {
        // The table is hand-checked, so a failure here is a typo in an entry or a
        // false positive in the audit; either would page someone for nothing.
        CuratedLocations curated = new CuratedLocations(new ClassPathResource("location_table.json"), ORIGIN);
        JsonNode root;
        try (InputStream in = new ClassPathResource("location_table.json").getInputStream()) {
            root = new ObjectMapper().readTree(in);
        }

        Map<String, LabelAudit> failures = new LinkedHashMap<>();
        int audited = 0;
        for (JsonNode entry : root.get("entries")) {
            String label = entry.get("raw").asString();
            LabelAudit audit = LabelAudit.of(label, curated.lookup(label).orElseThrow());
            if (!audit.covered() || !audit.verbatim()) {
                failures.put(label, audit);
            }
            audited++;
        }

        assertEquals(curated.size(), audited);
        assertTrue(failures.isEmpty(), "curated entries failing the audit: " + failures);
    }
}
