package com.merseyside.jobs

/**
 * What happened to the job, as told to whoever watches it as a stream.
 *
 * The same events describe both a job just started and one picked up on the
 * way: a screen that came back does not need to know which of the two it is
 * looking at.
 *
 * A final failure is not an event: it is thrown into the stream, so that the
 * one collecting handles it where they handle everything else that went wrong.
 */
sealed interface JobEvent<out R : Any, out Pr : JobProgress> {

    /**
     * The work is going on. The report may be missing — a job is not obliged
     * to send one, and the very first event of a started job never carries it.
     */
    data class Working<out Pr : JobProgress>(val progress: Pr?) : JobEvent<Nothing, Pr>

    /**
     * The work ran into a temporary obstacle and waits for the next attempt.
     * Not a refusal: the job will still be done, only later.
     *
     * @param attempt which attempt failed, counting from the first one.
     */
    data class Scheduled(val error: Throwable, val attempt: Int) : JobEvent<Nothing, Nothing>

    /** The end: the work is done and this is what it returned. */
    data class Done<out R : Any>(val result: R) : JobEvent<R, Nothing>

    /**
     * The work was given up — by the caller or by the system taking the time
     * away. Whatever it managed to do stays done.
     */
    data object Stopped : JobEvent<Nothing, Nothing>
}
