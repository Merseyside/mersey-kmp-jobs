package com.merseyside.jobs.hold

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Сервис на переднем плане: ради него всё и затевалось.
 *
 * Пока он жив, система считает приложение занятым делом и не выгружает его —
 * даже когда пользователь ушёл на другой экран. Своей логики у сервиса нет:
 * работу выполняют корутины рантайма, а сервис только держит процесс.
 */
class JobForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStarted.value = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.readNotification() ?: lastNotification

        // Уведомление показывается всегда, даже когда показывать нечего.
        // Сервис, поднятый через startForegroundService и не сделавший этого за
        // пять секунд, валит приложение целиком — остановиться молча нельзя
        showForeground(notification ?: PLACEHOLDER)

        if (notification == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Запоминаем на случай, если система поднимет сервис без наших данных
        lastNotification = notification

        // Процесс убили вместе с сервисом — поднимать его самому незачем:
        // корутин с работой в новом процессе всё равно нет. Незавершённые
        // задачи поднимет рантайм, когда приложение откроют снова
        return START_NOT_STICKY
    }

    private fun showForeground(notification: JobNotification) {
        val manager = NotificationManagerCompat.from(this)

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                notification.channelId,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
                .setName(notification.channelName)
                .build()
        )

        val built = NotificationCompat.Builder(this, notification.channelId)
            .setContentTitle(notification.title)
            .setContentText(notification.text)
            .setSmallIcon(notification.icon)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            built,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        isStarted.value = true
    }

    private fun Intent.readNotification(): JobNotification? {
        val channelId = getStringExtra(EXTRA_CHANNEL_ID) ?: return null
        val channelName = getStringExtra(EXTRA_CHANNEL_NAME) ?: return null
        val title = getStringExtra(EXTRA_TITLE) ?: return null
        val text = getStringExtra(EXTRA_TEXT) ?: return null
        val icon = getIntExtra(EXTRA_ICON, 0).takeIf { value -> value != 0 } ?: return null

        return JobNotification(channelId, channelName, title, text, icon)
    }

    internal companion object {

        /**
         * Вышел ли сервис на передний план.
         *
         * Нужно тому, кто его поднимает: остановка, пришедшая раньше
         * [ServiceCompat.startForeground], валит приложение целиком — система
         * считает, что сервис так и не начал работу. А короткая задача успевает
         * кончиться быстрее, чем система донесёт до сервиса команду запуска.
         */
        val isStarted = MutableStateFlow(false)

        /**
         * Последнее описание уведомления. Живёт в процессе, а не в задаче: если
         * система поднимет сервис без наших данных, показывать всё равно что-то
         * нужно, и лучше то же самое, чем заглушку.
         */
        private var lastNotification: JobNotification? = null

        /**
         * Запасное уведомление на случай, когда описания нет вовсе. Живёт доли
         * секунды: сервис показывает его и тут же останавливается.
         */
        private val PLACEHOLDER = JobNotification(
            channelId = "jobs",
            channelName = "Background work",
            title = "",
            text = "",
            icon = android.R.drawable.stat_notify_sync
        )

        fun intent(context: android.content.Context, notification: JobNotification): Intent =
            Intent(context, JobForegroundService::class.java)
                .putExtra(EXTRA_CHANNEL_ID, notification.channelId)
                .putExtra(EXTRA_CHANNEL_NAME, notification.channelName)
                .putExtra(EXTRA_TITLE, notification.title)
                .putExtra(EXTRA_TEXT, notification.text)
                .putExtra(EXTRA_ICON, notification.icon)

        private const val NOTIFICATION_ID = 4711

        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ICON = "icon"
    }
}
