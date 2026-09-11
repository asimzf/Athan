package com.asimzf.salaahalarm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asimzf.salaahalarm.alarm.AlarmReceiver
import com.asimzf.salaahalarm.alarm.AlarmScheduler
import com.asimzf.salaahalarm.alarm.AlarmService
import com.asimzf.salaahalarm.alarm.SetupStatus
import com.asimzf.salaahalarm.data.ALL_DAYS
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.AppState
import com.asimzf.salaahalarm.data.AppStore
import com.asimzf.salaahalarm.data.PrayerAnchor
import com.asimzf.salaahalarm.data.PrayerSettings
import com.asimzf.salaahalarm.location.LocationProvider
import com.asimzf.salaahalarm.prayer.DayTimes
import com.asimzf.salaahalarm.prayer.PrayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface Screen {
    data object List : Screen
    data class Edit(val ruleId: Int?) : Screen
    data object Settings : Screen
}

data class ListUiState(
    val alarms: List<AlarmRule> = emptyList(),
    val nextTriggers: Map<Int, Instant?> = emptyMap(),
    val today: DayTimes? = null,
    val settings: PrayerSettings = PrayerSettings(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    private val scheduler = AlarmScheduler(app)
    private val locationProvider = LocationProvider(app)

    private val _screen = MutableStateFlow<Screen>(Screen.List)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    /** Non-null while an alarm is sounding, so the app itself is always a way to stop it. */
    val ringingRuleId: StateFlow<Int?> = AlarmService.ringingRuleId

    private val _setup = MutableStateFlow(SetupStatus.read(app))
    val setup: StateFlow<SetupStatus> = _setup.asStateFlow()

    /** Call on every resume: the user may have just come back from a settings screen. */
    fun refreshSetup() {
        _setup.value = SetupStatus.read(getApplication<Application>())
    }

    fun stopRingingAlarm() {
        val app = getApplication<Application>()
        app.sendBroadcast(
            AlarmReceiver.controlIntent(app, AlarmReceiver.ACTION_DISMISS, ringingRuleId.value ?: -1)
        )
    }

    val state: StateFlow<ListUiState> = store.state
        .map { appState -> toUiState(appState) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private fun toUiState(appState: AppState): ListUiState {
        val engine = PrayerEngine(appState.settings)
        return ListUiState(
            alarms = appState.alarms.sortedWith(compareBy({ it.anchor.ordinal }, { it.offsetMinutes })),
            nextTriggers = scheduler.preview(appState),
            today = engine.timesFor(LocalDate.now(ZoneId.systemDefault())),
            settings = appState.settings,
        )
    }

    fun navigate(screen: Screen) {
        _screen.value = screen
    }

    fun back() {
        _screen.value = Screen.List
    }

    fun ruleById(id: Int?): AlarmRule? =
        id?.let { state.value.alarms.firstOrNull { rule -> rule.id == it } }

    /** A sensible starting point for a new alarm: 15 minutes before Fajr, every day. */
    suspend fun newRuleTemplate(): AlarmRule = AlarmRule(
        id = store.allocateId(),
        anchor = PrayerAnchor.FAJR,
        offsetMinutes = -15,
        days = ALL_DAYS,
    )

    fun save(rule: AlarmRule) = viewModelScope.launch {
        store.upsert(rule)
        resync()
        _screen.value = Screen.List
    }

    fun delete(id: Int) = viewModelScope.launch {
        scheduler.cancel(id)
        store.delete(id)
        resync()
        _screen.value = Screen.List
    }

    fun setEnabled(id: Int, enabled: Boolean) = viewModelScope.launch {
        store.setEnabled(id, enabled)
        resync()
    }

    fun setSkipNext(id: Int, skip: Boolean) = viewModelScope.launch {
        store.setSkipNext(id, skip)
        resync()
    }

    fun saveSettings(settings: PrayerSettings) = viewModelScope.launch {
        store.saveSettings(settings)
        resync()
    }

    /** Every mutation ends here: the stored rules are the truth, AlarmManager is a cache. */
    fun resync() = viewModelScope.launch {
        runCatching { scheduler.syncFromStore(store) }
    }

    fun refreshLocation(onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        _locating.value = true
        try {
            val fix = locationProvider.currentFix()
            if (fix == null) {
                onResult(false)
                return@launch
            }
            val current = store.current().settings
            store.saveSettings(
                current.copy(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    locationLabel = fix.label,
                    useDeviceLocation = true,
                )
            )
            resync()
            onResult(true)
        } finally {
            _locating.value = false
        }
    }
}
