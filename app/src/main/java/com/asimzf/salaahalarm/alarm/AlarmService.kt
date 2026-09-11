package com.asimzf.salaahalarm.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.asimzf.salaahalarm.R
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Holds the ringing state: audio on the alarm stream, vibration, a wake lock, and the
 * full-screen notification that puts [AlarmActivity] on the lock screen.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_START = "start"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SNOOZE = "snooze"

        /** Give up after this long so an unanswered alarm cannot drain the battery. */
        private const val AUTO_STOP_MINUTES = 10L
        private const val FADE_IN_SECONDS = 5

        private val VIBRATE_PATTERN = longArrayOf(0, 500, 500, 500, 1000)

        fun intent(context: Context, action: String, ruleId: Int): Intent =
            Intent(context, AlarmService::class.java)
                .setAction(action)
                .putExtra(AlarmScheduler.EXTRA_RULE_ID, ruleId)
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStopJob: Job? = null
    private var fadeJob: Job? = null
    private var activeRule: AlarmRule? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, -1) ?: -1

        when (intent?.action) {
            ACTION_DISMISS -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                snoozeAndStop(ruleId)
                return START_NOT_STICKY
            }
        }

        if (ruleId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A placeholder notification goes up immediately: startForeground must happen
        // within seconds of the service starting, well before the rule can be loaded.
        startForegroundCompat(buildNotification(null))

        scope.launch {
            val rule = AppStore(applicationContext).current().alarms.firstOrNull { it.id == ruleId }
            if (rule == null) {
                Log.w(TAG, "Rule $ruleId vanished before it could ring")
                stopEverything()
                return@launch
            }
            activeRule = rule
            startForegroundCompat(buildNotification(rule))
            beginRinging(rule)
        }

        return START_NOT_STICKY
    }

    /** Snooze has to work even if the process was rebuilt and [activeRule] is gone. */
    private fun snoozeAndStop(ruleId: Int) {
        val known = activeRule
        if (known != null) {
            AlarmScheduler(this).snooze(known, known.snoozeMinutes)
            stopEverything()
            return
        }
        // Silence first, resolve the rule after; nobody should wait on disk to stop a noise.
        silence()
        scope.launch {
            AppStore(applicationContext).current().alarms.firstOrNull { it.id == ruleId }
                ?.let { AlarmScheduler(this@AlarmService).snooze(it, it.snoozeMinutes) }
            stopEverything()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.NOTIF_RINGING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Notifications.NOTIF_RINGING, notification)
        }
    }

    private fun beginRinging(rule: AlarmRule) {
        acquireWakeLock()
        startAudio(rule)
        if (rule.vibrate) startVibration()

        // The full-screen intent is not reliable once the device is unlocked and in use,
        // so launch the ringing screen directly too.
        runCatching {
            startActivity(
                AlarmActivity.intent(this, rule.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }.onFailure { Log.w(TAG, "Could not launch ringing screen; notification still shown", it) }

        autoStopJob = scope.launch {
            delay(AUTO_STOP_MINUTES * 60 * 1000)
            Log.i(TAG, "Auto-stopping rule ${rule.id}: unanswered for $AUTO_STOP_MINUTES min")
            stopEverything()
        }
    }

    private fun startAudio(rule: AlarmRule) {
        val uri: Uri = rule.ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: run {
                Log.w(TAG, "No alarm sound available on this device")
                return
            }

        raiseAlarmStreamIfMuted()

        // USAGE_ALARM routes to the alarm stream, which plays through ringer-silent and
        // sits in Do Not Disturb's default "alarms" exception.
        val mediaPlayer = MediaPlayer()
        val started = runCatching {
            mediaPlayer.setAudioAttributes(Notifications.alarmAudioAttributes)
            mediaPlayer.setDataSource(this, uri)
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.prepare()
            mediaPlayer.start()
        }.isSuccess

        if (!started) {
            Log.e(TAG, "Could not start alarm audio for rule ${rule.id}")
            runCatching { mediaPlayer.release() }
            return
        }

        player = mediaPlayer
        fadeJob = scope.launch {
            val steps = FADE_IN_SECONDS * 4
            for (step in 1..steps) {
                val level = step.toFloat() / steps
                if (runCatching { mediaPlayer.setVolume(level, level) }.isFailure) return@launch
                delay(250)
            }
        }
    }

    /** A muted alarm stream means a silent alarm, which defeats the whole app. */
    private fun raiseAlarmStreamIfMuted() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                val target = (audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.6f)
                    .toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        }.onFailure { Log.w(TAG, "Could not raise alarm volume", it) }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(VIBRATE_PATTERN, 0),
                Notifications.alarmAudioAttributes,
            )
        }
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SalaahAlarm:ringing")
            ?.apply { acquire((AUTO_STOP_MINUTES + 1) * 60 * 1000) }
    }

    private fun buildNotification(rule: AlarmRule?): Notification {
        val title = rule?.displayLabel ?: "Salaah alarm"
        val now = ZonedDateTime.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

        val fullScreen = PendingIntent.getActivity(
            this,
            rule?.id ?: 0,
            AlarmActivity.intent(this, rule?.id ?: -1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(rule?.offsetDescription() ?: "Ringing now")
            .setSubText(now)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)

        if (rule != null) {
            builder.addAction(
                0,
                "Snooze ${rule.snoozeMinutes} min",
                servicePendingIntent(ACTION_SNOOZE, rule.id),
            )
            builder.addAction(0, "Dismiss", servicePendingIntent(ACTION_DISMISS, rule.id))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, ruleId: Int): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode() + ruleId,
            intent(this, action, ruleId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Stops noise and motion but leaves the service up. */
    private fun silence() {
        fadeJob?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun stopEverything() {
        autoStopJob?.cancel()
        silence()
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null
        AlarmActivity.finishIfShowing()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        autoStopJob?.cancel()
        silence()
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }
}
