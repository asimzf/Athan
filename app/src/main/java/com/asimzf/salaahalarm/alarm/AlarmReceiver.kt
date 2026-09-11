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

/**
 * Receives the AlarmManager callback. Two jobs, in this order:
 *   1. get the ringing service started, because the receiver's window is short
 *   2. re-arm the rule for its next day
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> handleFire(context, intent)
            AlarmScheduler.ACTION_REFRESH -> handleRefresh(context)
            else -> Log.w(TAG, "Ignoring unexpected action ${intent.action}")
        }
    }

    private fun handleFire(context: Context, intent: Intent) {
        val ruleId = intent.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, -1)
        if (ruleId < 0) return

        // Start ringing synchronously. Being launched by an exact alarm is an explicit
        // exemption from the Android 12+ background foreground-service start restriction.
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
                AlarmScheduler(context.applicationContext).syncFromStore(store)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to re-arm after firing rule $ruleId", t)
            } finally {
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

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
