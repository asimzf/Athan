package com.asimzf.salaahalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.PrayerAnchor
import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditScreen(
    viewModel: MainViewModel,
    ruleId: Int?,
    onDone: () -> Unit,
) {
    var draft by remember { mutableStateOf<AlarmRule?>(viewModel.ruleById(ruleId)) }

    LaunchedEffect(ruleId) {
        if (draft == null) draft = viewModel.newRuleTemplate()
    }

    val rule = draft ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ruleId == null) "New alarm" else "Edit alarm") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ruleId != null) {
                        IconButton(onClick = { viewModel.delete(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete alarm")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Anchor") {
                Text(
                    "The alarm follows this salaah, so it moves every day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrayerAnchor.values().forEach { anchor ->
                        FilterChip(
                            selected = rule.anchor == anchor,
                            onClick = { draft = rule.copy(anchor = anchor) },
                            label = { Text(anchor.displayName) },
                        )
                    }
                }
            }

            Section("Offset") {
                OffsetPicker(
                    offsetMinutes = rule.offsetMinutes,
                    anchorName = rule.anchor.displayName,
                    onChange = { draft = rule.copy(offsetMinutes = it) },
                )
            }

            Section("Repeat") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.values().forEach { day ->
                        val selected = day.value in rule.days
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val days = if (selected) rule.days - day.value else rule.days + day.value
                                draft = rule.copy(days = days)
                            },
                            label = {
                                Text(day.name.take(3).lowercase().replaceFirstChar(Char::uppercase))
                            },
                        )
                    }
                }
                if (rule.days.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pick at least one day, or this alarm will never fire.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Section("Label") {
                OutlinedTextField(
                    value = rule.label,
                    onValueChange = { draft = rule.copy(label = it) },
                    placeholder = { Text(rule.offsetDescription()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("Sound") {
                ToggleRow(
                    label = "Vibrate",
                    checked = rule.vibrate,
                    onChange = { draft = rule.copy(vibrate = it) },
                )
                Spacer(Modifier.height(12.dp))
                Text("Snooze: ${rule.snoozeMinutes} min", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = rule.snoozeMinutes.toFloat(),
                    onValueChange = { draft = rule.copy(snoozeMinutes = it.roundToInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
            }

            Button(
                onClick = { viewModel.save(rule) },
                enabled = rule.days.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Save alarm")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OffsetPicker(
    offsetMinutes: Int,
    anchorName: String,
    onChange: (Int) -> Unit,
) {
    val before = offsetMinutes < 0
    val magnitude = abs(offsetMinutes)

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        listOf("Before", "At", "After").forEachIndexed { index, label ->
            val selected = when (index) {
                0 -> offsetMinutes < 0
                1 -> offsetMinutes == 0
                else -> offsetMinutes > 0
            }
            SegmentedButton(
                selected = selected,
                onClick = {
                    onChange(
                        when (index) {
                            0 -> -(magnitude.takeIf { it > 0 } ?: 15)
                            1 -> 0
                            else -> magnitude.takeIf { it > 0 } ?: 15
                        }
                    )
                },
                shape = SegmentedButtonDefaults.itemShape(index, 3),
            ) { Text(label) }
        }
    }

    if (offsetMinutes != 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "$magnitude minutes ${if (before) "before" else "after"} $anchorName",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = magnitude.toFloat(),
            onValueChange = { value ->
                val minutes = value.roundToInt().coerceAtLeast(1)
                onChange(if (before) -minutes else minutes)
            },
            // Up to 6 hours, which covers things like "wake 4 hours after Isha for tahajjud".
            valueRange = 1f..360f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(5, 10, 15, 30, 45, 60).forEach { preset ->
                FilterChip(
                    selected = magnitude == preset,
                    onClick = { onChange(if (before) -preset else preset) },
                    label = { Text("$preset") },
                )
            }
        }
    } else {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Rings exactly at $anchorName.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
