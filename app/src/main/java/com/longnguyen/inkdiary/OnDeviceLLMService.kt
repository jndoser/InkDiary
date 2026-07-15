package com.longnguyen.inkdiary

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import kotlin.coroutines.resume

/**
 * On-device LLM backed by llamacpp-kotlin (Qwen2.5 0.5B GGUF).
 *
 * Restored from compiled DEX after the source files were accidentally deleted.
 *
 * Native note: Boox SoCs often report aes+crc32, so llamacpp selects the ARMv8.2
 * library name. We package ARMv8.0-safe binaries under those names in jniLibs.
 */
class OnDeviceLLMService(
    private val appContext: Context,
    private val contentResolver: ContentResolver,
    private val modelFile: File
) : LLMService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sharedFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 1000
    )
    private val llamaHelper = LlamaHelper(contentResolver, scope, sharedFlow)
    private val loadMutex = Mutex()
    private var isModelLoaded: Boolean = false

    private val systemPrompt =
        "You are a kind and concise diary companion. Your response should be brief (1-3 sentences) " +
            "so it fits on an E-ink screen. You are fluent in both English and Vietnamese. " +
            "Always respond in the same language the user writes in. If the user writes in Vietnamese, " +
            "respond in Vietnamese. If unsure, use English. You have memory of what the user wrote earlier today."

    fun loadModel(
        onLoaded: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            val result = ensureModelLoaded()
            if (result.isSuccess) {
                onLoaded()
            } else {
                val err = result.exceptionOrNull()
                onError(Exception(err?.message ?: "Unknown load error", err))
            }
        }
    }

    /**
     * Ensures the GGUF model is loaded into memory. Safe to call multiple times.
     */
    suspend fun ensureModelLoaded(): Result<Unit> = loadMutex.withLock {
        if (isModelLoaded) {
            return@withLock Result.success(Unit)
        }

        if (Config.isOnDeviceUnsafe(appContext)) {
            return@withLock Result.failure(
                IllegalStateException(
                    "On-device AI is disabled on this device (previous native crash). Use Gemini or SambaNova."
                )
            )
        }

        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.e(TAG, "Failed to load model")
            return@withLock Result.failure(
                IllegalStateException("Model file missing: ${modelFile.absolutePath}")
            )
        }

        return@withLock try {
            // If native code SIGILLs, the process dies; this flag lets next launch recover.
            Config.markOnDeviceLoadStarting(appContext)

            suspendCancellableCoroutine { cont ->
                val path = Uri.fromFile(modelFile).toString()
                Log.d(TAG, "Loading model from: $path")
                val startMs = System.currentTimeMillis()

                llamaHelper.load(
                    path = path,
                    contextLength = 2048,
                    mmprojPath = null
                ) { _ ->
                    isModelLoaded = true
                    Config.markOnDeviceLoadFinished(appContext, success = true)
                    val elapsed = System.currentTimeMillis() - startMs
                    Log.d(TAG, "Model loaded successfully in $elapsed ms.")
                    if (cont.isActive) {
                        cont.resume(Result.success(Unit))
                    }
                }

                // Also observe error events in case the load callback never fires.
                val job = scope.launch {
                    sharedFlow.takeWhile { event ->
                        when (event) {
                            is LlamaHelper.LLMEvent.Error -> {
                                Config.markOnDeviceLoadFinished(appContext, success = false)
                                if (cont.isActive) {
                                    cont.resume(
                                        Result.failure(Exception(event.message))
                                    )
                                }
                                false
                            }
                            is LlamaHelper.LLMEvent.Loaded -> false
                            else -> true
                        }
                    }.collect { }
                }

                cont.invokeOnCancellation {
                    job.cancel()
                    llamaHelper.abort()
                    if (!isModelLoaded) {
                        Config.markOnDeviceLoadFinished(appContext, success = false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            Config.markOnDeviceLoadFinished(appContext, success = false)
            Result.failure(e)
        }
    }

    override suspend fun generateResponse(
        prompt: String,
        history: List<ChatMessage>
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                val loadResult = ensureModelLoaded()
                if (loadResult.isFailure) {
                    val msg = loadResult.exceptionOrNull()?.localizedMessage ?: "unknown error"
                    Log.e(TAG, "Model not loaded yet")
                    return@withContext "API_ERROR: On-device model not loaded ($msg)."
                }
            }

            val fullPrompt = buildQwenPrompt(prompt, history)
            Log.d(TAG, "Generating response with prompt length: ${fullPrompt.length}")

            val responseBuilder = StringBuilder()
            var errorMessage: String? = null

            val predictJob = scope.launch {
                llamaHelper.predict(fullPrompt)
            }

            sharedFlow
                .takeWhile { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            responseBuilder.append(event.word)
                            true
                        }
                        is LlamaHelper.LLMEvent.Done -> false
                        is LlamaHelper.LLMEvent.Error -> {
                            Log.e(TAG, "LLMEvent Error occurred")
                            errorMessage = event.message
                            false
                        }
                        else -> true
                    }
                }
                .collect { }

            predictJob.join()

            if (errorMessage != null) {
                Log.e(TAG, "On-device inference error")
                return@withContext "API_ERROR: On-device error: $errorMessage"
            }

            val result = responseBuilder.toString().trim()
            if (result.isEmpty()) {
                return@withContext "API_ERROR: Empty response from on-device model"
            }

            Log.d(TAG, "Generated response: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "On-device inference error", e)
            "API_ERROR: On-device error: ${e.message}"
        }
    }

    private fun buildQwenPrompt(prompt: String, history: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        sb.append("<|im_end|>\n")

        val recentHistory = if (history.size > 6) history.takeLast(6) else history
        for (message in recentHistory) {
            val role = if (message.role == "user") "user" else "assistant"
            sb.append("<|im_start|>").append(role).append('\n')
            sb.append(message.content)
            sb.append("<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n")
        sb.append(prompt)
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    fun isLoaded(): Boolean = isModelLoaded

    fun close() {
        try {
            llamaHelper.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing llamaHelper", e)
        }
        isModelLoaded = false
    }

    companion object {
        private const val TAG = "OnDeviceLLMService"
    }
}
