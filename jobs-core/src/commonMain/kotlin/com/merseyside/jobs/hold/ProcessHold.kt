package com.merseyside.jobs.hold

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A request to the system not to kill the process while the work is going on.
 *
 * On different platforms these are different things, and they will not become
 * the same: on Android it is a foreground service, on iOS a postponement of
 * falling asleep, in the browser nothing. They have exactly two actions in
 * common: take the hold and release it.
 */
interface ProcessHold {

    /**
     * Called before the work of a job begins.
     *
     * @return whether the hold was taken. A refusal is an ordinary answer, not
     * a breakdown: the system gives no hold to an app that is not on the
     * screen — since Android 12 a foreground service cannot be raised from the
     * background, and iOS refuses the postponement to an app that has spent
     * its time already. In response the runtime puts the job to sleep until it
     * is woken from the screen. Starting anyway is worse than waiting: the
     * work would be cut short mid-step, and the system would take the whole
     * app down along with it.
     */
    suspend fun acquire(): Boolean

    /** Called when no running jobs are left. */
    suspend fun release()

    /**
     * The system takes the time back — the work has to be wrapped up right
     * now.
     *
     * That is what iOS does when the postponement of falling asleep comes to
     * its end. In response the runtime cancels the jobs: the passed steps are
     * already in the storage, and after a return to the app the work continues
     * from the nearest step not yet passed.
     */
    val expirations: Flow<Unit>
        get() = emptyFlow()
}

/**
 * A hold that holds nothing.
 *
 * For the browser, where the lifetime of a tab is not ours to command, and for
 * cases where the app needs no background work at all.
 */
object NoProcessHold : ProcessHold {

    // Nothing is taken, and the answer is still yes: there is nothing here for
    // the system to refuse, and a job must not be left asleep waiting for a
    // permission that nobody is going to give
    override suspend fun acquire() = true

    override suspend fun release() = Unit
}
