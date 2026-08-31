package com.merseyside.jobs.hold

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Удержание процесса на iOS — отсрочка засыпания.
 *
 * Сервисов, работающих сколько нужно, на iOS нет и не будет. Есть просьба
 * «не усыпляй меня прямо сейчас»: система даёт на неё считанные десятки секунд
 * после ухода приложения в фон, а сколько именно — решает сама.
 *
 * Поэтому это не фоновая работа, а возможность доделать начатое и сохранить
 * шаг. Когда время подходит к концу, iOS зовёт обработчик истечения — рантайм
 * получает его через [expirations] и сворачивает задачи сам. Не свернуть их
 * вовремя хуже: тогда систему убивает приложение целиком.
 */
class IosProcessHold : ProcessHold {

    private val mutableExpirations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val expirations: Flow<Unit> = mutableExpirations.asSharedFlow()

    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    /** UIApplication отвечает только главному потоку — отсюда переключение. */
    override suspend fun acquire() = withContext(Dispatchers.Main) {
        if (taskId != UIBackgroundTaskInvalid) return@withContext

        taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(TASK_NAME) {
            mutableExpirations.tryEmit(Unit)
            endTask()
        }
    }

    override suspend fun release() = withContext(Dispatchers.Main) { endTask() }

    private fun endTask() {
        if (taskId == UIBackgroundTaskInvalid) return

        UIApplication.sharedApplication.endBackgroundTask(taskId)
        taskId = UIBackgroundTaskInvalid
    }

    private companion object {

        const val TASK_NAME = "com.merseyside.jobs"
    }
}
