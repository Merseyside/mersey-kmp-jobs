package com.merseyside.jobs.hold

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Holding the process on iOS — a postponement of falling asleep.
 *
 * Services running for as long as needed do not exist on iOS and never will.
 * What exists is a request "do not put me to sleep right now": the system
 * grants mere tens of seconds after the app goes to background, and how many
 * exactly it decides itself.
 *
 * So this is not background work but a chance to finish what was started and
 * save a step. When the time comes to its end, iOS calls the expiration
 * handler — the runtime receives it through [expirations] and wraps the jobs
 * up itself. Not wrapping them up in time is worse: then the system kills the
 * whole app.
 */
class IosProcessHold : ProcessHold {

    private val mutableExpirations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val expirations: Flow<Unit> = mutableExpirations.asSharedFlow()

    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    /** UIApplication answers the main thread only — hence the switch. */
    override suspend fun acquire(): Boolean = withContext(Dispatchers.Main) {
        if (taskId != UIBackgroundTaskInvalid) return@withContext true

        taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(TASK_NAME) {
            mutableExpirations.tryEmit(Unit)
            endTask()
        }

        // The system has nothing to postpone with: the app has already spent
        // its background time. Starting the work now means being cut short on
        // the first step
        taskId != UIBackgroundTaskInvalid
    }

    override suspend fun release() = withContext(Dispatchers.Main) { endTask() }

    private fun endTask() {
        if (taskId == UIBackgroundTaskInvalid) return

        UIApplication.sharedApplication.endBackgroundTask(taskId)
        taskId = UIBackgroundTaskInvalid
    }

    private companion object {

        const val TASK_NAME = "com.merseyside.jobs"
    }
}
