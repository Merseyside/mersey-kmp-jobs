package com.merseyside.jobs

import com.merseyside.jobs.schedule.JobWakeups
import com.merseyside.jobs.storage.InMemoryJobStorage
import com.merseyside.merseyLib.time.units.Minutes
import com.merseyside.merseyLib.time.units.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleTest {

    @Serializable
    data class Check(val name: String)

    private class CountingSpec : JobSpec<Check, Int> {
        var runs = 0
        val ran = CompletableDeferred<Unit>()

        override val type = "check"
        override val paramsSerializer = Check.serializer()

        override suspend fun JobScope.execute(params: Check): Int {
            runs++
            ran.complete(Unit)
            return runs
        }
    }

    private class RecordingWakeups : JobWakeups {
        val registered = mutableMapOf<String, TimeUnit>()

        override suspend fun register(scheduleId: String, interval: TimeUnit) {
            registered[scheduleId] = interval
        }

        override suspend fun unregister(scheduleId: String) {
            registered.remove(scheduleId)
        }
    }

    @Test
    fun wakeUpRunsTheWorkOnceUntilItsTimeComes() = runBlocking<Unit> {
        val spec = CountingSpec()
        val storage = InMemoryJobStorage()
        val runner = RealJobRunner(listOf(spec), storage)

        runner.schedule(spec, Check("a"), every = INTERVAL)
        val id = storage.schedules().single().id

        assertTrue(withTimeout(TIMEOUT) { runner.runScheduled(id) })
        assertTrue(withTimeout(TIMEOUT) { runner.runScheduled(id) })

        assertEquals(1, spec.runs)
    }

    @Test
    fun restoreStartsNewSchedule() = runBlocking<Unit> {
        val spec = CountingSpec()
        val runner = RealJobRunner(listOf(spec))

        runner.schedule(spec, Check("a"), every = INTERVAL)
        runner.restore()

        withTimeout(TIMEOUT) { spec.ran.await() }
    }

    @Test
    fun schedulingAgainKeepsTheLastRun() = runBlocking<Unit> {
        val spec = CountingSpec()
        val storage = InMemoryJobStorage()
        val wakeups = RecordingWakeups()
        val runner = RealJobRunner(listOf(spec), storage, wakeups = wakeups)

        runner.schedule(spec, Check("a"), every = INTERVAL)
        val id = storage.schedules().single().id
        withTimeout(TIMEOUT) { runner.runScheduled(id) }

        runner.schedule(spec, Check("a"), every = Minutes(60))

        val record = storage.schedules().single()
        assertEquals(id, record.id)
        assertEquals(Minutes(60).millis, record.interval.millis)
        assertNotNull(record.lastRunAt)
        assertEquals(Minutes(60).millis, wakeups.registered.getValue(id).millis)
    }

    @Test
    fun forgottenScheduleCancelsItsWakeUp() = runBlocking<Unit> {
        val spec = CountingSpec()
        val storage = InMemoryJobStorage()
        val wakeups = RecordingWakeups()
        val runner = RealJobRunner(listOf(spec), storage, wakeups = wakeups)

        runner.schedule(spec, Check("a"), every = INTERVAL)
        val id = storage.schedules().single().id
        runner.unschedule(spec, Check("a"))

        assertTrue(wakeups.registered.isEmpty())
        assertFalse(runner.runScheduled(id))
        assertEquals(0, spec.runs)
    }

    @Test
    fun dueSchedulesRunTogetherAndAskForTheNextWakeUp() = runBlocking<Unit> {
        val spec = CountingSpec()
        val storage = InMemoryJobStorage()
        val wakeups = RecordingWakeups()
        val runner = RealJobRunner(listOf(spec), storage, wakeups = wakeups)

        runner.schedule(spec, Check("a"), every = INTERVAL)
        runner.schedule(spec, Check("b"), every = INTERVAL)
        wakeups.registered.clear()

        withTimeout(TIMEOUT) { runner.runDueSchedules() }

        assertEquals(2, spec.runs)
        assertEquals(storage.schedules().map { record -> record.id }.toSet(), wakeups.registered.keys)
    }

    private companion object {
        val INTERVAL = Minutes(360)
        const val TIMEOUT = 5_000L
    }
}
