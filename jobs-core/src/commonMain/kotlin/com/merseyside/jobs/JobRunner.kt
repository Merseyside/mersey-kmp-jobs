package com.merseyside.jobs

import kotlinx.coroutines.flow.Flow

/**
 * Where long-running jobs live.
 *
 * A job started here is not tied to a screen: navigation, device rotation and
 * the app going to background do not interrupt it. A screen takes a
 * [JobHandle] and watches the progress; the screen going away does not cancel
 * the work.
 *
 * Every action is suspending: they touch the storage, and the order of starts
 * and cancellations must be kept strictly — otherwise the very same start,
 * made twice, would split into two jobs.
 */
interface JobRunner {

    /**
     * Starts the work and returns a handle right away — no need to await the
     * end here.
     *
     * The same work already running is not started a second time: its handle
     * is returned instead. "The same" means a matching job type and matching
     * params. A kept failed job with the same params is retried: it goes on
     * under its own id and keeps its place in the queue.
     *
     * If this work was once interrupted, its saved steps are picked up: what
     * is already done is not done again.
     *
     * @param ownerKey who the work belongs to — a task, a field, a screen. By
     * it the work is found in [ongoing] and [observe]. Null means it belongs to
     * nobody in particular.
     * @param replacesPrevious unfinished work of the same kind with the same
     * [ownerKey] and other params is cancelled first — undo included — and only
     * then the new one starts: of all the requests only the last one matters.
     * Means nothing without a key.
     */
    suspend fun <P : Any, R : Any> start(
        spec: JobSpec<P, R>,
        params: P,
        ownerKey: String? = null,
        replacesPrevious: Boolean = false
    ): JobHandle<R>

    /** Handle of a running job — for a screen opened again. */
    suspend fun <R : Any> handle(id: JobId): JobHandle<R>?

    /**
     * Unfinished work of this type, if any is running now.
     *
     * For a screen that just opened and does not know what started before it:
     * it has nowhere to keep a job id, while the job type is always known. The
     * params come along with the handle — from them the screen restores what
     * the work started with.
     */
    suspend fun <P : Any, R : Any> current(spec: JobSpec<P, R>): OngoingJob<P, R>?

    /**
     * Every unfinished job of this kind, and the failed ones a kind that
     * [keeps them][JobSpec.keepsFailed] still holds.
     *
     * A screen may have several of them at once — three task statuses moved
     * one after another, each waiting for the network on its own. Which of them
     * is which the screen tells by the params.
     *
     * @param ownerKey only the work started with this key. Null means every
     * job of the kind, whatever its key.
     */
    suspend fun <P : Any, R : Any> ongoing(
        spec: JobSpec<P, R>,
        ownerKey: String? = null
    ): List<OngoingJob<P, R>>

    /**
     * The same as [ongoing], but it does not stop at those found: every job of
     * the kind started later comes too, for as long as the flow is collected.
     * Each job comes once; a failed job retried comes again, with a new handle.
     */
    fun <P : Any, R : Any> observe(
        spec: JobSpec<P, R>,
        ownerKey: String? = null
    ): Flow<OngoingJob<P, R>>

    /**
     * Stops the work and forgets its progress. This is giving the job up, not
     * pausing it: the next start begins from scratch.
     */
    suspend fun cancel(id: JobId)

    /**
     * Gives up the work only if it is not in the middle of an attempt: waits
     * for the network, stands in the queue or has failed and is kept.
     *
     * For a person deleting what has not gone out yet. A request already on
     * its way cannot be taken back — cancelling it would leave the server with
     * the work done and the app believing otherwise.
     *
     * @return whether the job was given up. False as well when there is no such
     * job anymore.
     */
    suspend fun <P : Any, R : Any> cancelIfIdle(spec: JobSpec<P, R>, params: P): Boolean

    /**
     * Swaps the params of work that has not gone out yet: the old job is given
     * up — undo included — and the new one takes its place in the queue.
     *
     * The same rule as [cancelIfIdle]: a job in the middle of an attempt is not
     * touched.
     *
     * @return the handle of the new job, or null if the old one is in the
     * middle of an attempt or no longer exists.
     */
    suspend fun <P : Any, R : Any> replace(
        spec: JobSpec<P, R>,
        old: P,
        new: P,
        ownerKey: String? = null
    ): JobHandle<R>?

    /**
     * Revives work interrupted against its will: by an app restart or by the
     * system taking time away from a background process.
     *
     * Called on app start and when the app comes back to the screen. Jobs that
     * finished on their own — with success or with an error — are not revived;
     * the failed ones a kind keeps come back to the registry as failed.
     */
    suspend fun restore()
}

/**
 * Running work: what it was started with and how to watch it.
 */
data class OngoingJob<P : Any, R : Any>(
    val params: P,
    val handle: JobHandle<R>
)
