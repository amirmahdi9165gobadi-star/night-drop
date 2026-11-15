package com.example.automation

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat

@Immutable
data class PermissionSnapshot(
    val isAccessibilityEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val canScheduleExactAlarms: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isNetworkConnected: Boolean = false
)

object SettingsNavigator {

    fun captureSnapshot(context: Context): PermissionSnapshot {
        val accessibilityEnabled = MacroAccessibilityService.isConnected.value ||
            isAccessibilityServiceEnabledInSystem(context)

        val overlayGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        return PermissionSnapshot(
            isAccessibilityEnabled = accessibilityEnabled,
            canDrawOverlays = overlayGranted,
            canScheduleExactAlarms = canExact,
            isIgnoringBatteryOptimizations = ignoringBattery,
            hasNotificationPermission = notifGranted,
            isNetworkConnected = connected
        )
    }

    private fun isAccessibilityServiceEnabledInSystem(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedId = ComponentName(context, MacroAccessibilityService::class.java).flattenToString()
        val shortId = "${context.packageName}/.automation.MacroAccessibilityService"
        return enabledServices.any { info ->
            info.id == expectedId || info.id == shortId || info.id.contains("MacroAccessibilityService")
        }
    }

    fun openAccessibilitySettings(context: Context) {
        launchSafe(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        if (!launchSafe(context, intent)) {
            openAppDetails(context)
        }
    }

    fun openExactAlarmSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
        launchSafe(context, intent)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val directRequest = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (!launchSafe(context, directRequest)) {
            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (!launchSafe(context, listIntent)) {
                openAppDetails(context)
            }
        }
    }

    fun openXiaomiAutostartSettings(context: Context) {
        val oemIntents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", "NightDrop")
            },
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
        )

        for (candidate in oemIntents) {
            if (launchSafe(context, candidate)) return
        }
        openAppDetails(context)
    }

    fun openConnectivityPanel(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        if (!launchSafe(context, intent)) {
            launchSafe(context, Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        if (!launchSafe(context, intent)) {
            openAppDetails(context)
        }
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        launchSafe(context, intent)
    }

    fun openDownloadsFolder(context: Context) {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        if (!launchSafe(context, intent)) {
            openAppDetails(context)
        }
    }

    private fun launchSafe(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
