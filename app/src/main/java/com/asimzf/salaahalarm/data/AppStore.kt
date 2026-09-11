package com.asimzf.salaahalarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("salaah_alarm")

/**
 * Single JSON blob in DataStore. There are only ever a handful of alarms, so a database
 * would be more machinery than the problem needs.
 */
class AppStore(private val context: Context) {

    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state: Flow<AppState> = context.dataStore.data.map { prefs ->
        prefs[key]?.let {
            runCatching { json.decodeFromString<AppState>(it) }.getOrElse { AppState() }
        } ?: AppState()
    }

    suspend fun current(): AppState = state.first()

    private suspend fun update(transform: (AppState) -> AppState) {
        context.dataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                runCatching { json.decodeFromString<AppState>(it) }.getOrElse { AppState() }
            } ?: AppState()
            prefs[key] = json.encodeToString(transform(existing))
        }
    }

    suspend fun upsert(rule: AlarmRule) = update { state ->
        val exists = state.alarms.any { it.id == rule.id }
        if (exists) {
            state.copy(alarms = state.alarms.map { if (it.id == rule.id) rule else it })
        } else {
            state.copy(alarms = state.alarms + rule, nextId = maxOf(state.nextId, rule.id + 1))
        }
    }

    suspend fun delete(id: Int) = update { it.copy(alarms = it.alarms.filterNot { a -> a.id == id }) }

    suspend fun setEnabled(id: Int, enabled: Boolean) = update { state ->
        state.copy(alarms = state.alarms.map {
            if (it.id == id) it.copy(enabled = enabled, skipNext = false) else it
        })
    }

    suspend fun setSkipNext(id: Int, skip: Boolean) = update { state ->
        state.copy(alarms = state.alarms.map { if (it.id == id) it.copy(skipNext = skip) else it })
    }

    suspend fun saveSettings(settings: PrayerSettings) = update { it.copy(settings = settings) }

    /** Reserves and returns a fresh alarm id. Ids double as PendingIntent request codes. */
    suspend fun allocateId(): Int {
        var allocated = 1
        update { state ->
            allocated = state.nextId
            state.copy(nextId = state.nextId + 1)
        }
        return allocated
    }
}
