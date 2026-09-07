package com.merseyside.jobs.hold

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Удержание процесса на Android — сервис на переднем плане.
 *
 * От приложения требуется одно: пока идут задачи, оно должно быть на экране в
 * момент запуска. Начиная с Android 12 сервис на переднем плане нельзя поднять
 * из фона, поэтому JobRunner.restore() вызывают при возвращении приложения на
 * экран, а не откуда придётся.
 *
 * Само уведомление на Android 13 и новее покажется, только если пользователь
 * разрешил уведомления. Разрешение спрашивает приложение; без него сервис всё
 * равно работает, просто молча.
 */
class AndroidProcessHold(
    context: Context,
    private val notification: suspend () -> JobNotification
) : ProcessHold {

    // Хранится контекст приложения: сервис живёт дольше любого экрана
    private val context = context.applicationContext

    override suspend fun acquire() {
        // Описание уведомления запрашивается здесь, а не в конструкторе: строки
        // приложения читаются приостанавливаемо, и на разных языках они разные
        ContextCompat.startForegroundService(
            context,
            JobForegroundService.intent(context, notification())
        )

        // Удержание взято не тогда, когда сервис попросили подняться, а когда
        // он поднялся. Разница важна для короткой работы: она успевает
        // кончиться раньше старта сервиса, и остановка приходит до того, как
        // тот вышел на передний план, — за это система валит приложение целиком
        withTimeoutOrNull(START_TIMEOUT_MILLIS) {
            JobForegroundService.isStarted.first { started -> started }
        }
    }

    override suspend fun release() {
        context.stopService(Intent(context, JobForegroundService::class.java))
    }

    private companion object {

        /**
         * Сколько ждать выхода сервиса на передний план. Обычно это десятки
         * миллисекунд; предел стоит на случай, когда система запуск не дала —
         * ждать её вечно нельзя, работа всё равно должна идти.
         */
        const val START_TIMEOUT_MILLIS = 5_000L
    }
}
