package org.example.fitfetch.location;

import org.example.fitfetch.domain.JobLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for resolved job locations.
 *
 * @see JobLocation
 * @see LocationService
 */
@Repository
public interface JobLocationRepository extends JpaRepository<JobLocation, Long> {

    /**
     * Returns every stored location for a job.
     *
     * @param fetchedJobId the job to load locations for
     * @return its locations, in no particular order; empty if none
     */
    List<JobLocation> findByFetchedJobId(Long fetchedJobId);

    /**
     * Deletes every stored location for a batch of jobs.
     *
     * <p>Run immediately before re-inserting. The unique constraint stops
     * duplicates but does nothing about rows left over from a previous
     * resolution: after a prompt change or a curated-table correction, an old
     * row would otherwise survive alongside its replacement, and a job would
     * carry both the wrong location and the right one.
     *
     * @param fetchedJobIds the jobs whose locations should be cleared
     * @return how many rows were deleted
     */
    @Modifying
    @Query("delete from JobLocation l where l.fetchedJobId in :ids")
    int deleteByFetchedJobIds(@Param("ids") Collection<Long> fetchedJobIds);
}
