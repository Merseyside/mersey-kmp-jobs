package com.merseyside.jobs.schedule

import com.merseyside.merseyLib.time.units.TimeUnit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * Wake-ups through a background task of iOS — `BGProcessingTask`.
 *
 * Unlike WorkManager, iOS knows no periodic work: one request means one
 * wake-up, and its identifiers are listed in Info.plist beforehand. So there is
 * one task for all the schedules. It is asked for no earlier than the shortest
 * interval, runs every schedule whose time has come, and is asked for again —
 * see [com.merseyside.jobs.JobRunner.runDueSchedules].
 *
 * The task waits for the network and not for the charger. iOS decides on its
 * own when to start it, mostly when the phone is not in use, and does not start
 * it in Low Power Mode at all. A request is a wish, not a promise: the work is
 * started by [com.merseyside.jobs.JobRunner.restore] as well, when the app is
 * opened.
 *
 * The app has to [register][ScheduledJobTask.register] the task when it starts.
 *
 * @param taskIdentifier the task's identifier — the same as in Info.plist,
 * under `BGTaskSchedulerPermittedIdentifiers`.
 */
class BackgroundTaskJobWakeups(private val taskIdentifier: String) : JobWakeups {

    private val mutex = Mutex()

    /**
     * The intervals of the schedules registered since the app started. After a
     * restart it is empty until the runner registers every schedule again —
     * which it does on each restore() and after each background run.
     */
    private val intervals = mutableMapOf<String, TimeUnit>()

    override suspend fun register(scheduleId: String, interval: TimeUnit) = mutex.withLock {
        intervals[scheduleId] = interval
        submit()
    }

    override suspend fun unregister(scheduleId: String) = mutex.withLock {
        intervals.remove(scheduleId)
        submit()
    }

    /**
     * A new request replaces the previous one with the same identifier: there
     * is never more than one. Called under [mutex].
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun submit() {
        val shortest = intervals.values.minOfOrNull { interval -> interval.millis }

        if (shortest == null) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
            return
        }

        val request = BGProcessingTaskRequest(identifier = taskIdentifier).apply {
            requiresNetworkConnectivity = true
            requiresExternalPower = false
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(shortest / MILLIS_IN_SECOND)
        }

        // A refusal is not an error worth stopping for: the simulator refuses
        // every request, and on a device the work still goes on opening the app
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }

    private companion object {

        const val MILLIS_IN_SECOND = 1_000.0
    }
}
