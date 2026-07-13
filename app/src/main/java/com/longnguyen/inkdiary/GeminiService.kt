package com.longnguyen.inkdiary

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "GeminiService"

class GeminiService(apiKey: String) : LLMService {
    private val cleanedKey = apiKey.trim()
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-pro",
        apiKey = cleanedKey,
        systemInstruction = content { text("You are a kind and concise diary companion. Your response should be brief (1-3 sentences) so it fits on an E-ink screen. You are fluent in both English and Vietnamese. Always respond in the same language the user writes in. If the user writes in Vietnamese, respond in Vietnamese. If unsure, use English. You have memory of what the user wrote earlier today.") }
    )

    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        if (cleanedKey.isBlank()) {
            Log.e(TAG, "Gemini Error: API Key is missing")
            return@withContext "Error: Gemini API Key is missing."
        }
        
        try {
            val googleHistory = history.map { 
                content(it.role) { text(it.content) }
            }
            Log.d(TAG, "Sending prompt to Gemini with history size ${googleHistory.size}. Prompt: $prompt")
            val chat = generativeModel.startChat(googleHistory)
            val response = chat.sendMessage(prompt)
            val text = response.text
            
            if (text == null) {
                Log.e(TAG, "Gemini Error: Response text was null")
                return@withContext "API_ERROR: Received empty response from Google."
            }
            
            Log.d(TAG, "Gemini Response: $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Error: ${e.message}", e)
            val errorMsg = e.message ?: "Unknown error"
            if (errorMsg.contains("blocked", ignoreCase = true)) {
                return@withContext "API_ERROR: Google API is blocked. Check your API key or region (VPN might be needed)."
            }
            "API_ERROR: ${e.localizedMessage}"
        }
    }
}
