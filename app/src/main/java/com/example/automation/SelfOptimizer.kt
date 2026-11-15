package com.example.automation

import com.example.data.local.ExecutionLogDao
import com.example.data.local.ExecutionLogEntity
import com.example.data.local.LogSeverity
import com.example.data.local.MacroDao
import com.example.data.local.MacroStepEntity
import com.example.data.local.NightDropPreferences
import kotlin.math.abs

data class CoordinateCalibrationEvent(
    val stepId: Long,
    val stepLabel: String,
    val oldX: Int,
    val oldY: Int,
    val newX: Int,
    val newY: Int
)

data class SelfOptimizationReport(
    val calibratedSteps: List<CoordinateCalibrationEvent>,
    val disconnectEventsObserved: Int,
    val previousProbeIntervalSec: Int,
    val newProbeIntervalSec: Int,
    val previousPingTimeoutSec: Int,
    val newPingTimeoutSec: Int,
    val summary: String
) {
    val thresholdsChanged: Boolean
        get() = previousProbeIntervalSec != newProbeIntervalSec ||
            previousPingTimeoutSec != newPingTimeoutSec
}

/**
 * Post-Operation Self-Optimizer:
 * 1. Auto-Calibrates drifted macro step coordinates whenever Semantic UI Finder / Node lookup
 *    locates and clicks the button at updated screen coordinates.
 * 2. Audits nightly execution logs; if >3 network disconnects/stalls occurred, intelligently
 *    increases network probe interval & ping timeout for subsequent nights.
 */
