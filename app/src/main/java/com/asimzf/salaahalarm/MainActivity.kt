package com.asimzf.salaahalarm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asimzf.salaahalarm.ui.AlarmEditScreen
import com.asimzf.salaahalarm.ui.AlarmListScreen
import com.asimzf.salaahalarm.ui.MainViewModel
import com.asimzf.salaahalarm.ui.Screen
import com.asimzf.salaahalarm.alarm.SetupStatus
import com.asimzf.salaahalarm.ui.SetupFix
import com.asimzf.salaahalarm.ui.SettingsScreen
import com.asimzf.salaahalarm.ui.theme.SalaahAlarmTheme

class MainActivity : ComponentActivity() {

    private fun launchSetupFix(fix: SetupFix) {
        val intent = when (fix) {
            SetupFix.NOTIFICATIONS -> SetupStatus.notificationSettings(this)
            SetupFix.EXACT_ALARMS -> SetupStatus.exactAlarmSettings(this)
            SetupFix.FULL_SCREEN -> SetupStatus.fullScreenIntentSettings(this)
            SetupFix.OVERLAY -> SetupStatus.overlaySettings(this)
            SetupFix.BATTERY -> SetupStatus.batterySettings(this)
        } ?: return
        runCatching { startActivity(intent) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SalaahAlarmTheme {
                val viewModel: MainViewModel = viewModel()
                val screen by viewModel.screen.collectAsState()
                val state by viewModel.state.collectAsState()
                val ringingRuleId by viewModel.ringingRuleId.collectAsState()
                val setup by viewModel.setup.collectAsState()

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted.values.any { it }) viewModel.refreshLocation()
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshSetup()
                            viewModel.resync()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    permissions.launch(wanted.toTypedArray())
                }

                when (val current = screen) {
                    is Screen.List -> AlarmListScreen(
                        state = state,
                        setup = setup,
                        ringingRuleId = ringingRuleId,
                        onStopRinging = viewModel::stopRingingAlarm,
                        onFixSetup = { fix -> launchSetupFix(fix) },
                        onAdd = { viewModel.navigate(Screen.Edit(null)) },
                        onEdit = { viewModel.navigate(Screen.Edit(it.id)) },
                        onToggle = viewModel::setEnabled,
                        onSkipNext = viewModel::setSkipNext,
                        onOpenSettings = { viewModel.navigate(Screen.Settings) },
                    )

                    is Screen.Edit -> AlarmEditScreen(
                        viewModel = viewModel,
                        ruleId = current.ruleId,
                        onDone = viewModel::back,
                    )

                    is Screen.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        state = state,
                        onBack = viewModel::back,
                    )
                }
            }
        }
    }
}
