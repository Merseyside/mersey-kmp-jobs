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

    /** Called when the first running job appears. */
    suspend fun acquire()

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

    override suspend fun acquire() = Unit

    override suspend fun release() = Unit
}
