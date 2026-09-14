package com.merseyside.jobs.hold

import androidx.annotation.DrawableRes

/**
 * The notification of the service that keeps the process alive.
 *
 * Android shows it for the whole time the jobs are running — that is the
 * condition on which the system agrees not to kill the app. The texts and the
 * icon come from the app: the library has no strings of its own and cannot
 * translate them.
 *
 * @param channelId the permanent name of the notification channel.
 * @param channelName how the channel is called in the system settings.
 */
data class JobNotification(
    val channelId: String,
    val channelName: String,
    val title: String,
    val text: String,
    @DrawableRes val icon: Int
)
