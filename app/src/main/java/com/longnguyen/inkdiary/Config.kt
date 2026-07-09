package com.longnguyen.inkdiary

import android.content.Context

object Config {
    private const val PREFS_NAME = "ink_diary_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val KEY_SAMBANOVA_API = "sambanova_api_key"
    private const val KEY_PREFERRED_LLM = "preferred_llm"

    const val LLM_GEMINI = "gemini"
    const val LLM_SAMBANOVA = "sambanova"
    private const val KEY_DEBUG_MODE = "debug_mode"

    fun getGeminiApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_GEMINI_API, null)
        return if (!savedKey.isNullOrBlank()) savedKey else BuildConfig.GEMINI_API_KEY
    }

    fun saveGeminiApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    fun getSambaNovaApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_SAMBANOVA_API, null)
        return if (!savedKey.isNullOrBlank()) savedKey else BuildConfig.SAMBANOVA_API_KEY
    }

    fun saveSambaNovaApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAMBANOVA_API, key.trim()).apply()
    }

    fun getPreferredLLM(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preferred = prefs.getString(KEY_PREFERRED_LLM, LLM_GEMINI) ?: LLM_GEMINI
        
        // Fallback if preferred is not available
        if (preferred == LLM_GEMINI && getGeminiApiKey(context).isBlank()) {
            if (getSambaNovaApiKey(context).isNotBlank()) return LLM_SAMBANOVA
        }
        if (preferred == LLM_SAMBANOVA && getSambaNovaApiKey(context).isBlank()) {
            if (getGeminiApiKey(context).isNotBlank()) return LLM_GEMINI
        }
        
        return preferred
    }

    fun setPreferredLLM(context: Context, llm: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_LLM, llm).apply()
    }

    fun isDebugEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEBUG_MODE, true) // Default to true so you don't lose it initially
    }

    fun setDebugEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }
}
