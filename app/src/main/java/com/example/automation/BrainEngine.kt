package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Immutable
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.GestureActionType
import com.example.data.local.LogSeverity
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStep
import com.example.data.local.MacroStepEntity
import com.example.data.local.StrategyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

enum class BrainState(val persianTitle: String) {
    IDLE("آماده‌باش"),
    WAKING_UP("بیدارسازی صفحه و بازگشایی قفل"),
    CHECKING_CONDITIONS("بررسی هوشمند باتری و شبکه"),
    EXECUTING_PRE_MACRO("اجرای ماکروی اتصال شبکه"),
    VERIFYING_NETWORK("تایید هوشمند اتصال اینترنت"),
    DOWNLOADING("دانلود هوشمند و پایش صف"),
    RECOVERING_ERROR("خودترمیمی و رفع مانع صفحه"),
    EXECUTING_POST_MACRO("اجرای ماکروی قطع اینترنت"),
    SAFE_SHUTDOWN("پروتکل تایید آفلاین و خاموشی ایمن"),
    EVOLVING_WITH_AI("تکامل هوشمند AI (Gemini ← DeepSeek)")
}

@Immutable
data class BatteryStatus(
    val levelPercent: Int,
    val isCharging: Boolean
)

data class PreCheckDecision(
    val shouldProceed: Boolean,
    val skipPreMacroBecauseOnline: Boolean,
    val abortReason: String? = null,
    val batteryStatus: BatteryStatus
)

sealed class DownloadPriorityDecision {
    data class ContinueCurrent(
        val speedBytesPerSec: Long,
        val etaMillis: Long,
        val remainingToCutoffMillis: Long?
    ) : DownloadPriorityDecision()

    data class PauseAndSwitchToSmaller(
        val nextTask: DownloadTaskEntity,
        val speedBytesPerSec: Long,
        val etaMillis: Long,
        val remainingToCutoffMillis: Long
    ) : DownloadPriorityDecision()
}

data class OfflineProtocolOutcome(
    val confirmedOffline: Boolean,
    val attemptsUsed: Int
)

data class StoragePreFlightResult(
    val allowed: Boolean,
    val contentLengthBytes: Long,
    val availableBytes: Long,
    val requiredBytes: Long,
    val reason: String? = null
)

