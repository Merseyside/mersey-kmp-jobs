package com.merseyside.jobs

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Опознавательный знак запущенной задачи. Живёт дольше процесса: по нему
 * незавершённая работа находит свои сохранённые шаги после перезапуска.
 */
@JvmInline
value class JobId(val value: String) {

    override fun toString(): String = value

    companion object {

        /**
         * Случайный знак. Достаточно длинный, чтобы две задачи не столкнулись,
         * и не претендующий на криптографическую стойкость: он никого не защищает.
         */
        fun random(): JobId {
            val value = (0 until LENGTH)
                .map { ALPHABET[Random.nextInt(ALPHABET.length)] }
                .joinToString(separator = "")

            return JobId(value)
        }

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val LENGTH = 16
    }
}
