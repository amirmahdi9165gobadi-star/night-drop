package com.example.automation

import com.example.data.local.GestureActionType
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GeminiTroubleshootResult(
    val succeeded: Boolean,
    val generatedSteps: List<MacroStep> = emptyList(),
    val rawResponseText: String? = null,
    val errorMessage: String? = null
)

/**
 * AI Cloud Troubleshooter powered by Gemini 2.5 Flash REST API:
 * When all offline strategies fail on an unexpected screen/dialog, extracts visible
 * clickable UI nodes from rootInActiveWindow, sends them with the current goal to
 * gemini-2.5-flash, and parses a structured JSON array of MacroSteps to execute and persist.
 */
class GeminiApiTroubleshooter(
    private val client: OkHttpClient = NetworkClient.geminiClient,
    private val endpointUrl: String = GEMINI_2_5_FLASH_ENDPOINT
) {

    suspend fun troubleshootAndGenerateMacro(
        apiKey: String,
        goalDescription: String,
        phase: MacroPhase,
        uiNodes: List<UiNodeSnapshot>,
        screenWidthPx: Int,
        screenHeightPx: Int
    ): GeminiTroubleshootResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext GeminiTroubleshootResult(
                succeeded = false,
                errorMessage = "کلید Gemini API در تنظیمات وارد نشده است"
            )
        }
        if (uiNodes.isEmpty()) {
            return@withContext GeminiTroubleshootResult(
                succeeded = false,
                errorMessage = "هیچ المان قابل استخراجی در صفحه فعلی یافت نشد"
            )
        }

        val prompt = buildTroubleshootPrompt(
            goalDescription = goalDescription,
            uiNodes = uiNodes,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val requestPayload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                }
            )
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("x-goog-api-key", cleanKey)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .post(requestPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use GeminiTroubleshootResult(
                        succeeded = false,
                        rawResponseText = bodyString,
                        errorMessage = "خطای سرور Gemini (HTTP ${response.code})"
                    )
                }

                val parsedSteps = parseGeminiMacroResponse(
                    rawResponse = bodyString,
                    phase = phase,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx
                )
                if (parsedSteps.isEmpty()) {
                    GeminiTroubleshootResult(
                        succeeded = false,
                        rawResponseText = bodyString,
                        errorMessage = "مدل Gemini هیچ قدم اجرایی معتبری برنگرداند"
                    )
                } else {
                    GeminiTroubleshootResult(
                        succeeded = true,
                        generatedSteps = parsedSteps,
                        rawResponseText = bodyString
                    )
                }
            }
        } catch (e: Exception) {
            GeminiTroubleshootResult(
                succeeded = false,
                errorMessage = e.message ?: "خطا در ارتباط با Gemini API"
            )
        }
    }

    companion object {
        const val GEMINI_TIMEOUT_MS = 8_000L
        const val GEMINI_2_5_FLASH_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        fun serializeUiNodesForPrompt(uiNodes: List<UiNodeSnapshot>): String {
            val array = JSONArray()
            uiNodes.forEach { node ->
                array.put(
                    JSONObject().apply {
                        node.text?.let { put("text", it) }
                        node.contentDescription?.let { put("contentDescription", it) }
                        node.viewId?.let { put("viewId", it) }
                        node.className?.let { put("className", it) }
                        put("clickable", node.isClickable)
                        put("centerX", node.centerX)
                        put("centerY", node.centerY)
                    }
                )
            }
            return array.toString()
        }

        fun buildTroubleshootPrompt(
            goalDescription: String,
            uiNodes: List<UiNodeSnapshot>,
            screenWidthPx: Int,
            screenHeightPx: Int
        ): String {
            val nodesJson = serializeUiNodesForPrompt(uiNodes)
            return """
                You are an Android UI automation recovery engine.
                Current automation goal: "$goalDescription"
                Screen resolution: ${screenWidthPx}x${screenHeightPx}
                Visible interactive UI elements extracted from rootInActiveWindow:
                $nodesJson

                Return ONLY a valid JSON array of sequential steps to dismiss any blocking dialog/popup or complete the goal.
                Each object in the JSON array must follow this schema:
                {
                  "actionType": "TAP" | "SWIPE" | "GLOBAL_HOME" | "GLOBAL_QUICK_SETTINGS" | "WAIT_DELAY",
                  "startX": <int pixel X>,
                  "startY": <int pixel Y>,
                  "endX": <int pixel X>,
                  "endY": <int pixel Y>,
                  "durationMs": <int >= 80>,
                  "delayBeforeNextMs": <int >= 500>,
                  "nodeText": <string or null matching element text>,
                  "contentDescription": <string or null matching element contentDescription>,
                  "label": <short Persian description>
                }
                Maximum 6 steps. Prefer clicking exact UI nodes by matching nodeText/contentDescription and their centerX/centerY.
            """.trimIndent()
        }

        fun parseGeminiMacroResponse(
            rawResponse: String,
            phase: MacroPhase,
            screenWidthPx: Int,
            screenHeightPx: Int
        ): List<MacroStep> {
            val trimmed = rawResponse.trim()
            if (trimmed.isEmpty()) return emptyList()

            val jsonText = extractCandidateTextIfWrapped(trimmed)
            val cleaned = stripMarkdownCodeFences(jsonText)
            val jsonArray = runCatching {
                when {
                    cleaned.startsWith("[") -> JSONArray(cleaned)
                    cleaned.startsWith("{") -> {
                        val obj = JSONObject(cleaned)
                        obj.optJSONArray("steps") ?: JSONArray()
                    }
                    else -> {
                        val start = cleaned.indexOf('[')
                        val end = cleaned.lastIndexOf(']')
                        if (start >= 0 && end > start) {
                            JSONArray(cleaned.substring(start, end + 1))
                        } else {
                            JSONArray()
                        }
                    }
                }
            }.getOrElse { return emptyList() }

            val safeW = screenWidthPx.coerceAtLeast(1)
            val safeH = screenHeightPx.coerceAtLeast(1)
            val steps = mutableListOf<MacroStep>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val rawType = item.optString("actionType")
                    .ifBlank { item.optString("action") }
                    .ifBlank { item.optString("type") }
                    .ifBlank { "TAP" }
                    .uppercase()
                val normalizedType = when (rawType) {
                    "CLICK" -> "TAP"
                    else -> rawType
                }
                val type = runCatching { GestureActionType.valueOf(normalizedType) }
                    .getOrDefault(GestureActionType.TAP)

                val sx = when {
                    item.has("startX") -> item.optInt("startX")
                    item.has("pixelStartX") -> item.optInt("pixelStartX")
                    item.has("x") -> item.optInt("x")
                    else -> safeW / 2
                }.coerceIn(0, safeW)

                val sy = when {
                    item.has("startY") -> item.optInt("startY")
                    item.has("pixelStartY") -> item.optInt("pixelStartY")
                    item.has("y") -> item.optInt("y")
                    else -> safeH / 2
                }.coerceIn(0, safeH)

                val ex = when {
                    item.has("endX") -> item.optInt("endX")
                    item.has("pixelEndX") -> item.optInt("pixelEndX")
                    else -> sx
                }.coerceIn(0, safeW)

                val ey = when {
                    item.has("endY") -> item.optInt("endY")
                    item.has("pixelEndY") -> item.optInt("pixelEndY")
                    else -> sy
                }.coerceIn(0, safeH)

                val duration = when {
                    item.has("durationMs") -> item.optLong("durationMs", 120L)
                    item.has("duration") -> item.optLong("duration", 120L)
                    else -> 120L
                }.coerceIn(80L, 3_000L)

                val delayMs = when {
                    item.has("delayBeforeNextMs") -> item.optLong("delayBeforeNextMs", 800L)
                    item.has("delayMs") -> item.optLong("delayMs", 800L)
                    item.has("delay") -> item.optLong("delay", 800L)
                    else -> 800L
                }.coerceAtLeast(500L)

                val nodeText = item.optString("nodeText", "")
                    .ifBlank { item.optString("text", "") }
                    .takeIf { it.isNotBlank() && it != "null" }
                val contentDesc = item.optString("contentDescription", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                val label = item.optString("label", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: nodeText?.let { "کلیک هوشمند AI روی «$it»" }
                    ?: "گام بازیابی AI #${i + 1}"

                steps.add(
                    MacroStep(
                        phase = phase,
                        orderIndex = i,
                        type = type,
                        startX = sx,
                        startY = sy,
                        endX = ex,
                        endY = ey,
                        normStartX = (sx.toFloat() / safeW).coerceIn(0f, 1f),
                        normStartY = (sy.toFloat() / safeH).coerceIn(0f, 1f),
                        normEndX = (ex.toFloat() / safeW).coerceIn(0f, 1f),
                        normEndY = (ey.toFloat() / safeH).coerceIn(0f, 1f),
                        duration = duration,
                        nodeText = nodeText,
                        contentDescription = contentDesc,
                        delayBeforeNextMs = delayMs,
                        label = label
                    )
                )
            }
            return steps
        }

        private fun extractCandidateTextIfWrapped(raw: String): String {
            if (!raw.startsWith("{")) return raw
            return runCatching {
                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates") ?: return raw
                val first = candidates.optJSONObject(0) ?: return raw
                val content = first.optJSONObject("content") ?: return raw
                val parts = content.optJSONArray("parts") ?: return raw
                parts.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() } ?: raw
            }.getOrDefault(raw)
        }

        private fun stripMarkdownCodeFences(input: String): String {
            var text = input.trim()
            if (text.startsWith("```")) {
                text = text.removePrefix("```json").removePrefix("```").trim()
                if (text.endsWith("```")) {
                    text = text.removeSuffix("```").trim()
                }
            }
            return text
        }
    }
}
