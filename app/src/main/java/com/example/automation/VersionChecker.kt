package com.example.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateCheckResult(
    val succeeded: Boolean,
    val hasUpdate: Boolean = false,
    val currentVersionName: String = "",
    val currentVersionCode: Int = 1,
    val latestVersionName: String = "",
    val latestVersionCode: Int = 1,
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val errorMessage: String? = null
)

/**
 * Self-Updater OTA VersionChecker:
 * Reads a release manifest JSON (supports GitHub Releases API and custom NightDrop release JSON),
 * compares version codes / semantic version strings against the installed version, and opens the
 * verified release download page in the system browser via zero-permission ACTION_VIEW Intent.
 */
class VersionChecker(
    private val client: OkHttpClient = NetworkClient.otaClient
) {

    suspend fun checkForUpdate(
        manifestUrl: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val url = manifestUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) {
            return@withContext UpdateCheckResult(
                succeeded = false,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                errorMessage = "آدرس JSON بررسی آپدیت معتبر نیست"
            )
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json, application/json")
            .get()
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use UpdateCheckResult(
                        succeeded = false,
                        currentVersionName = currentVersionName,
                        currentVersionCode = currentVersionCode,
                        errorMessage = "خطای دریافت اطلاعات نسخه (HTTP ${response.code})"
                    )
                }

                parseReleaseJson(
                    rawJson = body,
                    fallbackUrl = url,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult(
                succeeded = false,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                errorMessage = e.message ?: "خطا در بررسی نسخه جدید"
            )
        }
    }

    companion object {

        fun parseReleaseJson(
            rawJson: String,
            fallbackUrl: String,
            currentVersionCode: Int,
            currentVersionName: String
        ): UpdateCheckResult {
            return try {
                val json = JSONObject(rawJson)
                val tagOrName = json.optString("versionName")
                    .ifBlank { json.optString("tag_name") }
                    .ifBlank { json.optString("name") }
                    .trim()

                val explicitCode = json.optInt("versionCode", -1)
                val parsedSemverNew = isSemanticVersionNewer(tagOrName, currentVersionName)
                val hasUpdate = if (explicitCode > 0) {
                    explicitCode > currentVersionCode
                } else {
                    parsedSemverNew
                }

                val latestCode = if (explicitCode > 0) {
                    explicitCode
                } else if (parsedSemverNew) {
                    currentVersionCode + 1
                } else {
                    currentVersionCode
                }

                val notes = json.optString("changelog")
                    .ifBlank { json.optString("body") }
                    .trim()

                val apkAssetUrl = json.optJSONArray("assets")?.let { assets ->
                    var found = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val dl = asset.optString("browser_download_url")
                        if (dl.endsWith(".apk", ignoreCase = true)) {
                            found = dl
                            break
                        }
                    }
                    found
                }.orEmpty()

                val releaseUrl = json.optString("releaseUrl")
                    .ifBlank { json.optString("apkUrl") }
                    .ifBlank { json.optString("html_url") }
                    .ifBlank { apkAssetUrl }
                    .ifBlank { fallbackUrl }

                UpdateCheckResult(
                    succeeded = true,
                    hasUpdate = hasUpdate,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    latestVersionName = tagOrName.ifBlank { currentVersionName },
                    latestVersionCode = latestCode,
                    releaseNotes = notes,
                    releaseUrl = releaseUrl
                )
            } catch (e: Exception) {
                UpdateCheckResult(
                    succeeded = false,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    errorMessage = "ساختار JSON نسخه جدید نامعتبر است"
                )
            }
        }

        fun isSemanticVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
            val remoteParts = parseVersionTuple(remoteVersion)
            val currentParts = parseVersionTuple(currentVersion)
            if (remoteParts.isEmpty() || currentParts.isEmpty()) return false
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        private fun parseVersionTuple(raw: String): List<Int> {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            return Regex("\\d+").findAll(cleaned).map { it.value.toIntOrNull() ?: 0 }.toList()
        }

        /**
         * Opens the GitHub Release / APK download URL in the system browser using a
         * zero-permission ACTION_VIEW Intent (Google Play Policy compliant).
         */
        fun openReleaseInBrowser(context: Context, releaseUrl: String): Boolean {
            val clean = releaseUrl.trim()
            if (clean.isBlank()) return false
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(clean)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
