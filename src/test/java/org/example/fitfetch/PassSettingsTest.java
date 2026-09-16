package org.example.fitfetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that each pass's settings resolve from {@code application.yaml}, since
 * the context test needs a database and so never runs here. A typo in a
 * placeholder or its default would otherwise surface only on deployment.
 */
class PassSettingsTest {

    /** The settings with no default, which every deployment must supply. */
    private static final Map<String, Object> REQUIRED = Map.of(
            "DB_NAME", "fitfetch",
            "APP_POSTGRES_USER", "app",
            "APP_POSTGRES_PASSWORD", "secret",
            "GOOGLE_GEO_API_KEY", "key",
            "OLLAMA_BASE_URL", "http://ollama:11434",
            "OLLAMA_MODEL", "qwen2.5:7b-instruct",
            "FETCH_CRON_SCHEDULE", "0 0 3 * * *",
            "LOCATION_PAGE_SIZE", "300");

    private static StandardEnvironment environment(Map<String, Object> given) throws IOException {
        Map<String, Object> variables = new HashMap<>(REQUIRED);
        variables.putAll(given);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("given", variables));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    @DisplayName("Every pass setting resolves to its default when nothing is set")
    void testDefaults() throws IOException {
        StandardEnvironment environment = environment(Map.of());

        // Every pass is off unless its variable says otherwise, so a deployment
        // that sets none of them runs nothing rather than a guess.
        assertEquals(Boolean.FALSE, environment.getProperty("app.fetch.enable", Boolean.class));
        assertEquals(Boolean.FALSE, environment.getProperty("app.location.enable", Boolean.class));
        assertEquals("0 */5 * * * *", environment.getProperty("app.location.schedule"));
        assertEquals(Boolean.FALSE, environment.getProperty("app.location.warm-caches-only", Boolean.class));
        assertEquals(Boolean.FALSE, environment.getProperty("app.normalize.enable", Boolean.class));
        assertEquals("0 */10 * * * *", environment.getProperty("app.normalize.schedule"));
        assertEquals(2, environment.getProperty("app.normalize.page-size", Integer.class));
        assertEquals(Boolean.FALSE, environment.getProperty("app.embedding.enable", Boolean.class));
        assertEquals(20, environment.getProperty("app.embedding.page-size", Integer.class));
        assertEquals(32, environment.getProperty("app.embedding.batch-size", Integer.class));
        assertEquals(Boolean.FALSE, environment.getProperty("app.match.enable", Boolean.class));
        assertEquals(100, environment.getProperty("app.match.page-size", Integer.class));
        // Left out of the request unless set; see NormalizeConfig.thinking.
        assertEquals("", environment.getProperty("app.normalize.llm.think"));
    }

    @Test
    @DisplayName("Each pass setting is overridden by its environment variable")
    void testOverrides() throws IOException {
        StandardEnvironment environment = environment(Map.of(
                "NORMALIZE_ENABLE", "true",
                "NORMALIZE_SCHEDULE", "0 0 * * * *",
                "NORMALIZE_PAGE_SIZE", "25",
                "EMBEDDING_ENABLE", "true",
                "MATCH_PAGE_SIZE", "10",
                "APP_NORMALIZE_LLM_THINK", "false",
                "OLLAMA_NORMALIZE_MODEL", "qwen3:8b"));

        assertEquals(Boolean.TRUE, environment.getProperty("app.normalize.enable", Boolean.class));
        assertEquals("0 0 * * * *", environment.getProperty("app.normalize.schedule"));
        assertEquals(25, environment.getProperty("app.normalize.page-size", Integer.class));
        assertEquals(Boolean.TRUE, environment.getProperty("app.embedding.enable", Boolean.class));
        assertEquals(10, environment.getProperty("app.match.page-size", Integer.class));
        assertEquals("false", environment.getProperty("app.normalize.llm.think"));
        assertEquals("qwen3:8b", environment.getProperty("app.normalize.llm.model"));
    }

    @Test
    @DisplayName("The normalization model falls back to the shared one")
    void testNormalizeModelFallsBack() throws IOException {
        assertEquals("qwen2.5:7b-instruct", environment(Map.of()).getProperty("app.normalize.llm.model"));
    }

    @Test
    @DisplayName("A setting with no default is required, so a missing one fails rather than running on a guess")
    void testRequiredSettings() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }

        List<String> required = List.of("app.fetch.schedule", "app.location.page-size");
        for (String property : required) {
            assertThrows(IllegalArgumentException.class, () -> environment.getProperty(property), property);
        }
    }
}
