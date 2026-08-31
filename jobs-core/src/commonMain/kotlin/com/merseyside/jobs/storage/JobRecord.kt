package com.merseyside.jobs.storage

import com.merseyside.jobs.JobId

/**
 * Запись о задаче в хранилище — всё, что нужно, чтобы завести её заново.
 *
 * @param params параметры в виде JSON: библиотека не знает их типа.
 * @param isActive задача считалась работающей на момент последней записи.
 * Осталась активной после перезапуска приложения — значит процесс убили на
 * полпути, и работу нужно поднять. Снятый признак означает, что задача
 * закончилась сама: успехом или ошибкой.
 */
data class JobRecord(
    val id: JobId,
    val type: String,
    val params: String,
    val isActive: Boolean
)
