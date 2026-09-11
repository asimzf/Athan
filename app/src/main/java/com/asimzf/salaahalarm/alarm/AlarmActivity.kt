package com.asimzf.salaahalarm.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.AppStore
import com.asimzf.salaahalarm.ui.theme.SalaahAlarmTheme
import java.lang.ref.WeakReference
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The full-screen "your alarm is going off" screen. Shows over the lock screen. */
class AlarmActivity : ComponentActivity() {

    companion object {
        private var showing: WeakReference<AlarmActivity>? = null

        fun intent(context: Context, ruleId: Int): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_RULE_ID, ruleId)
                .setData(android.net.Uri.parse("salaahalarm://ringing/$ruleId"))

        /** Called by the service once the alarm is answered elsewhere (notification action). */
        fun finishIfShowing() {
            showing?.get()?.let { activity ->
                activity.runOnUiThread { activity.finish() }
            }
            showing = null
        }
    }

    private var ruleId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = WeakReference(this)
        ruleId = intent.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, -1)
        showOverLockScreen()

        // Back must not silently dismiss a ringing alarm; only the buttons answer it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            SalaahAlarmTheme {
                var rule by remember { mutableStateOf<AlarmRule?>(null) }
                LaunchedEffect(ruleId) {
                    rule = AppStore(applicationContext).current().alarms.firstOrNull { it.id == ruleId }
                }
                RingingScreen(
                    rule = rule,
                    onSnooze = { send(AlarmReceiver.ACTION_SNOOZE) },
                    onDismiss = { send(AlarmReceiver.ACTION_DISMISS) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ruleId = intent.getIntExtra(AlarmScheduler.EXTRA_RULE_ID, ruleId)
    }

    private fun send(action: String) {
        // Broadcast rather than startService: the receiver stops the service outright,
        // which the OS never refuses, whereas starting one can be background-blocked.
        sendBroadcast(AlarmReceiver.controlIntent(this, action, ruleId))
        finish()
    }

    /**
     * A physical volume key dismisses, the way every other alarm clock behaves. This is
     * the last line of defence: it works even if the buttons fail to draw.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_HEADSETHOOK -> {
            send(AlarmReceiver.ACTION_DISMISS)
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        if (showing?.get() === this) showing = null
        super.onDestroy()
    }
}

@Composable
private fun RingingScreen(
    rule: AlarmRule?,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clock = remember {
        ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = clock,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = rule?.displayLabel ?: "Alarm",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
            rule?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it.offsetDescription(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(64.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text("Dismiss", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(),
            ) {
                Text("Snooze ${rule?.snoozeMinutes ?: 10} min")
            }
        }
    }
}
