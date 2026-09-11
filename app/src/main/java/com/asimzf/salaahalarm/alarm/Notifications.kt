package com.asimzf.salaahalarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes

object Notifications {

    // Channel settings are immutable once created, so changing them needs a new id and
    // a delete of the old one. v1 was silent with vibration disabled, which made the
    // notification ineligible for a heads-up banner: while the phone was in use, and with
    // the full-screen intent demoted, the alarm reached only the pull-down shade.
    const val CHANNEL_RINGING = "ringing_v2"
    private const val CHANNEL_RINGING_LEGACY = "ringing"
    const val CHANNEL_STATUS = "status"
    const val NOTIF_RINGING = 1001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.deleteNotificationChannel(CHANNEL_RINGING_LEGACY)

        // Still no sound: the foreground service owns audio so it can use USAGE_ALARM,
        // loop, fade in and stop cleanly. Vibration is enabled purely so the notification
        // counts as non-silent and is therefore allowed to appear as a heads-up banner
        // rather than going straight to the shade. The sustained buzz is still the
        // service's; this pattern is a single short pulse.
        val ringing = NotificationChannel(
            CHANNEL_RINGING,
            "Ringing alarm",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shown while a salaah alarm is going off."
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Alarm status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Quiet notices about scheduling, e.g. missed or skipped alarms."
            setSound(null, null)
        }

        manager.createNotificationChannel(ringing)
        manager.createNotificationChannel(status)
    }

    val alarmAudioAttributes: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}
