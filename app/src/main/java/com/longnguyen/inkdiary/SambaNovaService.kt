package com.longnguyen.inkdiary

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "SambaNovaService"

class SambaNovaService(private val apiKey: String) : LLMService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.sambanova.ai/v1/chat/completions"

    data class SambaMessage(val role: String, val content: String)
    data class ChatRequest(val model: String, val messages: List<SambaMessage>, val stream: Boolean = false)
    data class ChatResponse(val choices: List<Choice>)
    data class Choice(val message: SambaMessage)

    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        var currentRetry = 0
        val maxRetries = 3
        var backoffMs = 2000L

        while (currentRetry < maxRetries) {
            Log.d(TAG, "Calling SambaNova (Attempt ${currentRetry + 1}) with prompt: $prompt")

            try {
                val messages = mutableListOf<SambaMessage>()
                messages.add(SambaMessage("system", "You are a kind and concise diary companion. Your response should be brief (1-3 sentences) so it fits on an E-ink screen. Respond in the language the user uses, but if you're unsure, use English."))
                
                history.forEach { 
                    val role = if (it.role == "user") "user" else "assistant"
                    messages.add(SambaMessage(role, it.content))
                }
                
                messages.add(SambaMessage("user", prompt))

                val chatRequest = ChatRequest(
                    model = "Meta-Llama-3.3-70B-Instruct",
                    messages = messages,
                    stream = false
                )

                val jsonBody = gson.toJson(chatRequest)
                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(jsonBody.toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()

                    if (response.isSuccessful) {
                        val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                        return@withContext chatResponse.choices.firstOrNull()?.message?.content
                    } else {
                        Log.e(TAG, "SambaNova Error: ${response.code} ${response.message}")
                        Log.e(TAG, "Error body: $responseBody")

                        if (response.code == 429) {
                            // Exponential backoff for rate limiting
                            val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            val sleepMs = if (retryAfter != null) retryAfter * 1000 else backoffMs
                            
                            Log.w(TAG, "Rate limit hit (429). Retrying in ${sleepMs}ms...")
                            delay(sleepMs)
                            
                            backoffMs *= 2
                            currentRetry++
                            return@use // continue loop
                        } else {
                            return@withContext null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SambaNova Exception: ${e.message}", e)
                return@withContext null
            }
        }
        null
    }
}
