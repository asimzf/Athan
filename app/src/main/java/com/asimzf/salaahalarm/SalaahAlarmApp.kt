package com.asimzf.salaahalarm

import android.app.Application
import com.asimzf.salaahalarm.alarm.AlarmScheduler
import com.asimzf.salaahalarm.alarm.Notifications
import com.asimzf.salaahalarm.data.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SalaahAlarmApp : Application() {

    lateinit var store: AppStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        Notifications.ensureChannels(this)

        // Re-derive on every cold start: settings, timezone or the date may have moved
        // since the alarms were last registered.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { AlarmScheduler(this@SalaahAlarmApp).syncFromStore(store) }
        }
    }
}
