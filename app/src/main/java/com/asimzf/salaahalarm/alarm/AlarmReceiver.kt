package com.asimzf.salaahalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.asimzf.salaahalarm.data.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Receives the AlarmManager callback and the notification's action buttons.
 *
 * Everything that must happen while the exact-alarm background-activity-start grace
 * window is still open happens synchronously here. Anything asynchronous (reading the
 * rule, re-arming) happens afterwards under goAsync.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> handleFire(context, intent)
            AlarmScheduler.ACTION_REFRESH -> handleRefresh(context)
            ACTION_DISMISS -> handleDismiss(context)
            ACTION_SNOOZE -> handleSnooze(context, intent)
            else -> Log.w(TAG, "Ignoring unexpected action ${intent.action}")
        }
    }

    private fun handleFire(context: Context, intent: Intent) {
        val ruleId = intent.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, -1)
        if (ruleId < 0) return
        val firedAt = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)

        // Order matters. Firing from an exact alarm grants this receiver a short
        // background-activity-start allowance; launching the ringing screen from the
        // service later — after a disk read — falls outside it and is silently blocked.
        // So start the activity here, synchronously, while the grant still holds.
        runCatching {
            context.startActivity(
                AlarmActivity.intent(context, ruleId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure { Log.w(TAG, "Ringing screen blocked; notification is the fallback", it) }

        val serviceIntent = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra(AlarmScheduler.EXTRA_RULE_ID, ruleId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = AppStore(context.applicationContext)
                // Consume a one-shot "skip next" so the rule returns to normal tomorrow.
                val rule = store.current().alarms.firstOrNull { it.id == ruleId }
                if (rule?.skipNext == true) store.setSkipNext(ruleId, false)

                // Never re-arm the occurrence that just fired. Without this floor an
                // early delivery re-schedules the same instant and the alarm loops.
                val floor = if (firedAt > 0L) {
                    Instant.ofEpochMilli(firedAt).plusSeconds(1)
                } else {
                    Instant.now().plusSeconds(60)
                }
                AlarmScheduler(context.applicationContext)
                    .syncFromStore(store, mapOf(ruleId to floor))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to re-arm after firing rule $ruleId", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Stopping a service has no background restriction, unlike starting one, so this is
     * the one dismissal path the OS cannot refuse. AlarmService.onDestroy does the
     * silencing, which means dismissal works even if the service is in a state where it
     * would not process a fresh start command.
     */
    private fun handleDismiss(context: Context) {
        AlarmService.silenceRunningInstance()
        context.stopService(Intent(context, AlarmService::class.java))
    }

    private fun handleSnooze(context: Context, intent: Intent) {
        val ruleId = intent.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, -1)

        // Silence first: nobody should wait on a disk read to stop a noise.
        AlarmService.silenceRunningInstance()

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (ruleId >= 0) {
                    AppStore(context.applicationContext).current().alarms
                        .firstOrNull { it.id == ruleId }
                        ?.let { AlarmScheduler(context.applicationContext).snooze(it, it.snoozeMinutes) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to snooze rule $ruleId", t)
            } finally {
                context.stopService(Intent(context, AlarmService::class.java))
                pending.finish()
            }
        }
    }

    private fun handleRefresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AlarmScheduler(context.applicationContext)
                    .syncFromStore(AppStore(context.applicationContext))
            } catch (t: Throwable) {
                Log.e(TAG, "Daily refresh failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        const val ACTION_DISMISS = "com.asimzf.salaahalarm.DISMISS"
        const val ACTION_SNOOZE = "com.asimzf.salaahalarm.SNOOZE"

        /**
         * Notification and ringing-screen controls go through a broadcast rather than
         * startService: broadcasts are never refused for background state.
         */
        fun controlIntent(context: Context, action: String, ruleId: Int): Intent =
            Intent(context, AlarmReceiver::class.java)
                .setAction(action)
                .setData(android.net.Uri.parse("salaahalarm://control/$action/$ruleId"))
                .putExtra(AlarmScheduler.EXTRA_RULE_ID, ruleId)
    }
}
