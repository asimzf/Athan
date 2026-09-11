package com.asimzf.salaahalarm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asimzf.salaahalarm.ui.AlarmEditScreen
import com.asimzf.salaahalarm.ui.AlarmListScreen
import com.asimzf.salaahalarm.ui.MainViewModel
import com.asimzf.salaahalarm.ui.Screen
import com.asimzf.salaahalarm.ui.SettingsScreen
import com.asimzf.salaahalarm.ui.theme.SalaahAlarmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SalaahAlarmTheme {
                val viewModel: MainViewModel = viewModel()
                val screen by viewModel.screen.collectAsState()
                val state by viewModel.state.collectAsState()

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted.values.any { it }) viewModel.refreshLocation()
                }

                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    permissions.launch(wanted.toTypedArray())
                    // Times shift with the date; re-derive whenever the app is opened.
                    viewModel.resync()
                }

                when (val current = screen) {
                    is Screen.List -> AlarmListScreen(
                        state = state,
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
