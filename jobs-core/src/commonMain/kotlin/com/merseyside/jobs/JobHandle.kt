package com.merseyside.jobs

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Handle of a started job: watch its course and await its end.
 *
 * A screen takes it from [JobRunner] by [JobId] — including after the screen
 * has been recreated. The job is not interrupted by that: it lives in the
 * runtime, not in whoever is watching it.
 */
interface JobHandle<out R : Any> {

    val id: JobId

    val state: StateFlow<JobState<R>>
}

/**
 * Awaits the end of the work. Returns the result, and rethrows the job's
 * failure to whoever waited.
 *
 * Waiting for the next attempt does not count as an end: a job that ran out of
 * network will still be done, and the waiter will get its result — later.
 */
suspend fun <R : Any> JobHandle<R>.await(): R =
    when (val finished = state.first { value -> value.isFinished }) {
        is JobState.Success -> finished.result
        is JobState.Failed -> throw finished.error
        JobState.Cancelled -> throw JobCancelledException(id)
        is JobState.Running, is JobState.Waiting ->
            error("Cannot happen: the state is filtered out above")
    }

class JobCancelledException(val jobId: JobId) : RuntimeException("Job $jobId is cancelled")

/**
 * The undo is being finished after a restart. What put an end to the work is
 * not stored — only the fact that the work will not happen — so this is what
 * [JobSpec.compensate] is given the second time around.
 */
class JobUndoResumedException(val jobId: JobId) :
    RuntimeException("Undo of job $jobId resumed after a restart")

/**
 * The system did not give the hold of the process: on Android that means the
 * app is not on the screen, on iOS that the background time is spent.
 *
 * Not a failure of the work itself and not its end — the job has not even
 * started the attempt. It sleeps until the app comes back to the screen and
 * [JobRunner.restore] wakes it.
 */
class ProcessHoldRefusedException(val jobId: JobId) :
    RuntimeException("Job $jobId was refused the hold of the process")
