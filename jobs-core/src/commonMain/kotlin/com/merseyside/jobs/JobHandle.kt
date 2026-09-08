package com.merseyside.jobs

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Ручка запущенной задачи: следить за ходом и дождаться конца.
 *
 * Экран берёт её у [JobRunner] по [JobId] — в том числе после того, как его
 * пересоздали. Задача от этого не прерывается: она живёт в рантайме, а не в
 * том, кто на неё смотрит.
 */
interface JobHandle<out R : Any> {

    val id: JobId

    val state: StateFlow<JobState<R>>
}

/**
 * Ждёт конца работы. Возвращает результат, а падение задачи пробрасывает тому,
 * кто ждал.
 *
 * Ожидание следующей попытки концом не считается: задача, которой не хватило
 * сети, ещё сделается, и ждущий дождётся её результата — пусть и позже.
 */
suspend fun <R : Any> JobHandle<R>.await(): R =
    when (val finished = state.first { value -> value.isFinished }) {
        is JobState.Success -> finished.result
        is JobState.Failed -> throw finished.error
        JobState.Cancelled -> throw JobCancelledException(id)
        is JobState.Running, is JobState.Waiting ->
            error("Не может быть: состояние отфильтровано выше")
    }

class JobCancelledException(val jobId: JobId) : RuntimeException("Задача $jobId отменена")
