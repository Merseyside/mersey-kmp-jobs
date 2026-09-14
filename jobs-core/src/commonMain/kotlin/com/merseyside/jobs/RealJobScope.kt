package com.merseyside.jobs

import com.merseyside.jobs.storage.JobStorage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Splitting the work into steps. Created anew for every pass of the job and
 * given a snapshot of what has already been passed before.
 *
 * @param saved passed steps from the storage. Filled in along the pass so that
 * a step with the same name does not run twice.
 * @param memory passed steps without the storage. Shared by every pass of the
 * job within the life of the process — that is their whole point.
 */
internal class RealJobScope(
    override val jobId: JobId,
    private val saved: MutableMap<String, String>,
    private val memory: MutableMap<String, Any?>,
    private val storage: JobStorage,
    private val json: Json,
    private val onProgress: (JobProgress) -> Unit
) : JobScope {

    override suspend fun <T> step(
        key: String,
        serializer: KSerializer<T>,
        body: suspend () -> T
    ): T {
        saved[key]?.let { stored -> return json.decodeFromString(serializer, stored) }

        val value = body()
        val encoded = json.encodeToString(serializer, value)

        storage.saveStep(jobId, key, encoded)
        saved[key] = encoded

        return value
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> memoryStep(key: String, body: suspend () -> T): T {
        // containsKey exactly, not a null check: null is a legitimate result
        // of a step, and there is no need to ask for it again
        if (memory.containsKey(key)) return memory[key] as T

        return body().also { value -> memory[key] = value }
    }

    override suspend fun report(progress: JobProgress) = onProgress(progress)
}
