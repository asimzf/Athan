package com.asimzf.salaahalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.asimzf.salaahalarm.data.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot, and a timezone or clock change invalidates every
 * computed trigger. Both mean: recompute from scratch.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Rescheduling after ${intent.action}")
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Notifications.ensureChannels(appContext)
                AlarmScheduler(appContext).syncFromStore(AppStore(appContext))
            } catch (t: Throwable) {
                Log.e("BootReceiver", "Reschedule failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
