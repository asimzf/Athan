package com.asimzf.salaahalarm.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.asimzf.salaahalarm.data.PrayerAnchor
import com.asimzf.salaahalarm.prayer.HighLatitude
import com.asimzf.salaahalarm.prayer.Method

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    state: ListUiState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    val locating by viewModel.locating.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            Section("Location") {
                Text(settings.locationLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "%.4f, %.4f".format(settings.latitude, settings.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.refreshLocation() },
                    enabled = !locating,
                ) {
                    Text(if (locating) "Locating…" else "Use my current location")
                }
                Spacer(Modifier.height(12.dp))
                ManualCoordinates(
                    latitude = settings.latitude,
                    longitude = settings.longitude,
                    onApply = { lat, lon ->
                        viewModel.saveSettings(
                            settings.copy(
                                latitude = lat,
                                longitude = lon,
                                locationLabel = "%.3f, %.3f".format(lat, lon),
                                useDeviceLocation = false,
                            )
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Times are rendered in this device's timezone, so travelling " +
                        "just works as long as you refresh your location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Calculation method") {
                val current = Method.fromId(settings.method)
                Dropdown(
                    label = current.displayName,
                    options = Method.values().map { it.displayName to it.id },
                    onSelect = { viewModel.saveSettings(settings.copy(method = it)) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    current.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Asr (madhab)") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        "SHAFI" to "Standard",
                        "HANAFI" to "Hanafi",
                    ).forEach { (id, label) ->
                        OutlinedButton(
                            onClick = { viewModel.saveSettings(settings.copy(madhab = id)) },
                            enabled = settings.madhab != id,
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Standard uses a shadow equal to the object's length; Hanafi uses " +
                        "twice that, which puts Asr noticeably later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("High latitude rule") {
                val current = HighLatitude.fromId(settings.highLatitudeRule)
                Dropdown(
                    label = current.displayName,
                    options = HighLatitude.values().map { it.displayName to it.id },
                    onSelect = { viewModel.saveSettings(settings.copy(highLatitudeRule = it)) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${current.blurb}. Only matters above roughly 48° latitude, where " +
                        "the sun may never reach the Fajr or Isha angle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Match your masjid") {
                Text(
                    text = "Shift each salaah to line up with your local timetable. Alarm " +
                        "offsets stack on top of these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PrayerAnchor.values().forEach { anchor ->
                    TuningRow(
                        anchor = anchor,
                        minutes = settings.tuningFor(anchor),
                        onChange = { minutes ->
                            val tuning = settings.tuning.toMutableMap()
                            if (minutes == 0) tuning.remove(anchor.name) else tuning[anchor.name] = minutes
                            viewModel.saveSettings(settings.copy(tuning = tuning))
                        },
                    )
                }
            }

            Section("Reliability") {
                Text(
                    text = "Android will happily kill a background app and swallow its alarms. " +
                        "These two settings are what keep alarms firing on time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (!state.canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }) { Text("Allow exact alarms") }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text("Exact alarms: allowed", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Battery optimisation settings") }
            }
        }
    }
}

@Composable
private fun TuningRow(anchor: PrayerAnchor, minutes: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(anchor.displayName, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((minutes - 1).coerceAtLeast(-60)) }) { Text("−") }
        Text(
            text = if (minutes > 0) "+$minutes" else "$minutes",
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = { onChange((minutes + 1).coerceAtMost(60)) }) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (display, id) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ManualCoordinates(
    latitude: Double,
    longitude: Double,
    onApply: (Double, Double) -> Unit,
) {
    var lat by remember(latitude) { mutableStateOf(latitude.toString()) }
    var lon by remember(longitude) { mutableStateOf(longitude.toString()) }
    val parsedLat = lat.toDoubleOrNull()
    val parsedLon = lon.toDoubleOrNull()
    val valid = parsedLat != null && parsedLon != null &&
        parsedLat in -90.0..90.0 && parsedLon in -180.0..180.0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = lat,
            onValueChange = { lat = it },
            label = { Text("Lat") },
            singleLine = true,
            isError = parsedLat == null || parsedLat !in -90.0..90.0,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = lon,
            onValueChange = { lon = it },
            label = { Text("Lon") },
            singleLine = true,
            isError = parsedLon == null || parsedLon !in -180.0..180.0,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { if (valid) onApply(parsedLat!!, parsedLon!!) },
        enabled = valid,
        modifier = Modifier.height(44.dp),
    ) { Text("Set manually") }
}
