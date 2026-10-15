package com.merseyside.jobs.storage

import com.merseyside.merseyLib.time.units.TimeUnit

/**
 * A schedule's record in the storage: which work to start and how often.
 *
 * @param id the schedule's own mark. The platform wakes the app up by it, so it
 * stays the same for as long as the schedule lives, even when its interval
 * changes.
 * @param params the params as JSON, exactly as a job of the same work keeps
 * them: by this pair a schedule is found again.
 * @param interval how often the work is started.
 * @param ownerKey what the started jobs belong to, see
 * [com.merseyside.jobs.JobRunner.start].
 * @param lastRunAt when the work was started by this schedule last time. Null
 * means never: the schedule is due right away.
 */
data class ScheduleRecord(
    val id: String,
    val type: String,
    val params: String,
    val interval: TimeUnit,
    val ownerKey: String?,
    val lastRunAt: TimeUnit?
)
