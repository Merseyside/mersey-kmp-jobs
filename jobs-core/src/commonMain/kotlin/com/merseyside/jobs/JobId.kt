package com.merseyside.jobs

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * The mark identifying a started job. Outlives the process: by it unfinished
 * work finds its saved steps after a restart.
 */
@JvmInline
value class JobId(val value: String) {

    override fun toString(): String = value

    companion object {

        /**
         * A random mark. Long enough for two jobs not to collide, and with no
         * claim to cryptographic strength: it protects nobody.
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
