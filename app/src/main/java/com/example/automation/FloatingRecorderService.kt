package com.example.automation

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.GestureActionType
import com.example.data.local.MacroPhase
import com.example.data.local.MacroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingRecorderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var repository: MacroRepository

    private var controlBarView: View? = null
    private var captureOverlayView: View? = null
    private var counterTextView: TextView? = null
    private var lastNodeBadgeView: TextView? = null
    private var pauseResumeButton: Button? = null
    private var undoButton: Button? = null

    private var currentPhase: MacroPhase = MacroPhase.PRE_DOWNLOAD
    private var isPaused = false
    private var isDispatchingGesture = false

    private var lastStepCompletedAt = 0L
    private var pauseStartedAt = 0L
    private var accumulatedPausedMs = 0L
    private var lastRecordedStepId: Long = -1L
    private var recordedCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val db = AppDatabase.getInstance(this)
        repository = MacroRepository(db.macroDao(), db.downloadTaskDao(), db.executionLogDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val phaseName = intent?.getStringExtra(EXTRA_PHASE)
        currentPhase = runCatching {
            MacroPhase.valueOf(phaseName ?: MacroPhase.PRE_DOWNLOAD.name)
        }.getOrDefault(MacroPhase.PRE_DOWNLOAD)

        isPaused = false
        accumulatedPausedMs = 0L
        lastStepCompletedAt = System.currentTimeMillis()

        serviceScope.launch(Dispatchers.IO) {
            val existing = repository.getSteps(currentPhase)
            recordedCount = existing.size
            lastRecordedStepId = existing.lastOrNull()?.id ?: -1L
            withContext(Dispatchers.Main.immediate) {
                if (controlBarView == null) {
                    showFloatingControlBar()
                    showCaptureOverlay()
                } else {
                    updateControlBarState()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingControlBar() {
        val density = resources.displayMetrics.density
        val pad = (10 * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0B1324"))
                cornerRadius = 16 * density
                setStroke((1.5f * density).roundToInt(), Color.parseColor("#38BDF8"))
            }
        }

        // Draggable header bar with live step counter
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * density).roundToInt())
        }

        val dragGrip = TextView(this).apply {
            text = "⠿ "
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
        }

        val phaseTitle = TextView(this).apply {
            text = if (currentPhase == MacroPhase.PRE_DOWNLOAD) {
                "ضبط قبل دانلود"
            } else {
                "ضبط بعد دانلود"
            }
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            setPadding(0, 0, (8 * density).roundToInt(), 0)
        }

        counterTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        headerRow.addView(dragGrip)
        headerRow.addView(phaseTitle)
        headerRow.addView(counterTextView)
        root.addView(headerRow)

        lastNodeBadgeView = TextView(this).apply {
            text = "لمس یا سوایپ روی صفحه ثبت می‌شود"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10.5f
            maxLines = 1
            setPadding(0, 0, 0, (6 * density).roundToInt())
        }
        root.addView(lastNodeBadgeView)

        // Primary professional control buttons: Pause/Resume, Undo, Save & Exit
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        pauseResumeButton = createBarButton("⏸ مکث", "#D97706") {
            togglePauseResume()
        }
        undoButton = createBarButton("↩ حذف آخرین", "#475569") {
            undoLastRecordedStep()
        }
        val saveBtn = createBarButton("💾 ذخیره و پایان", "#059669") {
            finishAndReturnToApp()
        }

        controlsRow.addView(pauseResumeButton)
        controlsRow.addView(spacerView(5, density))
        controlsRow.addView(undoButton)
        controlsRow.addView(spacerView(5, density))
        controlsRow.addView(saveBtn)
        root.addView(controlsRow)

        // Compact quick global buttons (handy for status bar/home/power dialog)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * density).roundToInt(), 0, 0)
        }
        quickRow.addView(createBarButton("🏠 خانه", "#1E293B") {
            recordGlobalAction(GestureActionType.GLOBAL_HOME, "رفتن به صفحه اصلی (Home)")
        })
        quickRow.addView(spacerView(4, density))
        quickRow.addView(createBarButton("⬇️ پنل سریع", "#1E293B") {
            recordGlobalAction(GestureActionType.GLOBAL_QUICK_SETTINGS, "باز کردن تنظیمات سریع")
        })
        quickRow.addView(spacerView(4, density))
        quickRow.addView(createBarButton("⚡ پاور", "#7F1D1D") {
            recordGlobalAction(GestureActionType.GLOBAL_POWER_DIALOG, "باز کردن منوی پاور")
        })
        root.addView(quickRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (14 * density).roundToInt()
            y = (96 * density).roundToInt()
        }

        var initX = 0
        var initY = 0
        var initTouchX = 0f
        var initTouchY = 0f
        headerRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    initTouchX = event.rawX
                    initTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Since gravity is TOP|END, horizontal offset moves opposite to rawX delta
                    params.x = (initX - (event.rawX - initTouchX)).roundToInt()
                    params.y = (initY + (event.rawY - initTouchY)).roundToInt()
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }

        controlBarView = root
        runCatching { windowManager.addView(root, params) }
        updateControlBarState()
    }

    private fun togglePauseResume() {
        if (isPaused) {
            isPaused = false
            if (pauseStartedAt > 0L) {
                accumulatedPausedMs += (System.currentTimeMillis() - pauseStartedAt).coerceAtLeast(0L)
                pauseStartedAt = 0L
            }
            showCaptureOverlay()
            lastNodeBadgeView?.text = "ضبط فعال شد؛ لمس‌ها ثبت می‌شوند"
        } else {
            isPaused = true
            pauseStartedAt = System.currentTimeMillis()
            removeCaptureOverlay()
            lastNodeBadgeView?.text = "ضبط متوقف است (لمس‌ها آزاد هستند)"
        }
        updateControlBarState()
    }

    private fun undoLastRecordedStep() {
        serviceScope.launch(Dispatchers.IO) {
            val newLast = repository.undoLastStep(currentPhase)
            val all = repository.getSteps(currentPhase)
            withContext(Dispatchers.Main.immediate) {
                recordedCount = all.size
                lastRecordedStepId = newLast?.id ?: -1L
                lastNodeBadgeView?.text = "آخرین حرکت حذف شد"
                updateControlBarState()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCaptureOverlay() {
        if (captureOverlayView != null || isPaused) return
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(720)
        val screenH = metrics.heightPixels.coerceAtLeast(1280)

        var downX = 0f
        var downY = 0f
        var downTime = 0L

        val overlay = View(this).apply {
            // Very subtle tint while recording so user knows live capture is active without blocking view
            setBackgroundColor(Color.parseColor("#0D38BDF8"))
            setOnTouchListener { _, event ->
                if (isDispatchingGesture || isPaused) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downTime = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val upX = event.rawX
                        val upY = event.rawY
                        val upTime = System.currentTimeMillis()
                        val gestureDuration = (upTime - downTime).coerceIn(80L, 2_500L)
                        val distance = hypot(upX - downX, upY - downY)

                        isDispatchingGesture = true
                        removeCaptureOverlay()

                        serviceScope.launch {
                            try {
                                val realGapMs = computeRealInterStepDelay(upTime)
                                // Wait a frame so our transparent overlay leaves window hierarchy, then inspect UI Node
                                delay(45L)
                                if (distance < 32f) {
                                    saveAndDispatchTap(downX, downY, screenW, screenH, realGapMs)
                                } else {
                                    saveAndDispatchSwipe(
                                        downX, downY, upX, upY, gestureDuration, screenW, screenH, realGapMs
                                    )
                                }
                                delay(160L)
                            } finally {
                                isDispatchingGesture = false
                                lastStepCompletedAt = System.currentTimeMillis()
                                if (!isPaused && controlBarView != null) {
                                    showCaptureOverlay()
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isDispatchingGesture = false
                        false
                    }
                    else -> true
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        captureOverlayView = overlay
        runCatching { windowManager.addView(overlay, params) }
        bringControlBarToFront()
    }

    private fun bringControlBarToFront() {
        controlBarView?.let { bar ->
            val barParams = bar.layoutParams ?: return
            runCatching {
                if (bar.isAttachedToWindow) {
                    windowManager.removeViewImmediate(bar)
                }
                windowManager.addView(bar, barParams)
            }
        }
    }

    private suspend fun saveAndDispatchTap(
        x: Float,
        y: Float,
        screenW: Int,
        screenH: Int,
        delayFromPrevMs: Long
    ) {
        val px = x.roundToInt().coerceIn(0, screenW)
        val py = y.roundToInt().coerceIn(0, screenH)

        // Hybrid Capture: inspect AccessibilityNodeInfo at (px, py) before forwarding tap
        val nodeInfo = MacroAccessibilityService.instance?.inspectUiNodeAt(px, py)
        val smartName = nodeInfo?.displayLabel
        val stepLabel = if (smartName != null) {
            "کلیک «$smartName»"
        } else {
            "لمس صفحه در ($px, $py)"
        }

        val newId = withContext(Dispatchers.IO) {
            if (lastRecordedStepId > 0L) {
                repository.updateStepDelayById(lastRecordedStepId, delayFromPrevMs)
            }
            repository.addStep(
                phase = currentPhase,
                actionType = GestureActionType.TAP,
                normStartX = (px / screenW.toFloat()).coerceIn(0f, 1f),
                normStartY = (py / screenH.toFloat()).coerceIn(0f, 1f),
                normEndX = (px / screenW.toFloat()).coerceIn(0f, 1f),
                normEndY = (py / screenH.toFloat()).coerceIn(0f, 1f),
                pixelStartX = px,
                pixelStartY = py,
                pixelEndX = px,
                pixelEndY = py,
                durationMs = 90L,
                delayAfterMs = 800L,
                label = stepLabel,
                nodeText = nodeInfo?.nodeText,
                contentDescription = nodeInfo?.contentDescription,
                viewIdResourceName = nodeInfo?.viewIdResourceName
            )
        }

        lastRecordedStepId = newId
        recordedCount++
        lastNodeBadgeView?.text = if (smartName != null) {
            "ثبت نود هوشمند: «$smartName»"
        } else {
            "ثبت مختصات لمس: ($px, $py)"
        }
        updateControlBarState()

        // Forward real touch to the underlying system UI or app
        MacroAccessibilityService.instance?.dispatchTapNow(x, y)
    }

    private suspend fun saveAndDispatchSwipe(
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
        durationMs: Long,
        screenW: Int,
        screenH: Int,
        delayFromPrevMs: Long
    ) {
        val px1 = sx.roundToInt().coerceIn(0, screenW)
        val py1 = sy.roundToInt().coerceIn(0, screenH)
        val px2 = ex.roundToInt().coerceIn(0, screenW)
        val py2 = ey.roundToInt().coerceIn(0, screenH)

        val newId = withContext(Dispatchers.IO) {
            if (lastRecordedStepId > 0L) {
                repository.updateStepDelayById(lastRecordedStepId, delayFromPrevMs)
            }
            repository.addStep(
                phase = currentPhase,
                actionType = GestureActionType.SWIPE,
                normStartX = (px1 / screenW.toFloat()).coerceIn(0f, 1f),
                normStartY = (py1 / screenH.toFloat()).coerceIn(0f, 1f),
                normEndX = (px2 / screenW.toFloat()).coerceIn(0f, 1f),
                normEndY = (py2 / screenH.toFloat()).coerceIn(0f, 1f),
                pixelStartX = px1,
                pixelStartY = py1,
                pixelEndX = px2,
                pixelEndY = py2,
                durationMs = durationMs,
                delayAfterMs = 800L,
                label = "سوایپ ($px1,$py1) ← ($px2,$py2)"
            )
        }

        lastRecordedStepId = newId
        recordedCount++
        lastNodeBadgeView?.text = "سوایپ ثبت شد (${durationMs}ms)"
        updateControlBarState()

        MacroAccessibilityService.instance?.dispatchSwipeNow(sx, sy, ex, ey, durationMs)
    }

    private fun recordGlobalAction(actionType: GestureActionType, label: String) {
        val now = System.currentTimeMillis()
        val realGapMs = computeRealInterStepDelay(now)
        serviceScope.launch {
            val newId = withContext(Dispatchers.IO) {
                if (lastRecordedStepId > 0L) {
                    repository.updateStepDelayById(lastRecordedStepId, realGapMs)
                }
                repository.addStep(
                    phase = currentPhase,
                    actionType = actionType,
                    durationMs = 200L,
                    delayAfterMs = 900L,
                    label = label
                )
            }
            lastRecordedStepId = newId
            recordedCount++
            lastStepCompletedAt = System.currentTimeMillis()
            lastNodeBadgeView?.text = "ثبت: $label"
            updateControlBarState()

            removeCaptureOverlay()
            delay(50L)
            MacroAccessibilityService.instance?.triggerGlobalAction(actionType)
            delay(220L)
            if (!isPaused && controlBarView != null) {
                showCaptureOverlay()
            }
        }
    }

    private fun computeRealInterStepDelay(eventTimestamp: Long): Long {
        val rawElapsed = (eventTimestamp - lastStepCompletedAt - accumulatedPausedMs).coerceAtLeast(0L)
        accumulatedPausedMs = 0L
        return rawElapsed.coerceIn(500L, 15_000L)
    }

    private fun updateControlBarState() {
        counterTextView?.text = "• $recordedCount حرکت ثبت شد"
        pauseResumeButton?.apply {
            text = if (isPaused) "▶ ادامه" else "⏸ مکث"
            setBackgroundColor(
                Color.parseColor(if (isPaused) "#0284C7" else "#D97706")
            )
        }
        undoButton?.isEnabled = recordedCount > 0
        undoButton?.alpha = if (recordedCount > 0) 1f else 0.45f
    }

    private fun createBarButton(text: String, hexColor: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            minHeight = (36 * density).roundToInt()
            minimumHeight = (36 * density).roundToInt()
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 10 * density
            }
            val hPad = (8 * density).roundToInt()
            val vPad = (4 * density).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            setOnClickListener { onClick() }
        }
    }

    private fun spacerView(dpWidth: Int, density: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams((dpWidth * density).roundToInt(), 1)
        }
    }

    private fun removeCaptureOverlay() {
        captureOverlayView?.let { view ->
            runCatching {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            }
            captureOverlayView = null
        }
    }

    private fun finishAndReturnToApp() {
        removeCaptureOverlay()
        val backIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        runCatching { startActivity(backIntent) }
        stopSelf()
    }

    override fun onDestroy() {
        removeCaptureOverlay()
        controlBarView?.let { view ->
            runCatching {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            }
            controlBarView = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PHASE = "extra_phase"
    }
}
