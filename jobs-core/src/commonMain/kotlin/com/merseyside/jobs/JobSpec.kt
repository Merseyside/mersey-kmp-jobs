package com.merseyside.jobs

import kotlinx.serialization.KSerializer

/**
 * Description of a kind of work: what the job is and how to run it.
 *
 * One per type, not per start. Registered when [JobRunner] is created — it is
 * by [type] that unfinished work is recreated after an app restart, when the
 * original lambda is no longer in memory.
 */
interface JobSpec<P : Any, R : Any> {

    /**
     * Permanent name of the kind of work. It goes into the storage, so
     * renaming it is the same as losing the saved progress of every job of
     * this kind.
     */
    val type: String

    /** Params go into the storage so that the job can be started again. */
    val paramsSerializer: KSerializer<P>

    suspend fun JobScope.execute(params: P): R

    /**
     * Whether the work is worth repeating after this error.
     *
     * Errors come in two kinds, and only the work itself can tell them apart:
     * the runtime knows nothing about someone else's exceptions. "No network",
     * "too many requests", "the service is silent" are temporary — the work
     * should be repeated later, and for whoever waits for it nothing happened.
     * "No permission", "wrong key", "the playlist is gone" are final: repeat
     * as long as you like, the answer stays the same, and the person has to be
     * told right away.
     *
     * By default nothing is repeated: ticking into the void silently is worse
     * than admitting a failure honestly.
     */
    fun isRetryable(error: Throwable): Boolean = false

    /**
     * When to try again after an error [isRetryable] called temporary. By
     * default the runtime repeats on its own, with a growing pause.
     */
    val retryPolicy: RetryPolicy get() = RetryPolicy.Backoff()

    /**
     * Undoes what the work has already written on its own, once it is clear
     * the work will not be finished: the server refused for good, or the job
     * was cancelled.
     *
     * This is where optimistic writes are rolled back. The screen is no help
     * here — it may be long gone, while the made-up row stays in the database.
     * The runtime calls this itself and only then forgets the job, so the undo
     * is not lost even if the process dies halfway: the record keeps the mark
     * and the next [JobRunner.restore] finishes what was started.
     *
     * The undo has to be ready for a repeated call: a job killed in the middle
     * of it comes back to it again.
     *
     * @param error what put an end to the work: the final error, or
     * [JobCancelledException] if the job was cancelled.
     */
    suspend fun compensate(params: P, error: Throwable) = Unit
}
