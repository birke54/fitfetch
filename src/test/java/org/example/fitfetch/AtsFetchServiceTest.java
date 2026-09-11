package org.example.fitfetch;

import org.example.fitfetch.ats.Ats;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.ats.GreenhouseAts;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AtsFetchServiceTest {

    @Mock private FetchedJobsRepository fetchedJobsRepository;

    @Mock private GreenhouseAts mockGreenhouseAts;

    // Adding a second mock board to properly test that execution continues when one fails
    @Mock private Ats mockSecondaryAts;

    @Spy private List<Ats> atsBoards = new ArrayList<>();

    @InjectMocks
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
    }

    @Test
    @DisplayName("Should successfully fetch and store jobs from all active ATS boards")
    void testFetchAtsBoards_Success() {
        // Arrange
        AtsJobEntry jobA = new GreenhouseJobEntry(
                "https://example.com", "Bachelors", 1L,
                OffsetDateTime.now(), "REQ-001", "Software Engineer", "Company A", OffsetDateTime.now(), "en", null,
                "This is the JD of the posting", List.of(), "company-a"
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
    }

    @Test
    @DisplayName("Should log an error and continue processing when a specific ATS board fails")
    void testFetchAtsBoards_OneBoardFails_ContinuesProcessing() {
        // Arrange: Create a second Greenhouse mock instance to simulate a distinct company board iteration
        GreenhouseAts mockSecondaryGreenhouseAts = mock(GreenhouseAts.class);
        atsBoards.add(mockSecondaryGreenhouseAts);

        AtsJobEntry jobB = new GreenhouseJobEntry(
                "https://example.com", "Bachelors", 2L,
                OffsetDateTime.now(), "REQ-002", "Frontend Engineer", "Company B", OffsetDateTime.now(), "en", null,
                "This is the JD of the posting", List.of(), "company-b"
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
