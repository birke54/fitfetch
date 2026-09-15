package org.example.fitfetch.fetching;

import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.metrics.MetricService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * One {@link RestClient} per ATS provider, each paced by that provider's own
 * {@link AtsRateLimiter}.
 *
 * <p>Separate clients rather than one shared client is what makes the limits
 * per provider: a Greenhouse pause holds back Greenhouse requests and nothing
 * else. Built once at startup for every {@link AtsName}, so a new provider gets
 * a client, with the default limits, as soon as its constant exists.
 *
 * @see FetchLimits
 */
@Component
public class AtsRestClients {

    private final Map<AtsName, RestClient> clients = new EnumMap<>(AtsName.class);

    /**
     * @param limits         {@code app.fetch.limits}
     * @param metricService  sink for the throttled counter
     * @param clock          time source
     * @param connectTimeout {@code app.http.connect-timeout}
     * @param readTimeout    {@code app.http.read-timeout}
     * @param observationRegistry where each request is observed
     */
    public AtsRestClients(FetchLimits limits,
                          MetricService metricService,
                          Clock clock,
                          @Value("${app.http.connect-timeout}") Duration connectTimeout,
                          @Value("${app.http.read-timeout}") Duration readTimeout,
                          ObservationRegistry observationRegistry) {
        for (AtsName ats : AtsName.values()) {
            AtsRateLimiter limiter = new AtsRateLimiter(ats, limits.forAts(ats), clock, AtsRateLimiter.THREAD_SLEEP);
            clients.put(ats, RestClientConfig.builderWithTimeouts(connectTimeout, readTimeout, observationRegistry)
                    .requestInterceptor(new RateLimitInterceptor(limiter, metricService, clock))
                    .build());
        }
    }

    /**
     * @param ats the provider
     * @return the client every request to that provider should go through
     */
    public RestClient forAts(AtsName ats) {
        return clients.get(ats);
    }
}