@Immutable
data class BrainSnapshot(
    val state: BrainState = BrainState.IDLE,
    val decisionMessage: String = "موتور هوشمند آماده دریافت زمان‌بندی شبانه است",
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isNetworkOnline: Boolean = false,
    val speedBytesPerSec: Long = 0L,
    val estimatedEtaSeconds: Long = -1L,
    val cutoffRemainingSeconds: Long = -1L,
    val healingAttempt: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

object BrainEngineBus {
    private val _state = MutableStateFlow(BrainSnapshot())
    val state: StateFlow<BrainSnapshot> = _state.asStateFlow()

    fun update(transform: (BrainSnapshot) -> BrainSnapshot) {
        _state.update(transform)
    }

    fun resetToIdle(message: String = "آماده اجرای زمان‌بندی شبانه") {
        _state.value = BrainSnapshot(
            state = BrainState.IDLE,
            decisionMessage = message,
            updatedAtMillis = System.currentTimeMillis()
        )
    }
}

/**
 * Central Intelligent State Machine & Context-Aware Decision Engine.
 * Governs pre-checks (battery & anti-misclick network detection), self-healing UI execution,
 * ETA-based download queue prioritization, and double-check offline shutdown protocol.
 */
class BrainEngine(
    context: Context,
    private val selfHealWaitMs: Long = 2_000L,
    private val offlineVerifyDelayMs: Long = 3_000L,
    private val minSpeedWarmupMillis: Long = 6_000L,
    private val airplaneResetWaitMs: Long = 10_000L,
    private val airplanePanelSettlingMs: Long = 700L,
    private val batteryProvider: (() -> BatteryStatus)? = null,
    private val networkOnlineProvider: (() -> Boolean)? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext: Context = context.applicationContext
    private val _snapshot = MutableStateFlow(BrainSnapshot())
    val snapshot: StateFlow<BrainSnapshot> = _snapshot.asStateFlow()
    val isAccessibilityConnected: StateFlow<Boolean> = MacroAccessibilityService.isConnected

    fun activeAccessibilityService(): MacroAccessibilityService? = MacroAccessibilityService.instance

    private fun readBattery(): BatteryStatus = batteryProvider?.invoke() ?: readSystemBatteryStatus(appContext)

    private fun isNetworkOnline(): Boolean =
        networkOnlineProvider?.invoke() ?: ConnectivityChecker.isAnyInternetConnected(appContext)

    fun transitionTo(
        newState: BrainState,
        decisionMessage: String,
        healingAttempt: Int = 0
    ) {
        val next = _snapshot.value.copy(
            state = newState,
            decisionMessage = decisionMessage,
            healingAttempt = healingAttempt,
            updatedAtMillis = clockMillis()
        )
        _snapshot.value = next
        BrainEngineBus.update { next }
    }

    /**
     * 2. Smart Pre-Check:
     * - Aborts if battery < 15% and not charging.
     * - Detects if internet is already connected to skip PRE_DOWNLOAD macro (anti-misclick).
     */
    fun evaluatePreChecks(): PreCheckDecision {
        transitionTo(BrainState.CHECKING_CONDITIONS, "در حال بررسی سطح باتری و وضعیت فعلی اینترنت...")

        val battery = readBattery()
        val alreadyOnline = isNetworkOnline()

        _snapshot.update {
            val updated = it.copy(
                batteryPercent = battery.levelPercent,
                isCharging = battery.isCharging,
                isNetworkOnline = alreadyOnline,
                updatedAtMillis = clockMillis()
            )
            BrainEngineBus.update { updated }
            updated
        }

        if (battery.levelPercent in 0 until LOW_BATTERY_THRESHOLD_PERCENT && !battery.isCharging) {
            val reason = "به دلیل کمبود باتری عملیات انجام نشد (${battery.levelPercent}% و بدون شارژر)"
            transitionTo(BrainState.IDLE, reason)
            return PreCheckDecision(
                shouldProceed = false,
                skipPreMacroBecauseOnline = false,
                abortReason = reason,
                batteryStatus = battery
            )
        }

        if (alreadyOnline) {
            transitionTo(
                BrainState.VERIFYING_NETWORK,
                "اینترنت از قبل متصل است؛ ماکروی روشن کردن دیتا برای جلوگیری از کلیک اشتباه رد شد"
            )
        }

        return PreCheckDecision(
            shouldProceed = true,
            skipPreMacroBecauseOnline = alreadyOnline,
            abortReason = null,
            batteryStatus = battery
        )
    }

    /**
     * 3. Self-Healing UI Navigation:
     * Executes a macro sequence step-by-step and inspects window feedback.
     * If a blocking popup/ad or UI dead-end blocks progress, triggers BACK/HOME,
     * waits 2 seconds, and restarts the macro with fresh focus (up to 3 attempts).
     */
    suspend fun executeMacroWithSelfHealing(
        phase: MacroPhase,
        steps: List<MacroStepEntity>,
        maxAttempts: Int = 3,
        stepExecutor: suspend (Int, MacroStepEntity) -> MacroStepExecutionResult,
        selfHealNavigator: suspend (useHome: Boolean) -> Unit,
        onLog: suspend (title: String, detail: String, severity: LogSeverity) -> Unit
    ): Boolean {
        if (steps.isEmpty()) return true

        val targetState = if (phase == MacroPhase.PRE_DOWNLOAD) {
            BrainState.EXECUTING_PRE_MACRO
        } else {
            BrainState.EXECUTING_POST_MACRO
        }

        for (attempt in 1..maxAttempts) {
            transitionTo(
                targetState,
                "اجرای ماکرو (${phase.name}) - تلاش $attempt از $maxAttempts",
                healingAttempt = if (attempt > 1) attempt - 1 else 0
            )

            var needsHealing = false
            var healingReason = ""

            for ((index, step) in steps.withIndex()) {
                val result = stepExecutor(index, step)
                val failedDispatch = !result.succeeded
                val popupBlocked = result.blockingPopupDetected
                val missedNodeNoChange = result.nodeExpectedButMissing &&
                    !result.usedSmartNodeClick &&
                    !result.windowChanged &&
                    step.actionType == GestureActionType.TAP.name

                if (failedDispatch || popupBlocked || missedNodeNoChange) {
                    needsHealing = true
                    healingReason = when {
                        popupBlocked -> "پاپ‌آپ یا دیالوگ مزاحم در گام ${index + 1} («${step.label}») شناسایی شد"
                        failedDispatch -> "عدم موفقیت در اجرای گام ${index + 1} («${step.label}»)"
                        else -> "تغییری در صفحه پس از گام ${index + 1} («${step.label}») رخ نداد"
                    }
                    break
                }

                if (step.delayAfterMs > 0L) {
                    delay(step.delayAfterMs)
                }
            }

            if (!needsHealing) {
                return true
            }

            if (attempt < maxAttempts) {
                transitionTo(
                    BrainState.RECOVERING_ERROR,
                    "$healingReason؛ بازگشت و تلاش مجدد ($attempt از $maxAttempts)...",
                    healingAttempt = attempt
                )
                onLog(
                    "خودترمیمی رابط کاربری (تلاش $attempt از $maxAttempts)",
                    "$healingReason — ارسال دستور بازگشت/خانه و انتظار ۲ ثانیه...",
                    LogSeverity.WARNING
                )
                selfHealNavigator(attempt >= 2)
                delay(selfHealWaitMs)
            } else {
                onLog(
                    "توقف خودترمیمی پس از $maxAttempts تلاش",
                    healingReason,
                    LogSeverity.ERROR
                )
            }
        }
        return false
    }

    /**
     * 4. Smart Queue Prioritizer:
     * Computes live average speed (bytes/sec) and ETA. If remaining time until Force Stop
     * Cutoff is shorter than ETA, pauses the heavy download and switches to a smaller queued item.
     */
    fun evaluateDownloadFeasibility(
        activeTask: DownloadTaskEntity,
        bytesDownloaded: Long,
        totalBytes: Long,
        elapsedDownloadMillis: Long,
        cutoffTriggerAtMillis: Long?,
        queuedCandidates: List<DownloadTaskEntity>
    ): DownloadPriorityDecision {
        val now = clockMillis()
        val elapsedSec = (elapsedDownloadMillis / 1_000.0).coerceAtLeast(1.0)
        val speedBps = if (bytesDownloaded > 0L) {
            (bytesDownloaded / elapsedSec).toLong().coerceAtLeast(1L)
        } else {
            0L
        }

        val remainingBytes = if (totalBytes > bytesDownloaded) totalBytes - bytesDownloaded else -1L
        val etaMillis = if (remainingBytes > 0L && speedBps > 0L) {
            (remainingBytes * 1_000L) / speedBps
        } else {
            -1L
        }

        val remainingCutoffMillis = cutoffTriggerAtMillis
            ?.takeIf { it > now }
            ?.let { it - now }

        _snapshot.update {
            val updated = it.copy(
                state = BrainState.DOWNLOADING,
                speedBytesPerSec = speedBps,
                estimatedEtaSeconds = if (etaMillis >= 0L) etaMillis / 1_000L else -1L,
                cutoffRemainingSeconds = remainingCutoffMillis?.div(1_000L) ?: -1L,
                decisionMessage = if (speedBps > 0L) {
                    "سرعت: ${speedBps / 1024} KB/s | ETA: ${if (etaMillis >= 0L) "${etaMillis / 1000}s" else "نامشخص"}"
                } else {
                    "در حال پایش سرعت دانلود «${activeTask.fileName}»..."
                },
                updatedAtMillis = now
            )
            BrainEngineBus.update { updated }
            updated
        }

        if (elapsedDownloadMillis >= minSpeedWarmupMillis &&
            etaMillis > 0L &&
            remainingCutoffMillis != null &&
            remainingCutoffMillis > 0L &&
            etaMillis > remainingCutoffMillis &&
            queuedCandidates.isNotEmpty()
        ) {
            val smallerCandidate = queuedCandidates
                .filter { it.id != activeTask.id }
                .sortedWith(
                    compareBy<DownloadTaskEntity> { candidate ->
                        if (candidate.totalBytes > 0L) candidate.totalBytes else Long.MAX_VALUE
                    }.thenBy { it.queueOrder }
                )
                .firstOrNull { candidate ->
                    candidate.totalBytes <= 0L || candidate.totalBytes < remainingBytes
                }

            if (smallerCandidate != null) {
                transitionTo(
                    BrainState.DOWNLOADING,
                    "فایل «${activeTask.fileName}» تا ساعت توقف کامل نمی‌شود؛ سوئیچ به فایل کم‌حجم‌تر «${smallerCandidate.fileName}»"
                )
                return DownloadPriorityDecision.PauseAndSwitchToSmaller(
                    nextTask = smallerCandidate,
                    speedBytesPerSec = speedBps,
                    etaMillis = etaMillis,
                    remainingToCutoffMillis = remainingCutoffMillis
                )
            }
        }

        return DownloadPriorityDecision.ContinueCurrent(
            speedBytesPerSec = speedBps,
            etaMillis = etaMillis,
            remainingToCutoffMillis = remainingCutoffMillis
        )
    }

    /**
     * 5. Double-Check Offline Protocol:
     * Executes internet-disconnect macro steps, waits 3 seconds, checks ConnectivityManager
     * to confirm NO network connection remains, retries disconnect macro if still online,
     * and only triggers power-off steps once verified offline.
     */
    suspend fun executeDoubleCheckOfflineProtocol(
        disconnectSteps: List<MacroStepEntity>,
        powerSteps: List<MacroStepEntity>,
        maxDisconnectRetries: Int = 2,
        runMacro: suspend (MacroPhase, List<MacroStepEntity>) -> Boolean,
        onLog: suspend (title: String, detail: String, severity: LogSeverity) -> Unit
    ): OfflineProtocolOutcome {
        transitionTo(BrainState.EXECUTING_POST_MACRO, "اجرای ماکروی قطع اینترنت و بستن اتصالات...")
        runMacro(MacroPhase.POST_DOWNLOAD, disconnectSteps)

        transitionTo(BrainState.SAFE_SHUTDOWN, "انتظار ۳ ثانیه برای تایید قطعی قطع اینترنت...")
        delay(offlineVerifyDelayMs)

        var attempt = 0
        while (isNetworkOnline() && attempt < maxDisconnectRetries) {
            attempt++
            onLog(
                "هشدار: اینترنت هنوز متصل است (تلاش مجدد $attempt)",
                "پروتکل تایید آفلاین اتصال فعال تشخیص داد؛ اجرای مجدد ماکروی قطع دیتا...",
                LogSeverity.WARNING
            )
            transitionTo(
                BrainState.RECOVERING_ERROR,
                "اینترنت هنوز قطع نشده؛ اجرای مجدد ماکروی قطع دیتا ($attempt از $maxDisconnectRetries)..."
            )
            runMacro(MacroPhase.POST_DOWNLOAD, disconnectSteps)
            transitionTo(BrainState.SAFE_SHUTDOWN, "بررسی مجدد قطع بودن کامل شبکه پس از ۳ ثانیه...")
            delay(offlineVerifyDelayMs)
        }

        val stillOnline = isNetworkOnline()
        _snapshot.update {
            val updated = it.copy(
                isNetworkOnline = stillOnline,
                updatedAtMillis = clockMillis()
            )
            BrainEngineBus.update { updated }
            updated
        }

        if (stillOnline) {
            onLog(
                "خاموشی ایمن متوقف شد: شبکه همچنان متصل است",
                "پس از ${attempt + 1} بار بررسی، اتصال اینترنت قطع نشد؛ فرمان خاموشی صادر نشد.",
                LogSeverity.ERROR
            )
            transitionTo(BrainState.IDLE, "اینترنت قطع نشد؛ فرمان خاموشی جهت ایمنی لغو گردید")
            return OfflineProtocolOutcome(confirmedOffline = false, attemptsUsed = attempt + 1)
        }

        onLog(
            "تایید قطعی آفلاین بودن دستگاه",
            "تمام اتصالات شبکه با موفقیت قطع شدند" +
                if (powerSteps.isNotEmpty()) "؛ اجرای فرمان خاموشی ایمن..." else ".",
            LogSeverity.SUCCESS
        )

        if (powerSteps.isNotEmpty()) {
            transitionTo(BrainState.SAFE_SHUTDOWN, "اینترنت تاییداً قطع شد؛ باز کردن منو/فرمان خاموش کردن گوشی...")
            runMacro(MacroPhase.POST_DOWNLOAD, powerSteps)
        }

        transitionTo(BrainState.IDLE, "چرخه شبانه با پروتکل خاموشی ایمن به پایان رسید")
        return OfflineProtocolOutcome(confirmedOffline = true, attemptsUsed = attempt + 1)
    }

    /**
     * Multi-Strategy Fallback & Multi-Tier AI Cloud/App Evolution:
     * 1. Executes offline strategies sorted by Room successScore (-25 fail / +10 win).
     * 2. Tier-1 AI: Calls GeminiApiTroubleshooter capped at [geminiTimeoutMs] (default 8s).
     * 3. Tier-2 AI Emergency Fallback: On Gemini timeout, exception, or failure, calls
     *    DeepSeekAppTroubleshooter.solveViaDeepSeekApp() and persists learned steps to Room.
     */
    suspend fun executeWithStrategyRanking(
        goal: StrategyGoal,
        strategyRanker: StrategyRanker,
        executeStrategy: suspend (StrategyEntity) -> Boolean,
        runGeminiFallback: (suspend () -> Pair<Boolean, List<MacroStep>>)? = null,
        runDeepSeekFallback: (suspend () -> Pair<Boolean, List<MacroStep>>)? = null,
        geminiTimeoutMs: Long = DEFAULT_GEMINI_TIMEOUT_MS,
        persistLearnedMacro: (suspend (List<MacroStep>) -> Unit)? = null,
        onLog: suspend (title: String, detail: String, severity: LogSeverity) -> Unit
    ): Boolean {
        val ranked = strategyRanker.getRankedStrategies(goal)
        for (strategy in ranked) {
            onLog(
                "اجرای استراتژی رتبه‌بندی‌شده (${strategy.successScore} امتیاز)",
                "${strategy.title} برای «${goal.persianName}»",
                LogSeverity.INFO
            )
            val succeeded = executeStrategy(strategy)
            val updated = strategyRanker.recordOutcome(strategy.id, succeeded)
            if (succeeded) {
                onLog(
                    "موفقیت استراتژی (${updated?.successScore ?: strategy.successScore} امتیاز)",
                    "${strategy.title} با موفقیت انجام شد.",
                    LogSeverity.SUCCESS
                )
                return true
            } else {
                onLog(
                    "شکست استراتژی (${updated?.successScore ?: strategy.successScore} امتیاز)",
                    "${strategy.title} ناموفق بود؛ کاهش امتیاز و حرکت به استراتژی جایگزین...",
                    LogSeverity.WARNING
                )
            }
        }

        // Tier 1: Gemini API Troubleshooter (strict 8-second timeout)
        if (runGeminiFallback != null) {
            transitionTo(
                BrainState.EVOLVING_WITH_AI,
                "تلاش اول AI: ارسال نقشه صفحه به Gemini API (حداکثر ${geminiTimeoutMs / 1000} ثانیه)..."
            )
            onLog(
                "فعال‌سازی لایه اول AI (Gemini API - تایم‌اوت ${geminiTimeoutMs / 1000}s)",
                "استراتژی‌های آفلاین برای «${goal.persianName}» به بن‌بست رسیدند؛ ارسال نقشه UI به Gemini...",
                LogSeverity.WARNING
            )

            var geminiTimedOut = false
            var geminiErrorMessage: String? = null
            val tier1Outcome = try {
                val timedResult = withTimeoutOrNull(geminiTimeoutMs) {
                    runGeminiFallback()
                }
                if (timedResult == null) {
                    geminiTimedOut = true
                }
                timedResult
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                geminiErrorMessage = e.message ?: e::class.java.simpleName
                null
            }

            if (tier1Outcome != null && tier1Outcome.first && tier1Outcome.second.isNotEmpty()) {
                persistLearnedMacro?.invoke(tier1Outcome.second)
                onLog(
                    "خودارتقایی موفق با Gemini AI (لایه اول)",
                    "راه‌حل جدید شامل ${tier1Outcome.second.size} گام با موفقیت اجرا و برای شب‌های آینده ذخیره شد.",
                    LogSeverity.SUCCESS
                )
                return true
            }

            val failureDetail = when {
                geminiTimedOut -> "تایم‌اوت ${geminiTimeoutMs / 1000} ثانیه‌ای در اتصال به Gemini API"
                geminiErrorMessage != null -> "خطای اتصال Gemini API: $geminiErrorMessage"
                else -> "Gemini API نتوانست راه‌حل اجرایی معتبری ارائه دهد"
            }
            onLog(
                "عدم موفقیت لایه اول AI (Gemini)",
                "$failureDetail" + if (runDeepSeekFallback != null) " ← فعال‌سازی فوری پشتیبان اضطراری DeepSeek App..." else ".",
                LogSeverity.WARNING
            )
        }

        // Tier 2: Emergency Fallback -> Local DeepSeek Android App (com.deepseek.chat)
        if (runDeepSeekFallback != null) {
            transitionTo(
                BrainState.EVOLVING_WITH_AI,
                "تلاش دوم (پشتیبان اضطراری): باز کردن اپلیکیشن DeepSeek و دریافت راه‌حل..."
            )
            onLog(
                "فعال‌سازی لایه دوم AI (پشتیبان اضطراری DeepSeek App)",
                "باز کردن اپلیکیشن DeepSeek برای تحلیل نقشه صفحه «${goal.persianName}» و استخراج دستورات لمسی...",
                LogSeverity.WARNING
            )

            val tier2Outcome = try {
                runDeepSeekFallback()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onLog(
                    "خطا در اجرای پشتیبان اضطراری DeepSeek",
                    e.message ?: "خطای غیرمنتظره در تعامل با اپلیکیشن DeepSeek",
                    LogSeverity.ERROR
                )
                null
            }

            if (tier2Outcome != null && tier2Outcome.first && tier2Outcome.second.isNotEmpty()) {
                persistLearnedMacro?.invoke(tier2Outcome.second)
                onLog(
                    "خودارتقایی موفق با اپلیکیشن DeepSeek (لایه دوم)",
                    "راه‌حل استخراج‌شده از DeepSeek شامل ${tier2Outcome.second.size} گام با موفقیت اجرا و در دیتابیس ذخیره شد.",
                    LogSeverity.SUCCESS
                )
                return true
            } else {
                onLog(
                    "عدم موفقیت پشتیبان اضطراری DeepSeek",
                    "زنجیره چندمرحله‌ای AI نتوانست راه‌حل معتبری از DeepSeek استخراج یا اجرا کند.",
                    LogSeverity.ERROR
                )
            }
        }

        return false
    }

    /**
     * Pre-Flight Storage Check (Requirement #3):
     * Requires at least [contentLengthBytes] (when known) + 500 MB safety margin
     * on the device storage before queuing a download.
     */
    fun evaluateStoragePreFlight(
        fileName: String,
        contentLengthBytes: Long,
        availableBytes: Long,
        safetyMarginBytes: Long = DEFAULT_STORAGE_SAFETY_MARGIN_BYTES
    ): StoragePreFlightResult {
        val knownSize = contentLengthBytes.coerceAtLeast(0L)
        val requiredBytes = knownSize + safetyMarginBytes
        return if (availableBytes < requiredBytes) {
            val needMb = requiredBytes / (1024L * 1024L)
            val freeMb = availableBytes / (1024L * 1024L)
            StoragePreFlightResult(
                allowed = false,
                contentLengthBytes = contentLengthBytes,
                availableBytes = availableBytes,
                requiredBytes = requiredBytes,
                reason = "به دلیل کمبود فضای حافظه رد شد (موردنیاز با ۵۰۰MB حاشیه: ${needMb}MB، آزاد: ${freeMb}MB برای «$fileName»)"
            )
        } else {
            StoragePreFlightResult(
                allowed = true,
                contentLengthBytes = contentLengthBytes,
                availableBytes = availableBytes,
                requiredBytes = requiredBytes
            )
        }
    }

    /**
     * Airplane Mode Network Reset (Requirement #2):
     * Triggered when download stalls at 0 speed and 2 consecutive 204 pings fail while mobile data is ON.
     * Opens Quick Settings, toggles Airplane mode ON, waits 10 seconds, toggles it OFF, and awaits network recovery.
     */
    suspend fun performAirplaneModeReset(
        openQuickSettings: suspend () -> Boolean,
        toggleAirplaneNode: suspend () -> Boolean,
        closePanel: suspend () -> Unit,
        awaitNetworkBack: suspend () -> Boolean,
        onLog: suspend (title: String, detail: String, severity: LogSeverity) -> Unit
    ): Boolean {
        transitionTo(
            BrainState.RECOVERING_ERROR,
            "قفل شدن شبکه تشخیص داده شد؛ باز کردن کنترل‌سنتر و فعال‌سازی حالت هواپیما..."
        )
        onLog(
            "احیای خودکار آنتن و شبکه (Airplane Mode Reset)",
            "سرعت به صفر رسید و ۲ تست پینگ متوالی ناموفق بود؛ فعال‌سازی حالت هواپیما به مدت ${airplaneResetWaitMs / 1000} ثانیه...",
            LogSeverity.WARNING
        )

        openQuickSettings()
        delay(airplanePanelSettlingMs)

        var turnedOn = toggleAirplaneNode()
        if (!turnedOn) {
            delay(airplanePanelSettlingMs)
            turnedOn = toggleAirplaneNode()
        }

        if (!turnedOn) {
            closePanel()
            onLog(
                "دکمه حالت هواپیما در صفحه پیدا نشد",
                "موتور معنایی کلید حالت هواپیما را در کنترل‌سنتر نیافت؛ ادامه پایش دانلود.",
                LogSeverity.WARNING
            )
            transitionTo(BrainState.DOWNLOADING, "کلید حالت هواپیما یافت نشد؛ بازگشت به پایش دانلود")
            return false
        }

        transitionTo(
            BrainState.RECOVERING_ERROR,
            "حالت هواپیما روشن شد؛ انتظار ${airplaneResetWaitMs / 1000} ثانیه برای ریست آنتن..."
        )
        delay(airplaneResetWaitMs)

        openQuickSettings()
        delay(airplanePanelSettlingMs)
        var turnedOff = toggleAirplaneNode()
        if (!turnedOff) {
            delay(airplanePanelSettlingMs)
            turnedOff = toggleAirplaneNode()
        }
        closePanel()

        transitionTo(
            BrainState.VERIFYING_NETWORK,
            "حالت هواپیما غیرفعال شد؛ انتظار برای بازگشت آنتن و تایید پینگ 204..."
        )
        val networkRestored = awaitNetworkBack()
        return if (networkRestored) {
            onLog(
                "احیای موفق آنتن و اینترنت",
                "حالت هواپیما قطع و اتصال واقعی اینترنت مجدداً تایید شد؛ ادامه دانلود.",
                LogSeverity.SUCCESS
            )
            transitionTo(BrainState.DOWNLOADING, "اتصال شبکه احیا شد؛ ادامه دانلود")
            true
        } else {
            onLog(
                "عدم بازگشت اینترنت پس از ریست حالت هواپیما",
                "پس از غیرفعال کردن حالت هواپیما، پینگ 204 هنوز برقرار نشد.",
                LogSeverity.ERROR
            )
            false
        }
    }

    /**
     * Smart Adaptive Watchdog (Battery Rule #1):
     * Uses a 5s interval during download warmup or speed stalls, then backs off to a low-power
     * 15s interval once bytes are flowing steadily to avoid needless CPU wakeups.
     */
    fun computeAdaptiveWatchdogIntervalMs(
        isSteadyDownloading: Boolean,
        zeroSpeedTicks: Int
    ): Long {
        return if (isSteadyDownloading && zeroSpeedTicks == 0) {
            STEADY_WATCHDOG_INTERVAL_MS
        } else {
            ACTIVE_WATCHDOG_INTERVAL_MS
        }
    }

    companion object {
        const val LOW_BATTERY_THRESHOLD_PERCENT = 15
        const val DEFAULT_STORAGE_SAFETY_MARGIN_BYTES = 500L * 1024L * 1024L
        const val DEFAULT_GEMINI_TIMEOUT_MS = 8_000L
        const val ACTIVE_WATCHDOG_INTERVAL_MS = 5_000L
        const val STEADY_WATCHDOG_INTERVAL_MS = 15_000L

        /**
         * Splits POST_DOWNLOAD steps into network-disconnect steps vs power-off steps
         * (starting from GLOBAL_POWER_DIALOG onward) so Double-Check Offline Protocol
         * verifies zero connectivity BEFORE triggering device shutdown.
         */
        fun splitPostMacroSteps(
            postSteps: List<MacroStepEntity>
        ): Pair<List<MacroStepEntity>, List<MacroStepEntity>> {
            val powerIdx = postSteps.indexOfFirst {
                it.actionType == GestureActionType.GLOBAL_POWER_DIALOG.name
            }
            return if (powerIdx >= 0) {
                postSteps.subList(0, powerIdx) to postSteps.subList(powerIdx, postSteps.size)
            } else {
                postSteps to emptyList()
            }
        }

        fun readSystemBatteryStatus(context: Context): BatteryStatus {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            val capacityFromBm = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val level = if (capacityFromBm in 0..100) {
                capacityFromBm
            } else {
                val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (rawLevel >= 0 && scale > 0) (rawLevel * 100) / scale else 100
            }

            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val isCharging = bm?.isCharging == true ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged > 0

            return BatteryStatus(levelPercent = level, isCharging = isCharging)
        }
    }
}
