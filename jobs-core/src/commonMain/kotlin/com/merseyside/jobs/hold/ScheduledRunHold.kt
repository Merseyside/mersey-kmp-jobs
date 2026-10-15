package com.merseyside.jobs.hold

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

/**
 * The process is held by the system's own background work right now: a
 * WorkManager worker on Android, a background task on iOS.
 *
 * The system keeps the process alive for as long as that work runs — up to ten
 * minutes on Android, several minutes on iOS. The platform hold is not needed
 * then, and could not be taken anyway: the app is in the background, where
 * Android refuses a foreground service.
 */
internal object ScheduledRunHold {

    private val runs = MutableStateFlow(0)

    private val mutableExpirations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val isHeld: Boolean
        get() = runs.value > 0

    /**
     * The system takes the time of the background work back. Only iOS says so:
     * Android stops a worker silently.
     */
    val expirations: Flow<Unit> = mutableExpirations.asSharedFlow()

    suspend fun <T> during(block: suspend () -> T): T {
        runs.update { count -> count + 1 }

        return try {
            block()
        } finally {
            runs.update { count -> count - 1 }
        }
    }

    fun expire() {
        mutableExpirations.tryEmit(Unit)
    }
}
