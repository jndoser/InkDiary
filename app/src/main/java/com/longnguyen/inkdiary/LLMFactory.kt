package com.longnguyen.inkdiary

import android.content.Context

object LLMFactory {
    fun getService(context: Context): LLMService {
        val preferred = Config.getPreferredLLM(context)
        return if (preferred == Config.LLM_SAMBANOVA) {
            SambaNovaService(Config.getSambaNovaApiKey(context))
        } else {
            GeminiService(Config.getGeminiApiKey(context))
        }
    }
}
