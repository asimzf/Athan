package com.asimzf.salaahalarm.prayer

import com.asimzf.salaahalarm.data.PrayerAnchor
import com.asimzf.salaahalarm.data.PrayerSettings
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.HighLatitudeRule
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerAdjustments
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.time.Instant
import java.time.LocalDate

/** The calculation methods adhan ships, in the order we show them. */
enum class Method(val id: String, val displayName: String, val blurb: String) {
    MUSLIM_WORLD_LEAGUE("MUSLIM_WORLD_LEAGUE", "Muslim World League", "Fajr 18°, Isha 17°"),
    NORTH_AMERICA("NORTH_AMERICA", "ISNA (North America)", "Fajr 15°, Isha 15°"),
    EGYPTIAN("EGYPTIAN", "Egyptian General Authority", "Fajr 19.5°, Isha 17.5°"),
    KARACHI("KARACHI", "University of Karachi", "Fajr 18°, Isha 18°"),
    UMM_AL_QURA("UMM_AL_QURA", "Umm al-Qura, Makkah", "Fajr 18.5°, Isha 90 min after Maghrib"),
    DUBAI("DUBAI", "Dubai", "Fajr 18.2°, Isha 18.2°"),
    QATAR("QATAR", "Qatar", "Fajr 18°, Isha 90 min after Maghrib"),
    KUWAIT("KUWAIT", "Kuwait", "Fajr 18°, Isha 17.5°"),
    SINGAPORE("SINGAPORE", "Singapore", "Fajr 20°, Isha 18°"),
    MOON_SIGHTING_COMMITTEE("MOON_SIGHTING_COMMITTEE", "Moonsighting Committee", "Seasonal, for high latitudes");

    companion object {
        fun fromId(id: String): Method = entries.firstOrNull { it.id == id } ?: MUSLIM_WORLD_LEAGUE
    }
}

enum class HighLatitude(val id: String, val displayName: String, val blurb: String) {
    MIDDLE_OF_THE_NIGHT("MIDDLE_OF_THE_NIGHT", "Middle of the night", "Fajr/Isha capped at the night's midpoint"),
    SEVENTH_OF_THE_NIGHT("SEVENTH_OF_THE_NIGHT", "One seventh of the night", "Night split into sevenths"),
    TWILIGHT_ANGLE("TWILIGHT_ANGLE", "Twilight angle", "Night portion scaled by the method's angle");

    companion object {
        fun fromId(id: String): HighLatitude = entries.firstOrNull { it.id == id } ?: MIDDLE_OF_THE_NIGHT
    }
}

/** A day's computed times. Any field is null only when the engine could not resolve the day. */
data class DayTimes(
    val date: LocalDate,
    val times: Map<PrayerAnchor, Instant>,
) {
    operator fun get(anchor: PrayerAnchor): Instant? = times[anchor]
}

/**
 * Wraps the adhan library. Pure with respect to time: give it a civil date, get back
 * absolute instants. Results are memoised per date because the scheduler walks forward
 * a day at a time across every alarm.
 */
class PrayerEngine(private val settings: PrayerSettings) {

    private val cache = HashMap<LocalDate, DayTimes?>()

    private fun parameters(): CalculationParameters {
        val method = runCatching { CalculationMethod.valueOf(settings.method) }
            .getOrDefault(CalculationMethod.MUSLIM_WORLD_LEAGUE)
        return method.getParameters().apply {
            madhab = runCatching { Madhab.valueOf(settings.madhab) }.getOrDefault(Madhab.SHAFI)
            highLatitudeRule = runCatching { HighLatitudeRule.valueOf(settings.highLatitudeRule) }
                .getOrDefault(HighLatitudeRule.MIDDLE_OF_THE_NIGHT)
            // `adjustments` is the user's tuning; `methodAdjustments` belongs to the method
            // and must be left alone or the method stops matching its published timetable.
            adjustments = PrayerAdjustments(
                settings.tuningFor(PrayerAnchor.FAJR),
                settings.tuningFor(PrayerAnchor.SUNRISE),
                settings.tuningFor(PrayerAnchor.DHUHR),
                settings.tuningFor(PrayerAnchor.ASR),
                settings.tuningFor(PrayerAnchor.MAGHRIB),
                settings.tuningFor(PrayerAnchor.ISHA),
            )
        }
    }

    /**
     * Null when the sun never reaches the required angle on that date — real above the
     * polar circles, where Fajr and Isha are genuinely undefined. Callers must skip the
     * day rather than substitute a guess.
     */
    fun timesFor(date: LocalDate): DayTimes? = cache.getOrPut(date) {
        val coordinates = Coordinates(settings.latitude, settings.longitude)
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val computed = runCatching { PrayerTimes(coordinates, components, parameters()) }.getOrNull()
            ?: return@getOrPut null

        // adhan nulls out every field together when a day cannot be resolved.
        val raw = mapOf(
            PrayerAnchor.FAJR to computed.fajr,
            PrayerAnchor.SUNRISE to computed.sunrise,
            PrayerAnchor.DHUHR to computed.dhuhr,
            PrayerAnchor.ASR to computed.asr,
            PrayerAnchor.MAGHRIB to computed.maghrib,
            PrayerAnchor.ISHA to computed.isha,
        )
        if (raw.values.any { it == null }) return@getOrPut null

        DayTimes(date, raw.mapValues { (_, value) -> value!!.toInstant() })
    }

    fun instantOf(anchor: PrayerAnchor, date: LocalDate): Instant? = timesFor(date)?.get(anchor)
}
