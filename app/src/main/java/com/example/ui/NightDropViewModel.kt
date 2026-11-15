package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.automation.BrainEngine
import com.example.automation.BrainEngineBus
import com.example.automation.BrainSnapshot
import com.example.automation.DownloadEnqueuer
import com.example.automation.FloatingRecorderService
import com.example.automation.MacroAccessibilityService
import com.example.automation.NightAlarmScheduler
import com.example.automation.NightDownloadService
import com.example.automation.PermissionSnapshot
import com.example.automation.SelfOptimizer
import com.example.automation.SettingsNavigator
import com.example.automation.StrategyRanker
import com.example.automation.UpdateCheckResult
import com.example.automation.VersionChecker
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.ExecutionLogEntity
import com.example.data.local.GestureActionType
import com.example.data.local.LogSeverity
import com.example.data.local.MacroPhase
import com.example.data.local.MacroRepository
import com.example.data.local.MacroStepEntity
import com.example.data.local.NightDropPreferences
import com.example.data.local.SchedulerSettings
import com.example.data.local.StrategyEntity
import com.example.data.local.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Immutable
data class NightDropUiState(
    val urlInput: String = "",
    val customFileName: String = "",
    val allowMetered: Boolean = true,
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val preSteps: List<MacroStepEntity> = emptyList(),
    val postSteps: List<MacroStepEntity> = emptyList(),
    val tasks: List<DownloadTaskEntity> = emptyList(),
    val logs: List<ExecutionLogEntity> = emptyList(),
    val settings: SchedulerSettings = SchedulerSettings(),
    val activeRecorderPhase: MacroPhase? = null,
    val defaultDelayMs: Long = 800L,
    val dryRunActiveStepIndex: Int = -1,
    val isDryRunning: Boolean = false,
    val brainSnapshot: BrainSnapshot = BrainSnapshot(),
    val strategies: List<StrategyEntity> = emptyList(),
    val updateCheckResult: UpdateCheckResult? = null,
    val isCheckingUpdate: Boolean = false,
    val isOptimizing: Boolean = false,
    val snackbarMessage: String? = null
)

class NightDropViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = MacroRepository(
        db.macroDao(),
        db.downloadTaskDao(),
        db.executionLogDao(),
        db.strategyDao()
    )
    private val preferences = NightDropPreferences(application)
    private val strategyRanker = StrategyRanker(db.strategyDao())
    private val selfOptimizer = SelfOptimizer()
    private val versionChecker = VersionChecker()

    init {
        viewModelScope.launch {
            strategyRanker.ensureSeeded()
        }
    }

    private val formState = MutableStateFlow(
        NightDropUiState(
            permissions = SettingsNavigator.captureSnapshot(application)
        )
    )

    private var dryRunJob: Job? = null

    val uiState: StateFlow<NightDropUiState> = combine(
        formState,
        repository.observeSteps(MacroPhase.PRE_DOWNLOAD),
        repository.observeSteps(MacroPhase.POST_DOWNLOAD),
        repository.observeAllTasks(),
        repository.observeRecentLogs(),
        preferences.settingsFlow,
        MacroAccessibilityService.isConnected,
        BrainEngineBus.state,
        repository.observeAllStrategies()
    ) { values ->
        val form = values[0] as NightDropUiState
        @Suppress("UNCHECKED_CAST")
        val pre = values[1] as List<MacroStepEntity>
        @Suppress("UNCHECKED_CAST")
        val post = values[2] as List<MacroStepEntity>
        @Suppress("UNCHECKED_CAST")
        val tasks = values[3] as List<DownloadTaskEntity>
        @Suppress("UNCHECKED_CAST")
        val logs = values[4] as List<ExecutionLogEntity>
        val settings = values[5] as SchedulerSettings
        val accConnected = values[6] as Boolean
        val brain = values[7] as BrainSnapshot
        @Suppress("UNCHECKED_CAST")
        val strategies = values[8] as List<StrategyEntity>

        form.copy(
            preSteps = pre,
            postSteps = post,
            tasks = tasks,
            logs = logs,
            settings = settings,
            brainSnapshot = brain,
            strategies = strategies,
            permissions = form.permissions.copy(
                isAccessibilityEnabled = accConnected || form.permissions.isAccessibilityEnabled
            )
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = formState.value
    )

    fun refreshPermissions() {
        val app = getApplication<Application>()
        val snapshot = SettingsNavigator.captureSnapshot(app)
        val battery = BrainEngine.readSystemBatteryStatus(app)
        BrainEngineBus.update {
            it.copy(
                batteryPercent = battery.levelPercent,
                isCharging = battery.isCharging,
                isNetworkOnline = snapshot.isNetworkConnected
            )
        }
        formState.update { it.copy(permissions = snapshot) }
    }

    fun onUrlChanged(value: String) {
        formState.update { it.copy(urlInput = value) }
    }

    fun onFileNameChanged(value: String) {
        formState.update { it.copy(customFileName = value) }
    }

    fun onAllowMeteredChanged(allow: Boolean) {
        formState.update { it.copy(allowMetered = allow) }
    }

    fun onStartTimeSelected(hour: Int, minute: Int) {
        viewModelScope.launch { preferences.setStartTime(hour, minute) }
    }

    fun onCutoffTimeSelected(hour: Int, minute: Int) {
        viewModelScope.launch { preferences.setCutoffTime(hour, minute) }
    }

    fun onCutoffEnabledChanged(enabled: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            preferences.setCutoffEnabled(enabled)
            if (!enabled) {
                NightAlarmScheduler.cancelCutoffAlarm(app)
            }
        }
    }

    fun onDefaultDelayChanged(delayMs: Long) {
        formState.update { it.copy(defaultDelayMs = delayMs.coerceIn(100L, 15_000L)) }
    }

    fun startFloatingRecorder(phase: MacroPhase): Boolean {
        val app = getApplication<Application>()
        if (!Settings.canDrawOverlays(app)) {
            formState.update {
                it.copy(snackbarMessage = "برای باز شدن پنل شناور روی صفحه اصلی و نوار ابزار، ابتدا مجوز «نمایش روی برنامه‌های دیگر» را فعال کنید.")
            }
            SettingsNavigator.openOverlaySettings(app)
            return false
        }

        val intent = Intent(app, FloatingRecorderService::class.java).apply {
            putExtra(FloatingRecorderService.EXTRA_PHASE, phase.name)
        }
        app.startService(intent)
        return true
    }

    fun startVisualMacroTest(phase: MacroPhase): Boolean {
        val app = getApplication<Application>()
        val accService = MacroAccessibilityService.instance
        if (accService == null) {
            formState.update {
                it.copy(snackbarMessage = "برای اجرای تست بصری ماکرو، ابتدا سرویس دسترس‌پذیری NightDrop را فعال کنید.")
            }
            SettingsNavigator.openAccessibilitySettings(app)
            return false
        }

        val steps = if (phase == MacroPhase.PRE_DOWNLOAD) uiState.value.preSteps else uiState.value.postSteps
        if (steps.isEmpty()) {
            formState.update {
                it.copy(snackbarMessage = "هنوز هیچ حرکتی برای این مرحله ضبط نشده است.")
            }
            return false
        }

        accService.startVisualMacroTest(phase, steps)
        return true
    }

    fun openRecorder(phase: MacroPhase) {
        stopDryRun()
        formState.update { it.copy(activeRecorderPhase = phase) }
    }

    fun closeRecorder() {
        stopDryRun()
        formState.update { it.copy(activeRecorderPhase = null) }
    }

    fun consumeSnackbar() {
        formState.update { it.copy(snackbarMessage = null) }
    }

    fun addUrlToQueue() {
        val current = uiState.value
        val url = current.urlInput.trim()
        if (!DownloadEnqueuer.isValidDownloadUrl(url)) {
            formState.update {
                it.copy(snackbarMessage = "لطفاً یک لینک معتبر با پیشوند http:// یا https:// وارد کنید.")
            }
            return
        }

        val fileName = DownloadEnqueuer.deriveFileName(url, current.customFileName)
        viewModelScope.launch {
            val order = repository.getNextQueueOrder()
            repository.insertTask(
                DownloadTaskEntity(
                    url = url,
                    fileName = fileName,
                    scheduledHour = current.settings.startHour,
                    scheduledMinute = current.settings.startMinute,
                    scheduledTriggerAtMillis = 0L,
                    status = TaskStatus.QUEUED.name,
                    allowMetered = current.allowMetered,
                    queueOrder = order
                )
            )
            formState.update {
                it.copy(
                    urlInput = "",
                    customFileName = "",
                    snackbarMessage = "فایل «$fileName» به صف دانلود متوالی اضافه شد."
                )
            }
        }
    }

    fun scheduleSequentialQueue() {
        val current = uiState.value
        val app = getApplication<Application>()

        viewModelScope.launch {
            // If user typed a URL in the field without tapping "Add", append it automatically
            val typedUrl = current.urlInput.trim()
            if (typedUrl.isNotEmpty()) {
                if (!DownloadEnqueuer.isValidDownloadUrl(typedUrl)) {
                    formState.update {
                        it.copy(snackbarMessage = "لینک وارد شده معتبر نیست؛ با http:// یا https:// شروع شود.")
                    }
                    return@launch
                }
                val order = repository.getNextQueueOrder()
                val fileName = DownloadEnqueuer.deriveFileName(typedUrl, current.customFileName)
                repository.insertTask(
                    DownloadTaskEntity(
                        url = typedUrl,
                        fileName = fileName,
                        scheduledHour = current.settings.startHour,
                        scheduledMinute = current.settings.startMinute,
                        scheduledTriggerAtMillis = 0L,
                        status = TaskStatus.QUEUED.name,
                        allowMetered = current.allowMetered,
                        queueOrder = order
                    )
                )
                formState.update { it.copy(urlInput = "", customFileName = "") }
            }

            val queueItems = repository.getPendingOrActiveTasks().filter {
                it.status == TaskStatus.QUEUED.name || it.status == TaskStatus.SCHEDULED.name
            }

            if (queueItems.isEmpty()) {
                formState.update {
                    it.copy(snackbarMessage = "صف دانلود خالی است؛ ابتدا حداقل یک لینک به صف اضافه کنید.")
                }
                return@launch
            }

            val startMillis = NightAlarmScheduler.computeNextTriggerMillis(
                current.settings.startHour,
                current.settings.startMinute
            )
            queueItems.forEach { task ->
                repository.updateTask(
                    task.copy(
                        status = TaskStatus.SCHEDULED.name,
                        scheduledHour = current.settings.startHour,
                        scheduledMinute = current.settings.startMinute,
                        scheduledTriggerAtMillis = startMillis
                    )
                )
            }

            val exactStart = NightAlarmScheduler.scheduleQueueStartAlarm(app, startMillis)
            val startText = String.format("%02d:%02d", current.settings.startHour, current.settings.startMinute)

            val cutoffInfo = if (current.settings.cutoffEnabled) {
                val cutoffMillis = NightAlarmScheduler.computeCutoffTriggerAfter(
                    startTriggerMillis = startMillis,
                    cutoffHour = current.settings.cutoffHour,
                    cutoffMinute = current.settings.cutoffMinute
                )
                NightAlarmScheduler.scheduleCutoffAlarm(app, cutoffMillis)
                String.format(" • توقف اجباری %02d:%02d", current.settings.cutoffHour, current.settings.cutoffMinute)
            } else {
                NightAlarmScheduler.cancelCutoffAlarm(app)
                ""
            }

            repository.logEvent(
                taskId = queueItems.first().id,
                title = "زمان‌بندی صف ${queueItems.size} فایلی ثبت شد",
                detail = "شروع: $startText$cutoffInfo",
                severity = if (exactStart) LogSeverity.INFO else LogSeverity.WARNING
            )

            formState.update {
                it.copy(
                    snackbarMessage = "صف شامل ${queueItems.size} فایل برای ساعت $startText$cutoffInfo زمان‌بندی شد."
                )
            }
        }
    }

    fun triggerImmediateQueueRun() {
        val current = uiState.value
        val app = getApplication<Application>()

        viewModelScope.launch {
            val typedUrl = current.urlInput.trim()
            if (typedUrl.isNotEmpty()) {
                if (!DownloadEnqueuer.isValidDownloadUrl(typedUrl)) {
                    formState.update {
                        it.copy(snackbarMessage = "لینک وارد شده معتبر نیست؛ با http:// یا https:// شروع شود.")
                    }
                    return@launch
                }
                val order = repository.getNextQueueOrder()
                val fileName = DownloadEnqueuer.deriveFileName(typedUrl, current.customFileName)
                repository.insertTask(
                    DownloadTaskEntity(
                        url = typedUrl,
                        fileName = fileName,
                        scheduledHour = current.settings.startHour,
                        scheduledMinute = current.settings.startMinute,
                        scheduledTriggerAtMillis = System.currentTimeMillis(),
                        status = TaskStatus.QUEUED.name,
                        allowMetered = current.allowMetered,
                        queueOrder = order
                    )
                )
                formState.update { it.copy(urlInput = "", customFileName = "") }
            }

            val pending = repository.getPendingOrActiveTasks().filter {
                it.status == TaskStatus.QUEUED.name || it.status == TaskStatus.SCHEDULED.name
            }
            if (pending.isEmpty()) {
                formState.update {
                    it.copy(snackbarMessage = "صف انتظار خالی است؛ ابتدا یک لینک اضافه کنید.")
                }
                return@launch
            }

            if (current.settings.cutoffEnabled) {
                val cutoffMillis = NightAlarmScheduler.computeCutoffTriggerAfter(
                    startTriggerMillis = System.currentTimeMillis(),
                    cutoffHour = current.settings.cutoffHour,
                    cutoffMinute = current.settings.cutoffMinute
                )
                NightAlarmScheduler.scheduleCutoffAlarm(app, cutoffMillis)
            }

            startQueueWorkflowService(app)
            formState.update {
                it.copy(snackbarMessage = "اجرای فوری صف متوالی (${pending.size} فایل) آغاز شد.")
            }
        }
    }

    fun retryTask(task: DownloadTaskEntity) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            repository.updateTask(
                task.copy(
                    status = TaskStatus.QUEUED.name,
                    errorMessage = null,
                    bytesDownloaded = 0L,
                    systemDownloadId = -1L
                )
            )
            startQueueWorkflowService(app)
            formState.update {
                it.copy(snackbarMessage = "«${task.fileName}» دوباره در صف اجرا قرار گرفت.")
            }
        }
    }

    private fun startQueueWorkflowService(app: Application) {
        val serviceIntent = Intent(app, NightDownloadService::class.java).apply {
            action = NightDownloadService.ACTION_START_QUEUE_WORKFLOW
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
        }.onFailure { err ->
            formState.update {
                it.copy(snackbarMessage = "خطا در اجرای سرویس دانلود: ${err.message ?: "دسترسی پس‌زمینه"}")
            }
        }
    }

    fun cancelOrDeleteTask(task: DownloadTaskEntity) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (task.status == TaskStatus.DOWNLOADING.name && task.systemDownloadId > 0L) {
                val dm = app.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                runCatching { dm.remove(task.systemDownloadId) }
            }
            repository.deleteTask(task.id)

            val remaining = repository.getPendingOrActiveTasks()
            if (remaining.isEmpty()) {
                NightAlarmScheduler.cancelQueueStartAlarm(app)
                NightAlarmScheduler.cancelCutoffAlarm(app)
            }
        }
    }

    fun clearFinishedTasks() {
        viewModelScope.launch { repository.clearFinishedTasks() }
    }

    fun recordTapStep(
        phase: MacroPhase,
        normX: Float,
        normY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int
    ) {
        val px = (normX * screenWidthPx).roundToInt()
        val py = (normY * screenHeightPx).roundToInt()
        val delayMs = formState.value.defaultDelayMs
        viewModelScope.launch {
            repository.addStep(
                phase = phase,
                actionType = GestureActionType.TAP,
                normStartX = normX,
                normStartY = normY,
                normEndX = normX,
                normEndY = normY,
                pixelStartX = px,
                pixelStartY = py,
                pixelEndX = px,
                pixelEndY = py,
                durationMs = 100L,
                delayAfterMs = delayMs,
                label = "لمس صفحه در ($px, $py)"
            )
        }
    }

    fun recordSwipeStep(
        phase: MacroPhase,
        normStartX: Float,
        normStartY: Float,
        normEndX: Float,
        normEndY: Float,
        durationMs: Long,
        screenWidthPx: Int,
        screenHeightPx: Int
    ) {
        val sx = (normStartX * screenWidthPx).roundToInt()
        val sy = (normStartY * screenHeightPx).roundToInt()
        val ex = (normEndX * screenWidthPx).roundToInt()
        val ey = (normEndY * screenHeightPx).roundToInt()
        val delayMs = formState.value.defaultDelayMs
        viewModelScope.launch {
            repository.addStep(
                phase = phase,
                actionType = GestureActionType.SWIPE,
                normStartX = normStartX,
                normStartY = normStartY,
                normEndX = normEndX,
                normEndY = normEndY,
                pixelStartX = sx,
                pixelStartY = sy,
                pixelEndX = ex,
                pixelEndY = ey,
                durationMs = durationMs.coerceIn(150L, 2_000L),
                delayAfterMs = delayMs,
                label = "سوایپ ($sx,$sy) ← ($ex,$ey)"
            )
        }
    }

    fun addQuickSystemStep(
        phase: MacroPhase,
        actionType: GestureActionType,
        label: String,
        delayAfterMs: Long = 800L
    ) {
        viewModelScope.launch {
            repository.addStep(
                phase = phase,
                actionType = actionType,
                durationMs = 200L,
                delayAfterMs = delayAfterMs,
                label = label
            )
        }
    }

    fun moveStep(phase: MacroPhase, stepId: Long, delta: Int) {
        viewModelScope.launch { repository.moveStep(phase, stepId, delta) }
    }

    fun applyPreDownloadPreset() {
        viewModelScope.launch {
            repository.applyPreset(
                MacroPhase.PRE_DOWNLOAD,
                listOf(
                    GestureActionType.GLOBAL_HOME to ("رفتن به صفحه اصلی (Home)" to 700L),
                    GestureActionType.GLOBAL_QUICK_SETTINGS to ("پایین کشیدن تنظیمات سریع" to 1200L),
                    GestureActionType.WAIT_DELAY to ("مکث تثبیت اولیه اتصال" to 1500L)
                )
            )
            formState.update { it.copy(snackbarMessage = "قالب پیش از دانلود اعمال شد.") }
        }
    }

    fun applyPostDownloadPreset() {
        viewModelScope.launch {
            repository.applyPreset(
                MacroPhase.POST_DOWNLOAD,
                listOf(
                    GestureActionType.GLOBAL_QUICK_SETTINGS to ("پایین کشیدن نوار ابزار برای قطع دیتا" to 1000L),
                    GestureActionType.GLOBAL_HOME to ("بازگشت به صفحه اصلی" to 800L),
                    GestureActionType.GLOBAL_POWER_DIALOG to ("باز کردن منوی خاموش کردن گوشی" to 1000L)
                )
            )
            formState.update { it.copy(snackbarMessage = "قالب بعد از دانلود اعمال شد.") }
        }
    }

    fun updateStepDelay(step: MacroStepEntity, newDelayMs: Long) {
        viewModelScope.launch { repository.updateStepDelay(step, newDelayMs) }
    }

    fun deleteStep(stepId: Long) {
        viewModelScope.launch { repository.deleteStep(stepId) }
    }

    fun clearPhase(phase: MacroPhase) {
        stopDryRun()
        viewModelScope.launch { repository.clearPhase(phase) }
    }

    fun runDryPlayback(phase: MacroPhase) {
        stopDryRun()
        val steps = if (phase == MacroPhase.PRE_DOWNLOAD) uiState.value.preSteps else uiState.value.postSteps
        if (steps.isEmpty()) return

        dryRunJob = viewModelScope.launch {
            formState.update { it.copy(isDryRunning = true, dryRunActiveStepIndex = -1) }
            try {
                steps.forEachIndexed { idx, step ->
                    formState.update { it.copy(dryRunActiveStepIndex = idx) }
                    delay(step.durationMs.coerceAtLeast(250L))
                    delay(step.delayAfterMs.coerceAtLeast(150L))
                }
            } finally {
                formState.update { it.copy(isDryRunning = false, dryRunActiveStepIndex = -1) }
            }
        }
    }

    fun stopDryRun() {
        dryRunJob?.cancel()
        dryRunJob = null
        formState.update { it.copy(isDryRunning = false, dryRunActiveStepIndex = -1) }
    }

    fun setNetworkStabilizationSeconds(seconds: Int) {
        viewModelScope.launch { preferences.setNetworkStabilizationSeconds(seconds) }
    }

    fun setWakeScreenOnAlarm(enabled: Boolean) {
        viewModelScope.launch { preferences.setWakeScreenOnAlarm(enabled) }
    }

    fun setWakeOnDownloadComplete(enabled: Boolean) {
        viewModelScope.launch { preferences.setWakeOnDownloadComplete(enabled) }
    }

    fun setVibrateOnComplete(enabled: Boolean) {
        viewModelScope.launch { preferences.setVibrateOnComplete(enabled) }
    }

    fun clearLogs() {
        viewModelScope.launch { repository.clearLogs() }
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            preferences.setGeminiApiKey(apiKey)
            formState.update {
                it.copy(
                    snackbarMessage = if (apiKey.isBlank()) {
                        "کلید Gemini API پاک شد."
                    } else {
                        "کلید Gemini API با موفقیت ذخیره شد."
                    }
                )
            }
        }
    }

    fun saveOtaManifestUrl(url: String) {
        viewModelScope.launch {
            preferences.setOtaManifestUrl(url)
        }
    }

    fun checkForAppUpdate(customUrl: String? = null) {
        val urlToCheck = (customUrl ?: uiState.value.settings.otaManifestUrl).trim()
        viewModelScope.launch {
            formState.update { it.copy(isCheckingUpdate = true) }
            preferences.setOtaManifestUrl(urlToCheck)
            val result = versionChecker.checkForUpdate(
                manifestUrl = urlToCheck,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME
            )
            formState.update {
                it.copy(
                    isCheckingUpdate = false,
                    updateCheckResult = result,
                    snackbarMessage = when {
                        !result.succeeded -> result.errorMessage ?: "خطا در بررسی نسخه جدید"
                        result.hasUpdate -> "نسخه جدید ${result.latestVersionName} در دسترس است!"
                        else -> "برنامه به‌روز است (نسخه ${result.currentVersionName})"
                    }
                )
            }
        }
    }

    fun openUpdateReleasePage() {
        val url = uiState.value.updateCheckResult?.releaseUrl
            ?: uiState.value.settings.otaManifestUrl
        val opened = VersionChecker.openReleaseInBrowser(getApplication(), url)
        if (!opened) {
            formState.update {
                it.copy(snackbarMessage = "امکان باز کردن لینک انتشار در مرورگر وجود ندارد.")
            }
        }
    }

    fun runSelfOptimizationNow() {
        viewModelScope.launch {
            formState.update { it.copy(isOptimizing = true) }
            val since = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
            val report = selfOptimizer.analyzeNightLogsAndOptimize(
                sessionStartMillis = since,
                executionLogDao = db.executionLogDao(),
                preferences = preferences
            )
            formState.update {
                it.copy(
                    isOptimizing = false,
                    snackbarMessage = report.summary
                )
            }
        }
    }

    fun resetStrategyScores() {
        viewModelScope.launch {
            strategyRanker.resetAllScores()
            formState.update {
                it.copy(snackbarMessage = "امتیاز استراتژی‌ها به حالت اولیه بازنشانی شد.")
            }
        }
    }
}
