package com.longnguyen.inkdiary

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and stores the on-device GGUF model used by [OnDeviceLLMService].
 *
 * Restored from compiled DEX after the source files were accidentally deleted.
 */
object ModelDownloadManager {
    private const val TAG = "ModelDownloadManager"

    private const val MODEL_FILENAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"

    /** Rough lower bound for a complete Q4_K_M 0.5B GGUF (~350 MB). */
    private const val MIN_EXPECTED_SIZE_BYTES = 350_000_000L

    // Kept for future model versioning / cache invalidation.
    @Suppress("unused")
    private const val versionHash =
        "d88a4df8098b8d2085aebe1b7031ebce40c33fbb18acb1316a7975d1e6b0f32a"

    fun getModelFile(context: Context): File {
        val modelsDir = File(context.filesDir, "models")
        return File(modelsDir, MODEL_FILENAME)
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() > MIN_EXPECTED_SIZE_BYTES
    }

    fun deleteModel(context: Context) {
        val file = getModelFile(context)
        if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                Log.d(TAG, "Model deleted: ${file.absolutePath}")
            }
        }
    }

    /**
     * @param onProgress optional callback with (downloadedBytes, totalBytes).
     *                   totalBytes may be -1 if the server does not report Content-Length.
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile(context)
            if (modelFile.exists() && modelFile.length() > MIN_EXPECTED_SIZE_BYTES) {
                Log.d(TAG, "Model already exists: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
                return@withContext Result.success(modelFile)
            }

            val modelsDir = modelFile.parentFile
            if (modelsDir != null && !modelsDir.exists()) {
                modelsDir.mkdirs()
            }

            val tempFile = File(modelsDir, "$MODEL_FILENAME.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            Log.d(TAG, "Starting model download from $MODEL_URL")

            var connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
            }

            var responseCode = connection.responseCode
            // Follow a single redirect (Hugging Face often 302s to CDN).
            if (responseCode in 300..399) {
                val redirectUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (redirectUrl.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed with HTTP $responseCode (no Location)")
                    )
                }
                Log.d(TAG, "Following redirect to: $redirectUrl")
                connection = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                responseCode = connection.responseCode
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext Result.failure(
                    IllegalStateException("Download failed with HTTP $responseCode")
                )
            }

            val totalBytes = connection.contentLengthLong
            Log.d(TAG, "Download started. Total size: $totalBytes bytes")

            connection.inputStream.use { rawInput ->
                BufferedInputStream(rawInput).use { input ->
                    BufferedOutputStream(FileOutputStream(tempFile)).use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            onProgress?.invoke(totalRead, totalBytes)
                        }
                        output.flush()
                    }
                }
            }
            connection.disconnect()

            if (tempFile.length() <= MIN_EXPECTED_SIZE_BYTES) {
                val size = tempFile.length()
                tempFile.delete()
                return@withContext Result.failure(
                    IllegalStateException(
                        "Download incomplete: $size bytes (expected >$MIN_EXPECTED_SIZE_BYTES)"
                    )
                )
            }

            if (modelFile.exists()) {
                modelFile.delete()
            }
            if (!tempFile.renameTo(modelFile)) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            Log.d(TAG, "Model downloaded successfully: ${modelFile.absolutePath}")
            Result.success(modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            Result.failure(e)
        }
    }
}
