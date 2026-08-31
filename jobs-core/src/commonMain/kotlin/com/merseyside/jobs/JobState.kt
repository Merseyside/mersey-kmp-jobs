package com.merseyside.jobs

/**
 * Чем задача занята с точки зрения того, кто её ждёт.
 */
sealed interface JobState<out R : Any> {

    /** Работает. Доклада может ещё не быть — задача не обязана его слать. */
    data class Running(val progress: JobProgress?) : JobState<Nothing>

    data class Success<out R : Any>(val result: R) : JobState<R>

    /**
     * Упала. Пройденные шаги остались в хранилище: повторный запуск с теми же
     * параметрами продолжит с места падения, а не с начала.
     */
    data class Failed(val error: Throwable) : JobState<Nothing>

    /** Отменена — либо вызывающей стороной, либо системой, забравшей время. */
    data object Cancelled : JobState<Nothing>

    val isFinished: Boolean
        get() = this !is Running
}
