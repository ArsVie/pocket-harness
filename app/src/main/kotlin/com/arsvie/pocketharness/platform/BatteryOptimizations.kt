package com.arsvie.pocketharness.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What the system says about keeping this app alive in the background (BACKLOG B-12).
 *
 * On MIUI/HyperOS — the owner's Redmi — background CPU is restricted and the process can be killed
 * outright even with the foreground service (ADR-005 §5) holding the turn. The only reliable fix is
 * a battery-optimization exemption, so the app has to say when it does not have one: a turn that
 * dies in the background otherwise reads as the app's own bug.
 */
data class BatteryStatus(
    /** `isIgnoringBatteryOptimizations` — false means background turns can be killed. */
    val exempt: Boolean,
    /** Power save mode: restricts background work; shown in Diagnostics, not acted on. */
    val powerSave: Boolean,
    /** POST_NOTIFICATIONS grant on API 33+; always true below that (granted at install). */
    val notificationsAllowed: Boolean,
)

object BatteryOptimizations {

    /** Reads the three states once; cheap enough for a resume callback. */
    fun read(context: Context): BatteryStatus {
        val power = context.getSystemService(PowerManager::class.java)
        val notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return BatteryStatus(
            exempt = power.isIgnoringBatteryOptimizations(context.packageName),
            powerSave = power.isPowerSaveMode,
            notificationsAllowed = notificationsAllowed,
        )
    }

    /**
     * Opens this app's battery page: the direct request page first, then app details. Returns
     * false when neither resolves, so the caller can fall back to the manual path (B-12 §3).
     */
    fun openSettings(context: Context): Boolean {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, direct)) return true
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, details)
    }

    /**
     * Opens this app's notification page (B-14 E3). `ACTION_APP_NOTIFICATION_SETTINGS` resolves on
     * every API this app supports; app details is the second try, so the row's manual path is only
     * ever the last resort.
     */
    fun openNotificationSettings(context: Context): Boolean {
        val page = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, page)) return true
        return openSettings(context)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess
}
