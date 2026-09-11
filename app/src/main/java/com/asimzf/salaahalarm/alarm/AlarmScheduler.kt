package com.asimzf.salaahalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.asimzf.salaahalarm.MainActivity
import com.asimzf.salaahalarm.data.AlarmRule
import com.asimzf.salaahalarm.data.AppState
import com.asimzf.salaahalarm.data.AppStore
import com.asimzf.salaahalarm.prayer.PrayerEngine
import java.time.Instant
import java.time.ZoneId

/**
 * Owns every interaction with AlarmManager.
 *
 * Only the *next* occurrence of each rule is ever registered. The receiver re-arms the
 * rule after it fires, which is what keeps the alarm tracking the sun without us having
 * to hold a calendar of future firings.
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        private const val TAG = "AlarmScheduler"

        const val ACTION_FIRE = "com.asimzf.salaahalarm.FIRE"
        const val ACTION_REFRESH = "com.asimzf.salaahalarm.REFRESH"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"

        /** Request code for the daily safety-net refresh, kept clear of alarm ids. */
        private const val REFRESH_REQUEST_CODE = Int.MAX_VALUE
    }

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    /**
     * Re-registers every enabled alarm. Safe to call repeatedly; it is idempotent.
     *
     * [notBefore] raises the floor for individual rules. Re-arming after a fire must pass
     * the trigger that just fired, because setAlarmClock can deliver a few milliseconds
     * early: measured against a bare Instant.now() the occurrence that just fired still
     * looks like it is in the future, so the rule re-arms itself and goes off again
     * seconds later. A rule's occurrences are a day apart, so raising its floor can never
     * skip a legitimate one.
     */
    fun sync(
        state: AppState,
        now: Instant = Instant.now(),
        notBefore: Map<Int, Instant> = emptyMap(),
    ): List<ScheduledAlarm> {
        val zone = ZoneId.systemDefault()
        val engine = PrayerEngine(state.settings)
        val scheduled = mutableListOf<ScheduledAlarm>()

        for (rule in state.alarms) {
            cancel(rule.id)
            if (!rule.enabled) continue

            val floor = notBefore[rule.id]?.let { maxOf(now, it) } ?: now
            val trigger = AlarmMath.nextTrigger(rule, zone, floor, engine::instantOf)
            if (trigger == null) {
                Log.w(TAG, "No upcoming trigger for rule ${rule.id} (${rule.displayLabel})")
                continue
            }
            schedule(rule, trigger)
            scheduled += ScheduledAlarm(rule, trigger)
        }

        scheduleDailyRefresh(now)
        return scheduled
    }

    /** Computes the next trigger for every alarm without touching AlarmManager. */
    fun preview(state: AppState, now: Instant = Instant.now()): Map<Int, Instant?> {
        val zone = ZoneId.systemDefault()
        val engine = PrayerEngine(state.settings)
        return state.alarms.associate { rule ->
            rule.id to AlarmMath.nextTrigger(rule, zone, now, engine::instantOf)
        }
    }

    private fun schedule(rule: AlarmRule, trigger: Instant) {
        val operation = createFirePendingIntent(rule.id, trigger)
        val millis = trigger.toEpochMilli()

        if (canScheduleExact()) {
            // setAlarmClock is the right API for a user-facing alarm: it is exempt from
            // Doze and from the exact-alarm quota, and it surfaces the system alarm icon.
            val show = PendingIntent.getActivity(
                context,
                rule.id,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), operation)
        } else {
            // Exact alarms revoked. Still fire, just without the timing guarantee, and
            // the UI tells the user their alarms may be late.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
        }
        Log.i(TAG, "Scheduled rule ${rule.id} for $trigger")
    }

    fun cancel(ruleId: Int) {
        existingFirePendingIntent(ruleId)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /**
     * Belt and braces: once a day, re-derive everything. Covers alarms silently dropped
     * by an OEM battery manager and settings changes that arrived while the app was dead.
     */
    private fun scheduleDailyRefresh(now: Instant) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_REFRESH)
        val operation = PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            now.plusSeconds(6 * 60 * 60).toEpochMilli(),
            operation,
        )
    }

    private fun fireIntent(ruleId: Int, trigger: Instant?): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            // The data URI makes each rule's intent distinct under Intent.filterEquals,
            // so cancelling one alarm cannot clobber another's PendingIntent.
            .setData(android.net.Uri.parse("salaahalarm://rule/$ruleId"))
            .putExtra(EXTRA_RULE_ID, ruleId)
            .apply { trigger?.let { putExtra(EXTRA_TRIGGER_AT, it.toEpochMilli()) } }

    /** Creates or replaces the rule's PendingIntent. Never null, because it may create. */
    private fun createFirePendingIntent(ruleId: Int, trigger: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ruleId,
            fireIntent(ruleId, trigger),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Looks up the rule's existing PendingIntent, or null when there is nothing to
     * cancel. Extras are ignored by Intent.filterEquals, so the trigger time the
     * original was built with does not need to be reproduced here to find it.
     */
    private fun existingFirePendingIntent(ruleId: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            ruleId,
            fireIntent(ruleId, null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )

    /** Schedules a one-off snooze. Does not disturb the rule's own next occurrence. */
    fun snooze(rule: AlarmRule, minutes: Int) {
        val trigger = Instant.now().plusSeconds(minutes * 60L)
        val operation = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(rule.id),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(android.net.Uri.parse("salaahalarm://snooze/${rule.id}"))
                .putExtra(EXTRA_RULE_ID, rule.id)
                .putExtra(EXTRA_TRIGGER_AT, trigger.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canScheduleExact()) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger.toEpochMilli(), null),
                operation,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), operation)
        }
    }

    private fun snoozeRequestCode(ruleId: Int) = Int.MAX_VALUE / 2 + ruleId

    suspend fun syncFromStore(
        store: AppStore,
        notBefore: Map<Int, Instant> = emptyMap(),
    ): List<ScheduledAlarm> = sync(store.current(), Instant.now(), notBefore)
}

data class ScheduledAlarm(val rule: AlarmRule, val triggerAt: Instant)
