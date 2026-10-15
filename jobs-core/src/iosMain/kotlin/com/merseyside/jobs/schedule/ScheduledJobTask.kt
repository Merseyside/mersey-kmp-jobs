package com.merseyside.jobs.schedule

import com.merseyside.jobs.JobRunner
import com.merseyside.jobs.hold.ScheduledRunHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler

/**
 * The background task iOS wakes the app up for: it hands every due schedule to
 * the runner and waits for the attempts to end.
 *
 * The iOS counterpart of `ScheduledJobWorker`.
 */
object ScheduledJobTask {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registers the task. iOS accepts the registration only while the app is
     * launching — until application(_:didFinishLaunchingWithOptions:) returns,
     * so it is called from the app's init, not from a screen: the app may be
     * woken up without one.
     *
     * @param taskIdentifier the same as in [BackgroundTaskJobWakeups] and in
     * Info.plist.
     * @param runner the runner the scheduled work goes through. A function
     * rather than the runner itself: the runner is created when it is first
     * asked for, not at the app start.
     */
    fun register(taskIdentifier: String, runner: () -> JobRunner) {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(taskIdentifier, null) { task ->
            if (task != null) run(task, runner)
        }
    }

    private fun run(task: BGTask, runner: () -> JobRunner) {
        val work = scope.launch {
            ScheduledRunHold.during { runner().runDueSchedules() }

            // Success even when a job is left waiting: it is the runner that
            // repeats it, at the next wake-up or when the app is opened
            task.setTaskCompletedWithSuccess(true)
        }

        // The time is up: the runner wraps the jobs up — the passed steps are
        // in the storage already — and the system is told at once. Not telling
        // it in time is worse: iOS would kill the app and give it less time later
        task.expirationHandler = {
            ScheduledRunHold.expire()
            work.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }
}
