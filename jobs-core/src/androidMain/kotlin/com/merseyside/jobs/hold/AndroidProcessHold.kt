package com.merseyside.jobs.hold

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holding the process on Android — a foreground service.
 *
 * One thing is required from the app: while the jobs are running, it must be
 * on the screen at the moment of the start. Since Android 12 a foreground
 * service cannot be raised from the background, which is why
 * JobRunner.restore() is called when the app comes back to the screen, and not
 * from wherever happens to be convenient. A start from the background is
 * refused rather than crashed through: the runtime is told "no" and lets the
 * job sleep until the app is back.
 *
 * The notification itself will be shown on Android 13 and newer only if the
 * user allowed notifications. The app asks for the permission; without it the
 * service still works, just silently.
 */
class AndroidProcessHold(
    context: Context,
    private val notification: suspend () -> JobNotification
) : ProcessHold {

    // The application context is kept: the service outlives any screen
    private val context = context.applicationContext

    override suspend fun acquire(): Boolean {
        // The notification description is asked for here and not in the
        // constructor: the app's strings are read in a suspending way, and in
        // different languages they are different
        try {
            ContextCompat.startForegroundService(
                context,
                JobForegroundService.intent(context, notification())
            )
        } catch (refused: IllegalStateException) {
            // The app is not on the screen. ForegroundServiceStartNotAllowed-
            // Exception is caught by its parent: the class appeared in Android
            // 12, and the same refusal on older versions comes under a name of
            // its own
            return false
        }

        // The hold is taken not when the service was asked to rise but when it
        // has risen. The difference matters for short work: it manages to end
        // before the service starts, and the stop comes before the service went
        // to the foreground — for which the system brings the whole app down
        withTimeoutOrNull(START_TIMEOUT_MILLIS) {
            JobForegroundService.isStarted.first { started -> started }
        }

        // The service was allowed to rise — that is what the answer is about.
        // Whether it managed to within the limit does not change the job's
        // course: waiting for it forever is not an option, the work has to go on
        return true
    }

    override suspend fun release() {
        context.stopService(Intent(context, JobForegroundService::class.java))
    }

    private companion object {

        /**
         * How long to wait for the service to reach the foreground. Usually
         * this is tens of milliseconds; the limit is there for the case when
         * the system did not allow the start — it cannot be awaited forever,
         * the work has to go on anyway.
         */
        const val START_TIMEOUT_MILLIS = 5_000L
    }
}
