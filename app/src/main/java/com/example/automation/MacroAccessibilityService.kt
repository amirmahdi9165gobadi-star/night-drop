package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import com.example.data.local.GestureActionType
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStepEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class CapturedUiNodeInfo(
    val nodeText: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?
) {
    val displayLabel: String?
        get() = nodeText?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
}

data class MacroStepExecutionResult(
    val succeeded: Boolean,
    val usedSmartNodeClick: Boolean = false,
    val usedSemanticFinder: Boolean = false,
    val matchedNodeLabel: String? = null,
    val resolvedBoundsCenterX: Int? = null,
    val resolvedBoundsCenterY: Int? = null,
    val resolvedScreenWidth: Int? = null,
    val resolvedScreenHeight: Int? = null,
    val windowChanged: Boolean = false,
    val nodeExpectedButMissing: Boolean = false,
    val blockingPopupDetected: Boolean = false
)

data class UiNodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val isClickable: Boolean,
    val centerX: Int,
    val centerY: Int
)

data class WindowSnapshot(
    val activePackage: String?,
    val windowCount: Int,
    val structuralHash: Int,
    val hasBlockingPopupHint: Boolean
)

class MacroAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var visualTestJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    /**
     * Idle Day-Time Sleep (Rule #2):
     * Discards incoming accessibility events in O(1) time during the day when BrainEngine is IDLE
     * and no visual test is running, preventing wakeups and background CPU/RAM usage.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (BrainEngineBus.state.value.state == BrainState.IDLE && !_isVisualTesting.value) {
            return
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        visualTestJob?.cancel()
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        visualTestJob?.cancel()
        serviceScope.cancel()
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    private fun newNodeTracker(): MutableSet<AccessibilityNodeInfo> =
        Collections.newSetFromMap(IdentityHashMap())

    private fun MutableSet<AccessibilityNodeInfo>.track(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node != null) add(node)
        return node
    }

    private fun recycleTrackedNodes(
        tracked: Iterable<AccessibilityNodeInfo>,
        vararg keepNodes: AccessibilityNodeInfo?
    ) {
        for (node in tracked) {
            var shouldKeep = false
            for (keep in keepNodes) {
                if (keep != null && node === keep) {
                    shouldKeep = true
                    break
                }
            }
            if (!shouldKeep) {
                recycleNodeSafe(node)
            }
        }
    }

    fun recycleNodeSafe(node: AccessibilityNodeInfo?) {
        if (node != null) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    /**
     * Hybrid Recording: inspects interactive windows / rootInActiveWindow at (x, y)
     * to extract AccessibilityNodeInfo text & contentDescription alongside coordinates.
     */
    fun inspectUiNodeAt(x: Int, y: Int): CapturedUiNodeInfo? {
        val tracker = newNodeTracker()
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return null

            var bestHit: AccessibilityNodeInfo? = null
            var bestArea = Long.MAX_VALUE
            val rect = Rect()

            for (root in roots) {
                val hit = findDeepestNodeAt(root, x, y, rect, tracker)
                if (hit != null) {
                    hit.getBoundsInScreen(rect)
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area in 1L until bestArea) {
                        bestArea = area
                        bestHit = hit
                    }
                }
            }

            val target = bestHit ?: return null
            return extractNodeLabels(target, tracker)
        } finally {
            recycleTrackedNodes(tracker)
        }
    }

    private fun collectSearchRoots(tracker: MutableSet<AccessibilityNodeInfo>? = null): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows
                ?.filter {
                    it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
                ?.sortedByDescending { it.layer }
                ?.forEach { window ->
                    val root = window.root
                    if (root != null) {
                        if (root.packageName?.toString() != packageName) {
                            tracker?.track(root)
                            roots.add(root)
                        } else {
                            recycleNodeSafe(root)
                        }
                    }
                }
        }
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) {
            if (activeRoot.packageName?.toString() != packageName && roots.none { it == activeRoot }) {
                tracker?.track(activeRoot)
                roots.add(activeRoot)
            } else if (roots.none { it === activeRoot }) {
                recycleNodeSafe(activeRoot)
            }
        }
        return roots
    }

    private fun findDeepestNodeAt(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        tempRect: Rect,
        tracker: MutableSet<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        node.getBoundsInScreen(tempRect)
        if (!tempRect.contains(x, y)) return null

        var bestChildHit: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        for (i in 0 until node.childCount) {
            val child = tracker.track(node.getChild(i)) ?: continue
            val hit = findDeepestNodeAt(child, x, y, tempRect, tracker)
            if (hit != null) {
                hit.getBoundsInScreen(tempRect)
                val area = tempRect.width().toLong() * tempRect.height().toLong()
                if (area in 1L until bestArea) {
                    bestArea = area
                    bestChildHit = hit
                }
            }
        }
        return bestChildHit ?: node
    }

    private fun extractNodeLabels(
        hitNode: AccessibilityNodeInfo,
        tracker: MutableSet<AccessibilityNodeInfo>
    ): CapturedUiNodeInfo? {
        var directText = hitNode.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        var directDesc = hitNode.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        var viewId = hitNode.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }

        // Walk up to clickable parent tile/button (common in Quick Settings tiles and custom switches)
        var ancestor: AccessibilityNodeInfo? = hitNode
        var clickableContainer: AccessibilityNodeInfo? = if (hitNode.isClickable) hitNode else null
        var depth = 0
        while (ancestor != null && depth < 5) {
            if (directText == null) {
                directText = ancestor.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (directDesc == null) {
                directDesc = ancestor.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (viewId == null) {
                viewId = ancestor.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (clickableContainer == null && ancestor.isClickable) {
                clickableContainer = ancestor
            }
            if (directText != null && directDesc != null && clickableContainer != null) break
            ancestor = tracker.track(ancestor.parent)
            depth++
        }

        // If user tapped an icon inside a clickable container, read label text from sibling children
        if (directText == null && directDesc == null && clickableContainer != null) {
            val childLabels = findFirstChildLabels(clickableContainer, maxDepth = 3, tracker = tracker)
            directText = childLabels.first
            directDesc = childLabels.second
        }

        if (directText == null && directDesc == null && viewId == null) return null
        return CapturedUiNodeInfo(
            nodeText = directText,
            contentDescription = directDesc,
            viewIdResourceName = viewId
        )
    }

    private fun findFirstChildLabels(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        tracker: MutableSet<AccessibilityNodeInfo>
    ): Pair<String?, String?> {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var foundText: String? = null
        var foundDesc: String? = null
        var visited = 0

        while (queue.isNotEmpty() && visited < 24) {
            val (node, level) = queue.removeFirst()
            visited++
            if (foundText == null) {
                foundText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (foundDesc == null) {
                foundDesc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (foundText != null && foundDesc != null) break
            if (level < maxDepth) {
                for (i in 0 until node.childCount) {
                    tracker.track(node.getChild(i))?.let { queue.add(it to (level + 1)) }
                }
            }
        }
        return foundText to foundDesc
    }

    fun captureWindowSnapshot(): WindowSnapshot {
        val tracker = newNodeTracker()
        try {
            val activeRoot = tracker.track(runCatching { rootInActiveWindow }.getOrNull())
            val activePkg = activeRoot?.packageName?.toString()
            val winCount = runCatching { windows?.size ?: 0 }.getOrDefault(0)
            var hash = (activePkg?.hashCode() ?: 0) * 31 + winCount
            var popupHint = false

            if (activeRoot != null) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(activeRoot)
                var visited = 0
                while (queue.isNotEmpty() && visited < 35) {
                    val node = queue.removeFirst()
                    visited++
                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val cls = node.className?.toString().orEmpty()
                    hash = 31 * hash + text.hashCode() + desc.hashCode() + node.childCount
                    if (cls.contains("Dialog", ignoreCase = true) ||
                        cls.contains("AlertDialog", ignoreCase = true) ||
                        text.contains("update", ignoreCase = true) ||
                        text.contains("به‌روزرسانی", ignoreCase = true) ||
                        text.contains("تبلیغ", ignoreCase = true)
                    ) {
                        popupHint = true
                    }
                    for (i in 0 until node.childCount) {
                        tracker.track(node.getChild(i))?.let { queue.add(it) }
                    }
                }
            }

            return WindowSnapshot(
                activePackage = activePkg,
                windowCount = winCount,
                structuralHash = hash,
                hasBlockingPopupHint = popupHint
            )
        } finally {
            recycleTrackedNodes(tracker)
        }
    }

    fun performSelfHealingNavigation(useHomeInsteadOfBack: Boolean = false): Boolean {
        val action = if (useHomeInsteadOfBack) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK
        return performGlobalAction(action)
    }

    /**
     * Semantic UI Finder (Requirement #1):
     * Recursively searches the active window & interactive SystemUI windows for any node whose
     * text, contentDescription, or viewId matches one of [keywords], returning the clickable
     * node or its clickable parent container.
     * Caller is responsible for calling [recycleNodeSafe] on the returned node when done.
     */
    fun findNodeByKeywords(keywords: List<String>): AccessibilityNodeInfo? {
        val normalizedKeywords = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedKeywords.isEmpty()) return null

        val tracker = newNodeTracker()
        var bestNode: AccessibilityNodeInfo? = null
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return null

            var bestScore = Int.MIN_VALUE

            outer@ for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 260) {
                    val node = queue.removeFirst()
                    visited++
                    if (!node.isVisibleToUser) continue

                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val viewId = node.viewIdResourceName?.trim().orEmpty()

                    var nodeScore = Int.MIN_VALUE
                    for (kw in normalizedKeywords) {
                        val exactHit = text.equals(kw, ignoreCase = true) || desc.equals(kw, ignoreCase = true)
                        val containsHit = text.contains(kw, ignoreCase = true) ||
                            desc.contains(kw, ignoreCase = true) ||
                            viewId.contains(kw, ignoreCase = true)

                        val score = when {
                            exactHit -> 100
                            text.startsWith(kw, ignoreCase = true) || desc.startsWith(kw, ignoreCase = true) -> 75
                            containsHit -> 50
                            else -> Int.MIN_VALUE
                        }
                        if (score > nodeScore) {
                            nodeScore = score
                        }
                    }

                    if (nodeScore > bestScore) {
                        val clickableTarget = findClickableAncestorOrSelf(node, tracker)
                        if (clickableTarget != null) {
                            bestScore = nodeScore
                            bestNode = clickableTarget
                            if (bestScore >= 100) {
                                break@outer
                            }
                        }
                    }

                    for (i in 0 until node.childCount) {
                        tracker.track(node.getChild(i))?.let { queue.add(it) }
                    }
                }
            }
            return bestNode
        } finally {
            recycleTrackedNodes(tracker, bestNode)
        }
    }

    fun clickNodeByKeywords(
        keywords: List<String>,
        showVisualDot: Boolean = false
    ): Pair<Boolean, String?> {
        val (clicked, label, _) = clickNodeByKeywordsWithBounds(keywords, showVisualDot)
        return clicked to label
    }

    fun clickNodeByKeywordsWithBounds(
        keywords: List<String>,
        showVisualDot: Boolean = false
    ): Triple<Boolean, String?, Pair<Int, Int>?> {
        val target = findNodeByKeywords(keywords) ?: return Triple(false, null, null)
        return try {
            val rect = Rect()
            target.getBoundsInScreen(rect)
            val center = if (!rect.isEmpty) rect.centerX() to rect.centerY() else null
            if (showVisualDot && !rect.isEmpty) {
                showVisualTouchDot(rect.exactCenterX(), rect.exactCenterY())
            }
            val clicked = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            val matchedLabel = target.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: target.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: keywords.firstOrNull()
            Triple(clicked, matchedLabel, center)
        } finally {
            recycleNodeSafe(target)
        }
    }

    /**
     * Extracts visible and interactive UI elements from rootInActiveWindow and system windows
     * so GeminiApiTroubleshooter can analyze unknown screens without screenshots.
     */
    fun dumpInteractiveUiTree(maxNodes: Int = 80): List<UiNodeSnapshot> {
        val tracker = newNodeTracker()
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return emptyList()

            val collected = mutableListOf<UiNodeSnapshot>()
            val rect = Rect()
            val seenKeys = hashSetOf<String>()

            for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 300 && collected.size < maxNodes) {
                    val node = queue.removeFirst()
                    visited++
                    if (!node.isVisibleToUser) continue

                    val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    val viewId = node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }
                    val clickable = node.isClickable ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

                    if (clickable || text != null || desc != null) {
                        node.getBoundsInScreen(rect)
                        if (!rect.isEmpty && (text != null || desc != null || clickable)) {
                            val dedupKey = "$text|$desc|$clickable|${rect.centerX()}|${rect.centerY()}"
                            if (seenKeys.add(dedupKey)) {
                                collected.add(
                                    UiNodeSnapshot(
                                        text = text,
                                        contentDescription = desc,
                                        viewId = viewId,
                                        className = node.className?.toString()?.substringAfterLast('.'),
                                        isClickable = clickable,
                                        centerX = rect.centerX(),
                                        centerY = rect.centerY()
                                    )
                                )
                            }
                        }
                    }

                    for (i in 0 until node.childCount) {
                        tracker.track(node.getChild(i))?.let { queue.add(it) }
                    }
                }
            }
            return collected
        } finally {
            recycleTrackedNodes(tracker)
        }
    }

    /**
     * Smart Fallback Execution:
     * Priority 1: Search current window for exact recorded nodeText / contentDescription and perform ACTION_CLICK.
     * Priority 2: Semantic UI Finder (findNodeByKeywords) across multilingual & carrier synonyms.
     * Priority 3: Dispatch gesture via exact recorded coordinates & duration.
     */
    suspend fun dispatchMacroStepWithReport(
        step: MacroStepEntity,
        showVisualDot: Boolean = false
    ): MacroStepExecutionResult {
        val action = runCatching { GestureActionType.valueOf(step.actionType) }.getOrNull()
            ?: return MacroStepExecutionResult(succeeded = false)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(720)
        val screenH = metrics.heightPixels.coerceAtLeast(1280)
        val beforeSnap = captureWindowSnapshot()

        val rawResult = when (action) {
            GestureActionType.TAP -> {
                // Priority 1: Exact UI Node Search + ACTION_CLICK
                val smartNode = findMatchingClickableNode(
                    nodeText = step.nodeText,
                    contentDescription = step.contentDescription
                )
                val exactResult = if (smartNode != null) {
                    try {
                        val rect = Rect()
                        smartNode.getBoundsInScreen(rect)
                        if (showVisualDot && !rect.isEmpty) {
                            showVisualTouchDot(rect.exactCenterX(), rect.exactCenterY())
                        }
                        val clicked = runCatching {
                            smartNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }.getOrDefault(false)
                        if (clicked) {
                            MacroStepExecutionResult(
                                succeeded = true,
                                usedSmartNodeClick = true,
                                matchedNodeLabel = step.primaryNodeLabel(),
                                resolvedBoundsCenterX = if (!rect.isEmpty) rect.centerX() else null,
                                resolvedBoundsCenterY = if (!rect.isEmpty) rect.centerY() else null,
                                resolvedScreenWidth = screenW,
                                resolvedScreenHeight = screenH
                            )
                        } else {
                            null
                        }
                    } finally {
                        recycleNodeSafe(smartNode)
                    }
                } else {
                    null
                }

                // Priority 2: Semantic UI Finder (keywords + synonym clusters)
                val semanticResult = exactResult ?: run {
                    val semanticKeywords = inferSemanticKeywordsForStep(step)
                    if (semanticKeywords.isNotEmpty()) {
                        val (semanticClicked, semanticLabel, center) = clickNodeByKeywordsWithBounds(
                            keywords = semanticKeywords,
                            showVisualDot = showVisualDot
                        )
                        if (semanticClicked) {
                            MacroStepExecutionResult(
                                succeeded = true,
                                usedSmartNodeClick = true,
                                usedSemanticFinder = true,
                                matchedNodeLabel = semanticLabel ?: semanticKeywords.firstOrNull(),
                                resolvedBoundsCenterX = center?.first,
                                resolvedBoundsCenterY = center?.second,
                                resolvedScreenWidth = screenW,
                                resolvedScreenHeight = screenH
                            )
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }

                // Priority 3: Fallback to exact recorded coordinates via dispatchGesture
                semanticResult ?: run {
                    val tx = resolveCoord(step.pixelStartX, step.normStartX, screenW)
                    val ty = resolveCoord(step.pixelStartY, step.normStartY, screenH)
                    if (showVisualDot) {
                        showVisualTouchDot(tx, ty)
                    }
                    val gestureOk = dispatchPathGesture(
                        tx,
                        ty,
                        tx,
                        ty,
                        step.durationMs.coerceIn(50L, 1_000L)
                    )
                    MacroStepExecutionResult(
                        succeeded = gestureOk,
                        usedSmartNodeClick = false,
                        nodeExpectedButMissing = step.hasNodeSelector
                    )
                }
            }

            GestureActionType.SWIPE -> {
                val sx = resolveCoord(step.pixelStartX, step.normStartX, screenW)
                val sy = resolveCoord(step.pixelStartY, step.normStartY, screenH)
                val ex = resolveCoord(step.pixelEndX, step.normEndX, screenW)
                val ey = resolveCoord(step.pixelEndY, step.normEndY, screenH)
                if (showVisualDot) {
                    showVisualTouchDot(sx, sy)
                }
                val result = dispatchPathGesture(
                    sx,
                    sy,
                    ex,
                    ey,
                    step.durationMs.coerceIn(150L, 3_000L)
                )
                if (showVisualDot) {
                    showVisualTouchDot(ex, ey)
                }
                MacroStepExecutionResult(succeeded = result, usedSmartNodeClick = false)
            }

            GestureActionType.GLOBAL_HOME ->
                MacroStepExecutionResult(performGlobalAction(GLOBAL_ACTION_HOME))
            GestureActionType.GLOBAL_QUICK_SETTINGS ->
                MacroStepExecutionResult(performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS))
            GestureActionType.GLOBAL_NOTIFICATIONS ->
                MacroStepExecutionResult(performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS))
            GestureActionType.GLOBAL_RECENTS ->
                MacroStepExecutionResult(performGlobalAction(GLOBAL_ACTION_RECENTS))
            GestureActionType.GLOBAL_POWER_DIALOG ->
                MacroStepExecutionResult(performGlobalAction(GLOBAL_ACTION_POWER_DIALOG))
            else -> MacroStepExecutionResult(succeeded = true)
        }

        delay(80L)
        val afterSnap = captureWindowSnapshot()
        return rawResult.copy(
            windowChanged = beforeSnap != afterSnap,
            blockingPopupDetected = afterSnap.hasBlockingPopupHint && rawResult.nodeExpectedButMissing
        )
    }

    private fun inferSemanticKeywordsForStep(step: MacroStepEntity): List<String> {
        val combined = listOfNotNull(step.nodeText, step.contentDescription, step.label)
            .joinToString(" ")
            .lowercase()

        val inferred = linkedSetOf<String>()
        step.nodeText?.trim()?.takeIf { it.isNotEmpty() }?.let { inferred.add(it) }
        step.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { inferred.add(it) }

        if (combined.contains("data") || combined.contains("دیتا") || combined.contains("داده") || combined.contains("internet") || combined.contains("اینترنت")) {
            inferred.addAll(MOBILE_DATA_KEYWORDS)
        }
        if (combined.contains("irancell") || combined.contains("ایرانسل") || combined.contains("mci") || combined.contains("همراه اول") || combined.contains("sim")) {
            inferred.addAll(CARRIER_KEYWORDS)
        }
        if (combined.contains("power") || combined.contains("خاموش") || combined.contains("shut")) {
            inferred.addAll(POWER_OFF_KEYWORDS)
        }
        if (combined.contains("airplane") || combined.contains("flight") || combined.contains("هواپیما")) {
            inferred.addAll(AIRPLANE_MODE_KEYWORDS)
        }
        return inferred.toList()
    }

    private fun findMatchingClickableNode(
        nodeText: String?,
        contentDescription: String?
    ): AccessibilityNodeInfo? {
        val targetText = nodeText?.trim()?.takeIf { it.isNotEmpty() }
        val targetDesc = contentDescription?.trim()?.takeIf { it.isNotEmpty() }
        if (targetText == null && targetDesc == null) return null

        val tracker = newNodeTracker()
        var winner: AccessibilityNodeInfo? = null
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return null

            val exactMatches = mutableListOf<AccessibilityNodeInfo>()
            val partialMatches = mutableListOf<AccessibilityNodeInfo>()

            for (root in roots) {
                if (targetText != null) {
                    root.findAccessibilityNodeInfosByText(targetText)?.forEach { candidate ->
                        tracker.track(candidate)
                        val text = candidate.text?.toString()?.trim()
                        val desc = candidate.contentDescription?.toString()?.trim()
                        if (text.equals(targetText, ignoreCase = true) ||
                            desc.equals(targetText, ignoreCase = true)
                        ) {
                            exactMatches.add(candidate)
                        } else {
                            partialMatches.add(candidate)
                        }
                    }
                }
                collectContentDescriptionMatches(
                    node = root,
                    targetText = targetText,
                    targetDesc = targetDesc,
                    exactMatches = exactMatches,
                    partialMatches = partialMatches,
                    tracker = tracker
                )
            }

            val orderedCandidates = exactMatches + partialMatches
            for (candidate in orderedCandidates) {
                val clickable = findClickableAncestorOrSelf(candidate, tracker)
                if (clickable != null) {
                    winner = clickable
                    break
                }
            }
            return winner
        } finally {
            recycleTrackedNodes(tracker, winner)
        }
    }

    private fun collectContentDescriptionMatches(
        node: AccessibilityNodeInfo,
        targetText: String?,
        targetDesc: String?,
        exactMatches: MutableList<AccessibilityNodeInfo>,
        partialMatches: MutableList<AccessibilityNodeInfo>,
        tracker: MutableSet<AccessibilityNodeInfo>
    ) {
        if (!node.isVisibleToUser) return
        val desc = node.contentDescription?.toString()?.trim()
        val text = node.text?.toString()?.trim()

        val exactHit = (targetDesc != null && (desc.equals(targetDesc, ignoreCase = true) || text.equals(targetDesc, ignoreCase = true))) ||
            (targetText != null && desc.equals(targetText, ignoreCase = true))

        val partialHit = !exactHit && (
            (targetDesc != null && ((desc?.contains(targetDesc, ignoreCase = true) == true) || (text?.contains(targetDesc, ignoreCase = true) == true))) ||
                (targetText != null && desc?.contains(targetText, ignoreCase = true) == true)
            )

        if (exactHit) {
            exactMatches.add(node)
        } else if (partialHit) {
            partialMatches.add(node)
        }

        for (i in 0 until node.childCount) {
            tracker.track(node.getChild(i))?.let {
                collectContentDescriptionMatches(it, targetText, targetDesc, exactMatches, partialMatches, tracker)
            }
        }
    }

    private fun findClickableAncestorOrSelf(
        node: AccessibilityNodeInfo,
        tracker: MutableSet<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            val supportsClick = current.isClickable ||
                current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (current.isEnabled && supportsClick) {
                return current
            }
            current = tracker.track(current.parent)
            depth++
        }
        return null
    }

    fun startVisualMacroTest(phase: MacroPhase, steps: List<MacroStepEntity>) {
        if (steps.isEmpty()) return
        visualTestJob?.cancel()
        visualTestJob = serviceScope.launch {
            _isVisualTesting.value = true
            val banner = showCountdownBanner(
                phaseLabel = if (phase == MacroPhase.PRE_DOWNLOAD) "تست ماکرو قبل از دانلود" else "تست ماکرو بعد از دانلود"
            )
            try {
                for (sec in 3 downTo 1) {
                    banner?.text = "تست ماکرو تا $sec ثانیه دیگر..."
                    delay(1_000L)
                }
                removeOverlayView(banner)

                steps.forEachIndexed { idx, step ->
                    val action = runCatching { GestureActionType.valueOf(step.actionType) }.getOrNull()
                    when (action) {
                        GestureActionType.OPEN_CONNECTIVITY_PANEL ->
                            SettingsNavigator.openConnectivityPanel(this@MacroAccessibilityService)
                        else -> dispatchMacroStepWithReport(step, showVisualDot = true)
                    }
                    if (idx < steps.lastIndex && step.delayAfterMs > 0L) {
                        delay(step.delayAfterMs)
                    }
                }
            } finally {
                removeOverlayView(banner)
                _isVisualTesting.value = false
            }
        }
    }

    suspend fun performSwipeUnlock(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(720)
        val height = metrics.heightPixels.coerceAtLeast(1280)
        val x = width / 2f
        val startY = height * 0.84f
        val endY = height * 0.22f
        return dispatchPathGesture(startX = x, startY = startY, endX = x, endY = endY, durationMs = 380L)
    }

    suspend fun dispatchMacroStep(
        step: MacroStepEntity,
        showVisualDot: Boolean = false
    ): Boolean = dispatchMacroStepWithReport(step, showVisualDot).succeeded

    suspend fun dispatchTapNow(x: Float, y: Float, durationMs: Long = 90L): Boolean {
        return dispatchPathGesture(x, y, x, y, durationMs.coerceIn(50L, 1_000L))
    }

    suspend fun dispatchSwipeNow(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L
    ): Boolean {
        return dispatchPathGesture(startX, startY, endX, endY, durationMs.coerceIn(150L, 3_000L))
    }

    fun triggerGlobalAction(actionType: GestureActionType): Boolean {
        return when (actionType) {
            GestureActionType.GLOBAL_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureActionType.GLOBAL_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            GestureActionType.GLOBAL_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            GestureActionType.GLOBAL_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureActionType.GLOBAL_POWER_DIALOG -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            else -> false
        }
    }

    fun openQuickSettingsPanel(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun closeSystemPanels(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun navigateBackFromExternalApp(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun isSoftKeyboardVisible(): Boolean {
        return runCatching {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        }.getOrDefault(false)
    }

    fun getActiveWindowPackageName(): String? {
        return runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
    }

    /**
     * Locates the prompt EditText / editable input field inside an external AI chat app (e.g., DeepSeek).
     * Caller is responsible for calling [recycleNodeSafe] on the returned node.
     */
    fun findDeepSeekInputField(): AccessibilityNodeInfo? {
        val tracker = newNodeTracker()
        var winner: AccessibilityNodeInfo? = null
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return null

            val candidates = mutableListOf<AccessibilityNodeInfo>()
            for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 300) {
                    val node = queue.removeFirst()
                    visited++
                    if (!node.isVisibleToUser || !node.isEnabled) continue

                    val cls = node.className?.toString().orEmpty()
                    val supportsSetText = node.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                    }
                    val isEditableField = node.isEditable ||
                        cls.contains("EditText", ignoreCase = true) ||
                        supportsSetText

                    if (isEditableField) {
                        candidates.add(node)
                    }

                    for (i in 0 until node.childCount) {
                        tracker.track(node.getChild(i))?.let { queue.add(it) }
                    }
                }
            }
            // Prefer the bottom-most editable field (composer input bar)
            val rect = Rect()
            winner = candidates.maxByOrNull { candidate ->
                candidate.getBoundsInScreen(rect)
                rect.centerY()
            }
            return winner
        } finally {
            recycleTrackedNodes(tracker, winner)
        }
    }

    /**
     * Injects text into an EditText using ACTION_SET_TEXT so long prompts are pasted reliably without keyboard delays.
     */
    fun injectTextIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /**
     * Finds the Send button on DeepSeek's chat screen via contentDescription/text/viewId keywords,
     * with a positional fallback near the composer input box for icon-only ImageButtons.
     * Caller is responsible for calling [recycleNodeSafe] on the returned node.
     */
    fun findDeepSeekSendButton(inputNode: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        findNodeByKeywords(DEEPSEEK_SEND_KEYWORDS)?.let { return it }

        val tracker = newNodeTracker()
        var winner: AccessibilityNodeInfo? = null
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return null

            val metrics = resources.displayMetrics
            val screenW = metrics.widthPixels.coerceAtLeast(720)
            val screenH = metrics.heightPixels.coerceAtLeast(1280)
            val inputRect = Rect()
            val hasInputBounds = inputNode != null && run {
                inputNode.getBoundsInScreen(inputRect)
                !inputRect.isEmpty
            }

            val rect = Rect()
            val fallbackCandidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

            for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 300) {
                    val node = queue.removeFirst()
                    visited++
                    if (!node.isVisibleToUser || !node.isEnabled) continue

                    val cls = node.className?.toString().orEmpty()
                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    val isClickableAction = node.isClickable ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

                    if (isClickableAction && !node.isEditable && !cls.contains("EditText", ignoreCase = true)) {
                        node.getBoundsInScreen(rect)
                        if (!rect.isEmpty && rect.width() in 24..(screenW / 2) && rect.height() in 24..(screenH / 4)) {
                            var score = 0
                            if (viewId.contains("send") || viewId.contains("submit")) score += 100
                            if (hasInputBounds) {
                                val sameRowOrBelow = rect.centerY() >= inputRect.top - 40 &&
                                    rect.centerY() <= inputRect.bottom + 220
                                val toRightOfInput = rect.centerX() >= inputRect.centerX()
                                if (sameRowOrBelow && toRightOfInput) score += 80
                                else if (sameRowOrBelow) score += 45
                            } else if (rect.centerY() >= (screenH * 0.45f) && rect.centerX() >= (screenW * 0.65f)) {
                                score += 40
                            }
                            if (cls.contains("Button", ignoreCase = true) || cls.contains("Image", ignoreCase = true)) {
                                score += 15
                            }
                            if (score >= 40) {
                                fallbackCandidates.add(node to score)
                            }
                        }
                    }

                    for (i in 0 until node.childCount) {
                        tracker.track(node.getChild(i))?.let { queue.add(it) }
                    }
                }
            }
            winner = fallbackCandidates.maxByOrNull { it.second }?.first
            return winner
        } finally {
            recycleTrackedNodes(tracker, winner, inputNode)
        }
    }

    /**
     * Reads the latest assistant response text visible in the DeepSeek window while filtering out
     * the EditText composer and the sent user prompt itself.
     */
    fun readLatestDeepSeekResponseText(promptSignature: String): String? {
        val tracker = newNodeTracker()
        try {
            val roots = collectSearchRoots(tracker)
            if (roots.isEmpty()) return null

            val messageBlocks = mutableListOf<String>()
            val seen = hashSetOf<String>()
            val promptKey = promptSignature.trim()

            for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 400) {
                    val node = queue.removeFirst()
                    visited++
                    if (!node.isVisibleToUser) continue

                    val cls = node.className?.toString().orEmpty()
                    if (node.isEditable || cls.contains("EditText", ignoreCase = true)) {
                        continue
                    }

                    val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    if (text != null &&
                        !isIgnoredDeepSeekChromeText(text, promptKey) &&
                        seen.add(text)
                    ) {
                        messageBlocks.add(text)
                    }

                    for (i in 0 until node.childCount) {
                        tracker.track(node.getChild(i))?.let { queue.add(it) }
                    }
                }
            }

            if (messageBlocks.isEmpty()) return null
            val jsonCandidates = messageBlocks.filter { block ->
                block.contains('[') && block.contains('{') && block.contains(']')
            }
            return jsonCandidates.lastOrNull() ?: messageBlocks.lastOrNull()
        } finally {
            recycleTrackedNodes(tracker)
        }
    }

    private fun isIgnoredDeepSeekChromeText(text: String, promptSignature: String): Boolean {
        if (promptSignature.isNotEmpty() && text.contains(promptSignature)) return true
        val trimmed = text.trim()
        if (trimmed.length < 4) return true
        return trimmed.equals("DeepSeek", ignoreCase = true) ||
            trimmed.equals("New chat", ignoreCase = true) ||
            trimmed.equals("DeepThink (R1)", ignoreCase = true) ||
            trimmed.equals("DeepThink", ignoreCase = true) ||
            trimmed.equals("Search", ignoreCase = true) ||
            trimmed.startsWith("Thinking", ignoreCase = true) ||
            trimmed.startsWith("در حال فکر", ignoreCase = true) ||
            trimmed.contains("Message DeepSeek", ignoreCase = true) ||
            trimmed.contains("AI-generated", ignoreCase = true)
    }

    private fun showVisualTouchDot(centerX: Float, centerY: Float) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val sizePx = (40f * density).roundToInt()
        val halfPx = sizePx / 2

        mainHandler.post {
            val dotView = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#99EF4444"))
                    setStroke((2f * density).roundToInt(), Color.parseColor("#FFEF4444"))
                }
                alpha = 1f
            }

            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (centerX - halfPx).roundToInt()
                y = (centerY - halfPx).roundToInt()
            }

            val added = runCatching {
                wm.addView(dotView, params)
                true
            }.getOrDefault(false)

            if (added) {
                dotView.animate()
                    .alpha(0f)
                    .setDuration(600L)
                    .withEndAction { removeOverlayView(dotView) }
                    .start()

                mainHandler.postDelayed({ removeOverlayView(dotView) }, 650L)
            }
        }
    }

    private fun showCountdownBanner(phaseLabel: String): TextView? {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val padH = (16f * density).roundToInt()
        val padV = (10f * density).roundToInt()

        val banner = TextView(this).apply {
            text = "$phaseLabel..."
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60E172A"))
                cornerRadius = 16f * density
                setStroke((1.5f * density).roundToInt(), Color.parseColor("#EF4444"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (64f * density).roundToInt()
        }

        return if (runCatching { wm.addView(banner, params) }.isSuccess) banner else null
    }

    private fun removeOverlayView(view: View?) {
        if (view == null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching {
            if (view.isAttachedToWindow) {
                wm.removeViewImmediate(view)
            }
        }
    }

    private fun resolveCoord(pixel: Int, norm: Float, maxBound: Int): Float {
        val raw = if (pixel > 0) pixel.toFloat() else (norm * maxBound)
        return raw.roundToInt().coerceIn(2, maxBound - 2).toFloat()
    }

    private suspend fun dispatchPathGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val metrics = resources.displayMetrics
        val maxW = (metrics.widthPixels - 2).coerceAtLeast(10).toFloat()
        val maxH = (metrics.heightPixels - 2).coerceAtLeast(10).toFloat()

        val sx = startX.coerceIn(2f, maxW)
        val sy = startY.coerceIn(2f, maxH)
        val ex = endX.coerceIn(2f, maxW)
        val ey = endY.coerceIn(2f, maxH)

        val path = Path().apply {
            moveTo(sx, sy)
            if (sx != ex || sy != ey) {
                lineTo(ex, ey)
            }
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _isVisualTesting = MutableStateFlow(false)
        val isVisualTesting: StateFlow<Boolean> = _isVisualTesting.asStateFlow()

        val MOBILE_DATA_KEYWORDS = listOf(
            "Mobile data",
            "داده تلفن همراه",
            "اینترنت همراه",
            "داده همراه",
            "Cellular data",
            "Internet",
            "اینترنت",
            "دیتا",
            "Data"
        )

        val CARRIER_KEYWORDS = listOf(
            "Irancell",
            "ایرانسل",
            "MCI",
            "همراه اول",
            "Rightel",
            "رایتل",
            "SIM 1",
            "SIM 2",
            "سیم‌کارت"
        )

        val POWER_OFF_KEYWORDS = listOf(
            "Power off",
            "خاموش کردن",
            "خاموش",
            "Shut down",
            "Power"
        )

        val AIRPLANE_MODE_KEYWORDS = listOf(
            "Airplane mode",
            "Flight mode",
            "حالت هواپیما",
            "هواپیما",
            "Aeroplane mode"
        )

        val DEEPSEEK_SEND_KEYWORDS = listOf(
            "Send",
            "ارسال",
            "send",
            "Submit",
            "send_btn",
            "btn_send",
            "send_button"
        )
    }
}
