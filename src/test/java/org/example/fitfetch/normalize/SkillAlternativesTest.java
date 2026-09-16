package org.example.fitfetch.normalize;

import org.example.fitfetch.skills.SkillCanonicalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillAlternativesTest {

    private final SkillCanonicalizer skills = SkillCanonicalizer.load(new ClassPathResource("skill_aliases.json"));

    SkillAlternativesTest() throws IOException {
    }

    private SkillAlternatives.Split split(String text, String... skillNames) {
        return SkillAlternatives.of(text, List.of(skillNames), skills);
    }

    @Test
    @DisplayName("A choice of skills becomes the alternatives, as a real posting words it")
    void testChoice() {
        SkillAlternatives.Split split = split("Experience with Go, Python, JavaScript/TypeScript, Java, or C#.",
                "Go", "Python", "JavaScript", "TypeScript", "Java", "C#");

        assertEquals(List.of(), split.skills());
        assertEquals(List.of("Go", "Python", "JavaScript", "TypeScript", "Java", "C#"), split.anyOfSkills());
    }

    @Test
    @DisplayName("A two-item choice needs no comma")
    void testTwoItems() {
        SkillAlternatives.Split split = split("Working proficiency with Java or Go.", "Java", "Go");

        assertEquals(List.of(), split.skills());
        assertEquals(List.of("Java", "Go"), split.anyOfSkills());
    }

    @Test
    @DisplayName("Only the items the choice reaches back over join it, not the examples after it")
    void testExamplesAfterTheChoiceStayRequired() {
        // The networking posting: a choice of cloud, then examples of what to know in it.
        SkillAlternatives.Split split = split(
                "Be familiar with the network design primitives of at least one of AWS, Azure, or GCP, "
                        + "e.g. VPCs, subnetting, routing, and CDNs.",
                "AWS", "Azure", "GCP", "VPC", "Subnetting", "Routing", "CDN");

        assertEquals(List.of("VPC", "Subnetting", "Routing", "CDN"), split.skills());
        assertEquals(List.of("AWS", "Azure", "GCP"), split.anyOfSkills());
    }

    @Test
    @DisplayName("An open-ended choice names examples, so neither list keeps them")
    void testOpenEnded() {
        assertEquals(new SkillAlternatives.Split(List.of(), List.of()),
                split("Experience with Ruby on Rails, Go, or any other modern backend language.",
                        "Ruby on Rails", "Go"));
        assertEquals(new SkillAlternatives.Split(List.of(), List.of()),
                split("Experience with LangGraph, LangChain, or a comparable agent framework.",
                        "LangGraph", "LangChain"));
    }

    @Test
    @DisplayName("A list joined by \"and\" is every skill, not a choice")
    void testAndListIsNotAChoice() {
        SkillAlternatives.Split split = split("Runs PostgreSQL, Kafka and Kubernetes in production.",
                "PostgreSQL", "Kafka", "Kubernetes");

        assertEquals(List.of("PostgreSQL", "Kafka", "Kubernetes"), split.skills());
        assertEquals(List.of(), split.anyOfSkills());
    }

    @Test
    @DisplayName("A choice of one skill is no choice, and an unrelated \"or\" changes nothing")
    void testNoChoice() {
        assertEquals(List.of("Kubernetes"),
                split("Knows Kubernetes, or is willing to learn it.", "Kubernetes").skills());
        assertEquals(List.of("Kubernetes", "Terraform"),
                split("Runs Kubernetes and Terraform.", "Kubernetes", "Terraform").skills());
    }

    @Test
    @DisplayName("A choice reaches back only within its own clause")
    void testStaysWithinItsClause() {
        // Kubernetes is stated outright; the choice is only between the clouds.
        SkillAlternatives.Split split = split("Operates Kubernetes: on AWS, Azure, or GCP.",
                "Kubernetes", "AWS", "Azure", "GCP");

        assertEquals(List.of("Kubernetes"), split.skills());
        assertEquals(List.of("AWS", "Azure", "GCP"), split.anyOfSkills());
    }
}
