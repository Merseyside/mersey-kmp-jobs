package com.merseyside.jobs.hold

import androidx.annotation.DrawableRes

/**
 * Уведомление сервиса, который держит процесс живым.
 *
 * Android показывает его всё время работы задач — это условие, на котором
 * система соглашается не убивать приложение. Тексты и значок приходят из
 * приложения: своих строк библиотека не имеет и переводить их не умеет.
 *
 * @param channelId постоянное имя канала уведомлений.
 * @param channelName как канал называется в системных настройках.
 */
data class JobNotification(
    val channelId: String,
    val channelName: String,
    val title: String,
    val text: String,
    @DrawableRes val icon: Int
)
