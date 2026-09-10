package com.merseyside.jobs

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * What a job is given on the inside. Through it the job splits its work into
 * steps and reports how things are going.
 */
interface JobScope {

    val jobId: JobId

    /**
     * A step with a checkpoint: the result goes into the storage, and on a
     * repeated pass the body is not executed at all — the saved value is
     * returned.
     *
     * @param key the step name. It must match from pass to pass, otherwise the
     * saved result will not be found. Steps inside a loop are numbered by hand.
     */
    suspend fun <T> step(key: String, serializer: KSerializer<T>, body: suspend () -> T): T

    /**
     * A step without the storage: the result stays in memory only.
     *
     * For data a provider's rules allow keeping for the time of showing it
     * only — music catalogues, for instance. While the process is alive, a
     * repeated start of the job does not replay such a step; after a restart
     * it runs again.
     */
    suspend fun <T> memoryStep(key: String, body: suspend () -> T): T

    /** Tell what the job is busy with now. Reaches the screen as it is. */
    suspend fun report(progress: JobProgress)
}

/**
 * The same as [JobScope.step], but the serializer is inferred from the type.
 */
suspend inline fun <reified T> JobScope.step(
    key: String,
    noinline body: suspend () -> T
): T = step(key, serializer(), body)
