package org.example.fitfetch.domain;

/**
 * How far a fetched job has got through normalization.
 *
 * <p>Every state but {@link #PENDING} is terminal for the pass: it only ever
 * reads pending jobs, so a job is sent to the model at most once however many
 * runs go by. That is the reason this is an enum rather than a flag. With a
 * boolean, a job the pass chose to skip and a job it had not reached yet were
 * both {@code false}, and every one of them was a candidate on every run.
 *
 * <p>These constants are mirrored by the {@code ck_fetched_jobs_normalize_status}
 * check constraint.
 *
 * @see LocationStatus
 */
public enum NormalizeStatus {

    /**
     * Not yet normalized. The pass takes a pending job only once its location is
     * {@link LocationStatus#RESOLVED RESOLVED} and within the search radius, so
     * jobs still waiting on location resolution stay here untouched.
     */
    PENDING,

    /** Normalized; {@code normalized_jobs} holds its seniority and signals. */
    NORMALIZED,

    /**
     * Resolved to no location within the search radius, so deliberately never
     * normalized.
     *
     * <p>Relative to the radius and origin in force when the job was checked.
     * After widening the radius or moving the origin, requeue these and the next
     * run checks them again:
     *
     * <pre>{@code
     * UPDATE fetched_jobs SET normalize_status = 'PENDING' WHERE normalize_status = 'OUT_OF_RANGE';
     * }</pre>
     *
     * <p>The location pass requeues a job itself when it rewrites the job's
     * locations, since the old answer no longer applies.
     */
    OUT_OF_RANGE,

    /**
     * The model could not produce a usable answer, or the description was
     * empty. Retrying gives the same result, since sampling is deterministic;
     * these jobs are what to look at after changing the prompt.
     */
    FAILED
}
