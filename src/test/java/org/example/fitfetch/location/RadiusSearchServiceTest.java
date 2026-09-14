package org.example.fitfetch.location;

import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RadiusSearchServiceTest {

    private static final String ORIGIN = "3001 NE 130th St, Seattle, WA 98125";
    private static final GeocodeOutcome SEATTLE = new GeocodeOutcome(
            GeocodeStatus.OK, 47.7231d, -122.2967d, "Seattle", "p", "ROOFTOP", false);

    private FetchedJobsRepository repository;
    private Geocoder geocoder;
    private RadiusSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(FetchedJobsRepository.class);
        geocoder = mock(Geocoder.class);
        service = new RadiusSearchService(repository, geocoder, ORIGIN);
    }

    @Test
    @DisplayName("Searches around the configured origin's current coordinates, with the box that encloses the radius")
    void testSearchesAroundOrigin() {
        when(geocoder.geocode(ORIGIN)).thenReturn(SEATTLE);
        List<FetchedJob> jobs = List.of(mock(FetchedJob.class));
        BoundingBox box = BoundingBox.around(47.7231d, -122.2967d, 50);
        when(repository.findWithinRadiusOfOrigin(47.7231d, -122.2967d, 50,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                BoundingBox.EARTH_RADIUS_MILES)).thenReturn(jobs);

        assertSame(jobs, service.findWithinMiles(50));
    }

    @Test
    @DisplayName("The origin is looked up on every search, so a reconfigured or refreshed origin is picked up")
    void testOriginLookedUpEachTime() {
        when(geocoder.geocode(ORIGIN)).thenReturn(SEATTLE);

        service.findWithinMiles(50);
        service.findWithinMiles(25);

        verify(geocoder, times(2)).geocode(ORIGIN);
    }

    @Test
    @DisplayName("An origin that does not geocode fails loudly instead of matching nothing")
    void testUngeocodableOrigin() {
        when(geocoder.geocode(ORIGIN)).thenReturn(GeocodeOutcome.empty(GeocodeStatus.ZERO_RESULTS));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.findWithinMiles(50));
        assertTrue(error.getMessage().contains("app.location.default-origin"), error.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("A geocoding failure for the origin propagates and runs no query")
    void testGeocodingFailurePropagates() {
        when(geocoder.geocode(ORIGIN)).thenThrow(new GeocodingDisabledException(ORIGIN));

        assertThrows(GeocodingException.class, () -> service.findWithinMiles(50));
        verify(repository, never()).findWithinRadiusOfOrigin(anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}
