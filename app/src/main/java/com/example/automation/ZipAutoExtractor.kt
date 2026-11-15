package com.example.automation

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class AutoUnzipOutcome(
    val wasZipFile: Boolean,
    val extractedSuccess: Boolean,
    val extractedFileCount: Int = 0,
    val destinationPath: String = "",
    val deletedSourceArchive: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Post-Download Auto-Unzip & Organizer (Requirement #4):
 * Automatically extracts completed .zip archives into an organized folder,
 * verifies extracted file integrity, and deletes the original .zip to save storage.
 */
object ZipAutoExtractor {

    private const val STREAM_BUFFER_SIZE = 8192

    suspend fun processCompletedDownload(
        context: Context,
        fileName: String,
        localUriString: String?,
        systemDownloadId: Long
    ): AutoUnzipOutcome = withContext(Dispatchers.IO) {
        val isZipByName = fileName.endsWith(".zip", ignoreCase = true) ||
            (localUriString?.endsWith(".zip", ignoreCase = true) == true)
        if (!isZipByName) {
            return@withContext AutoUnzipOutcome(wasZipFile = false, extractedSuccess = false)
        }

        val parsedUri = localUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val fileFromUri = parsedUri?.takeIf { it.scheme == "file" }?.path?.let { File(it) }
        val fallbackPublicZip = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        val resolvedFile = when {
            fileFromUri?.exists() == true -> fileFromUri
            fallbackPublicZip.exists() -> fallbackPublicZip
            else -> null
        }

        val baseFolderName = fileName
            .substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "archive_${System.currentTimeMillis()}" }

        val targetRoot = resolveExtractParentDir(context, fileFromUri?.parentFile)
        val extractDir = File(targetRoot, "NightDrop_Extracted/$baseFolderName")

        if (resolvedFile != null) {
            return@withContext extractZipFileAndVerify(
                zipFile = resolvedFile,
                targetDir = extractDir
            )
        }

        // Scoped storage fallback: stream via ContentResolver if DownloadManager returned a content:// URI
        if (parsedUri != null && parsedUri.scheme == "content") {
            val streamOutcome = runCatching {
                context.contentResolver.openInputStream(parsedUri)?.use { input ->
                    extractZipStreamAndVerify(input, extractDir)
                }
            }.getOrNull()

            if (streamOutcome != null && streamOutcome.extractedSuccess) {
                val deleted = deleteSourceDownloadEntry(context, parsedUri, null, systemDownloadId)
                return@withContext streamOutcome.copy(deletedSourceArchive = deleted)
            }
            return@withContext streamOutcome ?: AutoUnzipOutcome(
                wasZipFile = true,
                extractedSuccess = false,
                errorMessage = "دسترسی به جریان فایل zip ممکن نشد"
            )
        }

        AutoUnzipOutcome(
            wasZipFile = true,
            extractedSuccess = false,
            errorMessage = "فایل zip دانلود شده در حافظه پیدا نشد"
        )
    }

    fun extractZipFileAndVerify(zipFile: File, targetDir: File): AutoUnzipOutcome {
        if (!zipFile.exists() || !zipFile.isFile) {
            return AutoUnzipOutcome(
                wasZipFile = true,
                extractedSuccess = false,
                errorMessage = "فایل فشرده وجود ندارد"
            )
        }

        val outcome = try {
            FileInputStream(zipFile).use { fis ->
                extractZipStreamAndVerify(fis, targetDir)
            }
        } catch (e: Exception) {
            return AutoUnzipOutcome(
                wasZipFile = true,
                extractedSuccess = false,
                destinationPath = targetDir.absolutePath,
                errorMessage = e.message ?: "خطا در باز کردن فایل zip"
            )
        }
        if (!outcome.extractedSuccess) {
            return outcome
        }

        val deleted = runCatching { zipFile.delete() }.getOrDefault(false)
        return outcome.copy(deletedSourceArchive = deleted)
    }

    fun extractZipStreamAndVerify(
        rawInputStream: InputStream,
        targetDir: File
    ): AutoUnzipOutcome {
        return try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val canonicalTarget = targetDir.canonicalPath
            val extractedChecks = mutableListOf<Pair<File, Long>>()

            ZipInputStream(BufferedInputStream(rawInputStream, STREAM_BUFFER_SIZE)).use { zis ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    val canonicalOut = outFile.canonicalPath
                    // Prevent Zip-Slip path traversal
                    if (!canonicalOut.startsWith(canonicalTarget + File.separator)) {
                        throw SecurityException("مسیر ناامن در فایل zip: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(outFile), STREAM_BUFFER_SIZE).use { bos ->
                            var read = zis.read(buffer)
                            while (read != -1) {
                                bos.write(buffer, 0, read)
                                read = zis.read(buffer)
                            }
                        }
                        extractedChecks.add(outFile to entry.size)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Integrity check: ensure at least 1 file was extracted and matches expected entry sizes
            val allHealthy = extractedChecks.isNotEmpty() && extractedChecks.all { (file, expectedSize) ->
                file.exists() && (expectedSize < 0L || file.length() == expectedSize)
            }

            if (!allHealthy) {
                AutoUnzipOutcome(
                    wasZipFile = true,
                    extractedSuccess = false,
                    destinationPath = targetDir.absolutePath,
                    errorMessage = "بررسی سلامت فایل‌های استخراج‌شده ناموفق بود"
                )
            } else {
                AutoUnzipOutcome(
                    wasZipFile = true,
                    extractedSuccess = true,
                    extractedFileCount = extractedChecks.size,
                    destinationPath = targetDir.absolutePath,
                    deletedSourceArchive = false
                )
            }
        } catch (e: Exception) {
            AutoUnzipOutcome(
                wasZipFile = true,
                extractedSuccess = false,
                destinationPath = targetDir.absolutePath,
                errorMessage = e.message ?: "خطا در استخراج فایل zip"
            )
        }
    }

    private fun resolveExtractParentDir(context: Context, downloadParent: File?): File {
        if (downloadParent != null && downloadParent.canWrite()) {
            return downloadParent
        }
        val publicDownloads = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (publicDownloads != null && (publicDownloads.exists() || publicDownloads.mkdirs()) && publicDownloads.canWrite()) {
            return publicDownloads
        }
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    }

    private fun deleteSourceDownloadEntry(
        context: Context,
        uri: Uri,
        file: File?,
        systemDownloadId: Long
    ): Boolean {
        if (file?.exists() == true && runCatching { file.delete() }.getOrDefault(false)) {
            return true
        }
        val resolvedDeleted = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        if (resolvedDeleted) return true
        if (systemDownloadId > 0L) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            return runCatching { dm.remove(systemDownloadId) > 0 }.getOrDefault(false)
        }
        return false
    }
}
