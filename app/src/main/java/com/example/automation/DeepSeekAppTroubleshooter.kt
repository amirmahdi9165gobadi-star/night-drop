package com.example.automation

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class DeepSeekTroubleshootResult(
    val succeeded: Boolean,
    val generatedSteps: List<MacroStep> = emptyList(),
    val extractedJson: String? = null,
    val rawResponseText: String? = null,
    val errorMessage: String? = null
)

interface DeepSeekUiController {
    fun isPackageInstalled(packageName: String): Boolean
    fun launchApp(packageName: String): Boolean
    fun findInputAndInjectText(prompt: String): Boolean
    fun clickSendButton(): Boolean
    fun readLatestResponse(promptSignature: String): String?
    fun pressGlobalBack(): Boolean
}

class AccessibilityDeepSeekUiController(
    private val context: Context
) : DeepSeekUiController {

    override fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            val pm = context.packageManager
            if (pm.getLaunchIntentForPackage(packageName) != null) return true
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    override fun launchApp(packageName: String): Boolean {
        return runCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    override fun findInputAndInjectText(prompt: String): Boolean {
        return runCatching {
            val acc = MacroAccessibilityService.instance ?: return false
            val inputNode = acc.findDeepSeekInputField() ?: return false
            try {
                acc.injectTextIntoNode(inputNode, prompt)
            } finally {
                acc.recycleNodeSafe(inputNode)
            }
        }.getOrDefault(false)
    }

    override fun clickSendButton(): Boolean {
        return runCatching {
            val acc = MacroAccessibilityService.instance ?: return false
            // Always re-query fresh input bounds after soft keyboard (IME) shifts the layout
            val freshInputNode = acc.findDeepSeekInputField()
            val sendNode = acc.findDeepSeekSendButton(freshInputNode)
            try {
                sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } finally {
                acc.recycleNodeSafe(sendNode)
                acc.recycleNodeSafe(freshInputNode)
            }
        }.getOrDefault(false)
    }

    override fun readLatestResponse(promptSignature: String): String? {
        return runCatching {
            val acc = MacroAccessibilityService.instance ?: return null
            acc.readLatestDeepSeekResponseText(promptSignature)
        }.getOrNull()
    }

    override fun pressGlobalBack(): Boolean {
        return runCatching {
            val acc = MacroAccessibilityService.instance ?: return false
            val firstBack = acc.navigateBackFromExternalApp()
            // If soft keyboard was open, first BACK closes keyboard and stays in com.deepseek.chat
            if (acc.isSoftKeyboardVisible() ||
                acc.getActiveWindowPackageName() == DeepSeekAppTroubleshooter.DEEPSEEK_PACKAGE_NAME
            ) {
                Thread.sleep(220L)
                acc.navigateBackFromExternalApp()
            } else {
                firstBack
            }
        }.getOrDefault(false)
    }
}

/**
 * Tier-2 Emergency AI Fallback:
 * When GeminiApiTroubleshooter fails or times out (8s), launches the locally installed DeepSeek app
 * (com.deepseek.chat), submits a compact UI tree snapshot + goal via ACTION_SET_TEXT, polls every
 * 2s until the response stabilizes for 4s, extracts the JSON macro steps via Regex, presses Back,
 * and returns executable MacroSteps to BrainEngine & NightDownloadService.
 */
class DeepSeekAppTroubleshooter(
    private val context: Context,
    private val uiControllerFactory: () -> DeepSeekUiController = { AccessibilityDeepSeekUiController(context) },
    private val loadWaitMs: Long = DEFAULT_APP_LOAD_WAIT_MS,
    private val postInjectWaitMs: Long = 450L,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val stableDurationMs: Long = DEFAULT_STABLE_DURATION_MS,
    private val maxPollTimeoutMs: Long = DEFAULT_MAX_POLL_TIMEOUT_MS,
    private val backSettleMs: Long = 500L
) {

    suspend fun solveViaDeepSeekApp(
        goalDescription: String,
        phase: MacroPhase,
        uiNodes: List<UiNodeSnapshot>,
        screenWidthPx: Int,
        screenHeightPx: Int,
        onStatus: (suspend (String) -> Unit)? = null
    ): DeepSeekTroubleshootResult = withContext(Dispatchers.Main) {
        if (uiNodes.isEmpty()) {
            return@withContext DeepSeekTroubleshootResult(
                succeeded = false,
                errorMessage = "نقشه UI صفحه قبلی برای ارسال به DeepSeek خالی است"
            )
        }

        val controller = uiControllerFactory()
        var launchedDeepSeek = false

        try {
            // Step 1: Compact serialize UI tree BEFORE leaving target screen
            val compactTree = serializeCompactUiTree(uiNodes)
            val prompt = buildDeepSeekPrompt(
                goalDescription = goalDescription,
                compactUiTree = compactTree,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )

            if (!controller.isPackageInstalled(DEEPSEEK_PACKAGE_NAME)) {
                return@withContext DeepSeekTroubleshootResult(
                    succeeded = false,
                    errorMessage = "اپلیکیشن DeepSeek ($DEEPSEEK_PACKAGE_NAME) روی دستگاه نصب نیست"
                )
            }

            // Step 2: Launch DeepSeek and wait 3 seconds for it to load
            onStatus?.invoke("باز کردن اپلیکیشن DeepSeek و انتظار ${loadWaitMs / 1000} ثانیه...")
            if (!controller.launchApp(DEEPSEEK_PACKAGE_NAME)) {
                return@withContext DeepSeekTroubleshooterFailure(
                    "امکان باز کردن اپلیکیشن DeepSeek وجود ندارد"
                )
            }
            launchedDeepSeek = true
            delay(loadWaitMs)

            // Step 3: Find EditText composer and inject prompt via ACTION_SET_TEXT (retry if app UI or keyboard is warming up)
            var injected = false
            for (attempt in 1..3) {
                injected = controller.findInputAndInjectText(prompt)
                if (injected) break
                delay(900L)
            }
            if (!injected) {
                controller.pressGlobalBack()
                return@withContext DeepSeekTroubleshooterFailure(
                    "کادر متنی (EditText) در صفحه DeepSeek پیدا نشد یا تزریق متن ناموفق بود"
                )
            }

            // Allow DeepSeek composer/keyboard transition to swap voice icon for Send button
            delay(postInjectWaitMs)
            var sent = false
            for (attempt in 1..3) {
                sent = controller.clickSendButton()
                if (sent) break
                delay(550L)
            }
            if (!sent) {
                controller.pressGlobalBack()
                return@withContext DeepSeekTroubleshooterFailure(
                    "دکمه ارسال (Send) در صفحه DeepSeek پیدا نشد"
                )
            }

            // Step 4: Poll every 2 seconds until answer remains unchanged for 4 seconds
            onStatus?.invoke("در انتظار تکمیل پاسخ JSON از DeepSeek...")
            var elapsedMs = 0L
            var lastResponse: String? = null
            var unchangedDurationMs = 0L
            var stableResponse: String? = null

            while (elapsedMs < maxPollTimeoutMs) {
                delay(pollIntervalMs)
                elapsedMs += pollIntervalMs

                val currentText = controller.readLatestResponse(PROMPT_SIGNATURE)?.trim()
                if (currentText.isNullOrBlank()) {
                    lastResponse = null
                    unchangedDurationMs = 0L
                    continue
                }

                if (currentText == lastResponse) {
                    unchangedDurationMs += pollIntervalMs
                    val candidateJson = extractJsonArrayWithRegex(currentText)
                    if (candidateJson != null && unchangedDurationMs >= stableDurationMs) {
                        stableResponse = currentText
                        break
                    }
                } else {
                    lastResponse = currentText
                    unchangedDurationMs = 0L
                }
            }

            val finalRawText = stableResponse ?: lastResponse
            // Dismiss IME (if open) and return from DeepSeek to restore target screen focus
            controller.pressGlobalBack()
            delay(backSettleMs)

            if (finalRawText.isNullOrBlank()) {
                return@withContext DeepSeekTroubleshooterFailure(
                    "پاسخی از اپلیکیشن DeepSeek در بازه مجاز دریافت نشد"
                )
            }

            val extractedJson = extractJsonArrayWithRegex(finalRawText)
            if (extractedJson.isNullOrBlank()) {
                return@withContext DeepSeekTroubleshootResult(
                    succeeded = false,
                    rawResponseText = finalRawText,
                    errorMessage = "آرایه JSON معتبر در پاسخ DeepSeek یافت نشد"
                )
            }

            val steps = GeminiApiTroubleshooter.parseGeminiMacroResponse(
                rawResponse = extractedJson,
                phase = phase,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )

            return@withContext if (steps.isEmpty()) {
                DeepSeekTroubleshootResult(
                    succeeded = false,
                    extractedJson = extractedJson,
                    rawResponseText = finalRawText,
                    errorMessage = "قدم‌های استخراج‌شده از JSON خروجی DeepSeek خالی یا نامعتبر بود"
                )
            } else {
                DeepSeekTroubleshootResult(
                    succeeded = true,
                    generatedSteps = steps,
                    extractedJson = extractedJson,
                    rawResponseText = finalRawText
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (launchedDeepSeek) {
                runCatching { controller.pressGlobalBack() }
            }
            return@withContext DeepSeekTroubleshooterFailure(
                e.message ?: "خطای غیرمنتظره در ارتباط با اپلیکیشن DeepSeek"
            )
        }
    }

    private fun DeepSeekTroubleshooterFailure(reason: String): DeepSeekTroubleshootResult {
        return DeepSeekTroubleshootResult(succeeded = false, errorMessage = reason)
    }

    companion object {
        const val DEEPSEEK_PACKAGE_NAME = "com.deepseek.chat"
        const val DEFAULT_APP_LOAD_WAIT_MS = 3_000L
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_STABLE_DURATION_MS = 4_000L
        const val DEFAULT_MAX_POLL_TIMEOUT_MS = 45_000L
        const val PROMPT_SIGNATURE = "[NIGHTDROP_UI_MAP]"

        private val FENCED_JSON_ARRAY_REGEX = Regex(
            pattern = "```(?:json)?\\s*(\\[[\\s\\S]*?])\\s*```",
            option = RegexOption.IGNORE_CASE
        )
        private val RAW_JSON_ARRAY_REGEX = Regex(
            pattern = "\\[\\s*\\{[\\s\\S]*?}\\s*]"
        )

        /**
         * Compact UI Tree Serializer:
         * Converts visible clickable & textual nodes into a dense, low-token summary with (centerX, centerY)
         * so it can be pasted quickly into DeepSeek chat via ACTION_SET_TEXT.
         */
        fun serializeCompactUiTree(
            uiNodes: List<UiNodeSnapshot>,
            maxNodes: Int = 28
        ): String {
            if (uiNodes.isEmpty()) return "[]"

            val filtered = uiNodes
                .filter { node ->
                    node.isClickable ||
                        !node.text.isNullOrBlank() ||
                        !node.contentDescription.isNullOrBlank()
                }
                .distinctBy { "${it.text}|${it.contentDescription}|${it.centerX},${it.centerY}" }
                .sortedByDescending { node ->
                    when {
                        node.isClickable && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) -> 3
                        node.isClickable -> 2
                        else -> 1
                    }
                }
                .take(maxNodes)

            return filtered.mapIndexed { idx, node ->
                val tag = if (node.isClickable) "CLICK" else "TXT"
                val label = listOfNotNull(
                    node.text?.replace("\n", " ")?.take(42)?.let { "\"$it\"" },
                    node.contentDescription?.replace("\n", " ")?.take(36)?.let { "desc=\"$it\"" },
                    node.viewId?.substringAfterLast('/')?.take(24)?.let { "id=$it" }
                ).joinToString(" ").ifBlank { node.className ?: "Node" }
                "#${idx + 1}[$tag] $label @ (${node.centerX},${node.centerY})"
            }.joinToString(separator = "\n")
        }

        fun buildDeepSeekPrompt(
            goalDescription: String,
            compactUiTree: String,
            screenWidthPx: Int,
            screenHeightPx: Int
        ): String {
            return """
                $PROMPT_SIGNATURE
                من یک ربات اتوماسیون اندروید هستم. گیر افتاده‌ام و هدف فعلی من این است: «$goalDescription».
                ابعاد صفحه: ${screenWidthPx}x${screenHeightPx}
                لیست دکمه‌ها و مختصات فعلی صفحه:
                $compactUiTree

                فقط و فقط یک آرایه JSON خالص برگردان شامل قدم‌هایی که باید به ترتیب روی صفحه کلیک یا سوایپ کنم تا به هدف برسم (حداکثر ۶ گام، بدون توضیح اضافه):
                [{"actionType":"TAP","x":540,"y":960,"nodeText":"متن دکمه","delayBeforeNextMs":800}]
            """.trimIndent()
        }

        /**
         * Extracts the JSON macro array from DeepSeek's chat response using Regex.
         * Prefers the last syntactically valid JSONArray containing objects.
         */
        fun extractJsonArrayWithRegex(rawText: String): String? {
            if (rawText.isBlank()) return null

            val candidates = mutableListOf<String>()
            FENCED_JSON_ARRAY_REGEX.findAll(rawText).forEach { match ->
                match.groupValues.getOrNull(1)?.trim()?.let { candidates.add(it) }
            }
            RAW_JSON_ARRAY_REGEX.findAll(rawText).forEach { match ->
                candidates.add(match.value.trim())
            }

            // Validate from last to first (in case assistant echoes an example then emits final block)
            for (candidate in candidates.asReversed()) {
                val parsed = runCatching { JSONArray(candidate) }.getOrNull()
                if (parsed != null && parsed.length() > 0 && parsed.optJSONObject(0) != null) {
                    return candidate
                }
            }
            return null
        }
    }
}
