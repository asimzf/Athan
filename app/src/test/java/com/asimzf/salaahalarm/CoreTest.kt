package com.asimzf.salaahalarm

import com.asimzf.salaahalarm.alarm.AlarmMath
import com.asimzf.salaahalarm.data.ALL_DAYS
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.PrayerAnchor
import com.asimzf.salaahalarm.data.PrayerSettings
import com.asimzf.salaahalarm.prayer.PrayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class CoreTest {

    private val riyadhZone = ZoneId.of("Asia/Riyadh")
    private val riyadh = PrayerSettings(
        latitude = 24.7136,
        longitude = 46.6753,
        locationLabel = "Riyadh",
        method = "UMM_AL_QURA",
    )

    private fun at(zone: ZoneId, date: String, time: String): Instant {
        val (h, m) = time.split(":").map(String::toInt)
        return LocalDate.parse(date).atTime(h, m).atZone(zone).toInstant()
    }

    private fun hhmm(instant: Instant, zone: ZoneId): String =
        ZonedDateTime.ofInstant(instant, zone).toLocalTime().toString().take(5)

    // ---- prayer engine ----

    @Test
    fun `riyadh times are ordered and plausible`() {
        val engine = PrayerEngine(riyadh)
        val times = engine.timesFor(LocalDate.of(2026, 3, 15))
        assertNotNull("Riyadh must always resolve", times)
        times!!

        val ordered = PrayerAnchor.values().map { times[it]!! }
        for (i in 1 until ordered.size) {
            assertTrue(
                "${PrayerAnchor.values()[i]} must follow ${PrayerAnchor.values()[i - 1]}",
                ordered[i].isAfter(ordered[i - 1]),
            )
        }

        // Sanity-check against the published Umm al-Qura timetable for Riyadh in mid-March:
        // Fajr just before 05:00, Dhuhr near solar noon, Isha ~90 min after Maghrib.
        val fajr = hhmm(times[PrayerAnchor.FAJR]!!, riyadhZone)
        val dhuhr = hhmm(times[PrayerAnchor.DHUHR]!!, riyadhZone)
        println("Riyadh 2026-03-15: " + PrayerAnchor.values().joinToString(" ") {
            "${it.displayName}=${hhmm(times[it]!!, riyadhZone)}"
        })
        assertTrue("Fajr $fajr should be early morning", fajr in "04:00".."06:00")
        assertTrue("Dhuhr $dhuhr should be near midday", dhuhr in "11:30".."12:45")

        val ishaGap = Duration.between(times[PrayerAnchor.MAGHRIB]!!, times[PrayerAnchor.ISHA]!!)
        assertEquals("Umm al-Qura puts Isha 90 min after Maghrib", 90, ishaGap.toMinutes())
    }

    @Test
    fun `hanafi asr falls later than standard asr`() {
        val date = LocalDate.of(2026, 3, 15)
        val standard = PrayerEngine(riyadh.copy(madhab = "SHAFI")).instantOf(PrayerAnchor.ASR, date)!!
        val hanafi = PrayerEngine(riyadh.copy(madhab = "HANAFI")).instantOf(PrayerAnchor.ASR, date)!!
        assertTrue("Hanafi Asr must be later", hanafi.isAfter(standard))
        println("Asr standard=${hhmm(standard, riyadhZone)} hanafi=${hhmm(hanafi, riyadhZone)}")
    }

    @Test
    fun `user tuning shifts the computed time by exactly that many minutes`() {
        val date = LocalDate.of(2026, 3, 15)
        val base = PrayerEngine(riyadh).instantOf(PrayerAnchor.FAJR, date)!!
        val tuned = PrayerEngine(riyadh.copy(tuning = mapOf("FAJR" to -7)))
            .instantOf(PrayerAnchor.FAJR, date)!!
        assertEquals(-7, Duration.between(base, tuned).toMinutes())
    }

    @Test
    fun `times drift day to day, which is the whole point`() {
        val engine = PrayerEngine(riyadh)
        val first = engine.instantOf(PrayerAnchor.FAJR, LocalDate.of(2026, 3, 15))!!
        val second = engine.instantOf(PrayerAnchor.FAJR, LocalDate.of(2026, 3, 16))!!
        val delta = Duration.between(first, second).toMinutes()
        assertTrue("Consecutive Fajr times must differ from a flat 24h, got $delta min", delta != 1440L)
        assertTrue("but only by a few minutes, got $delta min", abs(delta - 1440L) <= 5)
    }

    // ---- scheduling math ----

    private fun rule(
        anchor: PrayerAnchor = PrayerAnchor.FAJR,
        offset: Int = 0,
        days: Set<Int> = ALL_DAYS,
        enabled: Boolean = true,
        skipNext: Boolean = false,
    ) = AlarmRule(id = 1, anchor = anchor, offsetMinutes = offset, days = days,
        enabled = enabled, skipNext = skipNext)

    @Test
    fun `negative offset fires before the salaah`() {
        val engine = PrayerEngine(riyadh)
        val now = at(riyadhZone, "2026-03-15", "00:30")
        val fajr = engine.instantOf(PrayerAnchor.FAJR, LocalDate.of(2026, 3, 15))!!

        val trigger = AlarmMath.nextTrigger(rule(offset = -15), riyadhZone, now, engine::instantOf)!!
        assertEquals(-15, Duration.between(fajr, trigger).toMinutes())
        assertTrue(trigger.isAfter(now))
    }

    @Test
    fun `an offset that crosses midnight still resolves`() {
        // Isha + 5h lands after midnight, so the anchor is the PREVIOUS calendar day.
        val engine = PrayerEngine(riyadh)
        // Riyadh Isha on the 15th is 19:32, so Isha+5h lands at 00:32 on the 16th.
        val now = at(riyadhZone, "2026-03-16", "00:10")
        val ishaPrevDay = engine.instantOf(PrayerAnchor.ISHA, LocalDate.of(2026, 3, 15))!!

        val trigger = AlarmMath.nextTrigger(
            rule(anchor = PrayerAnchor.ISHA, offset = 300), riyadhZone, now, engine::instantOf,
        )!!
        assertEquals(
            "must anchor to the previous day's Isha, not tonight's",
            ishaPrevDay.plusSeconds(300 * 60), trigger,
        )
        assertTrue(trigger.isAfter(now))
        println("Isha+5h from 01:00 -> ${hhmm(trigger, riyadhZone)} on ${
            ZonedDateTime.ofInstant(trigger, riyadhZone).toLocalDate()}")
    }

    @Test
    fun `day filter applies to the anchor day, not the trigger day`() {
        val engine = PrayerEngine(riyadh)
        // 2026-03-15 is a Sunday (value 7). Restrict to Sunday only.
        assertEquals(java.time.DayOfWeek.SUNDAY, LocalDate.of(2026, 3, 15).dayOfWeek)
        val now = at(riyadhZone, "2026-03-16", "00:10")

        val trigger = AlarmMath.nextTrigger(
            rule(anchor = PrayerAnchor.ISHA, offset = 300, days = setOf(7)),
            riyadhZone, now, engine::instantOf,
        )!!
        // Fires early Monday morning, but it is Sunday's Isha alarm.
        assertEquals(
            java.time.DayOfWeek.MONDAY,
            ZonedDateTime.ofInstant(trigger, riyadhZone).dayOfWeek,
        )
        assertEquals(
            engine.instantOf(PrayerAnchor.ISHA, LocalDate.of(2026, 3, 15))!!.plusSeconds(300 * 60),
            trigger,
        )
    }

    @Test
    fun `weekday-only rule skips the weekend`() {
        val engine = PrayerEngine(riyadh)
        // Saturday 2026-03-14, late evening: next weekday (Mon-Fri) Fajr is Monday the 16th.
        assertEquals(java.time.DayOfWeek.SATURDAY, LocalDate.of(2026, 3, 14).dayOfWeek)
        val now = at(riyadhZone, "2026-03-14", "22:00")

        val trigger = AlarmMath.nextTrigger(
            rule(days = setOf(1, 2, 3, 4, 5)), riyadhZone, now, engine::instantOf,
        )!!
        assertEquals(
            LocalDate.of(2026, 3, 16),
            ZonedDateTime.ofInstant(trigger, riyadhZone).toLocalDate(),
        )
    }

    @Test
    fun `skipNext skips exactly one occurrence`() {
        val engine = PrayerEngine(riyadh)
        val now = at(riyadhZone, "2026-03-15", "00:30")

        val normal = AlarmMath.nextTrigger(rule(), riyadhZone, now, engine::instantOf)!!
        val skipped = AlarmMath.nextTrigger(rule(skipNext = true), riyadhZone, now, engine::instantOf)!!

        assertEquals(
            LocalDate.of(2026, 3, 15),
            ZonedDateTime.ofInstant(normal, riyadhZone).toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 3, 16),
            ZonedDateTime.ofInstant(skipped, riyadhZone).toLocalDate(),
        )
    }

    @Test
    fun `disabled or dayless rules never fire`() {
        val engine = PrayerEngine(riyadh)
        val now = at(riyadhZone, "2026-03-15", "00:30")
        assertNull(AlarmMath.nextTrigger(rule(enabled = false), riyadhZone, now, engine::instantOf))
        assertNull(AlarmMath.nextTrigger(rule(days = emptySet()), riyadhZone, now, engine::instantOf))
    }

    @Test
    fun `days the engine cannot resolve are skipped, not guessed`() {
        // Stand in for a polar day: the first three days have no times at all.
        val start = LocalDate.of(2026, 6, 1)
        val fallbackDay = start.plusDays(3)
        val expected = fallbackDay.atTime(4, 0).atZone(riyadhZone).toInstant()

        val trigger = AlarmMath.nextTrigger(
            rule(), riyadhZone, at(riyadhZone, "2026-06-01", "00:10"),
        ) { _, date ->
            if (date < fallbackDay) null else date.atTime(4, 0).atZone(riyadhZone).toInstant()
        }
        assertEquals(expected, trigger)
    }

    @Test
    fun `a rule with no resolvable day at all returns null instead of looping`() {
        val trigger = AlarmMath.nextTrigger(
            rule(), riyadhZone, Instant.parse("2026-06-01T00:00:00Z"),
        ) { _, _ -> null }
        assertNull(trigger)
    }

    @Test
    fun `tromso in midsummer still yields an alarm under the middle-of-night rule`() {
        // 69.6N: the sun never reaches the Isha angle in June. The high-latitude rule is
        // what keeps the alarm schedulable instead of silently vanishing.
        val tromso = PrayerSettings(
            latitude = 69.6492, longitude = 18.9553, locationLabel = "Tromso",
            method = "MUSLIM_WORLD_LEAGUE", highLatitudeRule = "MIDDLE_OF_THE_NIGHT",
        )
        val zone = ZoneId.of("Europe/Oslo")
        val engine = PrayerEngine(tromso)
        val trigger = AlarmMath.nextTrigger(
            rule(anchor = PrayerAnchor.FAJR, offset = -20),
            zone, at(zone, "2026-06-21", "00:05"), engine::instantOf,
        )
        // The high-latitude rule cannot save midsummer: above the Arctic circle the sun
        // never sets, so adhan resolves nothing at all and the search walks forward until
        // it does. The alarm is real but weeks away, which the UI must show honestly.
        assertNotNull("should eventually find a day it can resolve", trigger)
        val landsOn = ZonedDateTime.ofInstant(trigger!!, zone).toLocalDate()
        println("Tromso Fajr-20min from 21 Jun -> $landsOn")
        assertTrue(
            "expected the first resolvable day to be well after midsummer, got $landsOn",
            landsOn > LocalDate.of(2026, 7, 1),
        )
        assertNull(
            "midsummer itself must resolve to nothing rather than a fabricated time",
            PrayerEngine(tromso).timesFor(LocalDate.of(2026, 6, 21)),
        )
    }
}
