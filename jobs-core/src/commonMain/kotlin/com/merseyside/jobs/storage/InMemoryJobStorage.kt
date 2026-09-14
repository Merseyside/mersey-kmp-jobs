package com.merseyside.jobs.storage

import com.merseyside.jobs.JobId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory storage — the default stub.
 *
 * Good where there is nothing to survive a restart: in tests and in the
 * browser, where the tab is closed with all its content anyway. On devices the
 * app must provide its own, otherwise saving steps loses its point.
 */
class InMemoryJobStorage : JobStorage {

    private val mutex = Mutex()
    private val jobs = mutableMapOf<JobId, JobRecord>()
    private val steps = mutableMapOf<JobId, MutableMap<String, String>>()

    override suspend fun saveJob(record: JobRecord) = mutex.withLock {
        jobs[record.id] = record
    }

    override suspend fun findJob(type: String, params: String): JobRecord? = mutex.withLock {
        jobs.values.firstOrNull { record -> record.type == type && record.params == params }
    }

    override suspend fun activeJobs(): List<JobRecord> = mutex.withLock {
        jobs.values.filter { record -> record.isActive }
    }

    override suspend fun compensatingJobs(): List<JobRecord> = mutex.withLock {
        jobs.values.filter { record -> record.isCompensating }
    }

    override suspend fun setActive(id: JobId, isActive: Boolean) = mutex.withLock {
        jobs[id]?.let { record -> jobs[id] = record.copy(isActive = isActive) }
        Unit
    }

    override suspend fun setCompensating(id: JobId) = mutex.withLock {
        jobs[id]?.let { record ->
            jobs[id] = record.copy(isActive = false, isCompensating = true)
        }
        Unit
    }

    override suspend fun removeJob(id: JobId) = mutex.withLock {
        jobs.remove(id)
        steps.remove(id)
        Unit
    }

    override suspend fun saveStep(id: JobId, key: String, value: String) = mutex.withLock {
        steps.getOrPut(id) { mutableMapOf() }[key] = value
        Unit
    }

    override suspend fun steps(id: JobId): Map<String, String> = mutex.withLock {
        steps[id]?.toMap().orEmpty()
    }
}
