package com.merseyside.jobs.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.merseyside.merseyLib.time.units.TimeUnit
import java.util.concurrent.TimeUnit as JavaTimeUnit

/**
 * Wake-ups through WorkManager: periodic work that survives app closing and
 * device reboots.
 *
 * The work waits for the network and for the battery not to be low: scheduled
 * work is not urgent, and it is better put off than started on a phone about
 * to die. WorkManager runs periodic work no more often than once in fifteen
 * minutes — a shorter interval is stretched to that.
 *
 * The app has to [install][ScheduledJobWorker.install] the runner for the
 * worker to have something to run.
 */
class WorkManagerJobWakeups(context: Context) : JobWakeups {

    // The application context is kept: the wake-ups outlive any screen
    private val context = context.applicationContext

    override suspend fun register(scheduleId: String, interval: TimeUnit) {
        val request = PeriodicWorkRequestBuilder<ScheduledJobWorker>(interval.millis, JavaTimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(workDataOf(ScheduledJobWorker.KEY_SCHEDULE_ID to scheduleId))
            .build()

        // UPDATE and not KEEP: a new interval has to take effect, and the time
        // already waited is not lost on that
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ScheduledJobWorker.uniqueName(scheduleId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    override suspend fun unregister(scheduleId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(ScheduledJobWorker.uniqueName(scheduleId))
    }
}
