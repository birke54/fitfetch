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
    @DisplayName("The circle is centred on the configured origin's current coordinates, with its enclosing box")
    void testAroundOrigin() {
        when(geocoder.geocode(ORIGIN)).thenReturn(SEATTLE);

        OriginRadius radius = service.around(50);

        assertEquals(47.7231d, radius.latitude());
        assertEquals(-122.2967d, radius.longitude());
        assertEquals(50, radius.miles());
        assertEquals(BoundingBox.around(47.7231d, -122.2967d, 50), radius.box());
    }

    @Test
    @DisplayName("A search runs the radius query around the origin")
    void testSearchesAroundOrigin() {
        when(geocoder.geocode(ORIGIN)).thenReturn(SEATTLE);
        List<FetchedJob> jobs = List.of(mock(FetchedJob.class));
        when(repository.findWithinRadius(OriginRadius.of(47.7231d, -122.2967d, 50))).thenReturn(jobs);

        assertSame(jobs, service.findWithinMiles(50));
    }

    @Test
    @DisplayName("The origin is looked up every time, so a reconfigured or refreshed origin is picked up")
    void testOriginLookedUpEachTime() {
        when(geocoder.geocode(ORIGIN)).thenReturn(SEATTLE);

        service.findWithinMiles(50);
        service.around(25);

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
        verify(repository, never()).findWithinRadius(any());
    }
}
