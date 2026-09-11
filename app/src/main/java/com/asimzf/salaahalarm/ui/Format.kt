package com.asimzf.salaahalarm.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

fun Instant.timeOfDay(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(TIME)

/** "today 04:32", "tomorrow 04:33", or "Fri 19 Sep 04:41" for anything further out. */
fun Instant.describeWhen(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val target = atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val dayLabel = when (target.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> target.format(DAY)
    }
    return "$dayLabel ${target.format(TIME)}"
}

/** "in 7h 12m" — the reassurance that the alarm really is armed. */
fun Instant.describeCountdown(now: Instant = Instant.now()): String {
    val duration = Duration.between(now, this)
    if (duration.isNegative) return "due"
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return when {
        days > 0 -> String.format(Locale.US, "in %dd %dh", days, hours)
        hours > 0 -> String.format(Locale.US, "in %dh %dm", hours, minutes)
        else -> String.format(Locale.US, "in %dm", minutes)
    }
}
