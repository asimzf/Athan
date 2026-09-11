package com.asimzf.salaahalarm.alarm

import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.PrayerAnchor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The whole scheduling idea, isolated from Android so it can be unit tested.
 *
 * Resolving "when does this alarm next go off" is a search, not a formula, because an
 * offset can push the trigger across midnight in either direction and because a day's
 * times can be undefined at high latitudes.
 */
object AlarmMath {

    /** A year of lookahead. Enough for any weekday pattern, bounded so a broken rule can't spin. */
    const val HORIZON_DAYS = 400

    /**
     * The next instant [rule] should fire, or null if it never will.
     *
     * The search starts at *yesterday* on purpose: an alarm like "Isha + 6 hours" fires
     * on the following calendar day, so yesterday's anchor can still be in the future.
     */
    fun nextTrigger(
        rule: AlarmRule,
        zone: ZoneId,
        now: Instant,
        anchorAt: (PrayerAnchor, LocalDate) -> Instant?,
    ): Instant? {
        if (!rule.enabled || rule.days.isEmpty()) return null

        var date = now.atZone(zone).toLocalDate().minusDays(1)
        var skipConsumed = false

        repeat(HORIZON_DAYS + 1) {
            if (rule.appliesOn(date)) {
                val anchor = anchorAt(rule.anchor, date)
                if (anchor != null) {
                    val trigger = anchor.plusSeconds(rule.offsetMinutes * 60L)
                    if (trigger.isAfter(now)) {
                        if (rule.skipNext && !skipConsumed) {
                            skipConsumed = true
                        } else {
                            return trigger
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return null
    }
}
