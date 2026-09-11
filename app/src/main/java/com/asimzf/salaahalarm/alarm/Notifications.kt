package com.asimzf.salaahalarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build

object Notifications {

    const val CHANNEL_RINGING = "ringing"
    const val CHANNEL_STATUS = "status"
    const val NOTIF_RINGING = 1001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        // No sound on the channel: the foreground service owns audio playback so that it
        // can use USAGE_ALARM, fade in, and stop cleanly on dismiss.
        val ringing = NotificationChannel(
            CHANNEL_RINGING,
            "Ringing alarm",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shown while a salaah alarm is going off."
            setSound(null, null)
            enableVibration(false)
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

    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
}
