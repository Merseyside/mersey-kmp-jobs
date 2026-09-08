package com.merseyside.jobs

/**
 * Чем задача занята с точки зрения того, кто её ждёт.
 */
sealed interface JobState<out R : Any> {

    /** Работает. Доклада может ещё не быть — задача не обязана его слать. */
    data class Running(val progress: JobProgress?) : JobState<Nothing>

    data class Success<out R : Any>(val result: R) : JobState<R>

    /**
     * Упала так, что помочь нечем: прав нет, ключ не тот, плейлиста больше не
     * существует. Пройденные шаги остались в хранилище: повторный запуск с теми
     * же параметрами продолжит с места падения, а не с начала.
     */
    data class Failed(val error: Throwable) : JobState<Nothing>

    /**
     * Упала по временной причине и ждёт следующей попытки: сети нет, сервис
     * ответил отказом «слишком часто» или молчит.
     *
     * Это не конец работы, а пауза: тот, кто ждёт результата, продолжает ждать.
     * Для показывающего экрана такая задача — по-прежнему «делается», просто
     * дольше обычного.
     *
     * @param attempt какая попытка провалилась, считая с первой.
     */
    data class Waiting(val error: Throwable, val attempt: Int) : JobState<Nothing>

    /** Отменена — либо вызывающей стороной, либо системой, забравшей время. */
    data object Cancelled : JobState<Nothing>

    /**
     * Работа кончилась — тем или иным способом. Ожидание следующей попытки
     * концом не считается: задача ещё сделается.
     */
    val isFinished: Boolean
        get() = this !is Running && this !is Waiting
}
