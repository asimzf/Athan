package com.asimzf.salaahalarm.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/** The six solar events an alarm can be anchored to. */
enum class PrayerAnchor(val displayName: String) {
    FAJR("Fajr"),
    SUNRISE("Sunrise"),
    DHUHR("Dhuhr"),
    ASR("Asr"),
    MAGHRIB("Maghrib"),
    ISHA("Isha");

    companion object {
        fun fromName(name: String): PrayerAnchor =
            entries.firstOrNull { it.name == name } ?: FAJR
    }
}

val ALL_DAYS: Set<Int> = DayOfWeek.values().map { it.value }.toSet()

/**
 * One alarm. The trigger instant is always derived, never stored:
 *
 *     trigger = salaahTime(anchor, date) + offsetMinutes
 *
 * which is why the alarm drifts with the sun from one day to the next.
 *
 * [days] holds `java.time.DayOfWeek.value` (Mon=1 .. Sun=7) and filters on the day of
 * the *anchoring salaah*, not the day the alarm happens to land on. So an Isha alarm
 * with a +300 minute offset that fires at 02:00 Tuesday is "Monday's Isha alarm".
 */
@Serializable
data class AlarmRule(
    val id: Int,
    val label: String = "",
    val anchor: PrayerAnchor = PrayerAnchor.FAJR,
    /** Negative fires before the salaah, positive after, 0 exactly at it. */
    val offsetMinutes: Int = 0,
    val days: Set<Int> = ALL_DAYS,
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    /** null uses the system default alarm sound. */
    val ringtoneUri: String? = null,
    val snoozeMinutes: Int = 10,
    /** Set by "skip next" — consumed once, then cleared when the alarm is skipped. */
    val skipNext: Boolean = false,
) {
    val displayLabel: String
        get() = label.ifBlank { "${anchor.displayName} ${offsetDescription()}" }

    fun offsetDescription(): String = when {
        offsetMinutes == 0 -> "at ${anchor.displayName}"
        offsetMinutes < 0 -> "${abs(offsetMinutes)} min before ${anchor.displayName}"
        else -> "$offsetMinutes min after ${anchor.displayName}"
    }

    fun daysDescription(): String = when {
        days.size == 7 -> "Every day"
        days.isEmpty() -> "Never"
        days == setOf(1, 2, 3, 4, 5) -> "Mon–Fri"
        days == setOf(6, 7) -> "Sat & Sun"
        else -> DayOfWeek.values()
            .filter { it.value in days }
            .joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
    }

    fun appliesOn(date: LocalDate): Boolean = date.dayOfWeek.value in days
}

/** Everything that feeds the prayer-time calculation. */
@Serializable
data class PrayerSettings(
    val latitude: Double = 21.4225,
    val longitude: Double = 39.8262,
    val locationLabel: String = "Makkah (default)",
    val useDeviceLocation: Boolean = true,
    val method: String = "MUSLIM_WORLD_LEAGUE",
    val madhab: String = "SHAFI",
    val highLatitudeRule: String = "MIDDLE_OF_THE_NIGHT",
    /** Per-anchor manual correction in minutes, to match a local masjid timetable. */
    val tuning: Map<String, Int> = emptyMap(),
) {
    fun tuningFor(anchor: PrayerAnchor): Int = tuning[anchor.name] ?: 0
}

@Serializable
data class AppState(
    val alarms: List<AlarmRule> = emptyList(),
    val settings: PrayerSettings = PrayerSettings(),
    val nextId: Int = 1,
)
