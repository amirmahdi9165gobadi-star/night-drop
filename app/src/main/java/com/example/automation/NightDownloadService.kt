package com.example.automation

import android.app.DownloadManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskDao
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.ExecutionLogDao
import com.example.data.local.ExecutionLogEntity
import com.example.data.local.GestureActionType
import com.example.data.local.LogSeverity
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStepEntity
import com.example.data.local.NightDropPreferences
import com.example.data.local.SchedulerSettings
import com.example.data.local.TaskStatus
import com.example.data.local.toEntity
import com.example.ui.WakeExecutionActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

@Immutable
data class ExecutionLiveState(
    val isActive: Boolean = false,
    val taskId: Long = -1L,
    val fileName: String = "",
    val phaseLabel: String = "",
    val activeStepIndex: Int = -1,
    val steps: List<MacroStepEntity> = emptyList(),
    val countdownSeconds: Int = 0,
    val statusDetail: String = "",
    val yieldToSystem: Boolean = false,
    val isFinished: Boolean = false
)

object NightExecutionBus {
    private val _state = MutableStateFlow(ExecutionLiveState())
    val state: StateFlow<ExecutionLiveState> = _state.asStateFlow()

    fun update(transform: (ExecutionLiveState) -> ExecutionLiveState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = ExecutionLiveState()
    }
}

class NightDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()

    private val brainEngine by lazy { BrainEngine(this) }
    private val selfOptimizer by lazy { SelfOptimizer() }
    private val geminiTroubleshooter by lazy { GeminiApiTroubleshooter() }
    private val deepSeekTroubleshooter by lazy { DeepSeekAppTroubleshooter(this) }

    private var activeWorkflowJob: Job? = null
    private var progressPollingJob: Job? = null

    @Volatile
    private var downloadPartialWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var cutoffTriggered = false

    @Volatile
    private var sessionCutoffMillis: Long? = null

    @Volatile
    private var sessionStartMillis: Long = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        startForegroundCompat(
            notification = buildServiceNotification(
                title = getString(R.string.service_running_title),
                body = when (action) {
                    ACTION_FORCE_CUTOFF -> "زمان توقف اجباری فرارسید؛ قطع دانلود و شبکه..."
                    ACTION_HANDLE_DOWNLOAD_COMPLETE -> "بررسی فایل بعدی صف..."
                    else -> getString(R.string.service_running_pre)
                }
            )
        )

        if (action == null) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START_QUEUE_WORKFLOW -> {
                cutoffTriggered = false
                activeWorkflowJob?.cancel()
                activeWorkflowJob = serviceScope.launch {
                    runQueueStartupWorkflow()
                }
            }
            ACTION_HANDLE_DOWNLOAD_COMPLETE -> {
                val systemDownloadId = intent.getLongExtra(EXTRA_SYSTEM_DOWNLOAD_ID, -1L)
                if (systemDownloadId > 0L) {
                    serviceScope.launch {
                        handleDownloadCompletion(systemDownloadId)
                    }
                }
            }
            ACTION_FORCE_CUTOFF -> {
                cutoffTriggered = true
                activeWorkflowJob?.cancel()
                progressPollingJob?.cancel()
                serviceScope.launch {
                    executeForceCutoff()
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runQueueStartupWorkflow() {
        sessionStartMillis = System.currentTimeMillis()
        selfOptimizer.clearSessionCalibrations()

        val db = AppDatabase.getInstance(this)
        val taskDao = db.downloadTaskDao()
        val macroDao = db.macroDao()
        val logDao = db.executionLogDao()
        val strategyRanker = StrategyRanker(db.strategyDao())
        val prefs = NightDropPreferences(this).getCurrentSettings()

        sessionCutoffMillis = if (prefs.cutoffEnabled) {
            NightAlarmScheduler.computeCutoffTriggerAfter(
                startTriggerMillis = System.currentTimeMillis(),
                cutoffHour = prefs.cutoffHour,
                cutoffMinute = prefs.cutoffMinute
            )
        } else {
            null
        }

        val firstCandidate = queueMutex.withLock {
            taskDao.getNextQueuedTask()
        } ?: run {
            brainEngine.transitionTo(BrainState.IDLE, "صف دانلود خالی است")
            logDao.record(0L, "صف دانلود خالی است", "آیتمی در صف انتظار یافت نشد.", LogSeverity.WARNING)
            stopForegroundAndSelf()
            return
        }

        val wakeSession = acquireWakeLocks(prefs.wakeScreenOnAlarm)
        var handoffPartialWakeToDownload = false
        try {
            brainEngine.transitionTo(
                BrainState.WAKING_UP,
                "بیدارسازی گوشی و آماده‌سازی برای صف (${firstCandidate.fileName})"
            )
            logDao.record(
                taskId = firstCandidate.id,
                title = "شروع هوشمند صف متوالی",
                detail = "نخستین فایل: ${firstCandidate.fileName}",
                severity = LogSeverity.INFO
            )

            val preSteps = macroDao.getStepsForPhase(MacroPhase.PRE_DOWNLOAD.name)
            NightExecutionBus.update {
                ExecutionLiveState(
                    isActive = true,
                    taskId = firstCandidate.id,
                    fileName = firstCandidate.fileName,
                    phaseLabel = BrainState.WAKING_UP.persianTitle,
                    activeStepIndex = -1,
                    steps = preSteps,
                    countdownSeconds = 0,
                    statusDetail = "بیدارسازی صفحه و بررسی هوشمند شرایط...",
                    yieldToSystem = false,
                    isFinished = false
                )
            }

            if (prefs.wakeScreenOnAlarm) {
                launchWakeActivity(firstCandidate.id, "PRE")
                delay(700L)
                dismissSwipeKeyguardIfNeeded(firstCandidate.id, logDao)
            }

            // Smart Pre-Check: Battery >= 15% (or charging) + Anti-Misclick Network Check
            val preCheck = brainEngine.evaluatePreChecks()
            if (!preCheck.shouldProceed) {
                val abortReason = preCheck.abortReason ?: "به دلیل کمبود باتری عملیات انجام نشد"
                logDao.record(
                    taskId = firstCandidate.id,
                    title = "لغو عملیات توسط موتور هوشمند",
                    detail = abortReason,
                    severity = LogSeverity.ERROR
                )
                postCompletionNotification("لغو خودکار NightDrop", succeeded = false)
                NightExecutionBus.update {
                    it.copy(statusDetail = abortReason, isFinished = true)
                }
                stopForegroundAndSelf()
                return
            }

            val preMacroStartMillis = if (!preCheck.skipPreMacroBecauseOnline) System.currentTimeMillis() else -1L

            if (preCheck.skipPreMacroBecauseOnline) {
                logDao.record(
                    taskId = firstCandidate.id,
                    title = "تشخیص اتصال فعال (ضد-کلیک اشتباه)",
                    detail = "اینترنت از قبل متصل است (باتری ${preCheck.batteryStatus.levelPercent}٪)؛ ماکروی روشن کردن دیتا اجرا نشد.",
                    severity = LogSeverity.INFO
                )
            } else {
                if (preSteps.isNotEmpty() && MacroAccessibilityService.instance != null) {
                    NightExecutionBus.update { it.copy(yieldToSystem = true) }
                    delay(350L)
                }
                executeEnableDataWithRankingAndAi(
                    preSteps = preSteps,
                    taskId = firstCandidate.id,
                    prefs = prefs,
                    db = db,
                    strategyRanker = strategyRanker,
                    logDao = logDao
                )
            }
            if (cutoffTriggered) return

            if (!preCheck.skipPreMacroBecauseOnline && prefs.adaptiveTimingSamplesMs.isNotEmpty()) {
                val adaptiveWaitMs = prefs.learnedNetworkDelayMs.coerceIn(1_000L, 12_000L)
                brainEngine.transitionTo(
                    BrainState.VERIFYING_NETWORK,
                    "حافظه تطبیقی سیم‌کارت: انتظار ${adaptiveWaitMs / 1000.0} ثانیه بر اساس میانگین ۳ شب اخیر..."
                )
                delay(adaptiveWaitMs)
                if (cutoffTriggered) return
            }

            brainEngine.transitionTo(
                BrainState.VERIFYING_NETWORK,
                "پایش هوشمند اینترنت (NET_CAPABILITY_VALIDATED + Ping 204)"
            )

            // Smart Connectivity & Ping Check (30s every 3s -> retry PRE macro once -> 30s every 3s)
            val isOnline = waitForValidatedInternetWithRetry(
                preSteps = preSteps,
                taskId = firstCandidate.id,
                logDao = logDao
            )
            if (cutoffTriggered) return

            if (isOnline && preMacroStartMillis > 0L) {
                val measuredMillis = (System.currentTimeMillis() - preMacroStartMillis).coerceAtLeast(1_000L)
                val newAverageMs = NightDropPreferences(this).recordAdaptiveConnectionDuration(measuredMillis)
                logDao.record(
                    taskId = firstCandidate.id,
                    title = "حافظه تطبیقی زمان اتصال سیم‌کارت",
                    detail = "اتصال امشب در ${String.format(java.util.Locale.US, "%.1f", measuredMillis / 1000.0)} ثانیه برقرار شد | میانگین جدید ۳ شب اخیر: ${String.format(java.util.Locale.US, "%.1f", newAverageMs / 1000.0)} ثانیه",
                    severity = LogSeverity.INFO
                )
            }

            if (!isOnline) {
                brainEngine.transitionTo(BrainState.IDLE, "عدم تایید اتصال اینترنت پس از تلاش مجدد")
                queueMutex.withLock {
                    val pending = taskDao.getPendingOrActiveTasks()
                    pending.forEach { pendingTask ->
                        taskDao.updateTask(
                            pendingTask.copy(
                                status = TaskStatus.FAILED.name,
                                errorMessage = "اتصال اینترنت واقعی پس از تلاش مجدد تایید نشد"
                            )
                        )
                    }
                }
                logDao.record(
                    taskId = firstCandidate.id,
                    title = "عدم تایید اتصال اینترنت",
                    detail = "پس از ۲ مرحله بررسی ۳۰ ثانیه‌ای (با Ping 204)، اینترنت وصل نشد.",
                    severity = LogSeverity.ERROR
                )
                NightExecutionBus.update {
                    it.copy(
                        statusDetail = "اتصال اینترنت برقرار نشد",
                        isFinished = true
                    )
                }
                stopForegroundAndSelf()
                return
            }

            // Instant Screen-Off (Battery Rule #1): release screen wake lock immediately once internet is confirmed
            releaseScreenWakeLock(wakeSession)
            NightExecutionBus.update { it.copy(yieldToSystem = true) }

            val started = queueMutex.withLock {
                enqueueNextTaskLocked(taskDao, logDao)
            }
            if (started) {
                retainPartialWakeLockForDownloads(wakeSession.cpuWake)
                handoffPartialWakeToDownload = true
                brainEngine.transitionTo(BrainState.DOWNLOADING, "خاموش شدن صفحه و پایش کم‌مصرف دانلود با PARTIAL_WAKE_LOCK")
                startProgressPolling()
            } else {
                brainEngine.transitionTo(BrainState.IDLE, "صف دانلود خالی شد")
                stopForegroundAndSelf()
            }
        } finally {
            releaseScreenWakeLock(wakeSession)
            if (!handoffPartialWakeToDownload) {
                releaseWakeLockSafe(wakeSession.cpuWake)
            }
        }
    }

    private suspend fun waitForValidatedInternetWithRetry(
        preSteps: List<MacroStepEntity>,
        taskId: Long,
        logDao: ExecutionLogDao
    ): Boolean {
        val tuningPrefs = NightDropPreferences(this).getCurrentSettings()
        val monitor = ConnectionMonitor(
            context = this,
            windowMillis = tuningPrefs.adaptivePingTimeoutSeconds * 1_000L,
            probeIntervalMillis = tuningPrefs.adaptiveProbeIntervalSeconds * 1_000L
        )
        val outcome = monitor.awaitInternetWithMacroRetry(
            onProgress = { progress ->
                if (!cutoffTriggered) {
                    NightExecutionBus.update {
                        it.copy(
                            phaseLabel = "پایش هوشمند اینترنت (دور ${progress.round})",
                            countdownSeconds = progress.remainingSeconds,
                            statusDetail = "تست NET_CAPABILITY_VALIDATED + Ping 204 (تلاش ${progress.attempt})..."
                        )
                    }
                    updateServiceNotification(
                        title = "پایش اتصال اینترنت (دور ${progress.round})",
                        body = "باقی‌مانده: ${progress.remainingSeconds} ثانیه | HTTP=${progress.checkResult.httpStatusCode}"
                    )
                }
            },
            onRetryPreMacro = {
                if (!cutoffTriggered) {
                    val preCheckRetry = brainEngine.evaluatePreChecks()
                    if (!preCheckRetry.skipPreMacroBecauseOnline) {
                        logDao.record(
                            taskId = taskId,
                            title = "تلاش مجدد ماکروی قبل از دانلود",
                            detail = "تا ۳۰ ثانیه اینترنت تایید نشد؛ اجرای مجدد ماکروی اتصال شبکه...",
                            severity = LogSeverity.WARNING
                        )
                        updateServiceNotification("تلاش مجدد اتصال", "اجرای مجدد ماکروی قبل از دانلود...")
                        executeMacroWithBrain(MacroPhase.PRE_DOWNLOAD, preSteps, taskId, logDao)
                    } else {
                        logDao.record(
                            taskId = taskId,
                            title = "رد کردن تلاش مجدد ماکرو",
                            detail = "شبکه در آستانه اتصال است؛ برای جلوگیری از خاموش شدن دیتا ماکرو دوباره اجرا نشد.",
                            severity = LogSeverity.INFO
                        )
                    }
                }
            }
        )

        if (outcome.isConnected) {
            val res = outcome.lastResult
            logDao.record(
                taskId = taskId,
                title = "اتصال اینترنت واقعی تایید شد",
                detail = "Validated=${res?.hasValidatedCapability} | HTTP=${res?.httpStatusCode} (دور ${outcome.roundReached})",
                severity = LogSeverity.SUCCESS
            )
        }
        return outcome.isConnected && !cutoffTriggered
    }

    private suspend fun enqueueNextTaskLocked(
        taskDao: DownloadTaskDao,
        logDao: ExecutionLogDao
    ): Boolean {
        if (cutoffTriggered) return false
        val alreadyDownloading = taskDao.getTasksByStatus(TaskStatus.DOWNLOADING.name)
        if (alreadyDownloading.isNotEmpty()) return true

        while (!cutoffTriggered) {
            val nextTask = taskDao.getNextQueuedTask() ?: return false
            val remoteSizeBytes = DownloadEnqueuer.fetchRemoteContentLength(nextTask.url)
            val availableStorageBytes = DownloadEnqueuer.getAvailableDownloadStorageBytes(this)
            val storageDecision = brainEngine.evaluateStoragePreFlight(
                fileName = nextTask.fileName,
                contentLengthBytes = remoteSizeBytes,
                availableBytes = availableStorageBytes
            )

            if (!storageDecision.allowed) {
                taskDao.updateTask(
                    nextTask.copy(
                        status = TaskStatus.SKIPPED.name,
                        totalBytes = if (remoteSizeBytes > 0L) remoteSizeBytes else nextTask.totalBytes,
                        completedAtMillis = System.currentTimeMillis(),
                        errorMessage = "به دلیل کمبود فضای حافظه رد شد"
                    )
                )
                logDao.record(
                    taskId = nextTask.id,
                    title = "رد خودکار فایل به دلیل کمبود حافظه",
                    detail = storageDecision.reason ?: "به دلیل کمبود فضای حافظه رد شد",
                    severity = LogSeverity.WARNING
                )
                continue
            }

            val enrichedTask = if (remoteSizeBytes > 0L && nextTask.totalBytes <= 0L) {
                nextTask.copy(totalBytes = remoteSizeBytes)
            } else {
                nextTask
            }
            val downloadId = try {
                DownloadEnqueuer.enqueueDownload(this, enrichedTask)
            } catch (error: Exception) {
                taskDao.updateTask(
                    enrichedTask.copy(
                        status = TaskStatus.FAILED.name,
                        completedAtMillis = System.currentTimeMillis(),
                        errorMessage = error.message ?: "لینک دانلود نامعتبر یا خطای DownloadManager"
                    )
                )
                logDao.record(
                    taskId = nextTask.id,
                    title = "خطا در ثبت دانلود (${nextTask.fileName})",
                    detail = error.message ?: "لینک نامعتبر بود و فایل رد شد",
                    severity = LogSeverity.ERROR
                )
                continue
            }
            taskDao.updateTask(
                enrichedTask.copy(
                    status = TaskStatus.DOWNLOADING.name,
                    systemDownloadId = downloadId,
                    errorMessage = null
                )
            )
            logDao.record(
                taskId = nextTask.id,
                title = "شروع دانلود فایل در صف",
                detail = "${nextTask.fileName} | شناسه سیستم: #$downloadId" +
                    if (remoteSizeBytes > 0L) " | حجم: ${remoteSizeBytes / (1024L * 1024L)}MB" else "",
                severity = LogSeverity.INFO
            )
            updateServiceNotification(
                title = "در حال دانلود ${nextTask.fileName}",
                body = "دانلود متوالی صف شبانه فعال است"
            )
            return true
        }
        return false
    }

    private suspend fun handleDownloadCompletion(systemDownloadId: Long) {
        if (cutoffTriggered) return
        val db = AppDatabase.getInstance(this)
        val taskDao = db.downloadTaskDao()
        val logDao = db.executionLogDao()

        var allFinished = false
        var lastSucceeded = true
        var lastFileName = "صف شبانه"
        var completedTaskId = 0L
        var completedLocalUri: String? = null

        queueMutex.withLock {
            val task = taskDao.getTaskBySystemDownloadId(systemDownloadId) ?: return
            if (task.status == TaskStatus.COMPLETED.name || task.status == TaskStatus.CANCELLED.name) {
                return
            }

            val queryState = DownloadEnqueuer.queryProgress(this, systemDownloadId)
            val succeeded = queryState?.status == DownloadManager.STATUS_SUCCESSFUL
            lastSucceeded = succeeded
            lastFileName = task.fileName
            completedTaskId = task.id
            if (succeeded) {
                completedLocalUri = DownloadEnqueuer.queryCompletedLocalUri(this, systemDownloadId)
            }

            taskDao.updateTask(
                task.copy(
                    status = if (succeeded) TaskStatus.COMPLETED.name else TaskStatus.FAILED.name,
                    bytesDownloaded = queryState?.bytesDownloaded ?: task.bytesDownloaded,
                    totalBytes = queryState?.totalBytes ?: task.totalBytes,
                    completedAtMillis = System.currentTimeMillis(),
                    errorMessage = if (!succeeded) "کد خطای دانلود: ${queryState?.reason ?: -1}" else null
                )
            )
            logDao.record(
                taskId = task.id,
                title = if (succeeded) "فایل تکمیل شد" else "فایل ناموفق بود",
                detail = task.fileName,
                severity = if (succeeded) LogSeverity.SUCCESS else LogSeverity.ERROR
            )

            // Immediately start the next queued item, if any
            val startedNext = enqueueNextTaskLocked(taskDao, logDao)
            if (!startedNext) {
                val stillActive = taskDao.getTasksByStatus(TaskStatus.DOWNLOADING.name)
                if (stillActive.isEmpty()) {
                    allFinished = true
                }
            }
        }

        if (lastSucceeded && completedTaskId > 0L) {
            val unzipOutcome = ZipAutoExtractor.processCompletedDownload(
                context = this,
                fileName = lastFileName,
                localUriString = completedLocalUri,
                systemDownloadId = systemDownloadId
            )
            if (unzipOutcome.wasZipFile) {
                if (unzipOutcome.extractedSuccess) {
                    logDao.record(
                        taskId = completedTaskId,
                        title = "استخراج خودکار فایل فشرده (Auto-Unzip)",
                        detail = "${unzipOutcome.extractedFileCount} فایل در «${unzipOutcome.destinationPath}» استخراج و تایید سلامت شد" +
                            if (unzipOutcome.deletedSourceArchive) "؛ آرشیو zip اصلی پاک شد." else ".",
                        severity = LogSeverity.SUCCESS
                    )
                } else {
                    logDao.record(
                        taskId = completedTaskId,
                        title = "خطا در استخراج خودکار فایل zip",
                        detail = unzipOutcome.errorMessage ?: "استخراج انجام نشد؛ فایل اصلی حفظ شد.",
                        severity = LogSeverity.WARNING
                    )
                }
            }
        }

        if (allFinished && !cutoffTriggered) {
            runPostDownloadWorkflow(
                reasonTitle = "تمام فایل‌های صف دانلود به پایان رسیدند",
                fileName = lastFileName,
                succeeded = lastSucceeded
            )
        } else if (!allFinished) {
            startProgressPolling()
        }
    }

    private suspend fun executeForceCutoff() {
        val db = AppDatabase.getInstance(this)
        val taskDao = db.downloadTaskDao()
        val logDao = db.executionLogDao()
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        queueMutex.withLock {
            val activeOrPending = taskDao.getPendingOrActiveTasks()
            val activeSystemIds = activeOrPending
                .mapNotNull { task -> task.systemDownloadId.takeIf { it > 0L } }
                .toLongArray()

            if (activeSystemIds.isNotEmpty()) {
                runCatching { downloadManager.remove(*activeSystemIds) }
            }

            activeOrPending.forEach { task ->
                taskDao.updateTask(
                    task.copy(
                        status = TaskStatus.CANCELLED.name,
                        errorMessage = "توقف اجباری در ساعت مقرر (حفظ بسته روزانه)"
                    )
                )
            }

            logDao.record(
                taskId = activeOrPending.firstOrNull()?.id ?: 0L,
                title = "تایمر توقف اجباری فعال شد",
                detail = "${activeSystemIds.size} دانلود فعال لغو و ${activeOrPending.size} مورد از صف متوقف شد.",
                severity = LogSeverity.WARNING
            )
        }

        runPostDownloadWorkflow(
            reasonTitle = "توقف اجباری • اجرای ماکروی قطع اینترنت و خاموش‌سازی",
            fileName = "Cutoff Timer",
            succeeded = true
        )
    }

    private suspend fun runPostDownloadWorkflow(
        reasonTitle: String,
        fileName: String,
        succeeded: Boolean
    ) {
        val db = AppDatabase.getInstance(this)
        val macroDao = db.macroDao()
        val logDao = db.executionLogDao()
        val prefs = NightDropPreferences(this).getCurrentSettings()

        val wakeSession = acquireWakeLocks(prefs.wakeOnDownloadComplete)
        try {
            val postSteps = macroDao.getStepsForPhase(MacroPhase.POST_DOWNLOAD.name)
            NightExecutionBus.update {
                ExecutionLiveState(
                    isActive = true,
                    taskId = 0L,
                    fileName = fileName,
                    phaseLabel = reasonTitle,
                    activeStepIndex = -1,
                    steps = postSteps,
                    countdownSeconds = 0,
                    statusDetail = "روشن کردن صفحه و اجرای ماکروی بعد از دانلود...",
                    yieldToSystem = false,
                    isFinished = false
                )
            }

            if (prefs.wakeOnDownloadComplete) {
                launchWakeActivity(0L, "POST")
                delay(700L)
                dismissSwipeKeyguardIfNeeded(0L, logDao)
            }

            if (postSteps.isNotEmpty() && MacroAccessibilityService.instance != null) {
                NightExecutionBus.update { it.copy(yieldToSystem = true) }
                delay(350L)
            }

            // Double-Check Offline Protocol:
            // Disconnect network steps -> wait 3s -> verify ConnectivityManager offline -> retry if needed -> power steps
            val strategyRanker = StrategyRanker(db.strategyDao())
            val (disconnectSteps, powerSteps) = BrainEngine.splitPostMacroSteps(postSteps)
            val offlineOutcome = brainEngine.executeDoubleCheckOfflineProtocol(
                disconnectSteps = disconnectSteps,
                powerSteps = powerSteps,
                runMacro = { phase, macroSteps ->
                    val isPowerPhase = macroSteps.any {
                        it.actionType == GestureActionType.GLOBAL_POWER_DIALOG.name
                    }
                    val goal = if (isPowerPhase) StrategyGoal.POWER_OFF else StrategyGoal.DISABLE_DATA
                    var cachedUiNodesSnapshot: List<UiNodeSnapshot>? = null
                    brainEngine.executeWithStrategyRanking(
                        goal = goal,
                        strategyRanker = strategyRanker,
                        executeStrategy = { strategy ->
                            when (runCatching { StrategyMethod.valueOf(strategy.strategyCode) }.getOrNull()) {
                                StrategyMethod.RECORDED_MACRO -> {
                                    if (macroSteps.isEmpty()) false
                                    else executeMacroWithBrain(phase, macroSteps, 0L, logDao)
                                }
                                StrategyMethod.SEMANTIC_TOOLBAR -> {
                                    val acc = MacroAccessibilityService.instance
                                    if (acc == null) {
                                        false
                                    } else if (isPowerPhase) {
                                        acc.triggerGlobalAction(GestureActionType.GLOBAL_POWER_DIALOG)
                                        delay(700L)
                                        acc.clickNodeByKeywords(MacroAccessibilityService.POWER_OFF_KEYWORDS).first
                                    } else {
                                        acc.openQuickSettingsPanel()
                                        delay(700L)
                                        val (clicked, _) = acc.clickNodeByKeywords(MacroAccessibilityService.MOBILE_DATA_KEYWORDS)
                                        delay(600L)
                                        acc.closeSystemPanels()
                                        clicked
                                    }
                                }
                                StrategyMethod.SETTINGS_INTENT -> {
                                    val acc = MacroAccessibilityService.instance
                                    if (acc == null || isPowerPhase) {
                                        false
                                    } else {
                                        SettingsNavigator.openConnectivityPanel(this@NightDownloadService)
                                        delay(900L)
                                        val (clicked, _) = acc.clickNodeByKeywords(MacroAccessibilityService.MOBILE_DATA_KEYWORDS)
                                        delay(600L)
                                        acc.closeSystemPanels()
                                        clicked
                                    }
                                }
                                null -> false
                            }
                        },
                        runGeminiFallback = {
                            val acc = MacroAccessibilityService.instance
                            if (acc != null && cachedUiNodesSnapshot == null) {
                                cachedUiNodesSnapshot = acc.dumpInteractiveUiTree()
                            }
                            runGeminiCloudRecovery(
                                goalDescription = goal.persianName,
                                phase = MacroPhase.POST_DOWNLOAD,
                                taskId = 0L,
                                prefs = prefs,
                                preCapturedUiNodes = cachedUiNodesSnapshot,
                                logDao = logDao
                            )
                        },
                        runDeepSeekFallback = {
                            val acc = MacroAccessibilityService.instance
                            if (acc != null && cachedUiNodesSnapshot == null) {
                                cachedUiNodesSnapshot = acc.dumpInteractiveUiTree()
                            }
                            runDeepSeekAppRecovery(
                                goalDescription = goal.persianName,
                                phase = MacroPhase.POST_DOWNLOAD,
                                taskId = 0L,
                                preCapturedUiNodes = cachedUiNodesSnapshot,
                                logDao = logDao
                            )
                        },
                        geminiTimeoutMs = GeminiApiTroubleshooter.GEMINI_TIMEOUT_MS,
                        persistLearnedMacro = { learnedSteps ->
                            if (!isPowerPhase) {
                                com.example.data.local.MacroRepository(
                                    macroDao,
                                    db.downloadTaskDao(),
                                    logDao,
                                    db.strategyDao()
                                ).replacePhaseWithLearnedSteps(MacroPhase.POST_DOWNLOAD, learnedSteps)
                            }
                        },
                        onLog = { title, detail, severity ->
                            logDao.record(0L, title, detail, severity)
                        }
                    )
                },
                onLog = { title, detail, severity ->
                    logDao.record(0L, title, detail, severity)
                }
            )

            val optReport = selfOptimizer.analyzeNightLogsAndOptimize(
                sessionStartMillis = sessionStartMillis,
                executionLogDao = logDao,
                preferences = NightDropPreferences(this)
            )

            logDao.record(
                taskId = 0L,
                title = if (offlineOutcome.confirmedOffline) {
                    "اتمام پروتکل خاموشی ایمن (تایید آفلاین)"
                } else {
                    "هشدار: قطع کامل شبکه تایید نشد"
                },
                detail = "$reasonTitle | تعداد بررسی: ${offlineOutcome.attemptsUsed} | ${optReport.summary}",
                severity = if (offlineOutcome.confirmedOffline && succeeded) LogSeverity.SUCCESS else LogSeverity.WARNING
            )

            if (prefs.vibrateOnComplete) {
                triggerShortVibration()
            }

            postCompletionNotification(reasonTitle, succeeded && offlineOutcome.confirmedOffline)
            NightExecutionBus.update {
                it.copy(
                    activeStepIndex = -1,
                    statusDetail = reasonTitle,
                    isFinished = true
                )
            }
        } finally {
            releaseWakeSession(wakeSession)
            releaseDownloadPartialWakeLock()
            delay(1_200L)
            stopForegroundAndSelf()
        }
    }

    private suspend fun executeEnableDataWithRankingAndAi(
        preSteps: List<MacroStepEntity>,
        taskId: Long,
        prefs: SchedulerSettings,
        db: AppDatabase,
        strategyRanker: StrategyRanker,
        logDao: ExecutionLogDao
    ): Boolean {
        var cachedUiNodesSnapshot: List<UiNodeSnapshot>? = null
        return brainEngine.executeWithStrategyRanking(
            goal = StrategyGoal.ENABLE_DATA,
            strategyRanker = strategyRanker,
            executeStrategy = { strategy ->
                when (runCatching { StrategyMethod.valueOf(strategy.strategyCode) }.getOrNull()) {
                    StrategyMethod.RECORDED_MACRO -> {
                        if (preSteps.isEmpty()) {
                            false
                        } else {
                            val ok = executeMacroWithBrain(MacroPhase.PRE_DOWNLOAD, preSteps, taskId, logDao)
                            delay(900L)
                            ok && ConnectivityChecker.isAnyInternetConnected(this)
                        }
                    }
                    StrategyMethod.SEMANTIC_TOOLBAR -> {
                        val acc = MacroAccessibilityService.instance ?: return@executeWithStrategyRanking false
                        acc.openQuickSettingsPanel()
                        delay(700L)
                        val (dataClicked, _) = acc.clickNodeByKeywords(MacroAccessibilityService.MOBILE_DATA_KEYWORDS)
                        if (!dataClicked) {
                            acc.clickNodeByKeywords(MacroAccessibilityService.CARRIER_KEYWORDS)
                        }
                        delay(1_200L)
                        acc.closeSystemPanels()
                        ConnectivityChecker.isAnyInternetConnected(this)
                    }
                    StrategyMethod.SETTINGS_INTENT -> {
                        val acc = MacroAccessibilityService.instance ?: return@executeWithStrategyRanking false
                        SettingsNavigator.openConnectivityPanel(this)
                        delay(900L)
                        val (clicked, _) = acc.clickNodeByKeywords(MacroAccessibilityService.MOBILE_DATA_KEYWORDS)
                        if (!clicked) {
                            acc.clickNodeByKeywords(MacroAccessibilityService.CARRIER_KEYWORDS)
                        }
                        delay(1_200L)
                        acc.closeSystemPanels()
                        ConnectivityChecker.isAnyInternetConnected(this)
                    }
                    null -> false
                }
            },
            runGeminiFallback = {
                val acc = MacroAccessibilityService.instance
                if (acc != null && cachedUiNodesSnapshot == null) {
                    cachedUiNodesSnapshot = acc.dumpInteractiveUiTree()
                }
                runGeminiCloudRecovery(
                    goalDescription = "روشن کردن دیتای سیم‌کارت و عبور از پنجره فعلی",
                    phase = MacroPhase.PRE_DOWNLOAD,
                    taskId = taskId,
                    prefs = prefs,
                    preCapturedUiNodes = cachedUiNodesSnapshot,
                    logDao = logDao
                )
            },
            runDeepSeekFallback = {
                val acc = MacroAccessibilityService.instance
                if (acc != null && cachedUiNodesSnapshot == null) {
                    cachedUiNodesSnapshot = acc.dumpInteractiveUiTree()
                }
                runDeepSeekAppRecovery(
                    goalDescription = "روشن کردن دیتای سیم‌کارت و عبور از پنجره فعلی",
                    phase = MacroPhase.PRE_DOWNLOAD,
                    taskId = taskId,
                    preCapturedUiNodes = cachedUiNodesSnapshot,
                    logDao = logDao
                )
            },
            geminiTimeoutMs = GeminiApiTroubleshooter.GEMINI_TIMEOUT_MS,
            persistLearnedMacro = { learnedSteps ->
                com.example.data.local.MacroRepository(
                    db.macroDao(),
                    db.downloadTaskDao(),
                    logDao,
                    db.strategyDao()
                ).replacePhaseWithLearnedSteps(MacroPhase.PRE_DOWNLOAD, learnedSteps)
            },
            onLog = { title, detail, severity ->
                logDao.record(taskId, title, detail, severity)
            }
        )
    }

    private suspend fun runGeminiCloudRecovery(
        goalDescription: String,
        phase: MacroPhase,
        taskId: Long,
        prefs: SchedulerSettings,
        preCapturedUiNodes: List<UiNodeSnapshot>? = null,
        logDao: ExecutionLogDao
    ): Pair<Boolean, List<com.example.data.local.MacroStep>> {
        val apiKey = prefs.geminiApiKey.trim()
        val acc = MacroAccessibilityService.instance
        if (apiKey.isBlank() || acc == null) {
            if (apiKey.isBlank()) {
                logDao.record(
                    taskId = taskId,
                    title = "کلید Gemini API تنظیم نشده است",
                    detail = "لایه اول (Gemini) کلید ندارد؛ ارجاع به پشتیبان اضطراری DeepSeek App...",
                    severity = LogSeverity.INFO
                )
            }
            return false to emptyList()
        }

        val uiTree = preCapturedUiNodes ?: acc.dumpInteractiveUiTree()
        val metrics = resources.displayMetrics
        val aiResult = geminiTroubleshooter.troubleshootAndGenerateMacro(
            apiKey = apiKey,
            goalDescription = goalDescription,
            phase = phase,
            uiNodes = uiTree,
            screenWidthPx = metrics.widthPixels.coerceAtLeast(720),
            screenHeightPx = metrics.heightPixels.coerceAtLeast(1280)
        )
        if (!aiResult.succeeded || aiResult.generatedSteps.isEmpty()) {
            logDao.record(
                taskId = taskId,
                title = "عدم دریافت برنامه اجرایی از Gemini",
                detail = aiResult.errorMessage ?: "پاسخ معتبری دریافت نشد",
                severity = LogSeverity.WARNING
            )
            return false to emptyList()
        }

        val generatedEntities = aiResult.generatedSteps.mapIndexed { idx, step ->
            step.copy(orderIndex = idx, phase = phase).toEntity()
        }
        val executedOk = executeMacroWithBrain(phase, generatedEntities, taskId, logDao)
        return executedOk to aiResult.generatedSteps
    }

    private suspend fun runDeepSeekAppRecovery(
        goalDescription: String,
        phase: MacroPhase,
        taskId: Long,
        preCapturedUiNodes: List<UiNodeSnapshot>? = null,
        logDao: ExecutionLogDao
    ): Pair<Boolean, List<com.example.data.local.MacroStep>> {
        val acc = MacroAccessibilityService.instance ?: return false to emptyList()
        val uiTree = preCapturedUiNodes?.takeIf { it.isNotEmpty() } ?: acc.dumpInteractiveUiTree()
        if (uiTree.isEmpty()) {
            logDao.record(
                taskId = taskId,
                title = "عدم وجود المان صفحه برای DeepSeek",
                detail = "هیچ المان قابل استخراجی از صفحه فعلی به دست نیامد.",
                severity = LogSeverity.WARNING
            )
            return false to emptyList()
        }

        val metrics = resources.displayMetrics
        val deepSeekResult = deepSeekTroubleshooter.solveViaDeepSeekApp(
            goalDescription = goalDescription,
            phase = phase,
            uiNodes = uiTree,
            screenWidthPx = metrics.widthPixels.coerceAtLeast(720),
            screenHeightPx = metrics.heightPixels.coerceAtLeast(1280),
            onStatus = { statusText ->
                updateServiceNotification("پشتیبان اضطراری DeepSeek", statusText)
            }
        )

        if (!deepSeekResult.succeeded || deepSeekResult.generatedSteps.isEmpty()) {
            logDao.record(
                taskId = taskId,
                title = "عدم دریافت راه‌حل از اپلیکیشن DeepSeek",
                detail = deepSeekResult.errorMessage ?: "پاسخ JSON معتبری از صفحه DeepSeek استخراج نشد",
                severity = LogSeverity.WARNING
            )
            return false to emptyList()
        }

        val generatedEntities = deepSeekResult.generatedSteps.mapIndexed { idx, step ->
            step.copy(orderIndex = idx, phase = phase).toEntity()
        }
        val executedOk = executeMacroWithBrain(phase, generatedEntities, taskId, logDao)
        return executedOk to deepSeekResult.generatedSteps
    }

    private suspend fun dismissSwipeKeyguardIfNeeded(taskId: Long, logDao: ExecutionLogDao) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked && !km.isDeviceLocked) {
            val accService = MacroAccessibilityService.instance
            if (accService != null) {
                val unlocked = accService.performSwipeUnlock()
                logDao.record(
                    taskId = taskId,
                    title = "بازگشایی قفل کشویی صفحه",
                    detail = if (unlocked) "سوایپ پایین به بالا با موفقیت انجام شد" else "سوایپ قفل لغو شد",
                    severity = if (unlocked) LogSeverity.INFO else LogSeverity.WARNING
                )
                delay(600L)
            }
        }
    }

    private suspend fun executeMacroWithBrain(
        phase: MacroPhase,
        steps: List<MacroStepEntity>,
        taskId: Long,
        logDao: ExecutionLogDao
    ): Boolean {
        val accService = MacroAccessibilityService.instance
        if (steps.isNotEmpty() && accService == null) {
            logDao.record(
                taskId = taskId,
                title = "سرویس دسترس‌پذیری غیرفعال است",
                detail = "برای اجرای لمس خودکار و خودترمیمی، سرویس دسترس‌پذیری NightDrop را فعال کنید.",
                severity = LogSeverity.WARNING
            )
            return false
        }

        return brainEngine.executeMacroWithSelfHealing(
            phase = phase,
            steps = steps,
            maxAttempts = 3,
            stepExecutor = { index, step ->
                if (cutoffTriggered && phase == MacroPhase.PRE_DOWNLOAD) {
                    return@executeMacroWithSelfHealing MacroStepExecutionResult(succeeded = false)
                }

                NightExecutionBus.update {
                    it.copy(
                        activeStepIndex = index,
                        statusDetail = "گام ${index + 1} از ${steps.size}: ${step.label}"
                    )
                }
                updateServiceNotification(
                    title = "اجرای ماکرو (${index + 1}/${steps.size})",
                    body = step.label
                )

                var stepExecutionDetail = "نوع: ${step.actionType} | تاخیر: ${step.delayAfterMs}ms"
                var stepSeverity = LogSeverity.INFO
                val action = runCatching { GestureActionType.valueOf(step.actionType) }.getOrNull()

                val execResult = when (action) {
                    GestureActionType.OPEN_CONNECTIVITY_PANEL -> {
                        SettingsNavigator.openConnectivityPanel(this)
                        MacroStepExecutionResult(succeeded = true)
                    }
                    GestureActionType.VIBRATE_ALERT -> {
                        triggerShortVibration()
                        MacroStepExecutionResult(succeeded = true)
                    }
                    GestureActionType.WAKE_SCREEN -> {
                        launchWakeActivity(taskId, "WAKE")
                        MacroStepExecutionResult(succeeded = true)
                    }
                    GestureActionType.TAP,
                    GestureActionType.SWIPE,
                    GestureActionType.GLOBAL_HOME,
                    GestureActionType.GLOBAL_QUICK_SETTINGS,
                    GestureActionType.GLOBAL_NOTIFICATIONS,
                    GestureActionType.GLOBAL_RECENTS,
                    GestureActionType.GLOBAL_POWER_DIALOG -> {
                        val result = accService?.dispatchMacroStepWithReport(step, showVisualDot = false)
                            ?: MacroStepExecutionResult(succeeded = false)
                        stepExecutionDetail = when {
                            result.usedSemanticFinder ->
                                "کلیک هوشمند معنایی روی نود «${result.matchedNodeLabel}» (بدون نیاز به مختصات) | موفق=${result.succeeded} | تاخیر: ${step.delayAfterMs}ms"
                            result.usedSmartNodeClick ->
                                "کلیک هوشمند روی نود «${result.matchedNodeLabel}» | موفق=${result.succeeded} | تاخیر: ${step.delayAfterMs}ms"
                            action == GestureActionType.TAP && step.hasNodeSelector ->
                                "نود «${step.primaryNodeLabel()}» پیدا نشد ← مختصات (${step.pixelStartX},${step.pixelStartY}) | موفق=${result.succeeded}"
                            else ->
                                "اجرا با مختصات دقیق (${step.pixelStartX},${step.pixelStartY}) | موفق=${result.succeeded} | تاخیر: ${step.delayAfterMs}ms"
                        }
                        if (!result.succeeded || result.blockingPopupDetected) {
                            stepSeverity = LogSeverity.WARNING
                        }
                        if (step.id > 0L && result.usedSmartNodeClick) {
                            val cal = selfOptimizer.calibrateStepIfDrifted(
                                step = step,
                                result = result,
                                macroDao = AppDatabase.getInstance(this).macroDao()
                            )
                            if (cal != null) {
                                logDao.record(
                                    taskId = taskId,
                                    title = "کالیبراسیون خودکار مختصات گام",
                                    detail = "مختصات «${cal.stepLabel}» از (${cal.oldX},${cal.oldY}) به (${cal.newX},${cal.newY}) به‌روزرسانی شد.",
                                    severity = LogSeverity.INFO
                                )
                            }
                        }
                        result
                    }
                    else -> MacroStepExecutionResult(succeeded = true)
                }

                logDao.record(
                    taskId = taskId,
                    title = "گام ${index + 1}: ${step.label}",
                    detail = stepExecutionDetail,
                    severity = stepSeverity
                )
                execResult
            },
            selfHealNavigator = { useHome ->
                accService?.performSelfHealingNavigation(useHomeInsteadOfBack = useHome)
            },
            onLog = { title, detail, severity ->
                logDao.record(taskId, title, detail, severity)
            }
        )
    }

    private fun startProgressPolling() {
        if (progressPollingJob?.isActive == true) return
        progressPollingJob = serviceScope.launch {
            val db = AppDatabase.getInstance(this@NightDownloadService)
            val dao = db.downloadTaskDao()
            val logDao = db.executionLogDao()
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val startTimestamps = mutableMapOf<Long, Long>()
            val lastBytesMap = mutableMapOf<Long, Long>()
            val zeroSpeedTicks = mutableMapOf<Long, Int>()
            val consecutivePingFailures = mutableMapOf<Long, Int>()
            val airplaneResetAttempts = mutableMapOf<Long, Int>()

            val networkEventSignal = AtomicReference(CompletableDeferred<Unit>())
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    networkEventSignal.get().complete(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        networkEventSignal.get().complete(Unit)
                    }
                }
            }
            runCatching {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(req, networkCallback)
            }

            try {
                while (isActive && !cutoffTriggered) {
                    val activeDownloads = dao.getTasksByStatus(TaskStatus.DOWNLOADING.name)
                    if (activeDownloads.isEmpty()) {
                        break
                    }
                    var anySteadyProgress = false
                    var highestZeroTicks = 0

                    for (task in activeDownloads) {
                        val startedAt = startTimestamps.getOrPut(task.id) { System.currentTimeMillis() }
                        val state = DownloadEnqueuer.queryProgress(this@NightDownloadService, task.systemDownloadId)
                        if (state != null) {
                            when (state.status) {
                                DownloadManager.STATUS_SUCCESSFUL,
                                DownloadManager.STATUS_FAILED -> {
                                    startTimestamps.remove(task.id)
                                    lastBytesMap.remove(task.id)
                                    zeroSpeedTicks.remove(task.id)
                                    consecutivePingFailures.remove(task.id)
                                    handleDownloadCompletion(task.systemDownloadId)
                                }
                                else -> {
                                    val prevBytes = lastBytesMap[task.id]
                                    lastBytesMap[task.id] = state.bytesDownloaded
                                    if (prevBytes != null && state.bytesDownloaded > prevBytes) {
                                        anySteadyProgress = true
                                    }

                                    val updatedTask = task.copy(
                                        bytesDownloaded = state.bytesDownloaded,
                                        totalBytes = state.totalBytes
                                    )
                                    dao.updateTask(updatedTask)

                                    val queuedCandidates = dao.getQueuedTasks()
                                    val elapsedMillis = System.currentTimeMillis() - startedAt
                                    val decision = brainEngine.evaluateDownloadFeasibility(
                                        activeTask = updatedTask,
                                        bytesDownloaded = state.bytesDownloaded,
                                        totalBytes = state.totalBytes,
                                        elapsedDownloadMillis = elapsedMillis,
                                        cutoffTriggerAtMillis = sessionCutoffMillis,
                                        queuedCandidates = queuedCandidates
                                    )

                                    if (decision is DownloadPriorityDecision.PauseAndSwitchToSmaller) {
                                        val switched = queueMutex.withLock {
                                            runCatching { downloadManager.remove(task.systemDownloadId) }
                                            dao.updateTask(
                                                updatedTask.copy(
                                                    status = TaskStatus.PAUSED.name,
                                                    systemDownloadId = -1L,
                                                    errorMessage = "توقف موقت هوشمند (زمان تا توقف اجباری برای اتمام کافی نیست)"
                                                )
                                            )
                                            val nextId = runCatching {
                                                DownloadEnqueuer.enqueueDownload(this@NightDownloadService, decision.nextTask)
                                            }.getOrNull() ?: -1L
                                            if (nextId > 0L) {
                                                dao.updateTask(
                                                    decision.nextTask.copy(
                                                        status = TaskStatus.DOWNLOADING.name,
                                                        systemDownloadId = nextId,
                                                        errorMessage = null
                                                    )
                                                )
                                                startTimestamps.remove(task.id)
                                                lastBytesMap.remove(task.id)
                                                zeroSpeedTicks.remove(task.id)
                                                consecutivePingFailures.remove(task.id)
                                                startTimestamps[decision.nextTask.id] = System.currentTimeMillis()
                                                true
                                            } else {
                                                dao.updateTask(
                                                    decision.nextTask.copy(
                                                        status = TaskStatus.FAILED.name,
                                                        errorMessage = "خطا در شروع فایل جایگزین"
                                                    )
                                                )
                                                false
                                            }
                                        }
                                        if (switched) {
                                            val speedKb = decision.speedBytesPerSec / 1024L
                                            val etaMin = decision.etaMillis / 60_000L
                                            val cutoffMin = decision.remainingToCutoffMillis / 60_000L
                                            logDao.record(
                                                taskId = task.id,
                                                title = "اولویت‌بندی هوشمند صف: سوئیچ به فایل کوچک‌تر",
                                                detail = "سرعت: ${speedKb}KB/s | تخمین اتمام «${task.fileName}»: ${etaMin}دقیقه > باقیمانده تا توقف: ${cutoffMin}دقیقه ← دانلود «${decision.nextTask.fileName}»",
                                                severity = LogSeverity.WARNING
                                            )
                                            updateServiceNotification(
                                                title = "اولویت‌بندی هوشمند: ${decision.nextTask.fileName}",
                                                body = "فایل حجیم‌تر موقتاً متوقف شد تا فایل کوچک‌تر تا صبح کامل شود"
                                            )
                                        }
                                    } else if (prevBytes != null && state.bytesDownloaded <= prevBytes) {
                                        // Speed dropped to 0 over this interval: run 204 ping and trigger Airplane Mode reset after 2 consecutive failures
                                        val stalledCount = (zeroSpeedTicks[task.id] ?: 0) + 1
                                        zeroSpeedTicks[task.id] = stalledCount
                                        highestZeroTicks = maxOf(highestZeroTicks, stalledCount)
                                        if (stalledCount >= 2 && ConnectivityChecker.isAnyInternetConnected(this@NightDownloadService)) {
                                            val pingRes = ConnectivityChecker.checkRealInternet(this@NightDownloadService)
                                            if (pingRes.httpPingSucceeded) {
                                                consecutivePingFailures[task.id] = 0
                                            } else {
                                                val pingFails = (consecutivePingFailures[task.id] ?: 0) + 1
                                                consecutivePingFailures[task.id] = pingFails
                                                val resetUsed = airplaneResetAttempts[task.id] ?: 0
                                                if (pingFails >= 2 && resetUsed < 2) {
                                                    airplaneResetAttempts[task.id] = resetUsed + 1
                                                    consecutivePingFailures[task.id] = 0
                                                    zeroSpeedTicks[task.id] = 0

                                                    val acc = MacroAccessibilityService.instance
                                                    val recovered = brainEngine.performAirplaneModeReset(
                                                        openQuickSettings = { acc?.openQuickSettingsPanel() ?: false },
                                                        toggleAirplaneNode = {
                                                            acc?.clickNodeByKeywords(
                                                                keywords = MacroAccessibilityService.AIRPLANE_MODE_KEYWORDS,
                                                                showVisualDot = false
                                                            )?.first ?: false
                                                        },
                                                        closePanel = { acc?.closeSystemPanels() },
                                                        awaitNetworkBack = {
                                                            val monitor = ConnectionMonitor(
                                                                context = this@NightDownloadService,
                                                                windowMillis = 25_000L,
                                                                probeIntervalMillis = 2_500L
                                                            )
                                                            monitor.awaitInternetWithMacroRetry(onRetryPreMacro = {}).isConnected
                                                        },
                                                        onLog = { title, detail, severity ->
                                                            logDao.record(task.id, title, detail, severity)
                                                        }
                                                    )
                                                    if (recovered) {
                                                        val postResetState = DownloadEnqueuer.queryProgress(
                                                            this@NightDownloadService,
                                                            task.systemDownloadId
                                                        )
                                                        if (postResetState?.status == DownloadManager.STATUS_FAILED) {
                                                            queueMutex.withLock {
                                                                runCatching { downloadManager.remove(task.systemDownloadId) }
                                                                val newDownloadId = DownloadEnqueuer.enqueueDownload(
                                                                    this@NightDownloadService,
                                                                    updatedTask
                                                                )
                                                                dao.updateTask(
                                                                    updatedTask.copy(
                                                                        status = TaskStatus.DOWNLOADING.name,
                                                                        systemDownloadId = newDownloadId,
                                                                        errorMessage = null
                                                                    )
                                                                )
                                                            }
                                                        }
                                                        startTimestamps[task.id] = System.currentTimeMillis()
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        zeroSpeedTicks[task.id] = 0
                                        consecutivePingFailures[task.id] = 0
                                    }
                                }
                            }
                        }
                    }

                    // Event-driven sleep with adaptive watchdog interval (5s active/recovery, 15s steady download)
                    val adaptiveIntervalMs = brainEngine.computeAdaptiveWatchdogIntervalMs(
                        isSteadyDownloading = anySteadyProgress,
                        zeroSpeedTicks = highestZeroTicks
                    )
                    val pendingSignal = networkEventSignal.get()
                    withTimeoutOrNull(adaptiveIntervalMs) {
                        pendingSignal.await()
                    }
                    if (pendingSignal.isCompleted) {
                        networkEventSignal.compareAndSet(pendingSignal, CompletableDeferred())
                    }
                }
            } finally {
                runCatching { cm?.unregisterNetworkCallback(networkCallback) }
            }
        }
    }

    private data class NightWakeLockSession(
        val cpuWake: PowerManager.WakeLock,
        var screenWake: PowerManager.WakeLock? = null
    )

    @Suppress("DEPRECATION")
    private fun acquireWakeLocks(wakeScreen: Boolean): NightWakeLockSession {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val cpuWake = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NightDrop:ServicePartialWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(15 * 60_000L)
        }
        val screenWake = if (wakeScreen) {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "NightDrop:ScreenWakeLock"
            ).apply {
                setReferenceCounted(false)
                runCatching { acquire(20_000L) }
            }
        } else {
            null
        }
        return NightWakeLockSession(cpuWake = cpuWake, screenWake = screenWake)
    }

    private fun releaseScreenWakeLock(session: NightWakeLockSession) {
        session.screenWake?.let { screenLock ->
            releaseWakeLockSafe(screenLock)
            session.screenWake = null
        }
    }

    private fun retainPartialWakeLockForDownloads(cpuWake: PowerManager.WakeLock) {
        val previous = downloadPartialWakeLock
        if (previous != null && previous !== cpuWake) {
            releaseWakeLockSafe(previous)
        }
        // Extend timeout safely for active overnight downloads; always released in stopForegroundAndSelf / onDestroy
        runCatching { cpuWake.acquire(6 * 60 * 60_000L) }
        downloadPartialWakeLock = cpuWake
    }

    private fun releaseDownloadPartialWakeLock() {
        downloadPartialWakeLock?.let { releaseWakeLockSafe(it) }
        downloadPartialWakeLock = null
    }

    private fun releaseWakeSession(session: NightWakeLockSession) {
        releaseScreenWakeLock(session)
        releaseWakeLockSafe(session.cpuWake)
    }

    private fun releaseWakeLockSafe(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
        }
    }

    private fun launchWakeActivity(taskId: Long, mode: String) {
        val wakeIntent = Intent(this, WakeExecutionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_MODE, mode)
        }
        runCatching { startActivity(wakeIntent) }
    }

    private fun triggerShortVibration() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(250L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(250L)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_EXECUTION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_EXECUTION, notification)
        }
    }

    private fun updateServiceNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_EXECUTION, buildServiceNotification(title, body))
    }

    private fun buildServiceNotification(title: String, body: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_EXECUTION)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun postCompletionNotification(title: String, succeeded: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openAppIntent = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE)
            .setSmallIcon(
                if (succeeded) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText("توالی پایانی NightDrop اجرا شد")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        nm.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val execChannel = NotificationChannel(
                CHANNEL_EXECUTION,
                getString(R.string.notif_channel_execution_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_execution_desc)
            }
            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE,
                getString(R.string.notif_channel_complete_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_complete_desc)
            }
            nm.createNotificationChannel(execChannel)
            nm.createNotificationChannel(completeChannel)
        }
    }

    private fun stopForegroundAndSelf() {
        releaseDownloadPartialWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseDownloadPartialWakeLock()
        serviceScope.cancel()
    }

    private suspend fun ExecutionLogDao.record(
        taskId: Long,
        title: String,
        detail: String,
        severity: LogSeverity
    ) {
        insertLog(
            ExecutionLogEntity(
                taskId = taskId,
                title = title,
                detail = detail,
                severity = severity.name
            )
        )
    }

    companion object {
        const val ACTION_START_QUEUE_WORKFLOW = "com.example.nightdrop.START_QUEUE_WORKFLOW"
        const val ACTION_HANDLE_DOWNLOAD_COMPLETE = "com.example.nightdrop.HANDLE_DOWNLOAD_COMPLETE"
        const val ACTION_FORCE_CUTOFF = "com.example.nightdrop.FORCE_CUTOFF"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SYSTEM_DOWNLOAD_ID = "extra_system_download_id"

        private const val CHANNEL_EXECUTION = "nightdrop_execution"
        private const val CHANNEL_COMPLETE = "nightdrop_complete"
        private const val NOTIFICATION_ID_EXECUTION = 4101
        private const val NOTIFICATION_ID_COMPLETE = 4102
    }
}
