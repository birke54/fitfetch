package org.example.fitfetch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole application context, which is what catches a wiring mistake
 * no unit test sees: a bean that cannot be constructed, a missing property, a
 * migration that will not apply.
 *
 * <p>The database is a real Postgres in a container rather than the developer's
 * own. The migrations are Postgres-specific, so there is no in-memory stand-in
 * that would prove the same thing, and pointing at a local server made this
 * test fail for anyone who did not happen to have one running -- which is to
 * say it failed by default, and stopped being read as a signal. The image
 * matches the one in {@code docker-compose.yml}.
 *
 * <p>{@code @ServiceConnection} supplies the datasource from the container, so
 * the URL, user and password in {@code application.yaml} are overridden here
 * and no test-only copy of them has to be kept in step.
 *
 * <p>Every pass is disabled by default, so nothing the schedules name is ever
 * invoked and no model is ever called; the values exist only so the context can
 * be built.
 *
 * <p>Needs a working Docker daemon. Without one the test fails rather than
 * silently passing, since a context that was never started has proved nothing.
 */
@SpringBootTest(properties = {
		// application.yaml leaves these without a default, so a deployment must
		// supply them and the context cannot start without them. Supplied here in
		// their own names rather than as the properties they feed, so the wiring
		// in application.yaml is still what is under test.
		"FETCH_CRON_SCHEDULE=0 0 3 * * *",
		"LOCATION_PAGE_SIZE=1",
		"OLLAMA_BASE_URL=http://localhost:11434",
		"OLLAMA_MODEL=test-model"
})
@Testcontainers
class FitFetchApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	@Test
	void contextLoads() {
	}

}
