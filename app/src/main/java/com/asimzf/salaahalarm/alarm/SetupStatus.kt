package com.asimzf.salaahalarm.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What the OS is currently letting this app do.
 *
 * An alarm clock is only as good as its permissions, and the failure mode is silent:
 * with notifications denied a foreground service still runs and still plays audio, but
 * shows nothing at all — no notification, and no full-screen intent, because there is no
 * notification for it to attach to. The result is an alarm that rings with no way to
 * stop it. So the app checks up front and says so, rather than finding out at 4am.
 */
data class SetupStatus(
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val canDrawOverlays: Boolean,
    val batteryUnrestricted: Boolean,
) {
    /** Without these an alarm cannot reliably be seen or stopped. */
    val isBlocking: Boolean get() = !notificationsEnabled || !exactAlarmsAllowed

    val allClear: Boolean
        get() = notificationsEnabled && exactAlarmsAllowed &&
            fullScreenIntentAllowed && canDrawOverlays && batteryUnrestricted

    companion object {
        fun read(context: Context): SetupStatus {
            val permissionHeld = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

            // The runtime permission and the per-app notification toggle are separate;
            // either being off means nothing is shown.
            val notificationsEnabled = permissionHeld &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

            val exactAlarmsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else {
                true
            }

            // Android 14 stopped auto-granting this to sideloaded apps, so the lock-screen
            // ringing UI needs an explicit toggle unless the app shipped through Play as
            // an alarm-category app.
            val fullScreenIntentAllowed = if (Build.VERSION.SDK_INT >= 34) {
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } else {
                true
            }

            // Grants unconditional activity starts, so the ringing screen can appear
            // even when the phone is unlocked and being used.
            val canDrawOverlays = Settings.canDrawOverlays(context)

            val batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

            return SetupStatus(
                notificationsEnabled = notificationsEnabled,
                exactAlarmsAllowed = exactAlarmsAllowed,
                fullScreenIntentAllowed = fullScreenIntentAllowed,
                canDrawOverlays = canDrawOverlays,
                batteryUnrestricted = batteryUnrestricted,
            )
        }

        fun notificationSettings(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        fun exactAlarmSettings(context: Context): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
            } else {
                null
            }

        fun fullScreenIntentSettings(context: Context): Intent? =
            if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${context.packageName}"))
            } else {
                null
            }

        fun overlaySettings(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))

        @Suppress("BatteryLife")
        fun batterySettings(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
    }
}
