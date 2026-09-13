package com.merseyside.jobs

/**
 * What the job is busy with, as seen by whoever waits for it.
 */
sealed interface JobState<out R : Any> {

    /** Running. There may be no report yet — a job is not obliged to send one. */
    data class Running(val progress: JobProgress?) : JobState<Nothing>

    data class Success<out R : Any>(val result: R) : JobState<R>

    /**
     * Failed beyond help: no permission, wrong key, the playlist no longer
     * exists. The passed steps stay in the storage: a repeated start with the
     * same params continues from the point of failure, not from the beginning.
     */
    data class Failed(val error: Throwable) : JobState<Nothing>

    /**
     * Waits for its time: the attempt failed for a temporary reason — no
     * network, the service answered "too many requests" or stays silent — or
     * has not begun at all, because the system refused the hold of the process
     * to an app that is not on the screen.
     *
     * This is not the end of the work but a pause: whoever waits for the
     * result keeps waiting. For the screen showing it such a job is still
     * "in progress", just longer than usual.
     *
     * @param attempt which attempt failed, counting from the first one. Zero
     * while nothing has been tried yet.
     */
    data class Waiting(val error: Throwable, val attempt: Int) : JobState<Nothing>

    /** Cancelled — either by the caller or by the system taking time away. */
    data object Cancelled : JobState<Nothing>

    /**
     * The work is over — one way or another. Waiting for the next attempt does
     * not count as an end: the job will still be done.
     */
    val isFinished: Boolean
        get() = this !is Running && this !is Waiting
}
