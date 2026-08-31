package com.merseyside.jobs.storage

import com.merseyside.jobs.JobId

/**
 * Где живёт прогресс задач между запусками приложения.
 *
 * Библиотека своей базы не заводит: хранилище приносит приложение — у него
 * уже есть и база, и понимание, где ей место на каждой платформе.
 */
interface JobStorage {

    suspend fun saveJob(record: JobRecord)

    /**
     * Запись о такой же работе, если она уже заводилась. По ней повторный
     * запуск подхватывает пройденные шаги вместо того, чтобы начинать заново.
     */
    suspend fun findJob(type: String, params: String): JobRecord?

    /** Задачи, застигнутые перезапуском на середине. */
    suspend fun activeJobs(): List<JobRecord>

    suspend fun setActive(id: JobId, isActive: Boolean)

    suspend fun removeJob(id: JobId)

    suspend fun saveStep(id: JobId, key: String, value: String)

    /** Пройденные шаги задачи: имя шага — его сохранённый результат в JSON. */
    suspend fun steps(id: JobId): Map<String, String>
}
