package com.merseyside.jobs.schedule

import com.merseyside.merseyLib.time.units.TimeUnit

/**
 * A request to the system to wake the app up for scheduled work, even when it
 * is closed.
 *
 * Only Android can do it reliably — through WorkManager. iOS decides on its own
 * whether to wake an app at all, and the browser cannot do it in any way. Where
 * there is nothing to ask, scheduled work is started by
 * [com.merseyside.jobs.JobRunner.restore] when the app comes back to the screen.
 */
interface JobWakeups {

    /**
     * Asks to wake the app up every [interval] and call
     * [com.merseyside.jobs.JobRunner.runScheduled] with [scheduleId]. A repeated
     * call with the same id replaces the interval instead of adding a second
     * wake-up.
     */
    suspend fun register(scheduleId: String, interval: TimeUnit)

    suspend fun unregister(scheduleId: String)
}

/**
 * Wakes nobody up: scheduled work goes only when the app is opened. For iOS,
 * the browser and tests.
 */
object NoJobWakeups : JobWakeups {

    override suspend fun register(scheduleId: String, interval: TimeUnit) = Unit

    override suspend fun unregister(scheduleId: String) = Unit
}
