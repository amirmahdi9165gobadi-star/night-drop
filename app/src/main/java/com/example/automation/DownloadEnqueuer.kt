package com.example.automation

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.URLUtil
import com.example.data.local.DownloadTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

data class DownloadQueryState(
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reason: Int
)

object DownloadEnqueuer {

    fun deriveFileName(url: String, customFileName: String): String {
        val trimmedCustom = customFileName.trim()
        if (trimmedCustom.isNotEmpty()) {
            return sanitizeFileName(trimmedCustom)
        }
        val guessed = URLUtil.guessFileName(url, null, null)
        return sanitizeFileName(guessed.ifBlank { "nightdrop_file_${System.currentTimeMillis()}" })
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
    }

    fun isValidDownloadUrl(url: String): Boolean {
        val trimmed = url.trim()
        return (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)) &&
            Uri.parse(trimmed).host?.isNotBlank() == true
    }

    suspend fun fetchRemoteContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!isValidDownloadUrl(cleanUrl)) return@withContext -1L
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Cache-Control", "no-cache")
            .head()
            .build()
        try {
            NetworkClient.httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..399) {
                    return@withContext -1L
                }
                val headerLen = response.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                if (headerLen > 0L) {
                    headerLen
                } else {
                    response.body?.contentLength()?.takeIf { it > 0L } ?: -1L
                }
            }
        } catch (_: Exception) {
            -1L
        }
    }

    suspend fun getAvailableDownloadStorageBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val targetDir = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.takeIf { it.exists() } ?: context.filesDir

        runCatching {
            StatFs(targetDir.path).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    fun enqueueDownload(context: Context, task: DownloadTaskEntity): Long {
        val cleanUrl = task.url.trim()
        require(isValidDownloadUrl(cleanUrl)) {
            "لینک دانلود معتبر نیست (باید با http:// یا https:// شروع شود): $cleanUrl"
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw IllegalStateException("سرویس DownloadManager در دسترس نیست")
        val uri = Uri.parse(cleanUrl)
        val fileName = deriveFileName(cleanUrl, task.fileName)

        val request = DownloadManager.Request(uri).apply {
            setTitle(fileName)
            setDescription("دانلود زمان‌بندی شده NightDrop")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(task.allowMetered)
            setAllowedOverRoaming(false)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        return downloadManager.enqueue(request)
    }

    fun queryProgress(context: Context, systemDownloadId: Long): DownloadQueryState? {
        if (systemDownloadId <= 0L) return null
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        val query = DownloadManager.Query().setFilterById(systemDownloadId)
        return runCatching {
            downloadManager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                    val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_PENDING
                    val downloaded = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                    val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L
                    val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0
                    DownloadQueryState(status, downloaded, total, reason)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun queryCompletedLocalUri(context: Context, systemDownloadId: Long): String? {
        if (systemDownloadId <= 0L) return null
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        val query = DownloadManager.Query().setFilterById(systemDownloadId)
        return runCatching {
            downloadManager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    if (uriIdx >= 0) cursor.getString(uriIdx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
