package org.example.fitfetch;

import org.example.fitfetch.ats.Ats;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.ats.GreenhouseAts;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseSubRecords.Location;
import org.example.fitfetch.metrics.MetricName;
import org.example.fitfetch.metrics.MetricService;
import org.example.fitfetch.metrics.TagName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AtsFetchServiceTest {

    @Mock private FetchedJobsRepository fetchedJobsRepository;

    @Mock private MetricService metricService;

    @Mock private GreenhouseAts mockGreenhouseAts;

    // Adding a second mock board to properly test that execution continues when one fails
    @Mock private Ats mockSecondaryAts;

    @Spy private List<Ats> atsBoards = new ArrayList<>();

    // Built in setUp: @InjectMocks cannot supply the constructor's boolean flag.
    private AtsFetchService atsFetchService;

    // Implement all abstract methods required by your interface
    static class TestSecondaryAts implements Ats {
        @Override
        public List<AtsJobEntry> fetchJobs() {
            return List.of();
        }

        @Override
        public List<String> getSlugs() {
            return List.of(); // Returns an empty set to fulfill the interface contract
        }
    }


    @BeforeEach
    void setUp() {
        atsBoards.add(mockGreenhouseAts);
        atsFetchService = new AtsFetchService(fetchedJobsRepository, atsBoards, metricService, true);
    }

    /** Runs each task inline, surfacing a failure the way a real Future does. */
    private static ExecutorService directExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            CompletableFuture<Object> future = new CompletableFuture<>();
            try {
                future.complete(callable.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        });
        return executor;
    }

    private static AtsJobEntry job(long id, String slug) {
        return new GreenhouseJobEntry(
                "https://example.com", "Bachelors", id, 100L + id,
                OffsetDateTime.now(), "REQ-" + id, "Software Engineer", "Company", OffsetDateTime.now(), "en", null,
                "This is the JD of the posting", new Location("Remote - US"), List.of(), List.of(), List.of(),
                slug);
    }

    @Test
    @DisplayName("A database error storing one board's jobs does not stop the next board")
    void testStoreFailureOnOneBoard_ContinuesProcessing() {
        GreenhouseAts secondBoard = mock(GreenhouseAts.class);
        atsBoards.add(secondBoard);
        when(mockGreenhouseAts.fetchJobs()).thenReturn(List.of(job(1L, "company-a")));
        when(secondBoard.fetchJobs()).thenReturn(List.of(job(2L, "company-b")));
        when(fetchedJobsRepository.findJobIdsByAtsNameAndJobIdIn(any(), anySet())).thenReturn(Set.of());
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .doReturn(List.of())
                .when(fetchedJobsRepository).saveAll(anyIterable());

        assertDoesNotThrow(() -> atsFetchService.fetchAtsBoards(directExecutor()));

        verify(fetchedJobsRepository, times(2)).saveAll(anyIterable());
    }

    @Test
    @DisplayName("Should successfully fetch and store jobs from all active ATS boards")
    void testFetchAtsBoards_Success() {
        // Arrange
        AtsJobEntry jobA = new GreenhouseJobEntry(
                "https://example.com", "Bachelors", 1L, 101L,
                OffsetDateTime.now(), "REQ-001", "Software Engineer", "Company A", OffsetDateTime.now(), "en", null,
                "This is the JD of the posting", new Location("Remote - US"), List.of(), List.of(), List.of(),
                "company-a"
        );

        List<AtsJobEntry> jobsA = List.of(jobA);

        when(mockGreenhouseAts.fetchJobs()).thenReturn(jobsA);

        // Mock the DB layer to simulate that this job does not exist yet
        when(fetchedJobsRepository.findJobIdsByAtsNameAndJobIdIn(eq(AtsName.GREENHOUSE), anySet()))
                .thenReturn(Set.of());

        // Define a direct executor that bypasses nested mock configurations
        ExecutorService directExecutor = mock(ExecutorService.class);
        when(directExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            Object result = callable.call();

            return new Future<>() {
                @Override public Object get() { return result; }
                @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
                @Override public boolean isCancelled() { return false; }
                @Override public boolean isDone() { return true; }
                @Override public Object get(long timeout, java.util.concurrent.TimeUnit unit) { return result; }
            };
        });

        // FIX 1: Intercept repository calls using FetchedJob instead of AtsJobEntry
        List<List<FetchedJob>> capturedBatches = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            Iterable<FetchedJob> iterable = invocation.getArgument(0);
            List<FetchedJob> batchList = new java.util.ArrayList<>();
            iterable.forEach(batchList::add);
            capturedBatches.add(batchList);
            return null;
        }).when(fetchedJobsRepository).saveAll(anyIterable());

        // Act
        atsFetchService.fetchAtsBoards(directExecutor);

        // Assert
        verify(mockGreenhouseAts, times(1)).fetchJobs();
        verify(fetchedJobsRepository, times(1)).saveAll(anyIterable());

        // FIX 2: Evaluate captured domain batches correctly
        assertEquals(1, capturedBatches.size());

        List<FetchedJob> firstBatch = capturedBatches.getFirst();
        assertEquals(1, firstBatch.size());

        FetchedJob savedJob = firstBatch.getFirst();
        assertEquals(AtsName.GREENHOUSE, savedJob.getAts());
        assertEquals("1", savedJob.getJobId());
        assertEquals("company-a", savedJob.getSlug());
        verify(metricService).recordCounterByIncrement(MetricName.FETCH_JOBS_SAVED_COUNT,
                Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue()), 1);
    }

    @Test
    @DisplayName("A cycle with nothing new records zero saved jobs")
    void testNothingNewRecordsZero() {
        when(mockGreenhouseAts.fetchJobs()).thenReturn(List.of(job(1L, "company-a")));
        when(fetchedJobsRepository.findJobIdsByAtsNameAndJobIdIn(any(), anySet())).thenReturn(Set.of("1"));

        atsFetchService.fetchAtsBoards(directExecutor());

        verify(fetchedJobsRepository, never()).saveAll(anyIterable());
        verify(metricService).recordCounterByIncrement(MetricName.FETCH_JOBS_SAVED_COUNT,
                Map.of(TagName.ATS, AtsName.GREENHOUSE.stringValue()), 0);
    }

    @Test
    @DisplayName("Jobs that fail to save are not counted as saved")
    void testFailedSaveNotCounted() {
        when(mockGreenhouseAts.fetchJobs()).thenReturn(List.of(job(1L, "company-a")));
        when(fetchedJobsRepository.findJobIdsByAtsNameAndJobIdIn(any(), anySet())).thenReturn(Set.of());
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(fetchedJobsRepository).saveAll(anyIterable());

        atsFetchService.fetchAtsBoards(directExecutor());

        verifyNoInteractions(metricService);
    }

    @Test
    @DisplayName("Should log an error and continue processing when a specific ATS board fails")
    void testFetchAtsBoards_OneBoardFails_ContinuesProcessing() {
        // Arrange: Create a second Greenhouse mock instance to simulate a distinct company board iteration
        GreenhouseAts mockSecondaryGreenhouseAts = mock(GreenhouseAts.class);
        atsBoards.add(mockSecondaryGreenhouseAts);

        AtsJobEntry jobB = new GreenhouseJobEntry(
                "https://example.com", "Bachelors", 2L, 102L,
                OffsetDateTime.now(), "REQ-002", "Frontend Engineer", "Company B", OffsetDateTime.now(), "en", null,
                "This is the JD of the posting", new Location("New York, New York"), List.of(), List.of(), List.of(),
                "company-b"
        );

        // Board 1 throws a timeout failure
        when(mockGreenhouseAts.fetchJobs()).thenThrow(new RuntimeException("Greenhouse API Gateway Timeout"));
        // Board 2 succeeds independently
        when(mockSecondaryGreenhouseAts.fetchJobs()).thenReturn(List.of(jobB));

        // Ensure the database check returns an empty set so it flags jobB as new data
        when(fetchedJobsRepository.findJobIdsByAtsNameAndJobIdIn(any(), anySet()))
                .thenReturn(Set.of());

        ExecutorService directExecutor = mock(ExecutorService.class);
        when(directExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            try {
                Object result = callable.call();
                return new Future<>() {
                    @Override public Object get() { return result; }
                    @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
                    @Override public boolean isCancelled() { return false; }
                    @Override public boolean isDone() { return true; }
                    @Override public Object get(long timeout, java.util.concurrent.TimeUnit unit) { return result; }
                };
            } catch (Exception e) {
                Future<Object> failingFuture = mock(Future.class);
                when(failingFuture.get()).thenThrow(new java.util.concurrent.ExecutionException(e));
                return failingFuture;
            }
        });

        // Act & Assert
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                atsFetchService.fetchAtsBoards(directExecutor)
        );

        verify(mockGreenhouseAts, times(1)).fetchJobs();
        verify(mockSecondaryGreenhouseAts, times(1)).fetchJobs();

        // Verifies that saveAll is invoked once for the successful second board data pipeline
        verify(fetchedJobsRepository, times(1)).saveAll(anyIterable());
    }
}
