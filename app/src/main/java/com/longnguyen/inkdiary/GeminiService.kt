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
        modelName = "gemini-1.5-flash",
        apiKey = cleanedKey,
        systemInstruction = content { text("You are a kind and concise diary companion. Your response should be brief (1-3 sentences) so it fits on an E-ink screen. Respond in the language the user uses, but if you're unsure, use English. You have memory of what the user wrote earlier today.") }
    )

    override suspend fun generateResponse(prompt: String, history: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        try {
            val googleHistory = history.map { 
                content(it.role) { text(it.content) }
            }
            Log.d(TAG, "Sending prompt to Gemini with history size ${googleHistory.size}. Prompt: $prompt")
            val chat = generativeModel.startChat(googleHistory)
            val response = chat.sendMessage(prompt)
            val text = response.text
            Log.d(TAG, "Gemini Response: $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Error: ${e.message}", e)
            null
        }
    }
}
