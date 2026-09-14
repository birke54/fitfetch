package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.metrics.MetricService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wires the fetch clients from the real {@code application.yaml}, without the
 * database the full context needs.
 */
class AtsRestClientsWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // What a Spring Boot application installs for @Value, so "PT2S"
            // converts to a Duration as it does at runtime.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(RestClientConfig.class, AtsRestClients.class, GreenhouseFetch.class)
            .withBean(MetricService.class, () -> mock(MetricService.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    @DisplayName("The shipped rate limits bind, and every ATS gets its own client")
    void testShippedConfigurationWires() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            FetchLimits limits = context.getBean(FetchLimits.class);
            assertThat(limits.forAts(AtsName.GREENHOUSE).requestsPerSecond()).isEqualTo(1.0);
            assertThat(limits.forAts(AtsName.GREENHOUSE).maxWait()).isEqualTo(Duration.ofMinutes(2));
            assertThat(limits.forAts(AtsName.GREENHOUSE).maxConcurrent()).isEqualTo(3);

            AtsRestClients clients = context.getBean(AtsRestClients.class);
            for (AtsName ats : AtsName.values()) {
                assertThat(clients.forAts(ats)).isNotNull();
            }
            assertThat(context).hasSingleBean(GreenhouseFetch.class);
        });
    }

    @Test
    @DisplayName("An override keyed by lower-case ATS name is picked up from properties")
    void testOverrideBinds() {
        runner.withPropertyValues("app.fetch.limits.ats.greenhouse.requests-per-second=5")
                .run(context -> assertThat(context.getBean(FetchLimits.class)
                        .forAts(AtsName.GREENHOUSE).requestsPerSecond()).isEqualTo(5.0));
    }
}
