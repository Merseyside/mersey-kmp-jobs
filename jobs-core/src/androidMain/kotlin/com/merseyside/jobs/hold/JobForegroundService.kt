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
 * The foreground service: the whole thing was started for its sake.
 *
 * While it is alive, the system considers the app busy with something and does
 * not unload it — even when the user has gone to another screen. The service
 * has no logic of its own: the work is done by the runtime's coroutines, and
 * the service only holds the process.
 */
class JobForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStarted.value = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.readNotification() ?: lastNotification

        // The notification is always shown, even when there is nothing to show.
        // A service raised through startForegroundService that fails to do so
        // within five seconds brings the whole app down — it cannot stop quietly
        showForeground(notification ?: PLACEHOLDER)

        if (notification == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Remembered in case the system raises the service without our data
        lastNotification = notification

        // The process was killed along with the service — there is no point in
        // raising it by itself: there are no coroutines with the work in the new
        // process anyway. Unfinished jobs will be revived by the runtime when
        // the app is opened again
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
         * Whether the service has reached the foreground.
         *
         * Needed by whoever raises it: a stop that came earlier than
         * [ServiceCompat.startForeground] brings the whole app down — the
         * system decides the service never started its work. And a short job
         * manages to end faster than the system delivers the start command to
         * the service.
         */
        val isStarted = MutableStateFlow(false)

        /**
         * The last notification description. Lives in the process, not in the
         * job: if the system raises the service without our data, something
         * still has to be shown, and the same thing is better than a stub.
         */
        private var lastNotification: JobNotification? = null

        /**
         * The spare notification for the case when there is no description at
         * all. Lives for a fraction of a second: the service shows it and stops
         * right away.
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
