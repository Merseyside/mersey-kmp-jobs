package com.merseyside.jobs

import com.merseyside.jobs.storage.JobStorage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Разметка работы на шаги. Создаётся заново на каждый проход задачи и получает
 * снимок того, что уже было пройдено раньше.
 *
 * @param saved пройденные шаги из хранилища. Пополняется по ходу прохода,
 * чтобы шаг с одним именем не выполнился дважды.
 * @param memory пройденные шаги без хранилища. Общий на все проходы задачи в
 * пределах жизни процесса — в этом весь их смысл.
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
        // Именно containsKey, а не проверка на null: null — законный результат
        // шага, и переспрашивать о нём не нужно
        if (memory.containsKey(key)) return memory[key] as T

        return body().also { value -> memory[key] = value }
    }

    override suspend fun report(progress: JobProgress) = onProgress(progress)
}
