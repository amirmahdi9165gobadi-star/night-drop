package com.example.data.local

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MacroPhase {
    PRE_DOWNLOAD,
    POST_DOWNLOAD
}

enum class GestureActionType {
    TAP,
    SWIPE,
    GLOBAL_HOME,
    GLOBAL_QUICK_SETTINGS,
    GLOBAL_NOTIFICATIONS,
    GLOBAL_RECENTS,
    GLOBAL_POWER_DIALOG,
    WAKE_SCREEN,
    OPEN_CONNECTIVITY_PANEL,
    VIBRATE_ALERT,
    WAIT_DELAY
}

enum class TaskStatus {
    QUEUED,
    SCHEDULED,
    PRE_ACTIONS_RUNNING,
    WAITING_NETWORK,
    DOWNLOADING,
    PAUSED,
    SKIPPED,
    POST_ACTIONS_RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class LogSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * Clean domain representation of a hybrid macro step combining UI Node semantics
 * (for layout-shift-resilient matching) with exact touch coordinates & timing.
 */
@Immutable
data class MacroStep(
    val id: Long = 0L,
    val phase: MacroPhase = MacroPhase.PRE_DOWNLOAD,
    val orderIndex: Int = 0,
    val type: GestureActionType = GestureActionType.TAP,
    val startX: Int = 0,
    val startY: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    val normStartX: Float = 0.5f,
    val normStartY: Float = 0.5f,
    val normEndX: Float = 0.5f,
    val normEndY: Float = 0.5f,
    val duration: Long = 100L,
    val nodeText: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val delayBeforeNextMs: Long = 500L,
    val label: String = ""
) {
    val hasNodeSelector: Boolean
        get() = !nodeText.isNullOrBlank() || !contentDescription.isNullOrBlank()

    fun primaryNodeLabel(): String? =
        nodeText?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
}

@Immutable
@Entity(tableName = "macro_steps")
data class MacroStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: String,
    val orderIndex: Int,
    val actionType: String,
    val normStartX: Float,
    val normStartY: Float,
    val normEndX: Float,
    val normEndY: Float,
    val pixelStartX: Int,
    val pixelStartY: Int,
    val pixelEndX: Int,
    val pixelEndY: Int,
    val durationMs: Long,
    val delayAfterMs: Long,
    val label: String,
    val nodeText: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null
) {
    fun toMacroStep(): MacroStep = MacroStep(
        id = id,
        phase = runCatching { MacroPhase.valueOf(phase) }.getOrDefault(MacroPhase.PRE_DOWNLOAD),
        orderIndex = orderIndex,
        type = runCatching { GestureActionType.valueOf(actionType) }.getOrDefault(GestureActionType.TAP),
        startX = pixelStartX,
        startY = pixelStartY,
        endX = pixelEndX,
        endY = pixelEndY,
        normStartX = normStartX,
        normStartY = normStartY,
        normEndX = normEndX,
        normEndY = normEndY,
        duration = durationMs,
        nodeText = nodeText,
        contentDescription = contentDescription,
        viewIdResourceName = viewIdResourceName,
        delayBeforeNextMs = delayAfterMs,
        label = label
    )

    val hasNodeSelector: Boolean
        get() = !nodeText.isNullOrBlank() || !contentDescription.isNullOrBlank()

    fun primaryNodeLabel(): String? =
        nodeText?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
}

fun MacroStep.toEntity(): MacroStepEntity = MacroStepEntity(
    id = id,
    phase = phase.name,
    orderIndex = orderIndex,
    actionType = type.name,
    normStartX = normStartX,
    normStartY = normStartY,
    normEndX = normEndX,
    normEndY = normEndY,
    pixelStartX = startX,
    pixelStartY = startY,
    pixelEndX = endX,
    pixelEndY = endY,
    durationMs = duration,
    delayAfterMs = delayBeforeNextMs.coerceAtLeast(500L),
    label = label,
    nodeText = nodeText,
    contentDescription = contentDescription,
    viewIdResourceName = viewIdResourceName
)

@Immutable
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val scheduledHour: Int,
    val scheduledMinute: Int,
    val scheduledTriggerAtMillis: Long,
    val status: String,
    val systemDownloadId: Long = -1L,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val allowMetered: Boolean = true,
    val queueOrder: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null,
    val errorMessage: String? = null
)

@Immutable
@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val detail: String,
    val severity: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

@Immutable
@Entity(tableName = "strategy_rankings")
data class StrategyEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val strategyCode: String,
    val title: String,
    val successScore: Int,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)
