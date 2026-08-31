package com.merseyside.jobs

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * То, что задача получает внутрь себя. Через него она размечает свою работу на
 * шаги и докладывает о ходе дела.
 */
interface JobScope {

    val jobId: JobId

    /**
     * Шаг с контрольной точкой: результат ложится в хранилище, и при повторном
     * проходе тело не выполняется вовсе — вернётся сохранённое.
     *
     * @param key имя шага. Должно совпадать от прохода к проходу, иначе
     * сохранённый результат не найдётся. Шаги в цикле нумеруются вручную.
     */
    suspend fun <T> step(key: String, serializer: KSerializer<T>, body: suspend () -> T): T

    /**
     * Шаг без хранилища: результат остаётся только в памяти.
     *
     * Для данных, которые правила поставщика разрешают держать лишь на время
     * показа — каталоги музыки, например. Пока процесс жив, повторный запуск
     * задачи такой шаг не переиграет; после перезапуска он выполнится заново.
     */
    suspend fun <T> memoryStep(key: String, body: suspend () -> T): T

    /** Рассказать, чем сейчас занята. Доезжает до экрана как есть. */
    suspend fun report(progress: JobProgress)
}

/**
 * То же, что [JobScope.step], но сериализатор выводится из типа.
 */
suspend inline fun <reified T> JobScope.step(
    key: String,
    noinline body: suspend () -> T
): T = step(key, serializer(), body)
