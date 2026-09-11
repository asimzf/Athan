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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        const val ACTION_START = "com.asimzf.salaahalarm.START_RINGING"
        const val ACTION_DISMISS = "com.asimzf.salaahalarm.STOP_RINGING"

        /** Give up after this long so an unanswered alarm cannot drain the battery. */
        private const val AUTO_STOP_MINUTES = 5L
        private const val FADE_IN_SECONDS = 5

        private val VIBRATE_PATTERN = longArrayOf(0, 500, 500, 500, 1000)

        /**
         * The live instance, so a dismissal can silence the noise directly instead of
         * relying on an Intent being delivered. Weak coupling on purpose: if the service
         * is already gone there is nothing to stop and this is a no-op.
         */
        @Volatile
        private var running: AlarmService? = null

        private val _ringingRuleId = MutableStateFlow<Int?>(null)

        /** Non-null while an alarm is actually sounding; drives the in-app stop banner. */
        val ringingRuleId: StateFlow<Int?> = _ringingRuleId.asStateFlow()

        /** Stops noise and vibration immediately, without waiting on service lifecycle. */
        fun silenceRunningInstance() {
            running?.silence()
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStopJob: Job? = null
    private var fadeJob: Job? = null
    private var activeRule: AlarmRule? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, -1) ?: -1

        if (intent?.action == ACTION_DISMISS) {
            stopEverything()
            return START_NOT_STICKY
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
        // Starting is idempotent on purpose. onStartCommand can run more than once —
        // a second alarm, a redelivery, or the same alarm re-fired — and the old code
        // simply reassigned `player`, leaving the previous MediaPlayer looping forever
        // with nothing holding a reference to it. Dismiss then stopped the tracked
        // player while the orphan kept sounding.
        silence()
        autoStopJob?.cancel()

        acquireWakeLock()
        startAudio(rule)
        if (rule.vibrate) startVibration()
        _ringingRuleId.value = rule.id

        // No startActivity here: a foreground service has no background-activity-start
        // privilege on Android 10+, so this silently did nothing. AlarmReceiver launches
        // the screen instead, while the exact-alarm grant is still open.

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
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
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

        // Dismiss is added even for the placeholder notification: stopping the noise
        // needs no knowledge of which rule it was, and the rule may still be loading or
        // may fail to load entirely. There must never be a ringing state without a stop.
        builder.addAction(
            R.drawable.ic_alarm_off,
            "Dismiss",
            controlPendingIntent(AlarmReceiver.ACTION_DISMISS, rule?.id ?: -1),
        )
        if (rule != null) {
            builder.addAction(
                R.drawable.ic_snooze,
                "Snooze ${rule.snoozeMinutes} min",
                controlPendingIntent(AlarmReceiver.ACTION_SNOOZE, rule.id),
            )
        }
        return builder.build()
    }

    /**
     * Broadcast, not getService: tapping a notification action can land while the app is
     * background-restricted, and startService is refused there while a broadcast is not.
     */
    private fun controlPendingIntent(action: String, ruleId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            action.hashCode() + ruleId,
            AlarmReceiver.controlIntent(this, action, ruleId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Stops noise and motion but leaves the service up. Safe to call repeatedly. */
    private fun silence() {
        fadeJob?.cancel()
        fadeJob = null

        player?.let { mp ->
            player = null
            runCatching { mp.setVolume(0f, 0f) }
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }

        runCatching { vibrator?.cancel() }
        vibrator = null
        // Also cancel through the manager: on API 31+ this covers any vibration this
        // app started that the Vibrator handle alone may not own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { getSystemService(VibratorManager::class.java)?.cancel() }
        }

        _ringingRuleId.value = null
    }

    private fun stopEverything() {
        _ringingRuleId.value = null
        autoStopJob?.cancel()
        silence()
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null
        AlarmActivity.finishIfShowing()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = null
        autoStopJob?.cancel()
        silence()
        AlarmActivity.finishIfShowing()
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }
}
