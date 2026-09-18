package com.merseyside.jobs.storage

import com.merseyside.jobs.JobId
import com.merseyside.merseyLib.time.units.TimeUnit

/**
 * A job's record in the storage — everything needed to start it again.
 *
 * @param params the params as JSON: the library does not know their type.
 * @param createdAt when the work was started for the first time. By it the
 * jobs of a sequential kind line up after a restart. Two jobs never share it:
 * the runtime moves the later one forward by a millisecond.
 * @param ownerKey who the work belongs to, see [com.merseyside.jobs.JobRunner.start].
 * Kept so that the work revived after a restart is still found and replaced.
 * @param isActive the job was considered running at the moment of the last
 * write. Still active after an app restart means the process was killed
 * halfway and the work has to be revived. A cleared flag means the job
 * finished on its own: with success or with an error.
 * @param isCompensating the job is being undone right now. Set before the undo
 * and cleared only by forgetting the record altogether, so a process killed
 * midway leaves the mark behind — and the next start finishes the undo.
 * @param isFailed the job failed for good, was undone, and is kept because its
 * kind [keeps failed jobs][com.merseyside.jobs.JobSpec.keepsFailed]. Not
 * revived; a repeated start with the same params continues it.
 */
data class JobRecord(
    val id: JobId,
    val type: String,
    val params: String,
    val createdAt: TimeUnit,
    val ownerKey: String?,
    val isActive: Boolean,
    val isCompensating: Boolean = false,
    val isFailed: Boolean = false
)