class SelfOptimizer(
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val sessionCalibrations = mutableListOf<CoordinateCalibrationEvent>()

    fun clearSessionCalibrations() {
        sessionCalibrations.clear()
    }

    suspend fun calibrateStepIfDrifted(
        step: MacroStepEntity,
        result: MacroStepExecutionResult,
        macroDao: MacroDao
    ): CoordinateCalibrationEvent? {
        if (!result.succeeded || !result.usedSmartNodeClick) return null
        val newX = result.resolvedBoundsCenterX ?: return null
        val newY = result.resolvedBoundsCenterY ?: return null
        if (newX <= 0 || newY <= 0) return null

        val oldX = step.pixelStartX
        val oldY = step.pixelStartY
        val drifted = oldX <= 0 || oldY <= 0 ||
            abs(newX - oldX) >= DRIFT_THRESHOLD_PX ||
            abs(newY - oldY) >= DRIFT_THRESHOLD_PX
        if (!drifted) return null

        val screenW = (result.resolvedScreenWidth ?: DEFAULT_REF_WIDTH).coerceAtLeast(1)
        val screenH = (result.resolvedScreenHeight ?: DEFAULT_REF_HEIGHT).coerceAtLeast(1)
        val newNormX = (newX.toFloat() / screenW).coerceIn(0f, 1f)
        val newNormY = (newY.toFloat() / screenH).coerceIn(0f, 1f)

        val updatedEntity = step.copy(
            pixelStartX = newX,
            pixelStartY = newY,
            pixelEndX = newX,
            pixelEndY = newY,
            normStartX = newNormX,
            normStartY = newNormY,
            normEndX = newNormX,
            normEndY = newNormY,
            nodeText = step.nodeText ?: result.matchedNodeLabel?.takeIf { it.isNotBlank() }
        )
        macroDao.updateStep(updatedEntity)

        val event = CoordinateCalibrationEvent(
            stepId = step.id,
            stepLabel = step.label,
            oldX = oldX,
            oldY = oldY,
            newX = newX,
            newY = newY
        )
        sessionCalibrations.add(event)
        return event
    }

    suspend fun analyzeNightLogsAndOptimize(
        sessionStartMillis: Long,
        executionLogDao: ExecutionLogDao,
        preferences: NightDropPreferences
    ): SelfOptimizationReport {
        val logs = executionLogDao.getLogsSince(sessionStartMillis)
        val disconnectCount = countDisconnectionEvents(logs)
        val current = preferences.getCurrentSettings()

        val prevProbe = current.adaptiveProbeIntervalSeconds
        val prevTimeout = current.adaptivePingTimeoutSeconds

        var nextProbe = prevProbe
        var nextTimeout = prevTimeout

        val summary = when {
            disconnectCount > DISCONNECT_THRESHOLD -> {
                nextProbe = (prevProbe + 2).coerceIn(2, MAX_PROBE_INTERVAL_SEC)
                nextTimeout = (prevTimeout + 15).coerceIn(20, MAX_PING_TIMEOUT_SEC)
                "بهینه‌سازی خودکار پس از $disconnectCount قطعی شبکه: افزایش بازه پایش به ${nextProbe}s و تایماوت به ${nextTimeout}s"
            }
            disconnectCount == 0 && (prevProbe > BASELINE_PROBE_INTERVAL_SEC || prevTimeout > BASELINE_PING_TIMEOUT_SEC) -> {
                nextProbe = (prevProbe - 1).coerceAtLeast(BASELINE_PROBE_INTERVAL_SEC)
                nextTimeout = (prevTimeout - 5).coerceAtLeast(BASELINE_PING_TIMEOUT_SEC)
                "پایداری کامل شبکه؛ کاهش تدریجی بازه پایش به ${nextProbe}s و تایماوت به ${nextTimeout}s"
            }
            else -> {
                "عملکرد پایدار ($disconnectCount رویداد شبکه)؛ کالیبراسیون مختصات: ${sessionCalibrations.size} مورد"
            }
        }

        preferences.updateAdaptiveNetworkThresholds(
            probeIntervalSeconds = nextProbe,
            pingTimeoutSeconds = nextTimeout,
            optimizationSummary = summary
        )

        val severity = if (nextProbe != prevProbe || nextTimeout != prevTimeout || sessionCalibrations.isNotEmpty()) {
            LogSeverity.INFO
        } else {
            LogSeverity.SUCCESS
        }

        executionLogDao.insertLog(
            ExecutionLogEntity(
                taskId = 0L,
                title = "گزارش موتور خوداصلاحگری (SelfOptimizer)",
                detail = summary,
                severity = severity.name,
                timestampMillis = clockMillis()
            )
        )

        return SelfOptimizationReport(
            calibratedSteps = sessionCalibrations.toList(),
            disconnectEventsObserved = disconnectCount,
            previousProbeIntervalSec = prevProbe,
            newProbeIntervalSec = nextProbe,
            previousPingTimeoutSec = prevTimeout,
            newPingTimeoutSec = nextTimeout,
            summary = summary
        )
    }

    companion object {
        const val DRIFT_THRESHOLD_PX = 20
        const val DISCONNECT_THRESHOLD = 3
        const val BASELINE_PROBE_INTERVAL_SEC = 3
        const val BASELINE_PING_TIMEOUT_SEC = 30
        const val MAX_PROBE_INTERVAL_SEC = 10
        const val MAX_PING_TIMEOUT_SEC = 90
        private const val DEFAULT_REF_WIDTH = 1080
        private const val DEFAULT_REF_HEIGHT = 2400

        fun countDisconnectionEvents(logs: List<ExecutionLogEntity>): Int {
            return logs.count { log ->
                val text = "${log.title} ${log.detail}"
                val isNetworkFailure = text.contains("قطعی") ||
                    text.contains("حالت هواپیما") ||
                    text.contains("پینگ") && (text.contains("ناموفق") || text.contains("نشد")) ||
                    text.contains("اینترنت هنوز متصل است") ||
                    text.contains("تلاش مجدد ماکروی قبل از دانلود") ||
                    text.contains("عدم تایید اتصال اینترنت") ||
                    text.contains("فایل ناموفق بود")
                isNetworkFailure && (log.severity == LogSeverity.WARNING.name || log.severity == LogSeverity.ERROR.name)
            }
        }
    }
}
