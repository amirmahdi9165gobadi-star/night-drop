package com.example.automation

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val systemDownloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (systemDownloadId <= 0L) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NightDrop:DownloadCompleteWakeLock"
        )
        wakeLock.acquire(30_000L)

        val serviceIntent = Intent(context, NightDownloadService::class.java).apply {
            action = NightDownloadService.ACTION_HANDLE_DOWNLOAD_COMPLETE
            putExtra(NightDownloadService.EXTRA_SYSTEM_DOWNLOAD_ID, systemDownloadId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Avoid ForegroundServiceStartNotAllowedException crash on Android 12-15
        } finally {
            runCatching {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }
}
