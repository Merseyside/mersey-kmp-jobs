package com.merseyside.jobs

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
     * params.
     *
     * If this work was once interrupted, its saved steps are picked up: what
     * is already done is not done again.
     */
    suspend fun <P : Any, R : Any> start(spec: JobSpec<P, R>, params: P): JobHandle<R>

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
     * Every unfinished job of this kind.
     *
     * A screen may have several of them at once — three task statuses moved
     * one after another, each waiting for the network on its own. Which of them
     * is which the screen tells by the params.
     */
    suspend fun <P : Any, R : Any> ongoing(spec: JobSpec<P, R>): List<OngoingJob<P, R>>

    /**
     * Stops the work and forgets its progress. This is giving the job up, not
     * pausing it: the next start begins from scratch.
     */
    suspend fun cancel(id: JobId)

    /**
     * Revives work interrupted against its will: by an app restart or by the
     * system taking time away from a background process.
     *
     * Called on app start and when the app comes back to the screen. Jobs that
     * finished on their own — with success or with an error — are not revived.
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
