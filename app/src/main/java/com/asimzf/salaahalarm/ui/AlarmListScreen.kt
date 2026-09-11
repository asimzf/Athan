package com.asimzf.salaahalarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.asimzf.salaahalarm.alarm.SetupStatus
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.PrayerAnchor
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    state: ListUiState,
    setup: SetupStatus,
    ringingRuleId: Int?,
    onStopRinging: () -> Unit,
    onFixSetup: (SetupFix) -> Unit,
    onAdd: () -> Unit,
    onEdit: (AlarmRule) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onSkipNext: (Int, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Salaah Alarm") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add alarm")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ringingRuleId != null) {
                item { StopRingingCard(onStopRinging) }
            }

            item { TodayCard(state) }

            if (!setup.allClear) {
                item { SetupCard(setup, onFixSetup) }
            }

            if (state.alarms.isEmpty()) {
                item { EmptyState() }
            }

            items(state.alarms, key = { it.id }) { rule ->
                AlarmCard(
                    rule = rule,
                    nextTrigger = state.nextTriggers[rule.id],
                    onClick = { onEdit(rule) },
                    onToggle = { onToggle(rule.id, it) },
                    onSkipNext = { onSkipNext(rule.id, it) },
                )
            }
        }
    }
}

@Composable
private fun TodayCard(state: ListUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = state.settings.locationLabel,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Today's times",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val today = state.today
            if (today == null) {
                Text(
                    text = "Times are undefined here today — the sun does not reach the " +
                        "required angle. Pick a high-latitude rule in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PrayerAnchor.values().forEach { anchor ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = anchor.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = today[anchor]?.timeOfDay() ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Opening the app is the guaranteed way to stop an alarm, whatever else the OS blocks. */
@Composable
private fun StopRingingCard(onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "An alarm is ringing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onStop) { Text("Stop") }
        }
    }
}

/**
 * Everything the OS has to allow before an alarm can actually be seen and stopped.
 * Notifications denied is the dangerous one: the alarm still sounds, but nothing is
 * shown, so it is called out as blocking rather than advisory.
 */
@Composable
private fun SetupCard(setup: SetupStatus, onFix: (SetupFix) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (setup.isBlocking) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (setup.isBlocking) "Alarms will not work yet" else "Worth fixing",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (setup.isBlocking) {
                    "Android is blocking something an alarm needs."
                } else {
                    "Alarms work, but these make them more reliable."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (!setup.notificationsEnabled) {
                SetupRow(
                    title = "Allow notifications",
                    detail = "Without this an alarm still rings but shows nothing at all — " +
                        "no ringing screen and no buttons to stop it.",
                    onFix = { onFix(SetupFix.NOTIFICATIONS) },
                )
            }
            if (!setup.exactAlarmsAllowed) {
                SetupRow(
                    title = "Allow alarms & reminders",
                    detail = "Without this alarms fire late, by minutes or more.",
                    onFix = { onFix(SetupFix.EXACT_ALARMS) },
                )
            }
            if (!setup.fullScreenIntentAllowed) {
                SetupRow(
                    title = "Allow full-screen notifications",
                    detail = "Lets the alarm screen take over a locked or sleeping screen. " +
                        "Without it the alarm only reaches the pull-down shade.",
                    onFix = { onFix(SetupFix.FULL_SCREEN) },
                )
            }
            if (!setup.canDrawOverlays) {
                SetupRow(
                    title = "Allow display over other apps",
                    detail = "Android demotes a full-screen alarm to a plain notification " +
                        "while you are using the phone. This is what lets the alarm screen " +
                        "appear anyway, instead of hiding in the shade.",
                    onFix = { onFix(SetupFix.OVERLAY) },
                )
            }
            if (!setup.batteryUnrestricted) {
                SetupRow(
                    title = "Remove battery restrictions",
                    detail = "Battery optimisation can kill the app and drop its alarms.",
                    onFix = { onFix(SetupFix.BATTERY) },
                )
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onFix) { Text("Fix") }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No alarms yet.\nTap + to wake up a set number of minutes\nbefore or after any salaah.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlarmCard(
    rule: AlarmRule,
    nextTrigger: Instant?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkipNext: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = nextTrigger?.timeOfDay() ?: "--:--",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (rule.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = rule.displayLabel,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${rule.offsetDescription()} · ${rule.daysDescription()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }

            if (rule.enabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            rule.skipNext -> "Next one skipped"
                            nextTrigger != null -> "${nextTrigger.describeWhen()} · ${nextTrigger.describeCountdown()}"
                            else -> "No upcoming time — check Settings"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (nextTrigger == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSkipNext(!rule.skipNext) }) {
                        Text(if (rule.skipNext) "Un-skip" else "Skip next")
                    }
                }
            }
        }
    }
}
