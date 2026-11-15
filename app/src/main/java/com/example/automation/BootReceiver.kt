package com.example.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.example.data.local.AppDatabase
import com.example.data.local.NightDropPreferences
import com.example.data.local.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um != null && !um.isUserUnlocked) {
                // Credential-encrypted Room/DataStore is unavailable until user unlocks once; BOOT_COMPLETED will follow
                return
            }
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).downloadTaskDao()
                val scheduled = dao.getTasksByStatus(TaskStatus.SCHEDULED.name)
                val now = System.currentTimeMillis()
                val earliest = scheduled.minOfOrNull { it.scheduledTriggerAtMillis } ?: return@launch

                if (earliest > now) {
                    NightAlarmScheduler.scheduleQueueStartAlarm(context, earliest)
                    val prefs = NightDropPreferences(context).getCurrentSettings()
                    if (prefs.cutoffEnabled) {
                        val cutoffMillis = NightAlarmScheduler.computeCutoffTriggerAfter(
                            startTriggerMillis = earliest,
                            cutoffHour = prefs.cutoffHour,
                            cutoffMinute = prefs.cutoffMinute
                        )
                        NightAlarmScheduler.scheduleCutoffAlarm(context, cutoffMillis)
                    }
                } else {
                    scheduled.forEach { task ->
                        dao.updateTask(
                            task.copy(
                                status = TaskStatus.FAILED.name,
                                errorMessage = "دستگاه در زمان زمان‌بندی شده خاموش بود"
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Avoid uncaught coroutine crash during early boot
            } finally {
                pendingResult.finish()
            }
        }
    }
}
