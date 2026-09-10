package com.merseyside.jobs.storage

import com.merseyside.jobs.JobId

/**
 * Where the progress of jobs lives between app launches.
 *
 * The library keeps no database of its own: the storage comes from the app —
 * it already has both a database and an understanding of where it belongs on
 * every platform.
 */
interface JobStorage {

    suspend fun saveJob(record: JobRecord)

    /**
     * The record of the same work, if it has been started before. By it a
     * repeated start picks up the passed steps instead of beginning anew.
     */
    suspend fun findJob(type: String, params: String): JobRecord?

    /** Jobs caught midway by a restart. */
    suspend fun activeJobs(): List<JobRecord>

    /**
     * Jobs whose undo did not reach its end: the process died between the mark
     * and the undo itself. They are finished off on the next start.
     */
    suspend fun compensatingJobs(): List<JobRecord>

    suspend fun setActive(id: JobId, isActive: Boolean)

    /**
     * Marks the job as being undone and no longer running: from here on there
     * is nothing to revive, only something to undo.
     */
    suspend fun setCompensating(id: JobId)

    suspend fun removeJob(id: JobId)

    suspend fun saveStep(id: JobId, key: String, value: String)

    /** Passed steps of a job: step name to its saved result as JSON. */
    suspend fun steps(id: JobId): Map<String, String>
}
