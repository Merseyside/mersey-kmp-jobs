package com.merseyside.jobs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * A use case for work the caller watches all the way through, not just waits
 * the end of.
 *
 * Unlike [JobUseCase] with its three outcomes, this one gives the whole course
 * of the work as a stream of [JobEvent]: the reports of a job running for
 * minutes, the pauses before the next attempt, and the end. That is what a
 * screen with a progress bar needs — and it is the same stream whether the
 * work was started right here or picked up already running.
 *
 * The report comes typed: the runtime knows nothing about someone else's
 * [JobProgress], so the kind of report is named once, by [progressOf], and the
 * screen gets its own type without casting anything itself.
 *
 * Collecting the stream is not what keeps the work alive: stop collecting —
 * close the screen — and the job goes on in the runtime.
 */
abstract class JobFlowUseCase<R : Any, P : Any, Pr : JobProgress>(
    private val jobRunner: JobRunner,
    private val spec: JobSpec<P, R>,
    /**
     * The job's own report, or null if this one is of no interest to the
     * screen. Usually a single `as?` — a job may send reports of several kinds.
     */
    private val progressOf: (JobProgress) -> Pr?
) {

    /**
     * Starts the work and streams its course until the end.
     *
     * The same work already running is not started a second time: the stream
     * joins it — from where it is now, not from its beginning.
     */
    fun execute(params: P): Flow<JobEvent<R, Pr>> = flow {
        emitAll(jobRunner.start(spec, params).events())
    }

    /**
     * Work of this kind started before and not finished yet — for a screen
     * that has just opened and does not know what was going on without it.
     *
     * An empty stream means there is nothing going on. The interrupted work is
     * revived first: the screen may have opened before whoever does it at app
     * start got to it.
     */
    fun ongoing(): Flow<JobEvent<R, Pr>> = flow {
        jobRunner.restore()

        val ongoing = jobRunner.current(spec) ?: return@flow

        emitAll(ongoing.handle.events())
    }

    /**
     * The states of the job as events. The stream ends together with the work:
     * waiting for the next attempt is not an end, a success and a refusal are.
     */
    private fun JobHandle<R>.events(): Flow<JobEvent<R, Pr>> =
        state.transformWhile { current ->
            when (current) {
                is JobState.Running -> {
                    emit(JobEvent.Working(current.progress?.let(progressOf)))
                    true
                }

                is JobState.Waiting -> {
                    emit(JobEvent.Scheduled(error = current.error, attempt = current.attempt))
                    true
                }

                is JobState.Success -> {
                    emit(JobEvent.Done(current.result))
                    false
                }

                // A failure goes to the collector as an exception: otherwise a
                // screen that joined someone else's work would keep waiting for
                // an end that has already come
                is JobState.Failed -> throw current.error

                JobState.Cancelled -> {
                    emit(JobEvent.Stopped)
                    false
                }
            }
        }
}
