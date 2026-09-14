package com.merseyside.jobs.storage

import com.merseyside.jobs.JobId

/**
 * A job's record in the storage — everything needed to start it again.
 *
 * @param params the params as JSON: the library does not know their type.
 * @param isActive the job was considered running at the moment of the last
 * write. Still active after an app restart means the process was killed
 * halfway and the work has to be revived. A cleared flag means the job
 * finished on its own: with success or with an error.
 * @param isCompensating the job is being undone right now. Set before the undo
 * and cleared only by forgetting the record altogether, so a process killed
 * midway leaves the mark behind — and the next start finishes the undo.
 */
data class JobRecord(
    val id: JobId,
    val type: String,
    val params: String,
    val isActive: Boolean,
    val isCompensating: Boolean = false
)
