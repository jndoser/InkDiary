package com.longnguyen.inkdiary

interface LLMService {
    suspend fun generateResponse(prompt: String, history: List<ChatMessage> = emptyList()): String?
}

data class ChatMessage(
    val role: String, // "user" or "model" / "assistant"
    val content: String
)
