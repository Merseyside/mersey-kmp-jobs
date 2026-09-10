package com.merseyside.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * A use case whose work lives in the job runtime rather than in a screen.
 *
 * The caller gets a third outcome besides "done" and "failed": [onScheduled] —
 * the work is not done yet, but it is planned and will go through once the
 * obstacle is gone. That is not an error and needs no rollback.
 */
abstract class JobUseCase<R : Any, P : Any>(
    private val jobRunner: JobRunner,
    private val spec: JobSpec<P, R>
) {

    fun execute(
        coroutineScope: CoroutineScope,
        params: P,
        onPreExecute: () -> Unit = {},
        onScheduled: (Throwable) -> Unit = {},
        onComplete: (R) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ): Job = coroutineScope.launch {
        onPreExecute()

        val handle = try {
            jobRunner.start(spec, params)
        } catch (error: Throwable) {
            onError(error)
            return@launch
        }

        handle.await(onScheduled, onComplete, onError)
    }

    /**
     * Unfinished jobs of this kind — for a screen opened again: it does not see
     * its own postponed changes until it asks about them itself.
     */
    fun observeOngoing(
        coroutineScope: CoroutineScope,
        onScheduled: (P, Throwable) -> Unit = { _, _ -> },
        onComplete: (P, R) -> Unit = { _, _ -> },
        onError: (P, Throwable) -> Unit = { _, _ -> }
    ): Job = coroutineScope.launch {
        jobRunner.ongoing(spec).forEach { ongoing ->
            launch {
                ongoing.handle.await(
                    onScheduled = { error -> onScheduled(ongoing.params, error) },
                    onComplete = { result -> onComplete(ongoing.params, result) },
                    onError = { error -> onError(ongoing.params, error) }
                )
            }
        }
    }

    private suspend fun JobHandle<R>.await(
        onScheduled: (Throwable) -> Unit,
        onComplete: (R) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val finished = state
            .onEach { current -> if (current is JobState.Waiting) onScheduled(current.error) }
            .first { current -> current.isFinished }

        when (finished) {
            is JobState.Success -> onComplete(finished.result)
            is JobState.Failed -> onError(finished.error)
            // Cancellation is arranged by whoever cancelled: no need to tell them
            else -> Unit
        }
    }
}
