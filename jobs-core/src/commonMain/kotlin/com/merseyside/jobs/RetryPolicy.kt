package com.merseyside.jobs

import com.merseyside.merseyLib.time.units.Millis
import com.merseyside.merseyLib.time.units.Minutes
import com.merseyside.merseyLib.time.units.Seconds
import com.merseyside.merseyLib.time.units.TimeUnit

/**
 * When to try again after a temporary error.
 */
sealed interface RetryPolicy {

    /**
     * Does not repeat on its own: the job sleeps until reminded from the
     * outside — by a repeated start with the same params or by
     * [JobRunner.restore].
     *
     * For work whose obstacle the app can see: there is no network, blind
     * attempts change nothing, and the job can be woken up exactly when the
     * network is back.
     */
    data object OnDemand : RetryPolicy

    /**
     * Repeats on its own, doubling the pause after every failure: the first
     * attempts go often — an obstacle most often passes right away — and later
     * the work ticks rarely and costs almost nothing.
     *
     * @param base the pause after the first failure.
     * @param max the ceiling: the pause does not grow above it.
     */
    data class Backoff(
        val base: TimeUnit = DEFAULT_BASE,
        val max: TimeUnit = DEFAULT_MAX
    ) : RetryPolicy {

        /** How long to wait after the [attempt]-th failure, counting from the first. */
        fun delayAfter(attempt: Int): TimeUnit {
            val grown = Millis(base.millis shl (attempt - 1).coerceAtMost(MAX_SHIFT))

            return if (grown > max) max else grown
        }

        companion object {

            /** The pause after the first failure. Doubles afterwards. */
            val DEFAULT_BASE: TimeUnit = Seconds(10)

            /** There is no point in trying less often than once in two minutes. */
            val DEFAULT_MAX: TimeUnit = Minutes(2)

            /** Shifting further is pointless — the ceiling is closer anyway. */
            private const val MAX_SHIFT = 8
        }
    }
}
