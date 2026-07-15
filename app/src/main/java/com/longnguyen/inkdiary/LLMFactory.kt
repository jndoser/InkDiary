package com.longnguyen.inkdiary

import android.content.Context

object LLMFactory {
    fun getService(context: Context): LLMService {
        val preferred = Config.getPreferredLLM(context)
        return when (preferred) {
            Config.LLM_ON_DEVICE -> {
                val modelFile = ModelDownloadManager.getModelFile(context)
                val service = OnDeviceLLMService(context.contentResolver, modelFile)
                // Note: The model needs to be loaded before calling generateResponse
                // In a real flow, you'd ensure it's loaded when the app starts or when the mode is selected.
                service
            }
            Config.LLM_SAMBANOVA -> SambaNovaService(Config.getSambaNovaApiKey(context))
            else -> GeminiService(Config.getGeminiApiKey(context))
        }
    }
}
