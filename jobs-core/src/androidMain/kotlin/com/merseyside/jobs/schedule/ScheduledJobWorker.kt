package com.merseyside.jobs.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.merseyside.jobs.JobRunner
import com.merseyside.jobs.hold.ScheduledRunHold

/**
 * The worker WorkManager wakes up for a schedule: it hands the schedule to the
 * runner and waits for the attempt to end.
 *
 * WorkManager creates the worker by itself and knows nothing about the app's
 * graph, so the runner is handed over through [install] — once, when the app
 * starts. The app may be woken up without a single screen, which is why it is
 * done in Application.onCreate and not in an activity.
 */
class ScheduledJobWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Not installed is a mistake of the app, not of the schedule: this run
        // is lost, the next one comes at its time
        val runner = runner?.invoke() ?: return Result.failure()
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()

        val exists = ScheduledRunHold.during { runner.runScheduled(scheduleId) }

        // The schedule was forgotten without us — with the app's data, say. The
        // wake-up has nothing to wake for anymore
        if (!exists) WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(scheduleId))

        // Success even when the job is left waiting: it is the runner that
        // repeats it, and a retry from WorkManager would only overlap the next
        // run of the schedule
        return Result.success()
    }

    companion object {

        @Volatile
        private var runner: (() -> JobRunner)? = null

        /**
         * Hands over the runner the scheduled work goes through. Called in
         * Application.onCreate. A function rather than the runner itself: the
         * runner is created when it is first asked for, not at the app start.
         */
        fun install(runner: () -> JobRunner) {
            this.runner = runner
        }

        internal const val KEY_SCHEDULE_ID = "scheduleId"

        internal fun uniqueName(scheduleId: String) = "mersey-jobs-schedule-$scheduleId"
    }
}
