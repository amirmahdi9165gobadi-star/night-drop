package com.example.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object NightAlarmScheduler {

    const val EXTRA_TASK_ID = "extra_task_id"
    private const val REQUEST_CODE_QUEUE_START = 9101
    private const val REQUEST_CODE_CUTOFF = 9102

    fun computeNextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis + 1_000L) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun computeCutoffTriggerAfter(
        startTriggerMillis: Long,
        cutoffHour: Int,
        cutoffMinute: Int
    ): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = startTriggerMillis
            set(Calendar.HOUR_OF_DAY, cutoffHour)
            set(Calendar.MINUTE, cutoffMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= startTriggerMillis + 1_000L) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun scheduleQueueStartAlarm(context: Context, triggerAtMillis: Long): Boolean {
        return scheduleExactCompat(
            context = context,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = buildQueueStartPendingIntent(context)
        )
    }

    fun cancelQueueStartAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildQueueStartPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun scheduleCutoffAlarm(context: Context, triggerAtMillis: Long): Boolean {
        return scheduleExactCompat(
            context = context,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = buildCutoffPendingIntent(context)
        )
    }

    fun cancelCutoffAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildCutoffPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun scheduleExactCompat(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        return try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                false
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            false
        }
    }

    private fun buildQueueStartPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DownloadAlarmReceiver::class.java).apply {
            action = "com.example.nightdrop.ACTION_TRIGGER_QUEUE_START"
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_QUEUE_START,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCutoffPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CutoffAlarmReceiver::class.java).apply {
            action = "com.example.nightdrop.ACTION_TRIGGER_CUTOFF"
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_CUTOFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
