package org.example.fitfetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolves the configured resource paths the way the running app does: in a
 * web application context, with the real {@code application.yaml}.
 *
 * <p>A bare path such as {@code location_table.json} resolves against the
 * servlet context there, not the classpath. The files are not in the servlet
 * context, so the app would fail to start. The unit tests load these files
 * with a {@code ClassPathResource} of their own and cannot catch it.
 */
@SpringBootTest(classes = ActuatorEndpointTest.ActuatorOnly.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClasspathResourcesTest {

    @Value("${app.slug-ids}")
    private Resource slugs;

    @Value("${app.location.table}")
    private Resource locationTable;

    @Value("${app.skills.aliases}")
    private Resource skillAliases;

    @Test
    @DisplayName("The skill alias table resolves to a file that exists in a web context")
    void testSkillAliasesResolve() {
        assertTrue(skillAliases.exists(), skillAliases.getDescription());
    }

    @Test
    @DisplayName("The slug list resolves to a file that exists in a web context")
    void testSlugsResolve() {
        assertTrue(slugs.exists(), slugs.getDescription());
    }

    @Test
    @DisplayName("The curated location table resolves to a file that exists in a web context")
    void testLocationTableResolves() {
        assertTrue(locationTable.exists(), locationTable.getDescription());
    }
}
