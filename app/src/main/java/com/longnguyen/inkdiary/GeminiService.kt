package com.longnguyen.inkdiary

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "GeminiService"

class GeminiService(apiKey: String) {
    private val cleanedKey = apiKey.trim()
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = cleanedKey,
        systemInstruction = content { text("You are a kind and concise diary companion. Your response should be brief (1-3 sentences) so it fits on an E-ink screen. Respond in the language the user uses, but if you're unsure, use English.") }
    )

    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending prompt to Gemini (Key starts with: ${cleanedKey.take(5)}...): $prompt")
            val response = generativeModel.generateContent(prompt)
            val text = response.text
            Log.d(TAG, "Gemini Response: $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Error: ${e.message}", e)
            null
        }
    }
}
