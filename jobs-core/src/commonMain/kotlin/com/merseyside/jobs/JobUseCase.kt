package com.merseyside.jobs

import kotlinx.coroutines.CancellationException
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
 *
 * @param replacesPrevious a new [execute] cancels the unfinished work with the
 * same [owner key][ownerKeyOf] and other params: of all the requests only the
 * last one matters — a task status changed three times without the network.
 * Means nothing when the key is null.
 */
abstract class JobUseCase<R : Any, P : Any>(
    private val jobRunner: JobRunner,
    private val spec: JobSpec<P, R>,
    private val replacesPrevious: Boolean = false
) {

    /**
     * Who the work of this very call belongs to — a task, a field. By the key
     * [observeOngoing] finds the work of one owner, and [replacesPrevious]
     * knows what to cancel. Null by default: the work belongs to nobody.
     */
    protected open fun ownerKeyOf(params: P): String? = null

    /**
     * Starts the work and waits for its end right in the calling coroutine.
     *
     * Waiting for the next attempt is not an end: the call stays suspended until
     * the network is back and the job is done. A final failure is thrown. A job
     * cancelled — by a newer one with the same key, for instance — ends the call
     * as a cancellation: whoever cancelled it knows already, the same way
     * [execute] keeps silent about it.
     *
     * Cancelling the calling coroutine stops only the waiting: the job goes on
     * in the runtime.
     */
    suspend operator fun invoke(params: P): R {
        val handle = jobRunner.start(spec, params, ownerKeyOf(params), replacesPrevious)

        return try {
            handle.await()
        } catch (cancelled: JobCancelledException) {
            throw CancellationException(cancelled.message, cancelled)
        }
    }

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
            jobRunner.start(spec, params, ownerKeyOf(params), replacesPrevious)
        } catch (error: Throwable) {
            onError(error)
            return@launch
        }

        handle.await(onScheduled = onScheduled, onComplete = onComplete, onError = onError)
    }

    /**
     * Every job of this kind: those going on right now, the failed ones the kind
     * [keeps][JobSpec.keepsFailed], and every job started later — from this
     * screen or any other — for as long as [coroutineScope] lives.
     *
     * The callbacks follow each job to its end. A job may go back and forth
     * between [onScheduled] and [onRunning] as the network comes and goes. A
     * cancelled job goes silent: whoever cancelled it knows already.
     *
     * @param ownerKey only the work of this owner. Null means all of it.
     */
    fun observeOngoing(
        coroutineScope: CoroutineScope,
        ownerKey: String? = null,
        onScheduled: (P, Throwable) -> Unit = { _, _ -> },
        onRunning: (P) -> Unit = {},
        onComplete: (P, R) -> Unit = { _, _ -> },
        onError: (P, Throwable) -> Unit = { _, _ -> }
    ): Job = coroutineScope.launch {
        jobRunner.observe(spec, ownerKey).collect { ongoing ->
            launch {
                ongoing.handle.await(
                    onScheduled = { error -> onScheduled(ongoing.params, error) },
                    onRunning = { onRunning(ongoing.params) },
                    onComplete = { result -> onComplete(ongoing.params, result) },
                    onError = { error -> onError(ongoing.params, error) }
                )
            }
        }
    }

    /**
     * Gives up the work with these params if it has not gone out yet: it waits
     * for the network, stands in the queue or has failed. The undo is done
     * before the call returns.
     *
     * @return false if the request is already on its way or there is no such
     * work: then it will end on its own.
     */
    suspend fun cancel(params: P): Boolean = jobRunner.cancelIfIdle(spec, params)

    /**
     * Swaps the params of work that has not gone out yet, keeping its place in
     * the queue. The new job comes to [observeOngoing] as any other.
     *
     * @return false if the old work is already on its way or no longer exists.
     */
    suspend fun replace(old: P, new: P): Boolean =
        jobRunner.replace(spec, old, new, ownerKeyOf(new)) != null

    private suspend fun JobHandle<R>.await(
        onScheduled: (Throwable) -> Unit,
        onRunning: () -> Unit = {},
        onComplete: (R) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val finished = state
            .onEach { current ->
                when (current) {
                    is JobState.Waiting -> onScheduled(current.error)
                    is JobState.Running -> onRunning()
                    else -> Unit
                }
            }
            .first { current -> current.isFinished }

        when (finished) {
            is JobState.Success -> onComplete(finished.result)
            is JobState.Failed -> onError(finished.error)
            // Cancellation is arranged by whoever cancelled: no need to tell them
            else -> Unit
        }
    }
}
