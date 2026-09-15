package org.example.fitfetch.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillCanonicalizerTest {

    private final SkillCanonicalizer table = new SkillCanonicalizer(Map.of(
            "Kubernetes", List.of("k8s", "kube"),
            "PostgreSQL", List.of("postgres"),
            "Go", List.of("golang")));

    @Test
    @DisplayName("An alias maps to its canonical name, ignoring case and extra spaces")
    void testAliases() {
        assertEquals("Kubernetes", table.canonical("K8s"));
        assertEquals("PostgreSQL", table.canonical("  postgres "));
        assertEquals("Go", table.canonical("GoLang"));
    }

    @Test
    @DisplayName("A canonical name matches itself, which fixes its casing")
    void testCanonicalMatchesItself() {
        assertEquals("Kubernetes", table.canonical("kubernetes"));
    }

    @Test
    @DisplayName("A skill the table does not know is kept as written, stripped")
    void testUnknownKept() {
        assertEquals("Terraform", table.canonical(" Terraform  "));
        assertEquals("Apache Beam", table.canonical("Apache   Beam"));
    }

    @Test
    @DisplayName("A list is canonicalized in order, with blanks and repeat spellings dropped")
    void testCanonicalAll() {
        assertEquals(List.of("Kubernetes", "Go", "Terraform"),
                table.canonicalAll(Arrays.asList("k8s", "Go", "", null, "Kubernetes", "Terraform", "golang")));
        assertEquals(List.of(), table.canonicalAll(null));
    }

    @Test
    @DisplayName("An alias claimed by two skills is rejected")
    void testConflictRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SkillCanonicalizer(Map.of(
                        "TypeScript", List.of("ts"),
                        "TimeScale", List.of("TS"))));
        assertTrue(error.getMessage().contains("'"), error.getMessage());
    }

    @Test
    @DisplayName("The shipped table loads with no conflicts and knows the common aliases")
    void testShippedTable() throws Exception {
        SkillCanonicalizer shipped = SkillCanonicalizer.load(new ClassPathResource("skill_aliases.json"));

        assertEquals("Kubernetes", shipped.canonical("k8s"));
        assertEquals("PostgreSQL", shipped.canonical("Postgres"));
        assertEquals("Go", shipped.canonical("golang"));
        assertEquals("Java", shipped.canonical("java"));
        // Close names must stay apart.
        assertNotEquals(shipped.canonical("Java"), shipped.canonical("JavaScript"));
    }

    @Test
    @DisplayName("With no table, skills are only stripped")
    void testNone() {
        assertEquals("k8s", SkillCanonicalizer.none().canonical(" k8s "));
    }
}
