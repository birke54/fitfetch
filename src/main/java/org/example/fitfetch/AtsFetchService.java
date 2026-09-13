package org.example.fitfetch;

import org.example.fitfetch.ats.Ats;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.domain.FetchedJob;
import org.example.fitfetch.fetching.FetchedJobsRepository;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Scheduled orchestrator that polls every registered {@link Ats} board and
 * persists the jobs it has not seen before.
 *
 * <p>On each run the configured ATS sites are fetched concurrently, one task
 * per site; each result is then de-duplicated against {@code fetched_jobs} and
 * the remaining entries are saved as un-normalized {@link FetchedJob} rows. A
 * failure in one board (timeout, HTTP error, interruption) is logged and does
 * not stop the others.
 *
 * @see Ats
 * @see FetchedJobsRepository
 */
@Service
public class AtsFetchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AtsFetchService.class);
    private final FetchedJobsRepository fetchedJobsRepository;
    private final List<Ats> atsSites;
    private final boolean enableFetching;

    /**
     * @param fetchedJobsRepository repository used for dedup lookups and saving
     *                              new jobs
     * @param atsSites              all {@link Ats} beans discovered by Spring,
     *                              one per configured provider
     * @param enableFetching        {@code app.fetch.enable} flag toggling
     *                              whether scheduled fetch cycles run
     */
    public AtsFetchService(FetchedJobsRepository fetchedJobsRepository,
                           List<Ats> atsSites,
                           @Value("${app.fetch.enable}") boolean enableFetching) {
        this.fetchedJobsRepository = fetchedJobsRepository;
        this.atsSites = atsSites;
        this.enableFetching = enableFetching;
    }

    /**
     * Cron entry point (schedule from {@code app.fetching-cron}) that runs a
     * full fetch cycle across all ATS boards.
     *
     * <p>Creates a fixed thread pool sized to the number of boards, delegates to
     * {@link #fetchAtsBoards(ExecutorService)}, and shuts the pool down when the
     * cycle completes.
     */
    @Scheduled(cron = "${app.fetch.schedule}")
    void fetchAtsBoards() {
        if (!enableFetching) {
            return;
        }
        try (var executor = Executors.newFixedThreadPool(atsSites.size())) {
            fetchAtsBoards(executor);
        }
    }

    /**
     * Runs one fetch cycle: submits a {@link Ats#fetchJobs()} task per board on
     * the given executor, then processes each result as it becomes available.
     *
     * <p>Each board is handled independently &mdash; an
     * {@link InterruptedException} (interrupt status is restored), an
     * {@link ExecutionException} from fetching, or a failure while storing one
     * board's jobs is logged and the remaining boards continue.
     *
     * @param executor the executor tasks are submitted to; not shut down by
     *                 this method
     */
    void fetchAtsBoards(ExecutorService executor) {
        // 1. Submit tasks and map each Future to its originating ATS site
        record AtsTask(Ats site, Future<List<AtsJobEntry>> future) {}

        List<AtsTask> tasks = atsSites.stream()
                .map(site -> new AtsTask(site, executor.submit(site::fetchJobs)))
                .toList();

        // 2. Safely process each task individually
        for (AtsTask task : tasks) {
            try {
                // .get() will block until this specific board is done, without blocking others from finishing in the background
                List<AtsJobEntry> jobs = task.future().get();
                storeFetchedJobs(jobs, task.site().getClass());
            } catch (InterruptedException e) {
                LOGGER.error("Fetching thread was interrupted for {}", task.site().getClass().getSimpleName(), e);
                Thread.currentThread().interrupt(); // Restore interrupted status
            } catch (ExecutionException e) {
                LOGGER.error("Fetching for the {} ATS failed: {}", task.site().getClass().getSimpleName(), e.getCause().getMessage());
            } catch (RuntimeException e) {
                // Storing failed, most likely the database. Left uncaught, this would
                // skip every board after this one; their jobs are already fetched.
                LOGGER.error("Storing jobs from the {} ATS failed", task.site().getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * De-duplicates the freshly fetched jobs for one board against what is
     * already stored and persists the new ones.
     *
     * <p>The provider is resolved from the board class via
     * {@link AtsName#fromBoardClass(Class)}; incoming IDs are checked in a
     * single {@link FetchedJobsRepository#findJobIdsByAtsNameAndJobIdIn} query,
     * and jobs not already present are saved as {@link FetchedJob} rows with
     * {@code isNormalized == false}. A no-op when every incoming job already
     * exists.
     *
     * <p>The lookup and the insert are not one transaction, and need not be.
     * {@code saveAll} is atomic on its own, and nothing else inserts jobs between
     * them: a cron-scheduled method never overlaps its own previous run. (A
     * {@code @Transactional} here would be ignored anyway, since Spring's proxy
     * does not intercept a private method called from inside the class.)
     *
     * @param jobs          the jobs returned by the board this cycle
     * @param atsBoardClass the concrete {@link Ats} implementation class the
     *                      jobs came from (possibly a Spring proxy)
     */
    private void storeFetchedJobs(List<AtsJobEntry> jobs, Class<? extends Ats> atsBoardClass) {
        AtsName atsName = AtsName.fromBoardClass(atsBoardClass);

        // 1. Gather all the incoming unique job IDs
        Set<String> incomingJobIds = jobs.stream()
                .map(job -> job.id().toString())
                .collect(Collectors.toSet());

        // 2. Query the DB to find which of these IDs already exist for this ATS
        // (You will need to add this method to your FetchedJobsRepository)
        Set<String> existingJobIds = fetchedJobsRepository.findJobIdsByAtsNameAndJobIdIn(atsName, incomingJobIds);

        // 3. Filter the incoming stream to only include jobs NOT in the existing list
        List<FetchedJob> toSave = jobs.stream()
                .filter(job -> !existingJobIds.contains(job.id().toString()))
                .map(job -> new FetchedJob(
                        atsName,
                        job.id().toString(),
                        job.slug(),
                        job,
                        false))
                .toList();

        if (!toSave.isEmpty()) {
            fetchedJobsRepository.saveAll(toSave);
            // TODO: Add metric for new saves
            LOGGER.info("Saved {} new jobs from {}", toSave.size(), atsName);
        } else {
            // TODO: Add metric for no op save
            LOGGER.info("No new jobs to save for {}", atsName);
        }
    }
}
