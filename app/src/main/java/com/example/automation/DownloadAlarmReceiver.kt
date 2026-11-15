package com.example.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class DownloadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NightDrop:AlarmWakeLock"
        )
        wakeLock.acquire(60_000L)

        val serviceIntent = Intent(context, NightDownloadService::class.java).apply {
            action = NightDownloadService.ACTION_START_QUEUE_WORKFLOW
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Avoid unhandled crash if Android 14+ restricts background start
        } finally {
            runCatching {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }
}
