package org.example.fitfetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts JPA with every entity and repository, without a database, so each
 * JPQL query is parsed and checked against the entity model as it would be at
 * startup. A typo in a query or a renamed field then fails here instead of
 * stopping the app.
 *
 * <p>Hibernate is told not to read database metadata, so nothing connects;
 * Flyway and schema validation, which need the database, are off. Native SQL is
 * not parsed and is not covered.
 */
@SpringBootTest(classes = JpaQueriesTest.JpaOnly.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:postgresql://localhost:1/unused",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class JpaQueriesTest {

    // @Configuration rather than @SpringBootConfiguration, for the reason
    // ActuatorEndpointTest gives.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
    @EntityScan(basePackageClasses = FitFetchApplication.class)
    @EnableJpaRepositories(basePackageClasses = FitFetchApplication.class)
    static class JpaOnly {
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Every repository, and so every JPQL query, starts against the entity model")
    void testRepositoriesStart() {
        assertTrue(context.getBeansOfType(Repository.class).size() >= 5,
                "found " + context.getBeansOfType(Repository.class).keySet());
    }
}
