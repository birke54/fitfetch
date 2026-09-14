package org.example.fitfetch.fetching;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.location.BoundingBox;
import org.example.fitfetch.location.OriginRadius;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checks the SQL is bound correctly. It does not run it; that needs Postgres.
 */
class RadiusQueriesImplTest {

    /** A named parameter; the look-behind skips Postgres {@code ::} casts. */
    private static final Pattern PARAMETER = Pattern.compile("(?<!:):([A-Za-z]\\w*)");

    private static final OriginRadius SEATTLE = OriginRadius.of(47.7231d, -122.2967d, 50);

    private EntityManager entityManager;
    private Query query;
    private Map<String, Object> bound;
    private RadiusQueriesImpl queries;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        bound = new HashMap<>();
        when(query.setParameter(anyString(), any())).thenAnswer(invocation -> {
            bound.put(invocation.getArgument(0), invocation.getArgument(1));
            return query;
        });
        when(entityManager.createNativeQuery(anyString(), eq(FetchedJob.class))).thenReturn(query);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        queries = new RadiusQueriesImpl(entityManager);
    }

    private static Set<String> parametersIn(String sql) {
        Matcher matcher = PARAMETER.matcher(sql);
        Set<String> names = new java.util.HashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("The search binds exactly the parameters its SQL names, from the radius")
    void testFindWithinRadiusBindsEveryParameter() {
        List<FetchedJob> jobs = List.of(mock(FetchedJob.class));
        when(query.getResultList()).thenReturn(jobs);

        assertSame(jobs, queries.findWithinRadius(SEATTLE));

        verify(entityManager).createNativeQuery(RadiusQueriesImpl.FIND_WITHIN_RADIUS, FetchedJob.class);
        assertEquals(parametersIn(RadiusQueriesImpl.FIND_WITHIN_RADIUS), bound.keySet());
        BoundingBox box = SEATTLE.box();
        assertEquals(Map.of(
                "latitude", 47.7231d, "longitude", -122.2967d, "miles", 50d,
                "minLatitude", box.minLatitude(), "maxLatitude", box.maxLatitude(),
                "minLongitude", box.minLongitude(), "maxLongitude", box.maxLongitude(),
                "earthRadius", BoundingBox.EARTH_RADIUS_MILES), bound);
    }

    @Test
    @DisplayName("The normalization page binds every parameter, the page size included")
    void testFindPendingNormalizationBindsEveryParameter() {
        when(query.getResultList()).thenReturn(List.of());

        queries.findPendingNormalizationWithinRadius(SEATTLE, 5);

        verify(entityManager).createNativeQuery(RadiusQueriesImpl.FIND_PENDING_NORMALIZATION, FetchedJob.class);
        assertEquals(parametersIn(RadiusQueriesImpl.FIND_PENDING_NORMALIZATION), bound.keySet());
        assertEquals(5, bound.get("pageSize"));
    }

    @Test
    @DisplayName("The out-of-range sweep binds every parameter and reports how many jobs it marked")
    void testMarkOutOfRangeBindsEveryParameter() {
        when(query.executeUpdate()).thenReturn(7);

        assertEquals(7, queries.markOutOfRangeForNormalization(SEATTLE));

        verify(entityManager).createNativeQuery(RadiusQueriesImpl.MARK_OUT_OF_RANGE);
        assertEquals(parametersIn(RadiusQueriesImpl.MARK_OUT_OF_RANGE), bound.keySet());
    }

    @Test
    @DisplayName("All three queries share one radius match, so they cannot disagree about range")
    void testQueriesShareTheRadiusMatch() {
        assertTrue(RadiusQueriesImpl.FIND_WITHIN_RADIUS.contains("IN (" + RadiusQueriesImpl.WITHIN_RADIUS));
        assertTrue(RadiusQueriesImpl.FIND_PENDING_NORMALIZATION.contains("IN (" + RadiusQueriesImpl.WITHIN_RADIUS));
        assertTrue(RadiusQueriesImpl.MARK_OUT_OF_RANGE.contains("NOT IN (" + RadiusQueriesImpl.WITHIN_RADIUS));
    }

    @Test
    @DisplayName("Only pending, located jobs are swept or paged, so no other state is ever overwritten")
    void testNormalizationQueriesTouchOnlyPendingLocatedJobs() {
        for (String sql : List.of(RadiusQueriesImpl.FIND_PENDING_NORMALIZATION, RadiusQueriesImpl.MARK_OUT_OF_RANGE)) {
            assertTrue(sql.contains("normalize_status = 'PENDING'"), sql);
            assertTrue(sql.contains("location_status = 'RESOLVED'"), sql);
        }
    }
}
